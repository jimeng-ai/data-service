package com.jimeng.dataserver.ai.billing.usage;

import cn.hutool.json.JSONObject;
import org.springframework.stereotype.Component;

/**
 * 把模型响应里的 {@code usage} 对象归一化为 {@link NormalizedUsage}，
 * 同时兼容 OpenAI（{@code prompt_tokens/completion_tokens} + *_tokens_details）
 * 与 Anthropic（{@code input_tokens/output_tokens} + cache_*_input_tokens）两套命名。
 */
@Component
public class UsageExtractor {

    public NormalizedUsage extract(JSONObject usage) {
        NormalizedUsage u = new NormalizedUsage();
        if (usage == null) {
            return u;
        }
        u.setRawJson(usage.toString());

        // 区分两套形态：仅 OpenAI 用 prompt_tokens，且其 cached_tokens 是 prompt 的子集。
        boolean openAiShape = usage.containsKey("prompt_tokens") && !usage.containsKey("input_tokens");
        u.setCacheReadInInput(openAiShape);

        Integer input = firstNonNull(usage.getInt("input_tokens"), usage.getInt("prompt_tokens"));
        Integer output = firstNonNull(usage.getInt("output_tokens"), usage.getInt("completion_tokens"));
        u.setInputTokens(input);
        u.setOutputTokens(output);

        Integer total = usage.getInt("total_tokens");
        if (total == null && (input != null || output != null)) {
            total = nz(input) + nz(output);
        }
        u.setTotalTokens(total);

        // 缓存读取：Anthropic cache_read_input_tokens；OpenAI prompt_tokens_details.cached_tokens
        Integer cacheRead = usage.getInt("cache_read_input_tokens");
        if (cacheRead == null) {
            JSONObject promptDetails = usage.getJSONObject("prompt_tokens_details");
            if (promptDetails != null) {
                cacheRead = promptDetails.getInt("cached_tokens");
            }
        }
        u.setCacheReadTokens(cacheRead);

        // 缓存写入：仅 Anthropic
        u.setCacheWriteTokens(usage.getInt("cache_creation_input_tokens"));

        // 推理/思考：仅 OpenAI 单列（Anthropic 已并入 output）
        JSONObject completionDetails = usage.getJSONObject("completion_tokens_details");
        if (completionDetails != null) {
            u.setReasoningTokens(completionDetails.getInt("reasoning_tokens"));
        }

        return u;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static Integer firstNonNull(Integer a, Integer b) {
        return a != null ? a : b;
    }
}
