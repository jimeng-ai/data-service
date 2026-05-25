package com.jimeng.dataserver.ai.agent.runtime;

import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;

/**
 * 当前请求的 Agent 运行时上下文（ThreadLocal）。
 *
 * <p>由 ClaudeService 在请求入口处设置，供 {@link com.jimeng.dataserver.ai.skill.service.SkillRuntimeService}
 * 过滤插件可见性、{@link com.jimeng.dataserver.ai.plugin.executor.HttpPluginToolExecutor}
 * 取凭证别名时读取。
 *
 * <p>异步线程通过 {@link com.jimeng.dataserver.web.MdcAsyncSupport#wrap(String, Runnable)} 自动继承。
 */
public final class AgentContext {

    private static final ThreadLocal<AgentRuntimeView> CURRENT = new ThreadLocal<>();

    private AgentContext() {}

    public static void set(AgentRuntimeView view) {
        CURRENT.set(view);
    }

    public static AgentRuntimeView get() {
        return CURRENT.get();
    }

    public static boolean isSet() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
