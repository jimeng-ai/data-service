package com.jimeng.dataserver.admin.operator.enterprise.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.operator.enterprise.dto.CreateEnterpriseRequest;
import com.jimeng.dataserver.admin.operator.enterprise.dto.EnterpriseView;
import com.jimeng.persistence.entity.SysEnterprise;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.mapper.SysEnterpriseMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 企业（租户）开通与管理。运营侧操作，全程 {@link TenantContext#runAsSystem}，
 * 对 {@code sys_enterprise}/{@code sys_user} 跨租户读写（这两表也不在租户白名单内）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnterpriseService {

    private static final Pattern TENANT_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final SysEnterpriseMapper sysEnterpriseMapper;
    private final SysUserMapper sysUserMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public EnterpriseView create(CreateEnterpriseRequest req) {
        if (req == null || StrUtil.isBlank(req.getName())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "企业名称不能为空");
        }
        if (StrUtil.isBlank(req.getSuperAdminUsername())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "超级管理员登录名不能为空");
        }
        if (StrUtil.isBlank(req.getSuperAdminPassword()) || req.getSuperAdminPassword().length() < 6) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "超级管理员初始密码至少 6 位");
        }

        return TenantContext.runAsSystem(() -> {
            String tenantId = resolveTenantId(req.getTenantId(), req.getName());
            String username = req.getSuperAdminUsername().trim();
            if (usernameExists(username)) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "登录名已被占用：" + username);
            }

            SysEnterprise ent = new SysEnterprise();
            ent.setTenantId(tenantId);
            ent.setName(req.getName().trim());
            ent.setDescription(req.getDescription());
            ent.setStatus(1);
            sysEnterpriseMapper.insert(ent);

            SysUser admin = new SysUser();
            admin.setTenantId(tenantId);
            admin.setUsername(username);
            admin.setPasswordHash(passwordEncoder.encode(req.getSuperAdminPassword()));
            admin.setDisplayName(StrUtil.blankToDefault(req.getSuperAdminDisplayName(), "超级管理员"));
            admin.setUserType(SysUser.TYPE_SUPER_ADMIN);
            admin.setStatus(1);
            sysUserMapper.insert(admin);

            log.info("已创建企业 tenant_id={} name={} 超管={}", tenantId, ent.getName(), username);
            return toView(ent, username);
        });
    }

    public List<EnterpriseView> list() {
        return TenantContext.runAsSystem(() -> {
            List<SysEnterprise> ents = sysEnterpriseMapper.selectList(
                    Wrappers.<SysEnterprise>lambdaQuery().orderByDesc(SysEnterprise::getCreateTime));
            return ents.stream()
                    .map(e -> toView(e, findSuperAdminUsername(e.getTenantId())))
                    .collect(Collectors.toList());
        });
    }

    public EnterpriseView getView(Long id) {
        return TenantContext.runAsSystem(() -> {
            SysEnterprise ent = requireById(id);
            return toView(ent, findSuperAdminUsername(ent.getTenantId()));
        });
    }

    @Transactional
    public void setStatus(Long id, boolean enabled) {
        TenantContext.runAsSystem(() -> {
            SysEnterprise ent = requireById(id);
            ent.setStatus(enabled ? 1 : 0);
            sysEnterpriseMapper.updateById(ent);
            return null;
        });
    }

    @Transactional
    public void resetSuperAdminPassword(Long enterpriseId, String newPassword) {
        if (StrUtil.isBlank(newPassword) || newPassword.length() < 6) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "新密码至少 6 位");
        }
        TenantContext.runAsSystem(() -> {
            SysEnterprise ent = requireById(enterpriseId);
            SysUser admin = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getTenantId, ent.getTenantId())
                    .eq(SysUser::getUserType, SysUser.TYPE_SUPER_ADMIN)
                    .orderByAsc(SysUser::getCreateTime)
                    .last("limit 1"));
            if (admin == null) {
                throw new ServiceException(ExceptionCode.NOT_FOUND, "该企业没有超级管理员");
            }
            admin.setPasswordHash(passwordEncoder.encode(newPassword));
            sysUserMapper.updateById(admin);
            return null;
        });
    }

    // ------------------------------------------------------------------ helpers

    private SysEnterprise requireById(Long id) {
        SysEnterprise ent = sysEnterpriseMapper.selectById(id);
        if (ent == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "企业不存在: " + id);
        }
        return ent;
    }

    private String resolveTenantId(String explicit, String name) {
        if (StrUtil.isNotBlank(explicit)) {
            String t = explicit.trim();
            if (!TENANT_PATTERN.matcher(t).matches()) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                        "租户标识非法，仅允许字母/数字/点/下划线/连字符，长度 1-64");
            }
            if (isReserved(t) || tenantExists(t)) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "租户标识已被占用：" + t);
            }
            return t;
        }
        String base = slugify(name);
        String candidate = base;
        for (int i = 0; i < 5 && (isReserved(candidate) || tenantExists(candidate)); i++) {
            candidate = trimTo64(base + "-" + IdUtil.fastSimpleUUID().substring(0, 4));
        }
        if (isReserved(candidate) || tenantExists(candidate)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "无法生成唯一租户标识，请手动指定");
        }
        return candidate;
    }

    private static boolean isReserved(String t) {
        return PlatformConstant.PLATFORM_TENANT.equals(t);
    }

    private static String slugify(String name) {
        String s = name.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "");
        if (s.isEmpty()) {
            s = "ent-" + IdUtil.fastSimpleUUID().substring(0, 6);
        }
        return trimTo64(s);
    }

    private static String trimTo64(String s) {
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    private boolean tenantExists(String tenantId) {
        Long c = sysEnterpriseMapper.selectCount(
                Wrappers.<SysEnterprise>lambdaQuery().eq(SysEnterprise::getTenantId, tenantId));
        return c != null && c > 0;
    }

    private boolean usernameExists(String username) {
        Long c = sysUserMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
        return c != null && c > 0;
    }

    private String findSuperAdminUsername(String tenantId) {
        SysUser admin = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getUserType, SysUser.TYPE_SUPER_ADMIN)
                .orderByAsc(SysUser::getCreateTime)
                .last("limit 1"));
        return admin == null ? null : admin.getUsername();
    }

    private EnterpriseView toView(SysEnterprise ent, String superAdminUsername) {
        return EnterpriseView.builder()
                .id(ent.getId())
                .tenantId(ent.getTenantId())
                .name(ent.getName())
                .description(ent.getDescription())
                .status(ent.getStatus())
                .superAdminUsername(superAdminUsername)
                .createTime(ent.getCreateTime())
                .build();
    }
}
