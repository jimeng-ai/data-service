package com.jimeng.dataserver.ai.agent.exec.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 沙箱边车配置（Nacos: agent.sandbox.*）。
 *
 * <p>provider-agnostic：转发给边车的 LLM 目标从这里取。换供应商只改这里（Phase 2 进一步
 * 改为指向 data-service 自己的 /data/claude/messages，则边车彻底不认识供应商）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.sandbox")
public class AgentSandboxProperties {

    /** 边车 base-url，例如 http://localhost:8088 */
    private String baseUrl;

    /** 调边车的内部鉴权 token（与边车 SANDBOX_SERVICE_TOKEN 一致；为空则不带头） */
    private String serviceToken;

    /** 墙钟超时（秒），与边车一致 */
    private int wallClockSec = 300;

    private int maxTurns = 25;

    private double maxBudgetUsd = 2;

    /** 转发给边车的 LLM 目标 */
    private Llm llm = new Llm();

    /** 转发给边车的生图目标（OpenAI 兼容 images/generations）；base-url/auth-token/model 任一为空则不启用生图工具 */
    private ImageGen imageGen = new ImageGen();

    @Data
    public static class Llm {
        private String baseUrl;
        private String authToken;
        private String model;
        private String authScheme = "bearer";
    }

    @Data
    public static class ImageGen {
        private String baseUrl;
        private String authToken;
        private String model;
        private String authScheme = "bearer";
        /** 生图上游形态：openai（默认，gpt-image-2 同步）/ kling-o3（可灵 o3 异步任务）。改这里即可切换生图模型。 */
        private String provider = "openai";
        /** 批量生图并发上限（一次多张时最多几张同时生成）；空/<=0 时边车用内置默认 5。302 限流就调小。 */
        private Integer batchConcurrency;
    }
}
