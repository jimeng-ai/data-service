package com.jimeng.dataserver.admin.common;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.persistence.BaseEntity;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把实体的 {@code createUser}(创建者用户 id) 批量解析成 {@code creatorName}(显示名)，写回实体，供列表展示。
 *
 * <p>{@code sys_user} 不在租户白名单（按 id 查不会被租户拦截器加 WHERE），与 PermissionResolver 一致用
 * 系统态查询；解析不到时回退为原始 id，避免空白。
 */
@Service
@RequiredArgsConstructor
public class UserNameResolver {

    private final SysUserMapper sysUserMapper;

    /** 批量回填一组实体的创建人显示名。 */
    public void fillCreatorNames(List<? extends BaseEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        Set<Long> ids = entities.stream()
                .map(BaseEntity::getCreateUser)
                .map(UserNameResolver::parseLongOrNull)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        Map<String, String> idToName = TenantContext.runAsSystem(() -> sysUserMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(u -> String.valueOf(u.getId()), UserNameResolver::displayName, (a, b) -> a)));
        for (BaseEntity e : entities) {
            String uid = e.getCreateUser();
            if (!StringUtils.hasText(uid)) {
                continue;
            }
            e.setCreatorName(idToName.getOrDefault(uid, uid));
        }
    }

    private static String displayName(SysUser u) {
        return StringUtils.hasText(u.getDisplayName()) ? u.getDisplayName() : u.getUsername();
    }

    private static Long parseLongOrNull(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
