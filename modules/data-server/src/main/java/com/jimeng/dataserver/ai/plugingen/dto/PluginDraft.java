package com.jimeng.dataserver.ai.plugingen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * AI 由一份 API 文档生成的「插件草稿」：插件元信息 + 多个工具。
 * baseUrl 与密钥永远留空给人工补全（{@link PluginAiService} 兜底置空）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginDraft {
    private PluginMeta plugin;
    private List<ToolSpec> tools;
    private List<String> warnings;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PluginMeta {
        private String name;
        private String description;
        /** 始终留空，由人工在「基础信息」里填 */
        private String baseUrl;
        private AuthMeta auth;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthMeta {
        /** NONE | API_KEY | BEARER | BASIC | HMAC */
        private String type;
        /** header | query（API_KEY 放哪） */
        private String in;
        /** 鉴权字段名，如 Authorization / X-Api-Key */
        private String name;
        /** 鉴权说明（密钥由人工填，AI 不编造） */
        private String notes;
    }
}
