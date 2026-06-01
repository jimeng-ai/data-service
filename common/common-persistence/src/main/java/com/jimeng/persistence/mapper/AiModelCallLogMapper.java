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

    /** 按 Agent 聚合的调用数 / token / 成本，倒序取前 N（仅含 agent_id 非空的调用）。 */
    List<Map<String, Object>> selectTopAgents(@Param("tenantId") String tenantId,
                                              @Param("start") Date start,
                                              @Param("end") Date end,
                                              @Param("limit") int limit);

    /**
     * 最近 N 条调用记录（用于仪表盘「最近使用 · 按 Agent」信息流）。
     * 仅含 {@code agent_id} 非空的调用——RAG 子调用（embedding/rerank/contextualization）
     * 不带 agent_id，会被排除，避免淹没真正的 Agent 活动。
     */
    List<AiModelCallLog> selectRecentCalls(@Param("tenantId") String tenantId,
                                           @Param("limit") int limit);

    /**
     * 跨租户全平台汇总（运营侧使用，不带 tenant_id 过滤，需 runAsSystem 包裹）。
     * 额外返回 {@code tenantCount}=窗口内有调用的租户数。
     */
    Map<String, Object> selectCrossTenantOverview(@Param("start") Date start,
                                                  @Param("end") Date end);

    /** 按 tenant_id 分组的汇总（运营侧「各租户用量」主列表，需 runAsSystem 包裹）。 */
    List<Map<String, Object>> selectOverviewByTenant(@Param("start") Date start,
                                                     @Param("end") Date end);
}
