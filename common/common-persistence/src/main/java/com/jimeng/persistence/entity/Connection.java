package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

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

    // ---- 连接器框架（V20260913）新增列 ----------------------------------------------------
    //
    // 这些列是把本表【演化】成连接器实例表的结果，不是新开一张平行表。平行表的代价是两套外部
    // 系统注册表并存：租户隔离、凭据加解密、Agent 授权、审计这些横切的事要写两遍，然后分叉。

    /**
     * 连接器类型标识，对应 {@code Connector#kind()}。存量行全部回填成 {@code HTTP}。
     *
     * <p><b>HTTP 类型继续用旧列</b>（base_url / auth_scheme / allow_methods / allow_paths），
     * 沙箱 egress 代理那条链路一个字节都不用改；新类型的参数一律进 {@link #configJson}。
     */
    @Schema(description = "连接器类型：HTTP | MYSQL")
    @TableField("kind")
    private String kind;

    /**
     * 非敏感参数 JSON，按该类型的 {@code ParamSpec} 校验后存入。敏感参数走
     * {@link #credentialCipher}（AES-GCM），<b>任何时候都不许落在这里</b>。
     */
    @Schema(description = "非敏感参数 JSON（按类型的参数定义校验）")
    @TableField("config_json")
    private String configJson;

    /**
     * 接入时探测出来的<b>实际可用</b>能力，逗号分隔（QUERY,DESCRIBE,...）。
     *
     * <p>不能只信类型的静态声明：同一种 MySQL，客户给的账号若读不了 information_schema，
     * DESCRIBE 就得降级成人工录入。不回填就会变成一次静默降级——配的时候看着正常，
     * 用的时候才发现少了东西。
     */
    @Schema(description = "探测后回填的实际可用能力，逗号分隔")
    @TableField("capability_flags")
    private String capabilityFlags;

    /**
     * 健康态：UNKNOWN | HEALTHY | UNHEALTHY。
     *
     * <p>本项目<b>没有 actuator</b>，没有 HealthIndicator / MeterRegistry 可挂，所以健康态
     * 只能自己存在行里、由框架的定时探测写回，界面直接读这一列。
     */
    @Schema(description = "健康态：UNKNOWN | HEALTHY | UNHEALTHY")
    @TableField("health_state")
    private String healthState;

    @Schema(description = "最近一次健康探测时间")
    @TableField("health_checked_at")
    private Date healthCheckedAt;

    /**
     * 探测失败原因。这里只放<b>已归类的安全文案</b>（ConnectorException 的 safeDetail），
     * 不放原始异常——JDBC / HTTP 异常常带主机名、连接参数，而这一列会随管理面响应出网。
     */
    @Schema(description = "探测失败原因（已脱敏的安全文案）")
    @TableField("health_reason")
    private String healthReason;

    /**
     * 只读验证通过的时间：试一个无害的写操作、<b>期望它失败</b>，失败才算通过。
     *
     * <p>为空表示没验过，不代表验过了不是只读的——护栏据此决定要不要放行写能力。
     */
    @Schema(description = "只读验证通过的时间；为空=未验证")
    @TableField("readonly_verified_at")
    private Date readonlyVerifiedAt;
}
