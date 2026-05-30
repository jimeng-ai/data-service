package com.jimeng.dataserver.admin.rbac.member.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.rbac.member.dto.MemberCreateRequest;
import com.jimeng.dataserver.admin.rbac.member.dto.MemberUpdateRequest;
import com.jimeng.dataserver.admin.rbac.member.dto.MemberView;
import com.jimeng.persistence.entity.SysRole;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.entity.SysUserRole;
import com.jimeng.persistence.mapper.SysRoleMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import com.jimeng.persistence.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 企业成员账号管理（超管侧，租户内）。{@code sys_user}/{@code sys_user_role} 不在租户白名单，显式拼 tenant_id。
 */
@Service
@RequiredArgsConstructor
public class MemberService {

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public MemberView create(String tenantId, MemberCreateRequest req) {
        if (req == null || StrUtil.isBlank(req.getUsername())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "登录名不能为空");
        }
        if (StrUtil.isBlank(req.getPassword()) || req.getPassword().length() < 6) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "初始密码至少 6 位");
        }
        String username = req.getUsername().trim();
        if (usernameExists(username)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "登录名已被占用：" + username);
        }
        SysUser u = new SysUser();
        u.setTenantId(tenantId);
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        u.setDisplayName(StrUtil.blankToDefault(req.getDisplayName(), username));
        u.setUserType(SysUser.TYPE_MEMBER);
        u.setStatus(1);
        sysUserMapper.insert(u);

        if (req.getRoleIds() != null && !req.getRoleIds().isEmpty()) {
            replaceRoles(tenantId, u.getId(), req.getRoleIds());
        }
        return toView(u, loadRoleIds(tenantId, u.getId()));
    }

    @Transactional
    public MemberView update(String tenantId, Long id, MemberUpdateRequest req) {
        SysUser u = requireMember(tenantId, id);
        if (req != null && req.getDisplayName() != null) {
            u.setDisplayName(req.getDisplayName());
        }
        sysUserMapper.updateById(u);
        return toView(sysUserMapper.selectById(id), loadRoleIds(tenantId, id));
    }

    public List<MemberView> list(String tenantId) {
        List<SysUser> users = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getUserType, SysUser.TYPE_MEMBER)
                .orderByDesc(SysUser::getCreateTime));
        return users.stream()
                .map(u -> toView(u, loadRoleIds(tenantId, u.getId())))
                .collect(Collectors.toList());
    }

    public MemberView get(String tenantId, Long id) {
        SysUser u = requireMember(tenantId, id);
        return toView(u, loadRoleIds(tenantId, id));
    }

    @Transactional
    public void setStatus(String tenantId, Long id, boolean enabled) {
        SysUser u = requireMember(tenantId, id);
        u.setStatus(enabled ? 1 : 0);
        sysUserMapper.updateById(u);
    }

    @Transactional
    public void resetPassword(String tenantId, Long id, String newPassword) {
        if (StrUtil.isBlank(newPassword) || newPassword.length() < 6) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "新密码至少 6 位");
        }
        SysUser u = requireMember(tenantId, id);
        u.setPasswordHash(passwordEncoder.encode(newPassword));
        sysUserMapper.updateById(u);
    }

    @Transactional
    public MemberView assignRoles(String tenantId, Long id, List<Long> roleIds) {
        requireMember(tenantId, id);
        replaceRoles(tenantId, id, roleIds == null ? List.of() : roleIds);
        return toView(sysUserMapper.selectById(id), loadRoleIds(tenantId, id));
    }

    // ------------------------------------------------------------------ helpers

    private void replaceRoles(String tenantId, Long userId, List<Long> roleIds) {
        // 校验角色属本租户
        for (Long roleId : roleIds) {
            SysRole role = sysRoleMapper.selectById(roleId);
            if (role == null || !tenantId.equals(role.getTenantId())) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "角色不存在或不属于本企业：" + roleId);
            }
        }
        sysUserRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getTenantId, tenantId)
                .eq(SysUserRole::getUserId, userId));
        for (Long roleId : roleIds.stream().distinct().toList()) {
            SysUserRole ur = new SysUserRole();
            ur.setTenantId(tenantId);
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            sysUserRoleMapper.insert(ur);
        }
    }

    private List<Long> loadRoleIds(String tenantId, Long userId) {
        return sysUserRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getTenantId, tenantId)
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
    }

    private SysUser requireMember(String tenantId, Long id) {
        SysUser u = sysUserMapper.selectById(id);
        if (u == null || !tenantId.equals(u.getTenantId()) || !SysUser.TYPE_MEMBER.equals(u.getUserType())) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "成员不存在: " + id);
        }
        return u;
    }

    private boolean usernameExists(String username) {
        Long c = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
        return c != null && c > 0;
    }

    private MemberView toView(SysUser u, List<Long> roleIds) {
        return MemberView.builder()
                .id(u.getId())
                .username(u.getUsername())
                .displayName(u.getDisplayName())
                .userType(u.getUserType())
                .status(u.getStatus())
                .roleIds(roleIds)
                .lastLoginAt(u.getLastLoginAt())
                .createTime(u.getCreateTime())
                .build();
    }
}
