package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 外部系统连接。描述「允许调谁、用什么身份、能调多狠」，<b>不</b>描述「怎么调」
 * （后者是 skill 的 SKILL.md + scripts/ 的事）。
 *
 * <p>凭据以 AES-GCM 密文存放（{@code CredentialCipher}），明文任何时候都不落库、不进容器。
 *
 * @TableName connection
 */
@Schema(description = "外部系统连接")
@EqualsAndHashCode(callSuper = true)
@TableName("connection")
@Data
public class Connection extends BaseEntity {

    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "容器可见标识，也是 $JM_CONN_BASE/<name>/ 里的那一段")
    @TableField("name")
    private String name;

    @TableField("display_name")
    private String displayName;

    @Schema(description = "真实上游 base URL，容器不可见")
    @TableField("base_url")
    private String baseUrl;

    @Schema(description = "bearer | api-key")
    @TableField("auth_scheme")
    private String authScheme;

    @Schema(description = "AES-GCM 密文")
    @TableField("credential_cipher")
    private String credentialCipher;

    @TableField("encryption_version")
    private Integer encryptionVersion;

    @Schema(description = "逗号分隔的允许方法，默认 GET")
    @TableField("allow_methods")
    private String allowMethods;

    @Schema(description = "JSON 数组的路径 glob")
    @TableField("allow_paths")
    private String allowPaths;

    @Schema(description = "direct | tunnel")
    @TableField("transport")
    private String transport;

    @Schema(description = "ACTIVE | DISABLED")
    @TableField("status")
    private String status;
}
