package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @TableName sys_dict
 */
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict")
@Data
public class SysDict extends BaseEntity {

    /**
     * 分组
     */
    @TableField("group_code")
    private String groupCode;

    /**
     * 字典key
     */
    @TableField("dict_key")
    private String dictKey;

    /**
     * 字典值
     */
    @TableField("dict_value")
    private String dictValue;

    /**
     * 用户id
     */
    @TableField("user_id")
    private Long userId;

}