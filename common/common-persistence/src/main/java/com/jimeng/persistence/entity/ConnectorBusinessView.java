package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据星图的业务视图：一个对象或一条关系的「人话」——业务名、一句说明、业务领域、关系角色名。
 *
 * <p>语义层是写给 AI 的说明书，这张表是同一份理解写给业务人员和产品看的版本，由补全链按面向业务的要求改写后单独存放，
 * <b>不是语义层原文</b>。租户隔离表（已加入 TENANT_AWARE_TABLES）。
 *
 * <p><b>本表不产生软删死行</b>，这是 {@code uk_business_view} 不含 deleted 的前提：{@code MODEL} 行需要删除时物理删除
 * （{@code ConnectorBusinessViewMapper#physicalDeleteRow}），{@code HUMAN} 行只原地更新。不要给本表加逻辑删除入口。
 */
@Schema(description = "数据星图：给人看的业务名称与说明")
@EqualsAndHashCode(callSuper = true)
@TableName("connector_business_view")
@Data
public class ConnectorBusinessView extends BaseEntity {

    public static final String KIND_OBJECT = "OBJECT";
    public static final String KIND_RELATION = "RELATION";
    public static final String SOURCE_MODEL = "MODEL";
    /** 以后客户自己改的名字。模型永远不覆盖它。 */
    public static final String SOURCE_HUMAN = "HUMAN";

    @TableField("tenant_id")
    private String tenantId;

    @TableField("connector_id")
    private Long connectorId;

    @Schema(description = "OBJECT / RELATION")
    @TableField("kind")
    private String kind;

    @Schema(description = "表名；RELATION 为起点表")
    @TableField("object_name")
    private String objectName;

    @Schema(description = "RELATION 为起点列；OBJECT 为空串")
    @TableField("field_name")
    private String fieldName;

    @Schema(description = "业务名（OBJECT）/ 关系角色名（RELATION）")
    @TableField("display_name")
    private String displayName;

    @Schema(description = "一句话说明（仅 OBJECT）")
    @TableField("summary")
    private String summary;

    @Schema(description = "业务领域（仅 OBJECT）")
    @TableField("domain")
    private String domain;

    @Schema(description = "MODEL / HUMAN")
    @TableField("source")
    private String source;

    @Schema(description = "生成名称 / 说明 / 角色名时输入的 SHA-256")
    @TableField("input_hash")
    private String inputHash;

    @TableField("model_code")
    private String modelCode;

    @TableField("prompt_version")
    private String promptVersion;
}
