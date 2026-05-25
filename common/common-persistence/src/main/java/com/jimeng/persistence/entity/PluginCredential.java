package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 插件凭证
 *
 * <p>当前版本：{@code credential_data} 字段明文存储 JSON。
 * 未来切加密时 {@code encryption_version} 升到 1+，字段值变成密文 base64，业务代码靠这个字段判断如何解读。
 *
 * @TableName plugin_credential
 */
@Schema(description = "插件凭证表")
@EqualsAndHashCode(callSuper = true)
@TableName("plugin_credential")
@Data
public class PluginCredential extends BaseEntity {

    @Schema(description = "租户 ID")
    @TableField("tenant_id")
    private String tenantId;

    @Schema(description = "所属插件 ID")
    @TableField("plugin_id")
    private Long pluginId;

    @Schema(description = "所属用户 ID（NULL = 租户内共享）")
    @TableField("owner_id")
    private String ownerId;

    @Schema(description = "凭证别名，如 prod / test")
    @TableField("alias")
    private String alias;

    @Schema(description = "凭证内容（当前版本：明文 JSON 字符串）")
    @TableField("credential_data")
    private String credentialData;

    @Schema(description = "加密版本：0=明文；未来加密版本走 1/2/...")
    @TableField("encryption_version")
    private Integer encryptionVersion;

    @Schema(description = "是否是该插件的默认凭证")
    @TableField("is_default")
    private Boolean isDefault;
}
