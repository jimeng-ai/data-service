package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.KbChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 物理删除某文档的全部分片（绕开 {@code @TableLogic} 逻辑删除）。
     *
     * <p>必须物理删：kb_chunk 上有唯一键 {@code uk_chunk_id(chunk_id)}，逻辑删除只置 deleted=1、
     * 仍占着唯一键。重新入库（retry / 重新切片）时用相同 {@code docId_index} 作 chunk_id 再插入会撞
     * {@code Duplicate entry}，导致整批索引回滚、入库失败、Rabbit 反复重试。先物理清掉旧行才幂等。
     */
    @Delete("DELETE FROM kb_chunk WHERE doc_id = #{docId}")
    int physicalDeleteByDocId(@Param("docId") Long docId);

    /** 物理删除某知识库的全部分片（同上，绕开逻辑删除，避免唯一键残留）。 */
    @Delete("DELETE FROM kb_chunk WHERE kb_id = #{kbId}")
    int physicalDeleteByKbId(@Param("kbId") Long kbId);
}
