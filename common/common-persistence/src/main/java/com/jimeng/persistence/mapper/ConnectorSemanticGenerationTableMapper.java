package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorSemanticGenerationTable;
import org.apache.ibatis.annotations.Mapper;

/**
 * 语义层生成批次内每表 Mapper。
 *
 * <p><b>状态迁移全部带「当前状态」条件做 CAS</b>（如 {@code WHERE id=? AND status='DISPATCHED' AND slice_no=?}），
 * 返回 0 行按「已被别处改过」处理：提交事务里这意味着整次提交回滚；对账与片结算里意味着这张表这一轮不归自己动。
 *
 * <p><b>⚠ 不要调 {@code delete} / {@code deleteById}</b>：软删死行占着 {@code uk_connector_semantic_generation_table}，
 * 同一批次里这张表就再也建不回来。每表记录与批次行一起作为运行记录保留。
 */
@Mapper
public interface ConnectorSemanticGenerationTableMapper extends BaseMapper<ConnectorSemanticGenerationTable> {
}
