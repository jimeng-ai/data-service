package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 高德行政区 adcode-citycode 字典
 *
 * @TableName adcode_citycode_dict
 */
@Schema(description = "行政区编码字典实体")
@EqualsAndHashCode(callSuper = true)
@TableName("adcode_citycode_dict")
@Data
public class AdcodeCitycodeDict extends BaseEntity {

    @Schema(description = "序号")
    @TableField("sort_no")
    private Integer sortNo;

    @Schema(description = "中文名")
    @TableField("name_cn")
    private String nameCn;

    @Schema(description = "高德adcode")
    @TableField("adcode")
    private String adcode;

    @Schema(description = "高德citycode")
    @TableField("citycode")
    private String citycode;

}
