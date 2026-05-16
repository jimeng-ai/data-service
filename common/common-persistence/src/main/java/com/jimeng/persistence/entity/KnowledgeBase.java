package com.jimeng.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jimeng.persistence.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_base")
@Data
public class KnowledgeBase extends BaseEntity {

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;
}
