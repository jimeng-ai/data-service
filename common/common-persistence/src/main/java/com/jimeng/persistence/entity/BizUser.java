package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户表
 * @TableName user
 */
@EqualsAndHashCode(callSuper = true)
@TableName("user")
@Data
public class BizUser extends BaseEntity {

    /**
     * 名称
     */
    @TableField("name")
    private String name;

    /**
     * 昵称
     */
    @TableField("nick_name")
    private String nickName;

    /**
     * 账号
     */
    @TableField("account")
    private String account;

    /**
     * 密码
     */
    @TableField("password")
    private String password;

    /**
     * 电话号码
     */
    @TableField("phone")
    private String phone;

    /**
     * 邮箱
     */
    @TableField("email")
    private String email;

    /**
     * 是否启用
     */
    @TableField("is_enabled")
    private Boolean isEnabled;

}