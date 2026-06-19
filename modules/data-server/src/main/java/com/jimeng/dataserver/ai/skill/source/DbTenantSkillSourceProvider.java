package com.jimeng.dataserver.ai.skill.source;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.ai.skill.SkillConst;
import com.jimeng.dataserver.ai.skill.model.ToolPackage;
import com.jimeng.dataserver.ai.skill.service.AiSkillRegistryService;
import com.jimeng.persistence.entity.AiSkill;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * DB 里租户 Skill（ai_skill 表）的工具包来源。
 *
 * <p>可见性规则（filterVisible）：
 * <ul>
 *   <li>SCOPE_TENANT：租户内所有人可见。</li>
 *   <li>SCOPE_PRIVATE：仅 ownerUserId == 当前用户可见。</li>
 *   <li>当前用户为 null（无请求上下文或异步线程）：只显示 SCOPE_TENANT。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DbTenantSkillSourceProvider implements ToolSourceProvider {

    private final AiSkillRegistryService registry;

    @Override
    public List<ToolPackage> getPackages() {
        String tenantId = TenantContext.get();
        if (tenantId == null) return List.of();
        Long userId = AdminRequestContext.findUserIdOrNull();
        List<AiSkill> visible = filterVisible(registry.listActiveByTenant(tenantId), userId);
        List<ToolPackage> out = new ArrayList<>(visible.size());
        for (AiSkill s : visible) out.add(new AiSkillToolPackage(s));
        return out;
    }

    /**
     * 纯函数：从行集中过滤当前用户可见的 Skill，不依赖 Spring/静态上下文，便于单测。
     *
     * @param rows          从 DB 取出的同租户 AiSkill 列表（已经过租户过滤）
     * @param currentUserId 当前登录用户 ID；null 表示无请求上下文
     * @return 可见的 AiSkill 列表（新列表，不修改入参）
     */
    public static List<AiSkill> filterVisible(List<AiSkill> rows, Long currentUserId) {
        List<AiSkill> out = new ArrayList<>();
        if (rows == null) return out;
        for (AiSkill s : rows) {
            boolean tenantScoped = SkillConst.SCOPE_TENANT.equals(s.getScope());
            boolean owned = currentUserId != null && Objects.equals(s.getOwnerUserId(), currentUserId);
            if (tenantScoped || owned) out.add(s);
        }
        return out;
    }
}
