package com.jimeng.dataserver.admin.operator.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * provider 连接下拉项：来自 Nacos providers.* 的连接名 + 其 chat 协议。
 * 页面据此让「选 provider 自动带出并锁定 protocol」。用 @Data class（非 record）。
 */
@Schema(description = "provider 连接下拉项")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProviderOption {

    @Schema(description = "连接名（Nacos providers.* 的 key）")
    private String name;

    @Schema(description = "该连接的 chat 协议：anthropic / openai")
    private String protocol;
}
