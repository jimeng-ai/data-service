package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface ConnectorEnrichmentStateMapper extends BaseMapper<ConnectorEnrichmentState> {

    /**
     * 认领：只有没人认领、或上一次认领已经过期（{@code claim_at < staleBefore}）时才成功。
     *
     * <p>一条 UPDATE 完成「判断 + 占位」，多副本同时来抢只有一个拿到 1。{@code now} 必须截到秒：列是 DATETIME，
     * 带毫秒的值写进去会被舍入，之后按 {@code claim_at = 认领时间} 续期和收尾就再也对不上。
     *
     * @return 1 = 认领成功；0 = 别人正在跑（或行不存在）
     */
    @Update("UPDATE connector_enrichment_state SET claim_at = #{now}, last_attempt_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND connector_id = #{connectorId} "
            + "AND (claim_at IS NULL OR claim_at < #{staleBefore})")
    int claim(@Param("tenantId") String tenantId,
              @Param("connectorId") Long connectorId,
              @Param("now") Date now,
              @Param("staleBefore") Date staleBefore);
}
