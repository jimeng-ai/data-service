package com.jimeng.dataserver.ai.connector.runtime;

import com.jimeng.dataserver.ai.connector.spi.Capability;

import java.util.Set;

/**
 * 一条连接器在「给模型看」这个视角下的摘要。
 *
 * <p>刻意<b>不含 id</b>：模型寻址用的是 {@code name}（租户内唯一、受
 * {@code ^[A-Za-z0-9_-]{1,64}$} 约束）。把雪花 id 交给模型没有任何好处——它会出现在模型的
 * 推理文本里、进对话历史、落进 {@code ai_model_call_content}，而 name 本来就是为此设计的。
 *
 * <p>{@code healthReason} 与 {@code readonlyVerified} 一定要给出去。模型看到
 * 「health_state=UNHEALTHY」却不知道为什么，就只能重试或者编；看不到「只读未验证」，
 * 就无法在回答里提示用户这条连接还没过安全校验。
 */
public record ConnectorSummary(
        String name,
        String displayName,
        String kind,
        Set<Capability> capabilities,
        String healthState,
        String healthReason,
        boolean readonlyVerified
) {}
