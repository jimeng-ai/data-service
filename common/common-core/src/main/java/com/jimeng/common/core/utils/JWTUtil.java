package com.jimeng.common.core.utils;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTPayload;
import com.jimeng.common.core.constant.JWTConstant;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT工具类
 * 提供token生成和验证的统一方法
 * @Author Claude
 * @Date 2024/12/19
 */
@Slf4j
public class JWTUtil {

    /**
     * 生成JWT token
     * @param userId 用户ID
     * @param username 用户名（可选）
     * @return JWT token字符串
     */
    public static String generateToken(String userId, String username) {
        try {
            Map<String, Object> payload = new HashMap<>();

            Date now = new Date();
            Date expiration = new Date(now.getTime() + JWTConstant.TOKEN_EXPIRE_TIME);

            // 标准声明
            payload.put(JWTPayload.ISSUED_AT, now);
            payload.put(JWTPayload.EXPIRES_AT, expiration);
            payload.put(JWTPayload.NOT_BEFORE, now);

            // 自定义声明
            payload.put("id", userId);
            if (username != null) {
                payload.put("username", username);
            }

            String token = cn.hutool.jwt.JWTUtil.createToken(payload, JWTConstant.TOKEN_SECRET.getBytes());
            log.debug("Generated JWT token for user: {}", userId);
            return token;

        } catch (Exception e) {
            log.error("Error generating JWT token for user: {}, error: {}", userId, e.getMessage());
            throw new RuntimeException("Token generation failed", e);
        }
    }

    /**
     * 验证JWT token
     * @param token JWT token
     * @return 是否验证通过
     */
    public static boolean validateToken(String token) {
        try {
            return cn.hutool.jwt.JWTUtil.verify(token, JWTConstant.TOKEN_SECRET.getBytes());
        } catch (Exception e) {
            log.warn("JWT token validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从token中获取用户ID
     * @param token JWT token
     * @return 用户ID
     */
    public static String getUserIdFromToken(String token) {
        try {
            if (!validateToken(token)) {
                return null;
            }

            JWT jwt = cn.hutool.jwt.JWTUtil.parseToken(token);
            JWTPayload payload = jwt.getPayload();
            Object userIdObj = payload.getClaim("id");

            return userIdObj != null ? String.valueOf(userIdObj) : null;
        } catch (Exception e) {
            log.warn("Error extracting user ID from token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 检查token是否过期
     * @param token JWT token
     * @return 是否过期
     */
    public static boolean isTokenExpired(String token) {
        try {
            JWT jwt = cn.hutool.jwt.JWTUtil.parseToken(token);
            JWTPayload payload = jwt.getPayload();

            Object expObj = payload.getClaim("exp");
            if (expObj == null) {
                return false; // 如果没有exp声明，认为不过期
            }

            Date expiration = new Date((Long) expObj * 1000L);
            Date now = new Date();

            return now.after(expiration);
        } catch (Exception e) {
            log.warn("Error checking token expiration: {}", e.getMessage());
            return true; // 发生异常时认为token过期
        }
    }
}