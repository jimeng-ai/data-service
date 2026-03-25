package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @TableName sys_env
 */
@Schema(description = "系统环境变量实体")
@EqualsAndHashCode(callSuper = true)
@TableName("sys_env")
@Data
public class SysEnv extends BaseEntity {

    /**
     * 分组
     */
    @Schema(description = "模块名称/分组")
    @TableField("module_name")
    private String moduleName;

    /**
     * 名称
     */
    @Schema(description = "环境变量名称")
    @TableField("name")
    private String name;

    /**
     * 属性名称
     */
    @Schema(description = "属性名称（配置key）")
    @TableField("property_name")
    private String propertyName;

    /**
     * 属性值
     */
    @Schema(description = "属性值（配置value）")
    @TableField("property_value")
    private String propertyValue;

    /**
     * 备注
     */
    @Schema(description = "备注说明")
    @TableField("remark")
    private String remark;

}