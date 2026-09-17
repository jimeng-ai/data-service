package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.billing.AiModelCallRecordService;
import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import com.jimeng.dataserver.ai.model.ModelRegistry;
import com.jimeng.persistence.entity.AiModel;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Atomically aggregates one terminal slice's usage and records its actual model call. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticSliceUsageRecorder {

    private static final String ENDPOINT = "sandbox:semantic-agent";
    private static final String BIZ_TYPE = "semantic_agent";

    private final ConnectorSemanticGenerationMapper generationMapper;
    private final AiModelCallRecordService callRecordService;
    private final ModelRegistry modelRegistry;

    /**
     * @return false when owner/status CAS is lost; in that case no billing row is written by this stale owner.
     */
    public boolean record(ConnectorSemanticGeneration generation,
                          int sliceNo,
                          String runId,
                          String configuredModel,
                          SliceRunResult result,
                          long latencyMs) {
        if (generation == null || result == null || sliceNo < 1 || runId == null) {
            throw new IllegalArgumentException("generation, result, sliceNo and runId are required");
        }
        String previousTenant = TenantContext.get();
        TenantContext.set(generation.getTenantId());
        try {
            ConnectorSemanticGeneration live = generationMapper.selectById(generation.getId());
            if (!ownedAndRunning(live, generation.getOwnerToken())) {
                return false;
            }
            Set<String> mergedModels = mergeModels(live.getModelsSeen(), result.modelsSeen());
            NormalizedUsage usage = result.usage();
            LambdaUpdateWrapper<ConnectorSemanticGeneration> update =
                    new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                            .eq(ConnectorSemanticGeneration::getId, generation.getId())
                            .eq(ConnectorSemanticGeneration::getOwnerToken, generation.getOwnerToken())
                            .eq(ConnectorSemanticGeneration::getStatus, "RUNNING");
            if (usage == null) {
                update.setSql("slices_without_usage = COALESCE(slices_without_usage, 0) + 1");
            } else {
                update.setSql("input_tokens = COALESCE(input_tokens, 0) + {0}", value(usage.getInputTokens()));
                update.setSql("output_tokens = COALESCE(output_tokens, 0) + {0}", value(usage.getOutputTokens()));
                update.setSql("cache_read_tokens = COALESCE(cache_read_tokens, 0) + {0}",
                        value(usage.getCacheReadTokens()));
                update.setSql("cache_write_tokens = COALESCE(cache_write_tokens, 0) + {0}",
                        value(usage.getCacheWriteTokens()));
            }
            if (!mergedModels.isEmpty()) {
                update.set(ConnectorSemanticGeneration::getModelsSeen, String.join(",", mergedModels));
            }
            if (generationMapper.update(null, update) == 0) {
                return false;
            }
            if (usage != null) {
                recordBilling(generation, sliceNo, runId, configuredModel, result,
                        usage, Math.max(0L, latencyMs));
            }
            return true;
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private void recordBilling(ConnectorSemanticGeneration generation,
                               int sliceNo,
                               String runId,
                               String configuredModel,
                               SliceRunResult result,
                               NormalizedUsage usage,
                               long latencyMs) {
        String actualModel = result.modelsSeen().stream().sorted().findFirst().orElse(configuredModel);
        AiModel catalogModel = modelRegistry.priceOf(actualModel);
        String provider = catalogModel == null || catalogModel.getProvider() == null
                || catalogModel.getProvider().isBlank() ? "unknown" : catalogModel.getProvider();
        if (catalogModel == null) {
            log.warn("语义层片用量的模型未在目录中，provider 记为 unknown generationId={} sliceNo={} model={}",
                    generation.getId(), sliceNo, actualModel);
        } else if (hasZeroPrice(catalogModel)) {
            log.warn("语义层片模型单价未录入，费用记为 0 generationId={} sliceNo={} model={}",
                    generation.getId(), sliceNo, actualModel);
        }
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("generation_id", String.valueOf(generation.getId()));
        note.put("slice_no", sliceNo);
        note.put("run_id", runId);
        note.put("models_seen", result.modelsSeen().stream().sorted().toList());
        int status = result.httpStatus() == null
                ? ("success".equalsIgnoreCase(result.summaryStatus()) ? 200 : 500)
                : result.httpStatus();
        callRecordService.recordComputedCall(provider, ENDPOINT, actualModel, BIZ_TYPE, usage,
                status, (int) Math.min(Integer.MAX_VALUE, latencyMs), note);
    }

    static boolean hasZeroPrice(AiModel model) {
        return model == null
                || nonPositive(model.getPriceInput())
                || nonPositive(model.getPriceOutput())
                || nonPositive(model.getPriceCacheRead())
                || nonPositive(model.getPriceCacheWrite());
    }

    private static boolean nonPositive(BigDecimal price) {
        return price == null || price.signum() <= 0;
    }

    private static boolean ownedAndRunning(ConnectorSemanticGeneration live, String ownerToken) {
        return live != null && ownerToken != null && ownerToken.equals(live.getOwnerToken())
                && "RUNNING".equals(live.getStatus());
    }

    private static Set<String> mergeModels(String current, Set<String> observed) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (current != null && !current.isBlank()) {
            for (String model : current.split(",")) {
                if (!model.isBlank()) {
                    merged.add(model.trim());
                }
            }
        }
        List<String> sorted = new ArrayList<>(observed == null ? Set.of() : observed);
        sorted.sort(String::compareTo);
        merged.addAll(sorted);
        return merged;
    }

    private static long value(Integer number) {
        return number == null ? 0L : Math.max(0, number.longValue());
    }

    private static void restoreTenant(String previousTenant) {
        if (previousTenant == null || previousTenant.isEmpty()) {
            TenantContext.clear();
        } else {
            TenantContext.set(previousTenant);
        }
    }
}
