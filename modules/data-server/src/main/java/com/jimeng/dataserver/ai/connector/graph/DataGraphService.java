package com.jimeng.dataserver.ai.connector.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemSummary;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据星图的读取：每次请求从 {@code connection} + {@code connector_schema} + {@code connector_semantic(JOIN)} 现算，
 * 不落库、不排队、不调模型（设计文档 §4）。语义层任何入口的改动，下一次打开页面即生效。
 *
 * <p>三张表都在 {@code TENANT_AWARE_TABLES} 里，查询在调用方租户下进行：别的租户的连接在这里就是查不到。
 */
@Service
@RequiredArgsConstructor
public class DataGraphService {

    private final ConnectionMapper connectionMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;

    /** 只列出结构快照里至少有一个 TABLE / VIEW 的连接，按连接 id 升序。 */
    public List<SystemSummary> systems() {
        List<Connection> connections = connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .orderByAsc(Connection::getId));
        Map<Long, Integer> tableCounts = new HashMap<>();
        for (ConnectorSchema row : schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .select(ConnectorSchema::getConnectorId, ConnectorSchema::getObjectType))) {
            if (row.getConnectorId() != null && DataGraphProjector.objectType(row.getObjectType()) != null) {
                tableCounts.merge(row.getConnectorId(), 1, Integer::sum);
            }
        }
        List<SystemSummary> out = new ArrayList<>();
        for (Connection c : connections) {
            int tables = tableCounts.getOrDefault(c.getId(), 0);
            if (tables > 0) {
                out.add(new SystemSummary(String.valueOf(c.getId()), c.getName(), c.getDisplayName(), c.getKind(),
                        c.getStatus(), c.getSemanticStatus(), tables));
            }
        }
        return out;
    }

    public SystemGraph system(String connectorId) {
        Connection connection = requireConnection(connectorId);
        return DataGraphProjector.system(connection, schemas(connection.getId()), joins(connection.getId()));
    }

    public TableDetail table(String connectorId, String name) {
        if (name == null || name.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "表名不能为空");
        }
        Connection connection = requireConnection(connectorId);
        TableDetail detail = DataGraphProjector.table(schemas(connection.getId()), joins(connection.getId()),
                name.trim());
        if (detail == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "表不存在");
        }
        return detail;
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
}
