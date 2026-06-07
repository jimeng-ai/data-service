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

    /** 内置的租户隔离表清单。新增表后请补充这里（或通过 {@code tenant.tenant-tables} 配置追加）。 */
    private static final Set<String> TENANT_AWARE_TABLES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "plugin",
            "plugin_tool",
            "plugin_http_mapping",
            "plugin_credential",
            "agent",
            "agent_plugin",
            "chat_conversation",
            "chat_message",
            "knowledge_base",
            "agent_exec_run",
            "agent_input_file",
            "agent_artifact",
            "ai_trace",
            "ai_trace_step"
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
