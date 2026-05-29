package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.AiModelCallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Mapper
public interface AiModelCallLogMapper extends BaseMapper<AiModelCallLog> {

    /**
     * 时间窗口内的汇总指标（调用数 / token / 成本 / 平均延迟 / 成功数）。
     *
     * <p>注意：{@code ai_model_call_log} 不在多租户拦截器白名单内，
     * 因此这里必须显式带上 {@code tenant_id} 过滤，避免跨租户聚合。
     */
    Map<String, Object> selectOverview(@Param("tenantId") String tenantId,
                                       @Param("start") Date start,
                                       @Param("end") Date end);

    /** 按天聚合的调用数 / token（缺失的日期不会返回，由 service 补零）。 */
    List<Map<String, Object>> selectDailyTrend(@Param("tenantId") String tenantId,
                                               @Param("start") Date start,
                                               @Param("end") Date end);

    /** 按模型聚合的调用数 / token，倒序取前 N。 */
    List<Map<String, Object>> selectTopModels(@Param("tenantId") String tenantId,
                                              @Param("start") Date start,
                                              @Param("end") Date end,
                                              @Param("limit") int limit);

    /** 最近 N 条调用记录（用于"最近调用"信息流）。 */
    List<AiModelCallLog> selectRecentCalls(@Param("tenantId") String tenantId,
                                           @Param("limit") int limit);
}
