package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.KbDocument;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {

    /**
     * 物理删除文档行（绕开 {@code @TableLogic} 逻辑删除）。
     *
     * <p>必须物理删：kb_document 上有唯一键 {@code uk_kb_hash(kb_id, file_hash)}，逻辑删除只置 deleted=1、
     * 仍占着唯一键。删除后重新上传同一文件时，幂等查询带 {@code deleted=0} 查不到旧软删行 → 走 INSERT →
     * 撞 {@code Duplicate entry} → 上传失败。delete() 本就已物理清理 MinIO 文件与 ES 索引，DB 行一并物理删才一致。
     */
    @Delete("DELETE FROM kb_document WHERE id = #{docId}")
    int physicalDeleteById(@Param("docId") Long docId);
}
