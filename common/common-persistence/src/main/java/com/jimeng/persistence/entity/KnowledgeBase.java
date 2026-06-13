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

    @TableField("tenant_id")
    private String tenantId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    // ===== 以下为列表页聚合统计，非持久化列（由 KnowledgeBaseService.fillStats 回填）=====

    /** 文档数（kb_document 计数，已排除逻辑删除）。 */
    @TableField(exist = false)
    private Integer docCount;

    /** 切片总数（sum(total_chunks)）。 */
    @TableField(exist = false)
    private Long chunkCount;

    /** 文件总大小（sum(file_size)，字节）。 */
    @TableField(exist = false)
    private Long totalSize;

    /** 已完成入库的文档数，用于「索引中」进度（doneCount / docCount）。 */
    @TableField(exist = false)
    private Integer doneCount;

    /** 索引状态汇总：READY（全部完成/空）/ INDEXING（有文档在处理）/ ERROR（有文档失败）。 */
    @TableField(exist = false)
    private String indexStatus;
}
