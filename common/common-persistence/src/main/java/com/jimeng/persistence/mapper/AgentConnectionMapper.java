package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.AgentConnection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentConnectionMapper extends BaseMapper<AgentConnection> {

    /**
     * 复活一条被软删除的授权行。
     *
     * <h3>为什么需要它：软删除 + 不含 deleted 的唯一键 = 撤销后无法再授权</h3>
     * {@code BaseEntity} 全局 {@code @TableLogic}，所以撤销授权执行的是
     * {@code UPDATE ... SET deleted = 1}；而 {@code uk_agent_connection_tenant_agent_conn}
     * 是 {@code (tenant_id, agent_id, connection_id)}，<b>不含 deleted</b>。
     *
     * <p>于是「撤销 → 再授权」这个再正常不过的操作会走成：
     * {@code selectOne}（被自动加上 {@code deleted = 0}）看不见那条软删行 → 转而
     * {@code insert} → <b>撞唯一键报错</b>。对用户表现为「这条连接我明明取消过，现在又加不回去了」。
     *
     * <p>三种可选解法里选了复活：唯一键带上 {@code deleted} 会在第二次软删时再次撞键
     * （{@code sys_enterprise} 就是这么半吊子的）；改物理删除会丢掉「谁在什么时候授权过」
     * 这条唯一的痕迹（本系统目前没有任何操作审计表）。复活既保留了行的创建信息，又幂等。
     *
     * <p><b>注意本方法写的是原生 SQL</b>：逻辑删除的自动条件只作用于 wrapper 构造的语句，
     * 不作用于这里，所以 {@code deleted = 1} 必须自己写。租户条件则相反——
     * {@code agent_connection} 在 {@code TENANT_AWARE_TABLES} 白名单里，
     * MyBatis-Plus 的租户拦截器会给这条 UPDATE 的 WHERE 自动补上 {@code tenant_id}，
     * 所以这里<b>刻意不写</b>，与本仓库其它地方的做法保持一致（写两遍会在将来改白名单时分叉）。
     *
     * @return 受影响行数；0 表示没有可复活的软删行
     */
    @Update("UPDATE agent_connection SET deleted = 0, update_time = NOW() "
            + "WHERE agent_id = #{agentId} AND connection_id = #{connectionId} AND deleted = 1")
    int reviveGrant(@Param("agentId") Long agentId, @Param("connectionId") Long connectionId);
}
