package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticDeriveService;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** 包装现有 Java 单次推导的生成策略。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleCallSemanticGenerator implements SemanticGenerator {

    private final ConnectorSemanticDeriveService deriveService;
    /** 字段名用于在多个 ThreadPoolTaskExecutor bean 之间消歧。 */
    private final ThreadPoolTaskExecutor semanticFallbackExecutor;

    @Override
    public GeneratorKind kind() {
        return GeneratorKind.SINGLE_CALL;
    }

    @Override
    public GenerationAck submit(GenerationRequest request) {
        try {
            String reason = request.degradeReason();
            String prefix = reason == null || reason.isBlank()
                    ? null
                    : "降级原因：" + reason.trim();
            Long connectorId = request.connectorId();
            semanticFallbackExecutor.execute(MdcAsyncSupport.wrap(
                    "semantic-fallback-" + connectorId,
                    () -> deriveService.deriveAsync(connectorId, prefix)));
            return new GenerationAck(kind(), true, null, "已提交单次推导");
        } catch (Exception e) {
            String detail = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            log.warn("单次语义推导提交失败 connectorId={}: {}",
                    request == null ? null : request.connectorId(), detail, e);
            return new GenerationAck(kind(), false, null, "单次推导提交失败：" + detail);
        }
    }
}
