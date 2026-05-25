package com.jimeng.dataserver.ai.plugin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * 一次插件工具调用的运行时上下文。
 * 模板渲染时从这里按命名空间取值。
 */
@Getter
@RequiredArgsConstructor
public class PluginExecutionContext {

    /** 租户 ID（来自 {@link com.jimeng.common.core.tenant.TenantContext}） */
    private final String tenantId;

    /** LLM 提供的参数 → 通过 {@code {{input.xxx}}} 引用 */
    private final Map<String, Object> input;

    /** 解密/解析后的凭证 → 通过 {@code {{secrets.xxx}}} 引用 */
    private final Map<String, Object> secrets;

    /** 运行时生成的辅助值（timestamp/nonce/uuid/body_sha256）→ 通过 {@code {{env.xxx}}} 引用 */
    private final Map<String, Object> env;

    /** 请求级元数据（user-id/trace-id 等）→ 通过 {@code {{meta.xxx}}} 引用 */
    private final Map<String, Object> meta;
}
