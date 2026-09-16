package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import org.apache.ibatis.annotations.Mapper;

/**
 * 语义层生成批次 Mapper。
 *
 * <p><b>状态迁移一律走「带条件的 update」，不要先查再改。</b>
 * {@code UPDATE ... SET status='RUNNING', owner_token=? WHERE id=? AND status='QUEUED'} 的返回行数就是「有没有抢到」；
 * 心跳、推进、收尾以 {@code owner_token} 做 CAS，返回 0 行就说明这一批已经不归自己管了。
 * 不需要自定义 SQL：BaseMapper 的 {@code update(null, LambdaUpdateWrapper)} 就返回行数；
 * 要写回 NULL 的列必须用 {@code set(列, null)}，{@code updateById} 会静默跳过 null 字段。
 * 计数（done / gave_up / removed）在 Java 侧 {@code selectCount} 后回写。
 *
 * <p><b>⚠ 不要调 {@code delete} / {@code deleteById}</b>：软删的未结束批次会永久占住
 * {@code uk_connector_semantic_generation_active}。批次行作为运行记录保留。
 */
@Mapper
public interface ConnectorSemanticGenerationMapper extends BaseMapper<ConnectorSemanticGeneration> {
}
