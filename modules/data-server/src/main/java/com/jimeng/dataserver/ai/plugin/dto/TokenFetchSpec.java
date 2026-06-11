package com.jimeng.dataserver.ai.plugin.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 token 获取的规格。
 * OAuth2 applier 拼固定值，Generic applier 从 {@code auth_config} 解析；统一交给
 * {@link com.jimeng.dataserver.ai.plugin.service.PluginTokenProvider} 执行。
 */
@Data
public class TokenFetchSpec {

    /** token 请求 */
    private String method = "POST";
    private String url;
    private String contentType = "application/json";
    private Map<String, String> headers = new LinkedHashMap<>();
    /** body 模板（含 {@code {{secrets.x}}}）；form 模式是 a=b&c=d 串，json 模式是 JSON 串 */
    private String bodyTemplate;

    /** 取值 */
    private String tokenPath = "$.access_token";
    private String expirePath;            // 可空 → 用 defaultTtlSec
    private String expireUnit = "sec";    // sec | ms
    private long defaultTtlSec = 3600;
    private long safetyMarginSec = 60;

    /** 注入 */
    private String injectLocation = "header"; // header | query
    private String injectName = "Authorization";
    private String injectPrefix = "Bearer ";
}
