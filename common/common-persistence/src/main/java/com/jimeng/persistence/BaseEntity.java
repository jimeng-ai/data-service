package com.jimeng.persistence;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Date;

/**
 * @Author Moonlight
 * @Description 基础实体类
 * @Date 2024/7/13 15:37
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    // 主键（使用雪花算法）
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    // 逻辑删除0-未删除 1-已删除
    @TableLogic
    @TableField("deleted")
    private Boolean deleted;

    // 创建时间
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private Date createTime;

    // 创建人
    @TableField(value = "create_user", fill = FieldFill.INSERT)
    private String createUser;

    // 修改时间
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    // 修改人
    @TableField(value = "update_user", fill = FieldFill.INSERT_UPDATE)
    private String updateUser;

}
