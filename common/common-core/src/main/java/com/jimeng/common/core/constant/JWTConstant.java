package com.jimeng.common.core.constant;

/**
 * @Author Moonlight
 * @Description JWT参数配置
 * @Date 2024/7/2 15:02
 */

public class JWTConstant {

    // 使用强随机密钥，实际部署时应从配置文件或环境变量读取
    public static final String TOKEN_SECRET = "tMCW+1T2rPPuxXpWoTaKV9x9R5qahBDz6lHHnx6nQG4=";

    // JWT过期时间 (1小时，单位：秒)
    public static final long TOKEN_EXPIRE_TIME = 3600L;

}
