package com.jimeng.common.core.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * JWT 签名密钥的唯一来源（签发侧）。
 *
 * <h3>为什么必须是配置而不是常量</h3>
 * 这个密钥此前硬编码在 {@code JWTConstant.TOKEN_SECRET} 里，并且在 common-core 与 gateway
 * 各存了一份。后果不是"不够规范"，而是：<b>任何能读到这个仓库的人，都能给任意租户、任意用户
 * 签发一枚有效令牌</b>——租户隔离、RBAC、超管边界全部形同虚设，且泄露已随 git 历史永久固化。
 *
 * <h3>fail-closed：没配就不启动</h3>
 * 刻意<b>不提供任何默认值</b>。配错/漏配时宁可启动失败，也不能悄悄退回一个众所周知的密钥——
 * 那种"配了才安全、不配就默认不安全"的开关，这套系统已经栽过（见边车 SANDBOX_SERVICE_TOKEN、
 * 连接凭据 CONNECTION_CREDENTIAL_KEY，都是同样的 fail-closed 取向）。
 *
 * <h3>怎么配</h3>
 * Nacos 的 {@code data-server.yml} 里 {@code jwt.secret: <值>}，或环境变量 {@code JWT_SECRET}
 * （Spring 宽松绑定）。建议 32 字节以上随机值，例如 {@code openssl rand -base64 48}。
 *
 * <p>轮转见 gateway 侧的同名组件：网关可同时接受多个密钥，因此可以做到「换签名密钥而不踢人下线」。
 */
@Slf4j
@Component
public class JwtSecretProvider {

    /** 密钥最小长度。太短的密钥可被暴力破解，签名就失去意义。 */
    private static final int MIN_LENGTH = 32;

    @Value("${jwt.secret:}")
    private String secret;

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "未配置 jwt.secret：签发 JWT 的密钥必须由配置提供（Nacos data-server.yml 的 jwt.secret，"
                            + "或环境变量 JWT_SECRET）。此处刻意不提供默认值——回落到内置密钥等于没有鉴权。"
                            + "生成方式：openssl rand -base64 48");
        }
        if (secret.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "jwt.secret 过短（" + secret.length() + " < " + MIN_LENGTH + "）：请用至少 "
                            + MIN_LENGTH + " 字符的随机值，例如 openssl rand -base64 48");
        }
        log.info("JWT 签名密钥已从配置装载（长度 {}）", secret.length());
    }

    /** 签名用的密钥字节。与历史实现保持一致：取字符串的 UTF-8 字节。 */
    public byte[] key() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }
}
