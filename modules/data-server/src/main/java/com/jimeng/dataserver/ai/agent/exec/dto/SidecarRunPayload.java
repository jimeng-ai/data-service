package com.jimeng.dataserver.ai.agent.exec.dto;

import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * data-service -> 边车 /sandbox/run 的请求体。字段名（camelCase）须与边车 TS 的 RunRequest 一致。
 * 用 Hutool JSONUtil 序列化（数字按数字输出，不受 spring.jackson.write_numbers_as_strings 影响）。
 *
 * <p><b>字段名拼错不会报错</b>：边车对未知字段静默忽略。{@link Conn#scheme} 就是现成的例子——边车读的是
 * {@code authScheme}，api-key 连接被静默当成 bearer。新字段因此有 {@code SidecarRunPayloadJsonTest} 按边车
 * {@code src/types.ts} 逐字段对拍。
 *
 * <p>密钥字段（{@code authToken}、{@code accessToken}、{@code token}）一律 {@link ToString.Exclude}：
 * {@code @Data} 生成的 toString 会被顺手打进日志（{@code log.info("{}", payload)}），而这些是真实计费的模型 key
 * 与能以用户身份回调网关的 JWT。
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

    /**
     * Agent 人格（agent.system_prompt）。边车把它排在平台操作契约【之前】，契约仍是最后一句话。
     *
     * <p>补这个字段是因为：跨到沙箱平面时 payload 里原本没有它，于是同一个 agent 一旦带附件
     * 就换了人格——不报错，只是行为变了。这是执行平面统一的第一块。
     *
     * <p>注意 modelParams（temperature / max_tokens 等）【无法】走这条路：Claude Agent SDK 的
     * Options 不接受这些字段（sdk.d.ts 里 temperature 零命中）。要按 agent 调参只能在
     * /data/claude/messages 出口侧做。下面组装时对此显式告警，不静默丢。
     */
    private String systemPrompt;
    /** 非空且 baseUrl/authToken/model 齐全时，边车注册 generate_image MCP 工具（OpenAI 兼容 /v1/images/generations）。 */
    private ImageGen imageGen;
    /** 非空且 baseUrl/authToken 齐全时，边车注册 web search+fetch MCP 工具。字段名须与边车 TS 的 WebSearchConfig 一致。 */
    private WebSearch webSearch;
    /**
     * 本次 run 被授予的外部系统连接。边车转注册给 egress 代理；容器只见 name，
     * 真实 baseUrl 与 token 只存在于代理内存里，按源 IP 注入。
     */
    private List<Conn> connections;

    private Limits limits;
    /** 本次 run 可用的 DOER skill（编排者从 MinIO 列出文件，边车物化到 .claude/skills）。 */
    private List<SkillRef> skills;

    /**
     * 运行形态。null = 现有文件处理行为（边车的 default profile）；{@code "semantic-layer"} = 语义层生成。
     *
     * <p>其他调用方一律不设：Hutool 默认不输出 null 字段（5.8.16 实测），边车也把 null 与缺省同样对待，所以老调用方的请求体零变化。
     * 边车对未知取值回 400，不会静默当 default 跑。
     */
    private String runProfile;

    /**
     * 只在 {@code runProfile="semantic-layer"} 时下发：边车宿主进程里的 semantic MCP 工具回调 data-service 需要的一切。
     * 边车的 default profile 收到它直接 400（防新调用方漏写 runProfile 被静默当文件处理 agent 跑）。
     */
    private SemanticContext semanticContext;

    @Data
    public static class SkillRef {
        private String name;
        private List<SkillFile> files;
    }

    @Data
    public static class SkillFile {
        private String objectName;
        private String relPath;
        private String bucket;
    }

    @Data
    public static class RagContext {
        /** 该 agent 绑定的知识库 ID（取第一个）。 */
        private String kbId;
        private Integer topK;
        private Boolean rerank;
        /** 短时效 JWT，边车用它以用户身份回调网关的 /data/rag/search。 */
        @ToString.Exclude
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
        @ToString.Exclude
        private String authToken;
        private String model;
        private String authScheme;
    }

    /** 字段名须与边车 TS 的 ImageGenConfig 一致（camelCase）。 */
    @Data
    public static class ImageGen {
        private String baseUrl;
        @ToString.Exclude
        private String authToken;
        private String model;
        private String authScheme;
        /** 上游图像 API 形态：openai（默认，gpt-image-2）/ kling-o3（可灵 o3 异步任务）。 */
        private String provider;
        /** 批量生图并发上限；空时边车用内置默认。字段名须与边车 TS 的 batchConcurrency 一致。 */
        private Integer batchConcurrency;
    }

    /** 字段名须与边车 TS 的 WebSearchConfig 一致（camelCase）。 */
    @Data
    public static class WebSearch {
        private String baseUrl;
        @ToString.Exclude
        private String authToken;
        private String provider;
        private Integer maxResults;
        private String authScheme;
    }

    @Data
    public static class Conn {
        /** 容器可见标识，也是 $JM_CONN_BASE/<name>/ 里的那一段 */
        private String name;
        private String baseUrl;
        @ToString.Exclude
        private String token;
        private String scheme;
        /** 允许的方法；空则边车按默认 ["GET"] 处理（只读） */
        private List<String> allowMethods;
        /** 允许的路径 glob；空则边车按默认 ["/**"] 处理 */
        private List<String> allowPaths;
    }

    @Data
    public static class Limits {
        private Integer wallClockSec;
        private Integer maxTurns;
        private Double maxBudgetUsd;
    }

    /**
     * 字段名须与边车 TS 的 {@code SemanticContext}（{@code src/types.ts}）逐字一致。
     *
     * <p>两个 id 声明为 {@code String}：雪花 id 超出 JS 安全整数（2^53），按数字下发会被边车的
     * {@code JSON.parse} <b>静默改值</b>，边车也按十进制字符串校验、传 number 直接 400。
     * {@code sliceNo} 很小，按数字下发。
     */
    @Data
    public static class SemanticContext {
        /** 回调根地址，含 /data（connector.semantic.agent.callback-base-url）。边车在其后拼 /internal/semantic-agent/&lt;op&gt;。 */
        private String callbackBaseUrl;
        /** 本片窄权限 token，裸 JWT，不带 Bearer（网关取 Authorization 原值验签）。 */
        @ToString.Exclude
        private String accessToken;
        /** 批次 id，十进制字符串。只用于边车日志，服务端以 token 里的 gen 为准。 */
        private String generationId;
        /** 连接 id，十进制字符串。只用于边车日志，服务端以 token 里的 cid 为准。 */
        private String connectorId;
        /** 本片序号，从 1 开始。 */
        private Integer sliceNo;
    }
}
