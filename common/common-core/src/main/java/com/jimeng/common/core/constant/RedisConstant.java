package com.jimeng.common.core.constant;

/**
 * @Author Moonlight
 * @Description Redis缓存Key
 * @Date 2024/7/2 15:02
 */

public class RedisConstant {
    // 用户信息（基于用户ID）
    public static final String USER_INFO = "USER:INFO:USERID";

    // 用户登录信息（基于账号，用于登录时快速查询）
    public static final String USER_LOGIN_INFO = "USER:LOGIN:ACCOUNT";
}
