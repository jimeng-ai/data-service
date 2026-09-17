package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticDeriveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 包装现有 Java 单次推导的生成策略。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleCallSemanticGenerator implements SemanticGenerator {

    private final ConnectorSemanticDeriveService deriveService;

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
                    : "降级原因：" + reason.trim() + "；";
            deriveService.deriveAsync(request.connectorId(), prefix);
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
