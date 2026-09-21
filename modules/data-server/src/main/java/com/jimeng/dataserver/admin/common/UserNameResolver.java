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

    /**
     * 按 id 取单个用户的显示名；取不到返回 null。
     *
     * <h3>★ 为什么要在这里、而不是在调用方自己查</h3>
     * {@code sys_user} <b>不在</b> {@code TENANT_AWARE_TABLES} 里（登录早于 TenantContext，
     * 按 username 全局解析），所以按 id 查它<b>不会</b>被租户拦截器自动加 {@code WHERE tenant_id=?}。
     * 调用方各自现搓一个跨租户读用户表的查询，是租户隔离最容易破的地方。
     * 集中在本类一处，连同下面那道校验一起，是让「取个名字」这件小事不必每次都重新论证一遍安全性。
     *
     * <h3>★ 那道租户校验</h3>
     * {@code runAsSystem} 是跨租户的，所以查出来之后必须自己比一次 {@code tenant_id}：
     * 不属于当前租户的一律返回 null。正常调用传的都是当前登录用户的 id，这道校验永远不该触发——
     * 它防的是「id 从别处流进来」的那天，那种时候拿到的会是<b>别的企业的员工姓名</b>。
     * 系统态（无 TenantContext）不做这道校验：运营侧本来就是跨租户的。
     */
    public String displayNameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser u = TenantContext.runAsSystem(() -> sysUserMapper.selectById(userId));
        if (u == null) {
            return null;
        }
        String current = TenantContext.get();
        if (StringUtils.hasText(current) && !current.equals(u.getTenantId())) {
            return null;
        }
        return displayName(u);
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
