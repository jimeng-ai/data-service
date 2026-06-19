package com.jimeng.dataserver.ai.skill.install;

import com.jimeng.dataserver.admin.common.AdminRequestContext;
import org.springframework.stereotype.Component;

/**
 * 安装 skill 的权限闸。v1 与 /data/tenant/skills/upload 同策略：已认证用户即可（安装的是租户私有 skill，
 * 仅在隔离 sandbox 运行）。未来如需收紧到 RBAC SKILL 资源，改这里即可（P6）。
 */
@Component
public class SkillInstallGuard {
    public boolean canInstall() {
        return AdminRequestContext.findUserIdOrNull() != null;
    }
}
