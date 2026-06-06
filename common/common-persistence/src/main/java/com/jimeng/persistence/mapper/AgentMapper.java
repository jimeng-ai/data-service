package com.jimeng.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jimeng.persistence.entity.Agent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Agent Mapper
 */
@Mapper
public interface AgentMapper extends BaseMapper<Agent> {

    /**
     * 释放【被软删行】占用的同代号唯一键：把同租户、同 code、已软删(deleted=1) 行的 code
     * 改写成不会再撞键的占位值（_deleted_{id}），从而让该 code 可被重新创建。
     *
     * <p>唯一键 {@code uk_agent_tenant_code(tenant_id, code)} 不含 deleted 列，逻辑删除后
     * 死行仍占着 code；不释放就无法重建同名代号（且列表看不到该死行，体验上"看不到却建不了"）。
     *
     * <p>用原生 {@code @Update} 注解 SQL，绕过 MyBatis-Plus 逻辑删除对 deleted=0 的自动过滤，
     * 才能命中 deleted=1 的死行；租户条件由 TenantLineInnerInterceptor 自动注入。
     */
    @Update("UPDATE agent SET code = CONCAT('_deleted_', id) WHERE code = #{code} AND deleted = 1")
    int releaseDeletedCode(@Param("code") String code);
}
