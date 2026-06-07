package com.jimeng.dataserver.ai.plugin.executor;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.plugin.dto.PluginError;
import com.jimeng.dataserver.ai.plugin.dto.PluginToolEntry;
import com.jimeng.dataserver.ai.plugin.service.PluginHttpInvoker;
import com.jimeng.dataserver.ai.plugin.service.PluginRegistryService;
import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * HTTP 插件工具执行器：接入现有的 {@link SkillToolExecutor} 注册机制，
 * 把 DB 配置的 HTTP 插件暴露成 Claude 可调用的工具。
 *
 * <p>路由依赖 {@link TenantContext}——所以入口必须经过
 * {@link com.jimeng.common.core.tenant.TenantContextFilter} 才能正常工作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpPluginToolExecutor implements SkillToolExecutor {

    private final PluginRegistryService registry;
    private final PluginHttpInvoker invoker;

    @Override
    public boolean supports(String toolName) {
        String tenantId = TenantContext.get();
        if (tenantId == null || toolName == null) return false;
        return registry.findToolByName(tenantId, toolName).isPresent();
    }

    @Override
    public String traceStepType() {
        return "PLUGIN_TRIGGER";
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        String tenantId = TenantContext.get();
        if (tenantId == null) {
            return PluginError.of(PluginError.CODE_CONFIG_INVALID,
                    "TenantContext 缺失，无法路由插件工具").toMap();
        }

        Optional<PluginToolEntry> entryOpt = registry.findToolByName(tenantId, toolName);
        if (entryOpt.isEmpty()) {
            return PluginError.of(PluginError.CODE_CONFIG_INVALID,
                    "未找到插件工具: " + toolName).toMap();
        }

        PluginToolEntry entry = entryOpt.get();
        // 防御性：再确认一遍租户归属（缓存正常情况下已分桶，这里防御 race）
        if (!tenantId.equals(entry.tenantId())) {
            log.warn("租户不匹配，拒绝执行: ctx={}, entry={}, tool={}",
                    tenantId, entry.tenantId(), toolName);
            return PluginError.of(PluginError.CODE_CONFIG_INVALID, "租户不匹配").toMap();
        }

        return invoker.invoke(entry, input);
    }
}
