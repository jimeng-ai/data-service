package com.jimeng.dataserver.ai.web;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对话链路内置 web 工具的全局配置（Nacos: ai.web-search.*）。
 *
 * <p>provider-agnostic：默认 anysearch（已实测 /v1/search）。配齐 base-url + api-key 即对
 * 【所有 Agent】开启 web_search/web_fetch；缺任一项则不启用，对话行为与现状完全一致。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.web-search")
public class WebSearchProperties {

    /** 上游搜索 API 形态；默认 anysearch。换后端改这里。 */
    private String provider = "anysearch";

    /** 搜索 API 根地址，例如 https://api.anysearch.com（工具会拼 /v1/search）。 */
    private String baseUrl;

    private String apiKey;

    /** search 默认返回条数。 */
    private Integer maxResults = 5;

    /** 配齐 base-url + api-key 才启用（method 命名避开 isXxx，免被 Spring 当可绑定属性）。 */
    public boolean enabled() {
        return StrUtil.isAllNotBlank(baseUrl, apiKey);
    }
}
