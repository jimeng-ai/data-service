package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.AiTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.Map;

@Mapper
public interface AiTraceMapper extends BaseMapper<AiTrace> {

    /**
     * 时间窗口内的概览指标（trace 总数 / 平均耗时 / 错误率）。
     *
     * <p>租户隔离：{@code ai_trace} 在多租户白名单内，租户侧调用时拦截器自动注入
     * {@code tenant_id} 过滤；运营侧用 {@code runAsSystem} 跨租户，可选传 {@code tenantId}
     * 收窄到单个企业（为空则全平台）。
     *
     * <p>属主隔离：租户侧普通成员传 {@code userId}（自身 id）只统计本人；超管 / 运营侧传 {@code null} 看全部。
     */
    Map<String, Object> selectOverview(@Param("tenantId") String tenantId,
                                       @Param("userId") String userId,
                                       @Param("start") Date start,
                                       @Param("end") Date end);
}
