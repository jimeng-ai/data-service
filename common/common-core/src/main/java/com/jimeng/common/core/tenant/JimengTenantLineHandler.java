package com.jimeng.common.core.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MyBatis-Plus 多租户 SQL 拼装处理器。
 *
 * <p>策略：使用<b>正向白名单</b>——只对已知含 {@code tenant_id} 列的表强制注入过滤，
 * 其他表全部跳过（避免对没有 tenant_id 列的表加 WHERE 把 SQL 弄炸）。
 *
 * <p>新增租户隔离表时，必须把表名加入 {@link #TENANT_AWARE_TABLES}（或在配置里追加）。
 */
@Slf4j
@Component
public class JimengTenantLineHandler implements TenantLineHandler {

    /**
     * 内置的租户隔离表清单。新增表后请补充这里（或通过 {@code tenant.extra-tenant-tables} 配置追加）。
     *
     * <p>配置键就是下面 {@code @Value} 读的那个 {@code tenant.extra-tenant-tables}。
     * （这段注释原先写的是 {@code tenant.tenant-tables}，根本不存在。按错键名配不报错、
     * 也不警告，只是那张表静默地没有租户过滤——正是本文件最想防的那类错。）
     */
    private static final Set<String> TENANT_AWARE_TABLES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "agent",
            "agent_skill",
            "connection",
            "agent_connection",
            "connector_schema",
            "connector_audit",
            "connector_pending_write",
            "connector_semantic",
            // 语义层生成 agent 的批次 / 批次内每表 / 重新生成暂存：存的同样是客户的表名、列名和对它们的说明
            "connector_semantic_generation",
            "connector_semantic_generation_table",
            "connector_semantic_staged",
            "skill_eval_run",
            "chat_conversation",
            "chat_message",
            "knowledge_base",
            "agent_exec_run",
            "agent_input_file",
            "agent_artifact",
            "ai_trace",
            "ai_trace_step",
            "product_feedback",
            "product_feedback_image",
            "ai_skill"
    )));

    /** 防御性兜底租户 ID：当 TenantContext 缺失但表又是租户隔离表时，用这个值让查询命不中任何真实数据。 */
    private static final String SAFE_GUARD_TENANT_ID = "__no_tenant__";

    @Value("${tenant.extra-tenant-tables:}")
    private String extraTenantTables;

    @Override
    public Expression getTenantId() {
        if (TenantContext.isSystemMode()) {
            // 理论上 ignoreTable 已返回 true 跳过；走到这里是兜底
            log.debug("getTenantId 在系统模式下被调用，返回防御值");
            return new StringValue(SAFE_GUARD_TENANT_ID);
        }
        String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isEmpty()) {
            log.warn("TenantContext 缺失但触发了租户表 SQL 注入，使用兜底租户 {}（请检查是否漏了 TenantContextFilter）",
                    SAFE_GUARD_TENANT_ID);
            return new StringValue(SAFE_GUARD_TENANT_ID);
        }
        return new StringValue(tenantId);
    }

    @Override
    public boolean ignoreTable(String tableName) {
        if (TenantContext.isSystemMode()) {
            return true;
        }
        if (tableName == null) {
            return true;
        }
        String normalized = tableName.toLowerCase();
        if (TENANT_AWARE_TABLES.contains(normalized)) {
            return false;
        }
        if (StringUtils.hasText(extraTenantTables)) {
            Set<String> extras = Arrays.stream(extraTenantTables.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
            if (extras.contains(normalized)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }
}
