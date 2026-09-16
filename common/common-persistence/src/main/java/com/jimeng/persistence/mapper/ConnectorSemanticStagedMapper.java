package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorSemanticStaged;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 语义层重新生成暂存行 Mapper。
 *
 * <p><b>写入逐行 {@code insert}</b>：本模块没有 {@code @Insert} 批量写法，一张表的产出在几十到几百行之间，逐行足够。
 *
 * <p><b>⚠ 不要调 {@code delete} / {@code deleteById}。</b>全局 {@code @TableLogic} 下它们是软删，
 * 死行占着 {@code uk_connector_semantic_staged}，同一张表重交就撞键。一律走下面三个物理删除。
 *
 * <p><b>⚠ 三个物理删除都显式带 {@code tenant_id}</b>，理由同 {@code ConnectorSemanticMapper} 里
 * 「语义层生成 agent」那段：系统模式下拦截器不加租户条件。调用方先 {@code requireOwned}，
 * {@code tenantId} 取自它返回的连接行。
 */
@Mapper
public interface ConnectorSemanticStagedMapper extends BaseMapper<ConnectorSemanticStaged> {

    /**
     * 删掉某张表提交产出的 OBJECT / FIELD / JOIN 暂存行：同一张表重交（整体替换语义）、或结构变化作废这张表时用。
     * CAVEAT 不删：它属于整条连接、按 term 合并，{@code owner_object} 只记第一个提交它的表，
     * 按它删会把别的表也提过的口径问题一起带走。
     */
    @Delete("DELETE FROM connector_semantic_staged WHERE tenant_id = #{tenantId} AND generation_id = #{generationId} "
            + "AND owner_object = #{ownerObject} AND scope IN ('OBJECT','FIELD','JOIN')")
    int physicalDeleteOwnedRows(@Param("tenantId") String tenantId,
                                @Param("generationId") Long generationId,
                                @Param("ownerObject") String ownerObject);

    /** 清掉一个批次的全部暂存行：收尾搬迁之后，或批次进入 FAILED、FELL_BACK、CANCELLED 时。 */
    @Delete("DELETE FROM connector_semantic_staged WHERE tenant_id = #{tenantId} AND generation_id = #{generationId}")
    int physicalDeleteByGeneration(@Param("tenantId") String tenantId, @Param("generationId") Long generationId);

    /** 删除连接时清理这条连接名下的全部暂存行（不论属于哪个批次）。 */
    @Delete("DELETE FROM connector_semantic_staged WHERE tenant_id = #{tenantId} AND connector_id = #{connectorId}")
    int physicalDeleteByConnector(@Param("tenantId") String tenantId, @Param("connectorId") Long connectorId);
}
