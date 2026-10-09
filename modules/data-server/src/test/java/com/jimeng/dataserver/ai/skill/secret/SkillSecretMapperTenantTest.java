package com.jimeng.dataserver.ai.skill.secret;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.jimeng.common.core.tenant.JimengTenantLineHandler;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.persistence.mapper.SkillSecretMapper;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.RoutingStatementHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 确认 {@code SkillSecretMapper#deleteBySkillId} 这条手写的 {@code @Delete} 也会被多租户插件追加 tenant_id 条件。
 *
 * <p>做法：用真实的 MyBatis 配置把 mapper 注册进去，取出那条语句真实的 {@code MappedStatement} 和 {@code BoundSql}，
 * 交给生产里注册的同一个 {@code TenantLineInnerInterceptor}（配 {@code JimengTenantLineHandler}）的
 * {@code beforePrepare}——那是它改写所有 INSERT / UPDATE / DELETE 的入口，按语句类型改写，不管 SQL 是 BaseMapper 生成的还是手写的。
 * 不需要数据库：改写发生在 SQL 文本上。
 */
class SkillSecretMapperTenantTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static String rewrittenSqlOfDeleteBySkillId(String tenantId) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(SkillSecretMapper.class);
        MappedStatement ms = configuration.getMappedStatement(SkillSecretMapper.class.getName() + ".deleteBySkillId");
        assertThat(ms.getSqlCommandType()).isEqualTo(SqlCommandType.DELETE);
        Map<String, Object> param = Map.of("skillId", 42L);
        BoundSql boundSql = ms.getBoundSql(param);
        StatementHandler handler = new RoutingStatementHandler(mock(Executor.class), ms, param, RowBounds.DEFAULT, null, boundSql);

        JimengTenantLineHandler tenantHandler = new JimengTenantLineHandler();
        ReflectionTestUtils.setField(tenantHandler, "extraTenantTables", "");
        if (tenantId != null) TenantContext.set(tenantId);
        new TenantLineInnerInterceptor(tenantHandler).beforePrepare(handler, mock(Connection.class), null);
        return boundSql.getSql().replaceAll("\\s+", " ").trim();
    }

    @Test
    @DisplayName("★ 手写的 DELETE 也被追加 tenant_id 条件，skill_id 条件还在")
    void customDeleteGetsTheTenantCondition() {
        String sql = rewrittenSqlOfDeleteBySkillId("t1");
        assertThat(sql).isEqualTo("DELETE FROM skill_secret WHERE skill_id = ? AND tenant_id = 't1'");
    }

    @Test
    @DisplayName("请求上下文里没有租户：落到兜底租户，什么也删不到（方向是安全的）")
    void withoutTenantContextItCannotMatchAnything() {
        assertThat(rewrittenSqlOfDeleteBySkillId(null)).isEqualTo("DELETE FROM skill_secret WHERE skill_id = ? AND tenant_id = '__no_tenant__'");
    }
}
