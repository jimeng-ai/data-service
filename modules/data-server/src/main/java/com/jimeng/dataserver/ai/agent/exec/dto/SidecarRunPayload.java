package com.jimeng.dataserver.ai.agent.exec.dto;

import lombok.Data;

import java.util.List;

/**
 * data-service -> 边车 /sandbox/run 的请求体。字段名（camelCase）须与边车 TS 的 RunRequest 一致。
 * 用 Hutool JSONUtil 序列化（数字按数字输出，不受 spring.jackson.write_numbers_as_strings 影响）。
 */
@Data
public class SidecarRunPayload {

    private String runId;
    private String tenantId;
    private String userId;
    private String traceId;
    private String agentId;
    private String prompt;
    private List<History> history;
    private List<InputFile> inputFiles;
    /** 边车把产物写到这个 bucket（= RagMinioStorageService 的 bucket），保证下载端点能读到。 */
    private String artifactBucket;
    /** 非空时边车获得 search_knowledge_base MCP 工具（A+B 统一：同一 agent 既跑代码又查知识库）。 */
    private RagContext ragContext;
    private Llm llm;
    /** 非空且 baseUrl/authToken/model 齐全时，边车注册 generate_image MCP 工具（OpenAI 兼容 /v1/images/generations）。 */
    private ImageGen imageGen;
    private Limits limits;

    @Data
    public static class RagContext {
        /** 该 agent 绑定的知识库 ID（取第一个）。 */
        private String kbId;
        private Integer topK;
        private Boolean rerank;
        /** 短时效 JWT，边车用它以用户身份回调网关的 /data/rag/search。 */
        private String accessToken;
    }

    @Data
    public static class History {
        private String role;
        private String content;
    }

    @Data
    public static class InputFile {
        private String objectName;
        private String filename;
        private String bucket;
        private Long sizeBytes;
    }

    @Data
    public static class Llm {
        private String baseUrl;
        private String authToken;
        private String model;
        private String authScheme;
    }

    /** 字段名须与边车 TS 的 ImageGenConfig 一致（camelCase）。 */
    @Data
    public static class ImageGen {
        private String baseUrl;
        private String authToken;
        private String model;
        private String authScheme;
    }

    @Data
    public static class Limits {
        private Integer wallClockSec;
        private Integer maxTurns;
        private Double maxBudgetUsd;
    }
}
