package com.jimeng.dataserver.ai.connector.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemSummary;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticDeriveService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorBusinessViewMapper;
import com.jimeng.persistence.mapper.ConnectorEnrichmentStateMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据星图的读取：每次请求从 {@code connection} + {@code connector_schema} + {@code connector_semantic(JOIN)}
 * + 业务视图现算（设计文档 §4）。不排队、不调模型：业务文字由补全链在后台写好，这里只读。
 *
 * <p>五张表都在 {@code TENANT_AWARE_TABLES} 里，查询在调用方租户下进行：别的租户的连接在这里就是查不到。
 */
@Service
@RequiredArgsConstructor
public class DataGraphService {

    /** 补全链的认领多久算过期（与 {@code SemanticEnrichmentService} 一致）：过期了就不再说「正在整理」。 */
    static final Duration CLAIM_STALE = Duration.ofMinutes(30);
    public static final String VIEW_RUNNING = "RUNNING";

    private final ConnectionMapper connectionMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectorBusinessViewMapper viewMapper;
    private final ConnectorEnrichmentStateMapper stateMapper;
    private final ConnectorProperties properties;

    /** 只列出结构快照里至少有一个 TABLE / VIEW 的连接，按连接 id 升序。 */
    public List<SystemSummary> systems() {
        List<Connection> connections = connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .orderByAsc(Connection::getId));
        Map<Long, Integer> tableCounts = new HashMap<>();
        Map<Long, Integer> snapshotCounts = new HashMap<>();
        for (ConnectorSchema row : schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .select(ConnectorSchema::getConnectorId, ConnectorSchema::getObjectType))) {
            if (row.getConnectorId() == null) {
                continue;
            }
            snapshotCounts.merge(row.getConnectorId(), 1, Integer::sum);
            if (DataGraphProjector.objectType(row.getObjectType()) != null) {
                tableCounts.merge(row.getConnectorId(), 1, Integer::sum);
            }
        }
        Map<Long, ConnectorEnrichmentState> states = new HashMap<>();
        for (ConnectorEnrichmentState s : stateMapper.selectList(new LambdaQueryWrapper<ConnectorEnrichmentState>())) {
            states.put(s.getConnectorId(), s);
        }
        List<SystemSummary> out = new ArrayList<>();
        for (Connection c : connections) {
            int tables = tableCounts.getOrDefault(c.getId(), 0);
            if (tables > 0) {
                out.add(new SystemSummary(String.valueOf(c.getId()), c.getName(), c.getDisplayName(), c.getKind(),
                        c.getStatus(), c.getSemanticStatus(), tables,
                        DataGraphProjector.truncated(snapshotCounts.getOrDefault(c.getId(), 0)),
                        viewStatus(states.get(c.getId()), c.getSemanticStatus(), enrichmentOn())));
            }
        }
        return out;
    }

    public SystemGraph system(String connectorId) {
        Connection connection = requireConnection(connectorId);
        Long id = connection.getId();
        return DataGraphProjector.system(connection, schemas(id), joins(id), views(id),
                viewStatus(state(id), connection.getSemanticStatus(), enrichmentOn()));
    }

    public TableDetail table(String connectorId, String name) {
        if (name == null || name.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "表名不能为空");
        }
        Connection connection = requireConnection(connectorId);
        Long id = connection.getId();
        TableDetail detail = DataGraphProjector.table(schemas(id), joins(id), views(id), name.trim());
        if (detail == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "表不存在");
        }
        return detail;
    }

    /**
     * 业务文字的整理状态，只用来决定页面挂不挂「业务名称整理中」（设计文档 §6.3、§7.2）：
     * 认领还没过期 = {@code RUNNING}；否则是上次结果 {@code READY} / {@code FAILED}；
     * 从没跑完过时，补全链会来跑（开关开着，语义层已生成或正在生成）也算 {@code RUNNING}，不会来跑为 {@code null}。
     *
     * <p>「从没跑过」不等于「马上就会跑」：开关关着（上线后回滚的手段），或者语义层没生成成功（定时对账只挑 READY 的连接，
     * 推导失败的要等下次推导成功才会被触发），补全链都不会来。把这些也报成「在整理」，页面上的提示就会一直挂着。
     */
    static String viewStatus(ConnectorEnrichmentState state, String semanticStatus, boolean enrichmentOn) {
        if (state != null) {
            Date claim = state.getClaimAt();
            if (claim != null && claim.getTime() > System.currentTimeMillis() - CLAIM_STALE.toMillis()) {
                return VIEW_RUNNING;
            }
            if (state.getLastStatus() != null) {
                return state.getLastStatus();
            }
        }
        boolean pending = enrichmentOn && (ConnectorSemanticDeriveService.SEM_READY.equals(semanticStatus)
                || ConnectorSemanticDeriveService.SEM_RUNNING.equals(semanticStatus));
        return pending ? VIEW_RUNNING : null;
    }

    private boolean enrichmentOn() {
        return properties.getSemantic().getEnrichment().isEnabled();
    }

    private Connection requireConnection(String connectorId) {
        Long id;
        try {
            id = Long.valueOf(connectorId == null ? "" : connectorId.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "系统不存在");
        }
        Connection connection = connectionMapper.selectById(id);
        if (connection == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "系统不存在");
        }
        return connection;
    }

    private List<ConnectorSchema> schemas(Long connectorId) {
        return schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getConnectorId, connectorId));
    }

    private List<ConnectorSemantic> joins(Long connectorId) {
        return semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .eq(ConnectorSemantic::getScope, ConnectorSemanticService.SCOPE_JOIN));
    }

    private List<ConnectorBusinessView> views(Long connectorId) {
        return viewMapper.selectList(new LambdaQueryWrapper<ConnectorBusinessView>()
                .eq(ConnectorBusinessView::getConnectorId, connectorId));
    }

    private ConnectorEnrichmentState state(Long connectorId) {
        return stateMapper.selectOne(new LambdaQueryWrapper<ConnectorEnrichmentState>()
                .eq(ConnectorEnrichmentState::getConnectorId, connectorId)
                .last("limit 1"));
    }
}
