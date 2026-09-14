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

    /**
     * 写操作开放程度：{@code FORBIDDEN}（默认，只读）| {@code REQUIRE_APPROVAL} | {@code AUTO}。
     *
     * <p><b>默认值只能是 FORBIDDEN。</b>这一列决定 Agent 能不能改客户的生产业务数据，
     * 出错的后果是业务事故，所以存量行、新建行、以及任何解析不出来的值一律落到只读
     * （解析见 {@code WritePolicy.parse}）。
     *
     * <p>它放宽的是<b>平台侧</b>的闸，放宽不了客户侧的账号权限——选了 AUTO 但客户给的仍是
     * 只读账号，写操作照样会被数据库拒绝。只读的承重层本来就在客户那边。
     */
    @Schema(description = "写操作开放程度：FORBIDDEN | REQUIRE_APPROVAL | AUTO")
    @TableField("write_policy")
    private String writePolicy;

    /**
     * 语义层推导状态。<b>推导失败不影响连接可用</b>——语义层是叠加的注解，没有它
     * conn_catalog / conn_describe 照常可用，只是模型少了那份「说明书」。
     * 所以这里是一个状态字段，不是一道闸。
     */
    @Schema(description = "语义层推导状态：NONE | RUNNING | READY | FAILED")
    @TableField("semantic_status")
    private String semanticStatus;

    @Schema(description = "语义层最近一次【成功】生成的时间；失败的重试不覆盖它")
    @TableField("semantic_synced_at")
    private Date semanticSyncedAt;

    /**
     * 并发认领凭据，<b>不是</b>给人看的时间。
     *
     * <p>单独一列而不是复用 {@code semantic_synced_at}：那一列要回答「上次什么时候还是好的」，
     * 而认领戳每次推导开始都会变、失败也会变。两个含义挤进一列的后果是——
     * 一次失败的重试就把上一次成功的时间抹掉，排查时最想要的那条信息正好没了。
     */
    @Schema(description = "语义层推导的认领时间（并发凭据，非业务时间）")
    @TableField("semantic_claim_at")
    private Date semanticClaimAt;

    @Schema(description = "推导结果摘要或失败原因，给管理台显示")
    @TableField("semantic_note")
    private String semanticNote;

    /**
     * 数据出库档位：{@code METADATA_ONLY}（纯元数据）| {@code DERIVED_STATS}（派生统计，<b>默认</b>）
     * | {@code SAMPLE_VALUES}（样本值，默认关闭、须企业超管显式开启）。
     *
     * <p>它回答的是客户最在意的那个问题：<b>为了看懂这个库，到底有什么东西会离开它</b>。
     * 「数据不出域」和「不能读数据」不是一回事——一个字节的数据值都不碰，表关系推断的精确率
     * 在真实生产库上从约 1.00 掉到约 0.49，<b>大约腰斩</b>，而推错的关系不报错，
     * 只会让模型 join 出一个看着很正常的错数字。所以这是三档，不是一个布尔。
     *
     * <p><b>不要在业务代码里比较这个字符串，也不要把它换算成 1/2/3。</b>
     * 一律 {@code SemanticDataTier.parse(...)} 成枚举后问谓词
     * （{@code allowsDerivedStats()} / {@code allowsSampleValues()}）。
     * 只要有人能拿到序号，迟早会出现一处本该是 {@code >} 的 {@code >=}，
     * 而那个错不会编译失败、不会跑挂，只是让客户的真实取值多走一档出去。
     *
     * <p>空值 = 默认档（{@code DERIVED_STATS}）：这一列是后加的，存量行的 NULL 只说明
     * 「没选过」，不说明「选了最严的」。认不出来的值 = 最严档（{@code METADATA_ONLY}）。
     * 两条兜底方向不同，但共守一条：<b>{@code SAMPLE_VALUES} 永远不可能由兜底到达</b>。
     */
    @Schema(description = "数据出库档位：METADATA_ONLY=只出结构 | DERIVED_STATS=可出派生统计（默认）"
            + " | SAMPLE_VALUES=可出真实取值（默认关闭，须超管显式开启）。空值按默认档，认不出来按最严档")
    @TableField("semantic_data_tier")
    private String semanticDataTier;
}
