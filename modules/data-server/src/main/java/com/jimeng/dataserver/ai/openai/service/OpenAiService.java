package com.jimeng.dataserver.ai.openai.service;

import com.jimeng.dataserver.ai.model.ModelResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * OpenAI 协议入口：把 /data/openai/chat/completions 请求按 requestBody.model 解析到对应连接的 chat client。
 * 解析不到模型时回落全局 active provider；协议须为 openai，否则 fail-fast 400 并指引到 /data/claude/messages。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenAiService {

    private static final String EXPECTED_PROTOCOL = "openai";

    private final ModelResolver modelResolver;

    public Object chatCompletions(Map<String, Object> requestBody) {
        return modelResolver.resolve(requestBody, EXPECTED_PROTOCOL).chat(requestBody, null);
    }

    public void chatCompletionsStream(Map<String, Object> requestBody, String connectionId, String traceId) {
        modelResolver.resolve(requestBody, EXPECTED_PROTOCOL).chatStream(requestBody, connectionId, traceId);
    }
}
