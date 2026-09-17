package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.connector.service.ConnectorService;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticStagedMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;

/**
 * 管理端两个语义层触发入口共用的门面。
 *
 * <p>租户内存在性校验和用户身份都在请求线程完成；门面只选择 agent 或既有单次推导路径，
 * 不在请求线程执行生成本身。
 */
@Service
public class ConnectorSemanticGenerationService {

    private static final String STATUS_INTERRUPTED = "INTERRUPTED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String MODE_STAGED = "STAGED";
    private static final String KEEP_NOTE =
            "sandbox 暂不可用，保留上次未完成的生成，稍后再点重新生成继续";
    private static final String MERGED_NOTE = "已有一次生成在排队或进行中，本次合并";

    private final ConnectorService connectorService;
    private final SemanticGeneratorSelector selector;
    private final AgentSemanticGenerator agentGenerator;
    private final SingleCallSemanticGenerator singleGenerator;
    private final ConnectorSemanticGenerationMapper generationMapper;
    private final ConnectorSemanticStagedMapper stagedMapper;
    private final ConnectionMapper connectionMapper;
    private final TransactionTemplate transactionTemplate;

    public ConnectorSemanticGenerationService(ConnectorService connectorService,
                                              SemanticGeneratorSelector selector,
                                              AgentSemanticGenerator agentGenerator,
                                              SingleCallSemanticGenerator singleGenerator,
                                              ConnectorSemanticGenerationMapper generationMapper,
                                              ConnectorSemanticStagedMapper stagedMapper,
                                              ConnectionMapper connectionMapper,
                                              PlatformTransactionManager transactionManager) {
        this.connectorService = connectorService;
        this.selector = selector;
        this.agentGenerator = agentGenerator;
        this.singleGenerator = singleGenerator;
        this.generationMapper = generationMapper;
        this.stagedMapper = stagedMapper;
        this.connectionMapper = connectionMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 在当前租户、当前请求身份下选择并提交一次语义层生成。 */
    public GenerationAck trigger(Long connectorId, GenerationTrigger trigger) {
        // 必须是第一步：get 依赖租户拦截器，把路径参数钉死在当前租户内。
        ConnectorView connector = connectorService.get(connectorId);
        SemanticGeneratorSelector.Selection selection = selector.select(connector);

        if (selection.kind() == GeneratorKind.SINGLE_CALL) {
            ConnectorSemanticGeneration interrupted = findInterrupted(connectorId);
            if (interrupted != null) {
                GenerationAck disposition = disposeInterrupted(interrupted, selection.interruptedBatchPolicy());
                if (disposition != null) {
                    return disposition;
                }
            }
        }

        GenerationRequest request = new GenerationRequest(connectorId, TenantContext.get(),
                AdminRequestContext.findUserIdOrNull(), trigger, selection.degradeReason());
        return selection.kind() == GeneratorKind.AGENT
                ? agentGenerator.submit(request)
                : singleGenerator.submit(request);
    }

    private ConnectorSemanticGeneration findInterrupted(Long connectorId) {
        return generationMapper.selectOne(new LambdaQueryWrapper<ConnectorSemanticGeneration>()
                .eq(ConnectorSemanticGeneration::getConnectorId, connectorId)
                .eq(ConnectorSemanticGeneration::getStatus, STATUS_INTERRUPTED)
                .orderByDesc(ConnectorSemanticGeneration::getCreateTime)
                .last("LIMIT 1"));
    }

    /** 返回非 null 表示本次触发到此为止；null 表示继续提交单次推导。 */
    private GenerationAck disposeInterrupted(ConnectorSemanticGeneration interrupted,
                                             InterruptedBatchPolicy policy) {
        if (policy == InterruptedBatchPolicy.KEEP) {
            writeKeepNote(interrupted.getConnectorId());
            return new GenerationAck(GeneratorKind.AGENT, false, interrupted.getId(), KEEP_NOTE);
        }
        if (policy == InterruptedBatchPolicy.LEAVE) {
            return null;
        }

        Boolean superseded = transactionTemplate.execute(status -> supersede(interrupted, status));
        if (!Boolean.TRUE.equals(superseded)) {
            // 观察到的 INTERRUPTED 已被别人续跑或处理，不能再并行启动一条单次推导。
            return new GenerationAck(GeneratorKind.AGENT, true, interrupted.getId(), MERGED_NOTE);
        }
        return null;
    }

    private boolean supersede(ConnectorSemanticGeneration interrupted,
                              org.springframework.transaction.TransactionStatus transaction) {
        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        update.setStatus(STATUS_CANCELLED);
        update.setReasonCode(GenerationReasonCode.SUPERSEDED.name());
        update.setNote("已有中断批次被新的单次推导替代");
        update.setFinishedAt(new Date());
        int rows = generationMapper.update(update, new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                .set(ConnectorSemanticGeneration::getCurrentRunId, null)
                .set(ConnectorSemanticGeneration::getOwnerToken, null)
                .set(ConnectorSemanticGeneration::getNotBefore, null)
                .eq(ConnectorSemanticGeneration::getId, interrupted.getId())
                .eq(ConnectorSemanticGeneration::getStatus, STATUS_INTERRUPTED));
        if (rows == 0) {
            transaction.setRollbackOnly();
            return false;
        }
        if (MODE_STAGED.equals(interrupted.getMode())) {
            stagedMapper.physicalDeleteByGeneration(interrupted.getTenantId(), interrupted.getId());
        }
        return true;
    }

    private void writeKeepNote(Long connectorId) {
        Connection update = new Connection();
        update.setSemanticNote(KEEP_NOTE);
        connectionMapper.update(update, new LambdaUpdateWrapper<Connection>()
                .eq(Connection::getId, connectorId)
                .and(w -> w.isNull(Connection::getSemanticStatus)
                        .or().ne(Connection::getSemanticStatus, "RUNNING")));
    }
}
