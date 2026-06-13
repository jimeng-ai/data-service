package com.jimeng.dataserver.ai.plugin.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.plugin.auth.TokenCachingAuthApplier;
import com.jimeng.dataserver.ai.plugin.dto.PluginExecutionContext;
import com.jimeng.dataserver.ai.plugin.dto.TokenFetchSpec;
import com.jimeng.persistence.entity.Plugin;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调试台「测试获取」：用插件已存的凭证真实调一次换取 token 的接口，原样回传响应，
 * 让前端把返回 JSON 渲染成可点选的树、由用户点字段自动生成 token_path / expire_path。
 *
 * <p>仅对换 token 的认证方式（OAUTH2 / TOKEN_FETCH，即 {@link TokenCachingAuthApplier}）有效；
 * 复用各 applier 的 {@code buildSpec} 与 {@link PluginTokenProvider#fetchRaw}，保证「测试通过」与
 * 「真实运行」走同一份请求逻辑。只回传、不缓存、不注入、不解析 token。
 */
@Slf4j
@Service
public class PluginAuthTestService {

    /** rawBody 回传上限，避免超大响应撑爆前端 JSON 树 */
    private static final int MAX_BODY = 16_000;

    private final Map<String, TokenCachingAuthApplier> appliersByType;
    private final PluginTokenProvider tokenProvider;
    private final PluginCredentialService credentialService;
    private final PluginCrudService crudService;

    public PluginAuthTestService(List<TokenCachingAuthApplier> appliers,
                                 PluginTokenProvider tokenProvider,
                                 PluginCredentialService credentialService,
                                 PluginCrudService crudService) {
        Map<String, TokenCachingAuthApplier> map = new LinkedHashMap<>();
        if (appliers != null) {
            for (TokenCachingAuthApplier a : appliers) {
                if (a != null && StringUtils.hasText(a.authType())) {
                    map.put(a.authType().toUpperCase(), a);
                }
            }
        }
        this.appliersByType = map;
        this.tokenProvider = tokenProvider;
        this.credentialService = credentialService;
        this.crudService = crudService;
    }

    /**
     * @param pluginId           插件 ID（调用方已做实例级鉴权）
     * @param authConfigOverride 调试台里正在编辑、尚未保存的 auth_config 草稿；为空则用已存配置
     * @param authTypeOverride   调试台里正在编辑、尚未保存的认证方式草稿；为空则用已存 authType
     */
    public TestFetchResult testFetch(Long pluginId, String authConfigOverride, String authTypeOverride) {
        Plugin plugin = crudService.getPlugin(pluginId);
        // 优先用草稿 authType：用户改了认证方式但还没保存也能直接测，免去「先保存再测试」的别扭
        String authType = StringUtils.hasText(authTypeOverride)
                ? authTypeOverride.toUpperCase()
                : (plugin.getAuthType() == null ? "" : plugin.getAuthType().toUpperCase());
        TokenCachingAuthApplier applier = appliersByType.get(authType);
        if (applier == null) {
            return TestFetchResult.error("该认证方式不支持测试获取（仅 OAuth2 / 通用 Token 获取）");
        }

        String authConfigJson = StringUtils.hasText(authConfigOverride)
                ? authConfigOverride : plugin.getAuthConfig();
        Map<String, Object> authConfig;
        try {
            authConfig = parseJsonMap(authConfigJson);
        } catch (Exception e) {
            return TestFetchResult.error("auth_config 不是合法 JSON：" + e.getMessage());
        }

        // 凭证缺失不硬失败：请求体可能是内联值（无 {{secrets.x}}），用空 secrets 继续；
        // 真要引用 secret 而没配，会在第三方响应里看到鉴权失败，照样有信息可看。
        Map<String, Object> secrets;
        try {
            secrets = credentialService.resolveSecrets(pluginId);
        } catch (PluginCredentialService.CredentialMissingException e) {
            secrets = Map.of();
        }

        TokenFetchSpec spec;
        try {
            spec = applier.buildSpec(authConfig, secrets);
        } catch (IllegalArgumentException e) {
            return TestFetchResult.error(e.getMessage());
        }

        PluginExecutionContext ctx = new PluginExecutionContext(
                TenantContext.get(), Map.of(), secrets, Map.of(), Map.of());

        long start = System.currentTimeMillis();
        PluginTokenProvider.RawResponse resp;
        try {
            resp = tokenProvider.fetchRaw(spec, ctx);
        } catch (PluginTokenProvider.TokenFetchException e) {
            return TestFetchResult.error(e.getMessage());
        }
        long durationMs = System.currentTimeMillis() - start;

        TestFetchResult r = new TestFetchResult();
        r.setHttpStatus(resp.status());
        r.setRawBody(truncate(resp.body()));
        r.setParsedJson(tryParse(resp.body()));
        r.setDurationMs(durationMs);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) throws Exception {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        return CommonUtil.getObjectMapper().readValue(json, Map.class);
    }

    /** 尽力把响应体解析成对象树（供前端点选）；非 JSON 返回 null，rawBody 仍可看。 */
    private Object tryParse(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            return CommonUtil.getObjectMapper().readValue(body, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_BODY ? s : s.substring(0, MAX_BODY) + "...(已截断)";
    }

    /** 测试获取结果：成功回 httpStatus + rawBody + parsedJson + durationMs；失败只回 error。 */
    @Data
    public static class TestFetchResult {
        private Integer httpStatus;
        private String rawBody;
        private Object parsedJson;
        private Long durationMs;
        private String error;

        public static TestFetchResult error(String msg) {
            TestFetchResult r = new TestFetchResult();
            r.setError(msg);
            return r;
        }
    }
}
