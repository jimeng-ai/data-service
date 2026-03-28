package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 高德POI分类与编码表
 *
 * @TableName poi_category_dict
 */
@Schema(description = "POI分类字典实体")
@EqualsAndHashCode(callSuper = true)
@TableName("poi_category_dict")
@Data
public class PoiCategoryDict extends BaseEntity {

    @Schema(description = "序号")
    @TableField("sort_no")
    private Integer sortNo;

    @Schema(description = "高德NEW_TYPE编码")
    @TableField("new_type")
    private String newType;

    @Schema(description = "大类（中文）")
    @TableField("big_category_cn")
    private String bigCategoryCn;

    @Schema(description = "中类（中文）")
    @TableField("mid_category_cn")
    private String midCategoryCn;

    @Schema(description = "小类（中文）")
    @TableField("sub_category_cn")
    private String subCategoryCn;

    @Schema(description = "大类（英文）")
    @TableField("big_category_en")
    private String bigCategoryEn;

    @Schema(description = "中类（英文）")
    @TableField("mid_category_en")
    private String midCategoryEn;

    @Schema(description = "小类（英文）")
    @TableField("sub_category_en")
    private String subCategoryEn;

}
