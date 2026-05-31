package com.jimeng.dataserver.admin.auth.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWTPayload;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.constant.JWTConstant;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.admin.auth.dto.ChangePasswordRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginResponse;
import com.jimeng.persistence.entity.SysEnterprise;
import com.jimeng.persistence.entity.SysUser;
import com.jimeng.persistence.mapper.SysEnterpriseMapper;
import com.jimeng.persistence.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 企业账号（{@code sys_user}：超管 + 成员）登录、改密、查询当前用户。
 * jm-agent-front 与 jm-admin 企业门户共用 {@code /data/admin/auth/**}。
 *
 * <p>JWT 直接用 hutool 签发；共用 {@link JWTConstant#TOKEN_SECRET}，gateway 的 {@code AuthorizeFilter}
 * 用同一密钥校验，并据 {@code tenant_id} claim 注入 {@code X-Tenant-Id}。固定 12 小时。
 *
 * <p>{@code sys_user}/{@code sys_enterprise} 均不在租户白名单内，查询不会被自动注入 {@code tenant_id}；
 * 登录发生在 TenantContext 设置之前，按 username 全局解析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    /** Token 过期时间（毫秒），12h 友好。 */
    public static final long ADMIN_TOKEN_EXPIRE_MS = 12L * 60 * 60 * 1000;

    private final SysUserMapper sysUserMapper;
    private final SysEnterpriseMapper sysEnterpriseMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public LoginResponse login(LoginRequest req) {
        if (req == null || StrUtil.isBlank(req.getUsername()) || StrUtil.isBlank(req.getPassword())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "用户名或密码不能为空");
        }
        SysUser user = sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, req.getUsername()));
        if (user == null) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号已禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "用户名或密码错误");
        }
        requireEnterpriseEnabled(user.getTenantId());

        user.setLastLoginAt(new Date());
        sysUserMapper.updateById(user);

        return buildResponse(user);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        if (req == null || StrUtil.isBlank(req.getOldPassword()) || StrUtil.isBlank(req.getNewPassword())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "旧密码或新密码不能为空");
        }
        if (req.getNewPassword().length() < 6) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "新密码至少 6 位");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "账号不存在");
        }
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPasswordHash())) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "旧密码错误");
        }
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        sysUserMapper.updateById(user);
    }

    public LoginResponse.AdminUserView getCurrentUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "账号不存在");
        }
        return toView(user);
    }

    /**
     * 滑动续期：用当前仍有效的 token 换发一枚新的 12h token。重新校验账号 + 企业状态，避免给已禁用账号续命。
     */
    public LoginResponse refresh(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号不存在");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号已禁用");
        }
        requireEnterpriseEnabled(user.getTenantId());
        return buildResponse(user);
    }

    private void requireEnterpriseEnabled(String tenantId) {
        SysEnterprise ent = TenantContext.runAsSystem(() -> sysEnterpriseMapper.selectOne(
                Wrappers.<SysEnterprise>lambdaQuery().eq(SysEnterprise::getTenantId, tenantId)));
        if (ent == null || ent.getStatus() == null || ent.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "企业已停用");
        }
    }

    private LoginResponse buildResponse(SysUser user) {
        return LoginResponse.builder()
                .token(signToken(user))
                .expiresIn(ADMIN_TOKEN_EXPIRE_MS / 1000)
                .user(toView(user))
                .build();
    }

    private LoginResponse.AdminUserView toView(SysUser user) {
        return LoginResponse.AdminUserView.builder()
                .id(user.getId())
                .tenantId(user.getTenantId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .realm(PlatformConstant.REALM_ENTERPRISE)
                .userType(user.getUserType())
                .build();
    }

    private String signToken(SysUser user) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ADMIN_TOKEN_EXPIRE_MS);
        Map<String, Object> payload = new HashMap<>();
        payload.put(JWTPayload.ISSUED_AT, now);
        payload.put(JWTPayload.EXPIRES_AT, exp);
        payload.put(JWTPayload.NOT_BEFORE, now);
        // gateway AuthorizeFilter 读 id / tenant_id claim 并分别注入 user-id / X-Tenant-Id 头
        payload.put("id", String.valueOf(user.getId()));
        payload.put("tenant_id", user.getTenantId());
        payload.put("username", user.getUsername());
        payload.put("realm", PlatformConstant.REALM_ENTERPRISE);
        payload.put("user_type", user.getUserType());
        return cn.hutool.jwt.JWTUtil.createToken(payload, JWTConstant.TOKEN_SECRET.getBytes());
    }

    /**
     * 为内部服务（代码执行 Agent 沙箱边车）签发一枚短时效 JWT，使其能"以该用户身份"回调
     * 走网关的接口（如 /data/rag/search）。复用同一密钥，gateway 据 tenant_id claim 注入 X-Tenant-Id。
     * ttl 应只覆盖一次运行（分钟级），缩小被盗用窗口。
     */
    public String mintInternalToken(String userId, String tenantId, long ttlMs) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttlMs);
        Map<String, Object> payload = new HashMap<>();
        payload.put(JWTPayload.ISSUED_AT, now);
        payload.put(JWTPayload.EXPIRES_AT, exp);
        payload.put(JWTPayload.NOT_BEFORE, now);
        payload.put("id", userId);
        payload.put("tenant_id", tenantId);
        payload.put("realm", PlatformConstant.REALM_ENTERPRISE);
        return cn.hutool.jwt.JWTUtil.createToken(payload, JWTConstant.TOKEN_SECRET.getBytes());
    }
}
