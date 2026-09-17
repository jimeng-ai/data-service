package com.jimeng.dataserver.ai.connector.generation.callback;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回调运行确认。确认必须先于请求级参数校验，并在独立事务中提交，避免后续 4000/5007
 * 把已经到达服务端的 callback 计数回滚而触发错误的 NO_CALLBACK 判断。
 */
@Service
@RequiredArgsConstructor
public class SemanticRunConfirmation {

    private final ConnectorSemanticGenerationMapper generationMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void confirm(SemanticAgentPrincipal principal) {
        if (principal == null) {
            throw new ServiceException(ExceptionCode.SEMANTIC_GENERATION_CLOSED);
        }
        int changed = generationMapper.update(null, new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                .setSql("current_run_callbacks = current_run_callbacks + 1")
                .eq(ConnectorSemanticGeneration::getId, principal.generationId())
                .eq(ConnectorSemanticGeneration::getTenantId, principal.tenantId())
                .eq(ConnectorSemanticGeneration::getConnectorId, principal.connectorId())
                .eq(ConnectorSemanticGeneration::getStatus, "RUNNING")
                .eq(ConnectorSemanticGeneration::getCurrentRunId, principal.runId()));
        if (changed != 1) {
            throw new ServiceException(ExceptionCode.SEMANTIC_GENERATION_CLOSED);
        }
    }
}
