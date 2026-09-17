package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticStagedMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/** 删除连接事务内的语义层生成清理；运行记录保留，暂存产物物理删除。 */
@Component
public class SemanticGenerationCleanup {

    private static final List<String> UNFINISHED =
            List.of("QUEUED", "RUNNING", "FINALIZING", "INTERRUPTED");

    private final SemanticConnectionClaim connectionClaim;
    private final ConnectorSemanticStagedMapper stagedMapper;
    private final ConnectorSemanticGenerationMapper generationMapper;

    public SemanticGenerationCleanup(SemanticConnectionClaim connectionClaim,
                                     ConnectorSemanticStagedMapper stagedMapper,
                                     ConnectorSemanticGenerationMapper generationMapper) {
        this.connectionClaim = connectionClaim;
        this.stagedMapper = stagedMapper;
        this.generationMapper = generationMapper;
    }

    /**
     * 清理必须加入 {@code ConnectorService.delete} 的事务，且第一步锁连接行，保持与提交/收尾相同锁序。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onConnectorDeleted(String tenantId, Long connectorId) {
        connectionClaim.lockRow(connectorId);
        stagedMapper.physicalDeleteByConnector(tenantId, connectorId);

        ConnectorSemanticGeneration update = new ConnectorSemanticGeneration();
        update.setStatus("CANCELLED");
        update.setReasonCode(GenerationReasonCode.CONNECTION_DELETED.name());
        update.setNote("连接已删除，语义层生成已取消");
        update.setFinishedAt(new Date());
        generationMapper.update(update, new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                .set(ConnectorSemanticGeneration::getCurrentRunId, null)
                .set(ConnectorSemanticGeneration::getOwnerToken, null)
                .set(ConnectorSemanticGeneration::getNotBefore, null)
                .eq(ConnectorSemanticGeneration::getConnectorId, connectorId)
                .in(ConnectorSemanticGeneration::getStatus, UNFINISHED));
    }
}
