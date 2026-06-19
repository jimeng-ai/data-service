package com.jimeng.dataserver.admin.rbac.permission;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.common.AdminRequestContext;
import com.jimeng.dataserver.admin.rbac.enums.ResourceType;
import com.jimeng.persistence.entity.Agent;
import com.jimeng.persistence.entity.AiSkill;
import com.jimeng.persistence.entity.KnowledgeBase;
import com.jimeng.persistence.entity.Plugin;
import com.jimeng.persistence.entity.SysRoleResource;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.entity.SysUserRole;
import com.jimeng.persistence.mapper.AgentMapper;
import com.jimeng.persistence.mapper.AiSkillMapper;
import com.jimeng.persistence.mapper.KnowledgeBaseMapper;
import com.jimeng.persistence.mapper.PluginMapper;
import com.jimeng.persistence.mapper.SysRoleResourceMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import com.jimeng.persistence.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 权限解析：按 {@code user-id} + {@code X-Tenant-Id} 当场解析账号的有效权限。
 *
 * <p>不把权限塞进 JWT——token 小、改权限即时生效。被 {@code /me/permissions} 和各业务 service
 * （Agent/KB/Plugin 列表与单资源访问）复用。
 */
@Service
@RequiredArgsConstructor
public class PermissionResolver {

    /**
     * "全公司可见"哨兵角色 id：{@code sys_role_resource.role_id = 0} 的授权表示该实例对【整个租户】可见，
     * 不绑定具体角色（部门）。解析任意成员权限时都会并入这些授权，故新建的部门也自动能看到。
     * 写入方见 {@code ResourceShareService}。
     */
    public static final long TENANT_WIDE_ROLE_ID = 0L;

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleResourceMapper sysRoleResourceMapper;
    // 仅用于「本人创建放行」兜底查询 create_user（修复无角色创建者孤儿），均在租户拦截器白名单内。
    private final AgentMapper agentMapper;
    private final PluginMapper pluginMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final AiSkillMapper aiSkillMapper;

    /** 解析当前请求账号的权限（读 gateway 注入的头）。 */
    public ResolvedPermissions resolveCurrent() {
        return resolve(AdminRequestContext.requireUserId(), AdminRequestContext.requireTenantId());
    }

    /**
     * 按当前账号的实例授权过滤列表（超管返回全部）。用于 Agent/KB/Plugin 的「浏览/列表」接口。
     */
    public <T> List<T> filterCurrent(List<T> items, ResourceType type, Function<T, Long> idFn) {
        ResolvedPermissions p = resolveCurrent();
        if (p.isSuperAdmin()) {
            return items;
        }
        Set<Long> allowed = p.permittedIds(type);
        return items.stream().filter(it -> allowed.contains(idFn.apply(it))).collect(Collectors.toList());
    }

    /**
     * 同 {@link #filterCurrent(List, ResourceType, Function)}，但额外放行「当前账号本人创建」的实例
     * （{@code create_user == 当前 user-id}）。用于修复无角色创建者孤儿：成员无任何角色时
     * {@code sys_role_resource} 没有授权行，建完资源会被过滤掉，靠属主兜底让其至少能看到自己建的。
     */
    public <T> List<T> filterCurrent(List<T> items, ResourceType type, Function<T, Long> idFn,
                                     Function<T, String> ownerFn) {
        ResolvedPermissions p = resolveCurrent();
        if (p.isSuperAdmin()) {
            return items;
        }
        Set<Long> allowed = p.permittedIds(type);
        String me = currentOwnerId();
        return items.stream()
                .filter(it -> allowed.contains(idFn.apply(it)) || me.equals(ownerFn.apply(it)))
                .collect(Collectors.toList());
    }

