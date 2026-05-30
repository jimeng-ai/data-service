package com.jimeng.dataserver.admin.operator.auth.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWTPayload;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jimeng.common.core.constant.JWTConstant;
import com.jimeng.common.core.constant.PlatformConstant;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.admin.auth.dto.ChangePasswordRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginResponse;
import com.jimeng.persistence.entity.SysOperator;
import com.jimeng.persistence.mapper.SysOperatorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 平台运营账号（{@code sys_operator}）登录、改密、查询当前用户、滑动续期。
 *
 * <p>签发的 JWT 带 {@code tenant_id="platform"}（保留租户，满足 gateway/TenantContextFilter）+
 * {@code realm="OPERATOR"}。复用 {@link AdminAuthService} 一致的 hutool 签发方式与 12h 过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperatorAuthService {

    public static final long TOKEN_EXPIRE_MS = 12L * 60 * 60 * 1000;

    private final SysOperatorMapper sysOperatorMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Transactional
    public LoginResponse login(LoginRequest req) {
        if (req == null || StrUtil.isBlank(req.getUsername()) || StrUtil.isBlank(req.getPassword())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "用户名或密码不能为空");
        }
        SysOperator op = sysOperatorMapper.selectOne(
                Wrappers.<SysOperator>lambdaQuery().eq(SysOperator::getUsername, req.getUsername()));
        if (op == null) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "用户名或密码错误");
        }
        if (op.getStatus() == null || op.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号已禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), op.getPasswordHash())) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "用户名或密码错误");
        }
        op.setLastLoginAt(new Date());
        sysOperatorMapper.updateById(op);
        return buildResponse(op);
    }

    @Transactional
    public void changePassword(Long operatorId, ChangePasswordRequest req) {
        if (req == null || StrUtil.isBlank(req.getOldPassword()) || StrUtil.isBlank(req.getNewPassword())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "旧密码或新密码不能为空");
        }
        if (req.getNewPassword().length() < 6) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "新密码至少 6 位");
        }
        SysOperator op = sysOperatorMapper.selectById(operatorId);
        if (op == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "账号不存在");
        }
        if (!passwordEncoder.matches(req.getOldPassword(), op.getPasswordHash())) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "旧密码错误");
        }
        op.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        sysOperatorMapper.updateById(op);
    }

    public LoginResponse.AdminUserView getCurrentUser(Long operatorId) {
        SysOperator op = sysOperatorMapper.selectById(operatorId);
        if (op == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "账号不存在");
        }
        return toView(op);
    }

    public LoginResponse refresh(Long operatorId) {
        SysOperator op = sysOperatorMapper.selectById(operatorId);
        if (op == null) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号不存在");
        }
        if (op.getStatus() == null || op.getStatus() != 1) {
            throw new ServiceException(ExceptionCode.AUTHENTICATION_FAIL, "账号已禁用");
        }
        return buildResponse(op);
    }

    private LoginResponse buildResponse(SysOperator op) {
        return LoginResponse.builder()
                .token(signToken(op))
                .expiresIn(TOKEN_EXPIRE_MS / 1000)
                .user(toView(op))
                .build();
    }

    private LoginResponse.AdminUserView toView(SysOperator op) {
        return LoginResponse.AdminUserView.builder()
                .id(op.getId())
                .tenantId(PlatformConstant.PLATFORM_TENANT)
                .username(op.getUsername())
                .displayName(op.getDisplayName())
                .realm(PlatformConstant.REALM_OPERATOR)
                .build();
    }

    private String signToken(SysOperator op) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + TOKEN_EXPIRE_MS);
        Map<String, Object> payload = new HashMap<>();
        payload.put(JWTPayload.ISSUED_AT, now);
        payload.put(JWTPayload.EXPIRES_AT, exp);
        payload.put(JWTPayload.NOT_BEFORE, now);
        payload.put("id", String.valueOf(op.getId()));
        // 保留租户：满足 gateway 注入 X-Tenant-Id 与 TenantContextFilter 的非空校验
        payload.put("tenant_id", PlatformConstant.PLATFORM_TENANT);
        payload.put("username", op.getUsername());
        payload.put("realm", PlatformConstant.REALM_OPERATOR);
        return cn.hutool.jwt.JWTUtil.createToken(payload, JWTConstant.TOKEN_SECRET.getBytes());
    }
}
