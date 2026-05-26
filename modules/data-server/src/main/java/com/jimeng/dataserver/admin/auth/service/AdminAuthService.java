package com.jimeng.dataserver.admin.auth.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWTPayload;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.constant.JWTConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.auth.dto.ChangePasswordRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginResponse;
import com.jimeng.persistence.entity.SysAdmin;
import com.jimeng.persistence.mapper.SysAdminMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理后台账户：登录、改密、查询当前用户。
 *
 * <p>JWT 直接用 hutool 签发；共用 {@link JWTConstant#TOKEN_SECRET}，
 * gateway 的 {@code AuthorizeFilter} 用同一密钥校验。
 *
 * <p>不复用 {@code common-core/JWTUtil#generateToken} —— 那里有个旧 bug
 * （把秒数 3600 当毫秒加进 token 过期时间，导致 token 3.6 秒就过期）。
 * 这里固定 12 小时，admin 用足够。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    /** Token 过期时间（毫秒），admin 后台 12h 友好。 */
    public static final long ADMIN_TOKEN_EXPIRE_MS = 12L * 60 * 60 * 1000;

    private final SysAdminMapper sysAdminMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public LoginResponse login(LoginRequest req) {
        if (req == null || StrUtil.isBlank(req.getUsername()) || StrUtil.isBlank(req.getPassword())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "用户名或密码不能为空");
        }
        SysAdmin admin = sysAdminMapper.selectOne(
                Wrappers.<SysAdmin>lambdaQuery().eq(SysAdmin::getUsername, req.getUsername()));
        if (admin == null) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "用户名或密码错误");
        }
        if (admin.getStatus() == null || admin.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号已禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), admin.getPasswordHash())) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "用户名或密码错误");
        }

        admin.setLastLoginAt(new Date());
        sysAdminMapper.updateById(admin);

        String token = signToken(admin);
        return LoginResponse.builder()
                .token(token)
                .expiresIn(ADMIN_TOKEN_EXPIRE_MS / 1000)
                .user(LoginResponse.AdminUserView.builder()
                        .id(admin.getId())
                        .tenantId(admin.getTenantId())
                        .username(admin.getUsername())
                        .displayName(admin.getDisplayName())
                        .build())
                .build();
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        if (req == null || StrUtil.isBlank(req.getOldPassword()) || StrUtil.isBlank(req.getNewPassword())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "旧密码或新密码不能为空");
        }
        if (req.getNewPassword().length() < 6) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "新密码至少 6 位");
        }
        SysAdmin admin = sysAdminMapper.selectById(userId);
        if (admin == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "账号不存在");
        }
        if (!passwordEncoder.matches(req.getOldPassword(), admin.getPasswordHash())) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "旧密码错误");
        }
        admin.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        sysAdminMapper.updateById(admin);
    }

    public LoginResponse.AdminUserView getCurrentUser(Long userId) {
        SysAdmin admin = sysAdminMapper.selectById(userId);
        if (admin == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "账号不存在");
        }
        return LoginResponse.AdminUserView.builder()
                .id(admin.getId())
                .tenantId(admin.getTenantId())
                .username(admin.getUsername())
                .displayName(admin.getDisplayName())
                .build();
    }

    private String signToken(SysAdmin admin) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ADMIN_TOKEN_EXPIRE_MS);
        Map<String, Object> payload = new HashMap<>();
        payload.put(JWTPayload.ISSUED_AT, now);
        payload.put(JWTPayload.EXPIRES_AT, exp);
        payload.put(JWTPayload.NOT_BEFORE, now);
        // gateway AuthorizeFilter 读 id / tenant_id claim 并分别注入 user-id / X-Tenant-Id 头
        payload.put("id", String.valueOf(admin.getId()));
        payload.put("tenant_id", admin.getTenantId());
        payload.put("username", admin.getUsername());
        return cn.hutool.jwt.JWTUtil.createToken(payload, JWTConstant.TOKEN_SECRET.getBytes());
    }
}