    /** 断言当前账号可访问某实例（超管恒通过，本人创建兜底放行），否则抛 4001。 */
    public void assertCurrentAccess(ResourceType type, Long id) {
        if (resolveCurrent().canAccess(type, id)) {
            return;
        }
        // 无授权时兜底：本人创建的实例放行（修复无角色创建者孤儿；与 filterCurrent 的属主放行一致）。
        // 仅在已判定无授权时才多查一次，正常授权路径零额外开销。
        if (isOwnedByCurrent(type, id)) {
            return;
        }
        throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "无权访问该资源");
    }

    /** 某实例的 create_user 是否等于当前账号（按类型查对应表；表均在租户白名单内，跨租户查不到）。 */
    private boolean isOwnedByCurrent(ResourceType type, Long id) {
        if (id == null) {
            return false;
        }
        String createUser = switch (type) {
            case AGENT -> {
                Agent a = agentMapper.selectById(id);
                yield a == null ? null : a.getCreateUser();
            }
            case PLUGIN -> {
                Plugin pl = pluginMapper.selectById(id);
                yield pl == null ? null : pl.getCreateUser();
            }
            case KNOWLEDGE_BASE -> {
                KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
                yield kb == null ? null : kb.getCreateUser();
            }
            case SKILL -> {
                AiSkill sk = aiSkillMapper.selectById(id);
                yield sk == null ? null : sk.getCreateUser();
            }
            case MENU -> null;
        };
        return createUser != null && currentOwnerId().equals(createUser);
    }

    // ----------------------------------------------------------------------------------------
    // 「按人私有」属主轴：与上面的 RBAC 授权轴对称，按 BaseEntity.create_user 做行级隔离。
    // 适用于会话 / 调用日志 trace / 代码执行 run·文件·产物 等“成员只看自己、超管看全租户”的资源。
    // ----------------------------------------------------------------------------------------

    /** 当前账号 id 的字符串形态（= create_user 列存的值，由 MyMetaObjectHandler 写入 user-id 头）。 */
    public String currentOwnerId() {
        return String.valueOf(AdminRequestContext.requireUserId());
    }

    /** 当前账号是否超管。 */
    public boolean isSuperAdmin() {
        return resolveCurrent().isSuperAdmin();
    }

    /**
     * 「按人私有」列表过滤的属主取值：超管返回 {@code null}（调用方据此跳过 {@code .eq} 看全部），
     * 成员返回自身 id。用法：
     * <pre>String owner = permissionResolver.ownerScopeOrNull();
     * wrapper.eq(owner != null, X::getCreateUser, owner);</pre>
     */
    public String ownerScopeOrNull() {
        return isSuperAdmin() ? null : currentOwnerId();
    }

    /**
     * 「按人私有」单行属主断言：超管放行；否则要求 {@code ownerUserId == 当前账号}，不等抛
     * {@code NOT_FOUND}（而非 4001）——避免暴露资源是否存在，并让“点别人的资源”表现得与“不存在”一致。
     */
    public void assertOwnerOrSuperAdmin(String ownerUserId) {
        if (resolveCurrent().isSuperAdmin()) {
            return;
        }
        if (!currentOwnerId().equals(ownerUserId)) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "资源不存在");
        }
    }

    public ResolvedPermissions resolve(Long userId, String tenantId) {
        SysUser u = TenantContext.runAsSystem(() -> sysUserMapper.selectById(userId));
        if (u == null || u.getStatus() == null || u.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号不存在或已禁用");
        }
        if (SysUser.TYPE_SUPER_ADMIN.equals(u.getUserType())) {
            return new ResolvedPermissions(true, u.getUserType(),
                    new HashSet<>(PlatformConstant.ALL_MODULES), null, null, null);
        }

        // 成员：取角色 → 取「角色授权」+「全公司共享授权(哨兵 role_id=0)」的并集。
        // 哨兵授权不绑部门，对全租户成员可见；即使成员一个角色都没有，也能看到全公司共享的实例。
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getTenantId, tenantId)
                .eq(SysUserRole::getUserId, userId));
        Set<Long> queryRoleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
        queryRoleIds.add(TENANT_WIDE_ROLE_ID); // 并入全公司共享
        List<SysRoleResource> grants = sysRoleResourceMapper.selectList(Wrappers.<SysRoleResource>lambdaQuery()
                .eq(SysRoleResource::getTenantId, tenantId)
                .in(SysRoleResource::getRoleId, queryRoleIds));

        Set<String> modules = new HashSet<>();
        Set<Long> agentIds = new HashSet<>();
        Set<Long> kbIds = new HashSet<>();
        Set<Long> pluginIds = new HashSet<>();
        for (SysRoleResource g : grants) {
            ResourceType type = parseType(g.getResourceType());
            if (type == null) {
                continue;
            }
            switch (type) {
                case MENU -> { if (g.getResourceCode() != null) modules.add(g.getResourceCode()); }
                case AGENT -> agentIds.add(g.getResourceId());
                case KNOWLEDGE_BASE -> kbIds.add(g.getResourceId());
                case PLUGIN -> pluginIds.add(g.getResourceId());
            }
        }
        return new ResolvedPermissions(false, u.getUserType(), modules, agentIds, kbIds, pluginIds);
    }

    private ResourceType parseType(String raw) {
        try {
            return ResourceType.valueOf(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
