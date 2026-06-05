package com.jimeng.dataserver.ai.billing;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.runtime.AgentIdContext;
import com.jimeng.dataserver.ai.billing.pricing.ModelPricing;
import com.jimeng.dataserver.ai.billing.usage.NormalizedUsage;
import com.jimeng.dataserver.ai.billing.usage.UsageExtractor;
import com.jimeng.persistence.entity.AiModelCallContent;
import com.jimeng.persistence.entity.AiModelCallLog;
import com.jimeng.persistence.mapper.AiModelCallContentMapper;
import com.jimeng.persistence.mapper.AiModelCallLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.jimeng.dataserver.ai.billing.support.MapValues.firstNonBlank;
import static com.jimeng.dataserver.ai.billing.support.MapValues.firstNonNull;
import static com.jimeng.dataserver.ai.billing.support.MapValues.getBoolean;
import static com.jimeng.dataserver.ai.billing.support.MapValues.getDecimal;
import static com.jimeng.dataserver.ai.billing.support.MapValues.getInteger;
import static com.jimeng.dataserver.ai.billing.support.MapValues.getString;
import static com.jimeng.dataserver.ai.billing.support.RequestContextUtil.currentRequest;
import static com.jimeng.dataserver.ai.billing.support.RequestContextUtil.getHeader;
import static com.jimeng.dataserver.ai.billing.support.RequestContextUtil.maskSensitiveHeaders;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiModelCallRecordService {

    private static final int STATUS_PENDING = 0;
    private static final int STATUS_SUCCESS = 1;
    private static final int STATUS_FAILED = 2;
    private static final int MAX_ERROR_MSG_LEN = 1000;

    private final AiModelCallLogMapper aiModelCallLogMapper;
    private final AiModelCallContentMapper aiModelCallContentMapper;
    private final UsageExtractor usageExtractor;
    private final ModelPricing modelPricing;

    public Long recordRequest(Map<String, Object> requestBody, Map<String, String> requestHeaders) {
        return recordRequest(requestBody, requestHeaders, "anthropic", "/v1/messages", "claude-opus-4-6");
    }

    public Long recordRequest(Map<String, Object> requestBody, Map<String, String> requestHeaders,
                              String provider, String endpoint, String defaultModel) {
        AiModelCallLog logEntity = new AiModelCallLog();
        logEntity.setProvider(provider);
        logEntity.setEndpoint(endpoint);
        logEntity.setModel(getString(requestBody, "model", defaultModel));
        logEntity.setStream(getBoolean(requestBody, "stream"));
        logEntity.setMaxTokens(firstNonNull(
                getInteger(requestBody, "max_tokens"),
                getInteger(requestBody, "max_completion_tokens")
        ));
        logEntity.setTemperature(getDecimal(requestBody, "temperature"));
        logEntity.setTopP(getDecimal(requestBody, "top_p"));
        logEntity.setRetryCount(0);
        logEntity.setCallStatus(STATUS_PENDING);

        // 业务字段（可选）
        logEntity.setBizType(getString(requestBody, "biz_type", null));
        logEntity.setBizId(getString(requestBody, "biz_id", null));
        logEntity.setSceneCode(getString(requestBody, "scene_code", null));
        // Agent 维度：agent_id 在转发前会被 ClaudeService.applyAgentContext 从 body 移除，
        // 所以优先读 body、再回退到 AgentIdContext（由 MdcAsyncSupport 透传到流式异步线程）。
        // 落库以便仪表盘「最近使用」按 Agent 统计；非数字或缺失时忽略。
        String agentIdStr = firstNonBlank(
                getString(requestBody, "agent_id", null),
                AgentIdContext.get());
        if (agentIdStr != null && !agentIdStr.isBlank()) {
            try {
                logEntity.setAgentId(Long.valueOf(agentIdStr.trim()));
            } catch (NumberFormatException ignored) {
                // ignore non-numeric agent_id
            }
        }

        // 从请求头/上下文提取链路和用户信息
        HttpServletRequest request = currentRequest();
        logEntity.setTraceId(firstNonBlank(
                getHeader(request, "trace-id"),
                getHeader(request, "x-trace-id"),
                getString(requestHeaders, "trace-id", null),
                getString(requestHeaders, "x-trace-id", null)
        ));
        logEntity.setTenantId(firstNonBlank(
                getString(requestBody, "tenant_id", null),
                getHeader(request, "tenant-id"),
                getHeader(request, "x-tenant-id"),
                // 流式问答在异步线程里记录日志，此时已无 HTTP 请求上下文，
                // 但 MdcAsyncSupport 已把租户传播到 TenantContext，作为兜底来源。
                TenantContext.get()
        ));
        logEntity.setUserId(firstNonBlank(
                getString(requestBody, "user_id", null),
                getHeader(request, "user-id"),
                getHeader(request, "x-user-id")
        ));

        List<String> toolNames = extractToolNames(requestBody);
        logEntity.setHasTool(!toolNames.isEmpty());
        logEntity.setToolNames(toolNames.isEmpty() ? null : String.join(",", toolNames));

        ContentFlags flags = extractContentFlags(requestBody);
        logEntity.setHasText(flags.hasText);
        logEntity.setHasImage(flags.hasImage);
        logEntity.setHasDocument(flags.hasDocument);
        logEntity.setHasVideo(flags.hasVideo);

        aiModelCallLogMapper.insert(logEntity);

        AiModelCallContent contentEntity = new AiModelCallContent();
        contentEntity.setLogId(logEntity.getId());
        contentEntity.setReqHeaders(JSONUtil.toJsonStr(maskSensitiveHeaders(requestHeaders)));
        contentEntity.setReqBody(JSONUtil.toJsonStr(requestBody));
        aiModelCallContentMapper.insert(contentEntity);

        return logEntity.getId();
    }

    public void recordResponse(Long logId, Integer httpStatus, String responseBody, Integer latencyMs) {
        AiModelCallLog logEntity = new AiModelCallLog();
        logEntity.setId(logId);
        logEntity.setHttpStatus(httpStatus);
        logEntity.setLatencyMs(latencyMs);
        logEntity.setCallStatus(isSuccess(httpStatus) ? STATUS_SUCCESS : STATUS_FAILED);

        boolean isStream = false;
        if (StrUtil.isNotBlank(responseBody) && JSONUtil.isTypeJSON(responseBody)) {
            JSONObject root = JSONUtil.parseObj(responseBody);
            logEntity.setRequestId(root.getStr("id"));
            NormalizedUsage usage = usageExtractor.extract(root.getJSONObject("usage"));
            applyUsage(logEntity, usage, resolveModel(root.getStr("model"), logId));
            isStream = Boolean.TRUE.equals(root.getBool("stream"));

            JSONObject error = root.getJSONObject("error");
            if (error != null) {
                logEntity.setErrorCode(error.getStr("type"));
                logEntity.setErrorMsg(limit(error.getStr("message"), MAX_ERROR_MSG_LEN));
                logEntity.setCallStatus(STATUS_FAILED);
            }
        } else if (!isSuccess(httpStatus)) {
            logEntity.setErrorMsg(limit(responseBody, MAX_ERROR_MSG_LEN));
        }
        aiModelCallLogMapper.updateById(logEntity);

        LambdaUpdateWrapper<AiModelCallContent> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AiModelCallContent::getLogId, logId);
        if (isStream) {
            wrapper.set(AiModelCallContent::getStreamEvents, responseBody);
        } else {
            wrapper.set(AiModelCallContent::getRespBody, responseBody);
        }
        aiModelCallContentMapper.update(null, wrapper);
    }

    /**
     * 记录流式响应结果。直接使用累积器提供的 token 用量，
     * 将流事件 JSON 存入 content 表的 stream_events 字段。
     *
     * @param logId            日志记录 ID
     * @param httpStatus       HTTP 状态码
     * @param inputTokens      输入 token 数
     * @param outputTokens     输出 token 数
     * @param streamEventsJson 流事件 JSON 字符串
     * @param latencyMs        延迟毫秒数
     */
    public void recordStreamResponse(Long logId, Integer httpStatus,
                                     int inputTokens, int outputTokens,
                                     String streamEventsJson, Integer latencyMs,
                                     String requestId) {
        AiModelCallLog logEntity = new AiModelCallLog();
        logEntity.setId(logId);
        logEntity.setHttpStatus(httpStatus);
        logEntity.setLatencyMs(latencyMs);
        logEntity.setCallStatus(isSuccess(httpStatus) ? STATUS_SUCCESS : STATUS_FAILED);
        if (StrUtil.isNotBlank(requestId)) {
            logEntity.setRequestId(requestId);
        }
        // 从重建的响应体解析 usage（含缓存 token）与 model；解析不到时回退到累积器给的 input/output。
        NormalizedUsage usage = parseUsage(streamEventsJson);
        if (usage.getInputTokens() == null) {
            usage.setInputTokens(inputTokens);
        }
        if (usage.getOutputTokens() == null) {
            usage.setOutputTokens(outputTokens);
        }
        if (usage.getTotalTokens() == null) {
            usage.setTotalTokens(nz(usage.getInputTokens()) + nz(usage.getOutputTokens()));
        }
        applyUsage(logEntity, usage, resolveModel(extractModel(streamEventsJson), logId));
        aiModelCallLogMapper.updateById(logEntity);

        LambdaUpdateWrapper<AiModelCallContent> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(AiModelCallContent::getLogId, logId);
        wrapper.set(AiModelCallContent::getStreamEvents, streamEventsJson);
        aiModelCallContentMapper.update(null, wrapper);
    }

    /**
     * 记录一次「按文档聚合」的模型调用。用于 RAG contextualization：一份文档的每个 chunk
     * 都要调一次 LLM，逐次落库会在 {@code ai_model_call_log} 里产生上千行，因此调用方把整篇
     * 文档各次 usage 累加成一个 {@link NormalizedUsage}，在此写成一行（一次性成功态，含汇总 cost）。
     *
     * @param usage     已累加的归一化用量（input/output/cache 等为整篇文档各次之和）
     * @param callCount 实际计入的调用次数（写入 content 备注，便于核对）
     * @return 日志记录 ID
     */
    public Long recordAggregatedCall(String provider, String endpoint, String model, String bizType,
                                     NormalizedUsage usage, int callCount, Integer latencyMs) {
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("aggregated", true);
        note.put("biz_type", bizType);
        note.put("call_count", callCount);
        return recordComputedCall(provider, endpoint, model, bizType, usage, 200, latencyMs, note);
    }

    /**
     * 记录一次「用量由调用方算好」的合成调用：usage 不来自标准响应解析，而是调用方累加/估算后传入。
     * 用于 RAG contextualization（按文档聚合）与 rerank（响应常无 usage，按 query+文档估算 token）。
     * 一次性写一行（含汇总 cost），失败态由 httpStatus 决定。
     *
     * @param usage      归一化用量（已由调用方算好）
     * @param httpStatus HTTP 状态（决定成功/失败态）
     * @param note       写入 content.req_body 的备注（aggregated / usage_estimated / call_count 等）
     * @return 日志记录 ID
     */
    public Long recordComputedCall(String provider, String endpoint, String model, String bizType,
                                   NormalizedUsage usage, Integer httpStatus, Integer latencyMs,
                                   Map<String, Object> note) {
        AiModelCallLog logEntity = new AiModelCallLog();
        logEntity.setProvider(provider);
        logEntity.setEndpoint(endpoint);
        logEntity.setModel(model);
        logEntity.setStream(false);
        logEntity.setRetryCount(0);
        logEntity.setBizType(bizType);
        logEntity.setHttpStatus(httpStatus);
        logEntity.setLatencyMs(latencyMs);
        logEntity.setCallStatus(isSuccess(httpStatus) ? STATUS_SUCCESS : STATUS_FAILED);
        logEntity.setHasText(true);

        // 租户/用户/链路：可能跑在入库异步线程（无 HTTP 请求），主要靠 TenantContext（Consumer/Filter 已设置）。
        HttpServletRequest request = currentRequest();
        logEntity.setTraceId(firstNonBlank(getHeader(request, "trace-id"), getHeader(request, "x-trace-id")));
        logEntity.setTenantId(firstNonBlank(
                getHeader(request, "tenant-id"),
                getHeader(request, "x-tenant-id"),
                TenantContext.get()));
        logEntity.setUserId(firstNonBlank(getHeader(request, "user-id"), getHeader(request, "x-user-id")));

        applyUsage(logEntity, usage, model);
        aiModelCallLogMapper.insert(logEntity);

        AiModelCallContent contentEntity = new AiModelCallContent();
        contentEntity.setLogId(logEntity.getId());
        contentEntity.setReqBody(JSONUtil.toJsonStr(note == null ? Map.of() : note));
        if (usage != null && StrUtil.isNotBlank(usage.getRawJson())) {
            contentEntity.setRespBody(usage.getRawJson());
        }
        aiModelCallContentMapper.insert(contentEntity);

        return logEntity.getId();
    }

    public void recordException(Long logId, Throwable throwable, Integer latencyMs) {
        AiModelCallLog logEntity = new AiModelCallLog();
        logEntity.setId(logId);
        logEntity.setCallStatus(STATUS_FAILED);
        logEntity.setLatencyMs(latencyMs);
        logEntity.setErrorCode(throwable == null ? "UNKNOWN_ERROR" : throwable.getClass().getSimpleName());
        logEntity.setErrorMsg(limit(throwable == null ? "未知异常" : throwable.getMessage(), MAX_ERROR_MSG_LEN));
        aiModelCallLogMapper.updateById(logEntity);
    }

    /** 把归一化用量 + 费用写入日志实体（兼容 OpenAI / Anthropic）。 */
    private void applyUsage(AiModelCallLog logEntity, NormalizedUsage usage, String model) {
        logEntity.setInputTokens(usage.getInputTokens());
        logEntity.setOutputTokens(usage.getOutputTokens());
        logEntity.setTotalTokens(usage.getTotalTokens());
        logEntity.setCacheReadTokens(usage.getCacheReadTokens());
        logEntity.setCacheWriteTokens(usage.getCacheWriteTokens());
        logEntity.setReasoningTokens(usage.getReasoningTokens());
        logEntity.setUsageRaw(usage.getRawJson());
        logEntity.setCostUsd(modelPricing.compute(model, usage));
    }

    /** 解析响应体里的 usage 对象（无 / 非 JSON 时返回空对象，不抛异常）。 */
    private NormalizedUsage parseUsage(String responseJson) {
        if (StrUtil.isBlank(responseJson) || !JSONUtil.isTypeJSON(responseJson)) {
            return new NormalizedUsage();
        }
        return usageExtractor.extract(JSONUtil.parseObj(responseJson).getJSONObject("usage"));
    }

    /** 从响应体读取 model（流式重建体含顶层 model）。 */
    private String extractModel(String responseJson) {
        if (StrUtil.isBlank(responseJson) || !JSONUtil.isTypeJSON(responseJson)) {
            return null;
        }
        return JSONUtil.parseObj(responseJson).getStr("model");
    }

    /** model 缺失时回落到请求阶段已落库的 model，保证计费能命中单价。 */
    private String resolveModel(String modelFromResponse, Long logId) {
        if (StrUtil.isNotBlank(modelFromResponse)) {
            return modelFromResponse;
        }
        AiModelCallLog existing = aiModelCallLogMapper.selectById(logId);
        return existing == null ? null : existing.getModel();
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private List<String> extractToolNames(Map<String, Object> requestBody) {
        Object toolsObj = requestBody.get("tools");
        if (!(toolsObj instanceof List<?> tools) || tools.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object toolObj : tools) {
            if (!(toolObj instanceof Map<?, ?> toolMap)) {
                continue;
            }
            Object nameObj = toolMap.get("name");
            if (nameObj == null && toolMap.get("function") instanceof Map<?, ?> functionMap) {
                nameObj = functionMap.get("name");
            }
            if (nameObj != null && StrUtil.isNotBlank(String.valueOf(nameObj))) {
                names.add(String.valueOf(nameObj));
            }
        }
        return names;
    }

    private ContentFlags extractContentFlags(Map<String, Object> requestBody) {
        ContentFlags flags = new ContentFlags();
        Object messagesObj = requestBody.get("messages");
        if (!(messagesObj instanceof List<?> messages)) {
            return flags;
        }
        for (Object messageObj : messages) {
            if (!(messageObj instanceof Map<?, ?> messageMap)) {
                continue;
            }
            Object contentObj = messageMap.get("content");
            if (contentObj instanceof String) {
                flags.hasText = true;
                continue;
            }
            if (contentObj instanceof List<?> contentList) {
                for (Object blockObj : contentList) {
                    if (!(blockObj instanceof Map<?, ?> blockMap)) {
                        continue;
                    }
                    String type = blockMap.get("type") == null ? null : String.valueOf(blockMap.get("type"));
                    if ("text".equals(type)) {
                        flags.hasText = true;
                    } else if ("image".equals(type) || "image_url".equals(type) || "input_image".equals(type)) {
                        flags.hasImage = true;
                    } else if ("document".equals(type) || "file".equals(type) || "input_file".equals(type)) {
                        flags.hasDocument = true;
                    } else if ("video".equals(type) || "input_video".equals(type) || "video_url".equals(type)) {
                        flags.hasVideo = true;
                    }
                }
            }
        }
        return flags;
    }

    private boolean isSuccess(Integer httpStatus) {
        return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
    }

    private String limit(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private static class ContentFlags {
        private boolean hasText = false;
        private boolean hasImage = false;
        private boolean hasDocument = false;
        private boolean hasVideo = false;
    }
}
