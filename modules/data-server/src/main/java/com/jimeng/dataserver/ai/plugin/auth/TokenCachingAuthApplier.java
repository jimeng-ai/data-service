package com.jimeng.dataserver.ai.plugin.auth;

import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.RenderedRequest;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;

import java.util.Map;

/**
 * 需要服务端换 token + 缓存的鉴权 applier。
 *
 * <p>因需要 tenantId/pluginId 算缓存键，不走基类 {@link #apply}，改走 {@link #applyWithContext}。
 * {@link com.jimeng.dataserver.ai.plugin.service.PluginHttpInvoker} 用 {@code instanceof} 分派，
 * 并在业务接口返 401 时调 {@link #invalidate} 作废缓存后重试一次。
 */
public interface TokenCachingAuthApplier extends PluginAuthApplier {

    /** 注入 token（命中缓存或加锁 fetch）。 */
    void applyWithContext(RenderedRequest req, PluginExecutionContext ctx,
                          Long pluginId, Map<String, Object> authConfig);

    /** 业务接口 401 时作废该插件的 token 缓存。 */
    void invalidate(PluginExecutionContext ctx, Long pluginId, Map<String, Object> authConfig);

    /**
     * 把 auth_config 解析为统一的 token 请求规格，供调试台「测试获取」直接执行。
     * OAuth2 需要 {@code secrets} 拼 body/Basic；Generic 忽略 secrets（body 里以 {@code {{secrets.x}}} 模板引用）。
     * 默认不支持，由具体 applier 覆写。
     */
    default TokenFetchSpec buildSpec(Map<String, Object> authConfig, Map<String, Object> secrets) {
        throw new UnsupportedOperationException(authType() + " 不支持测试获取");
    }

    /** 基类入口对本类无效（缺 tenantId/pluginId 上下文）。 */
    @Override
    default void apply(RenderedRequest request, Map<String, Object> credentials, Map<String, Object> authConfig) {
        throw new UnsupportedOperationException("token-caching applier 必须走 applyWithContext");
    }
}
