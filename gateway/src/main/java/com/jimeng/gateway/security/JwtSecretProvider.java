package com.jimeng.gateway.security;

import cn.hutool.jwt.JWTUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 验签密钥（校验侧）。与 data-server 的同名组件取同一个 {@code jwt.secret} 值。
 *
 * <h3>为什么必须是配置而不是常量</h3>
 * 密钥此前硬编码在 {@code com.jimeng.gateway.constants.JWTConstant} 里，且与 common-core 那份
 * 是重复的两处。任何能读到仓库的人都能自签任意租户/用户的令牌——网关这道唯一的鉴权闸门直接失效。
 * gateway 不依赖 common-core，所以这里必须有自己的一份实现，但<b>取值必须与 data-server 一致</b>，
 * 否则签发与验签分叉，表现为"所有人都登录不上"。
 *
 * <h3>fail-closed：没配就不启动</h3>
 * 不提供任何默认值。网关是全站唯一的鉴权入口，这里回落到内置密钥等于把门拆了。
 *
 * <h3>平滑轮转</h3>
 * {@code jwt.additional-secrets}（逗号分隔，<b>仅用于验签</b>）让网关可以同时接受多个密钥，
 * 于是换密钥不必把所有人踢下线：
 * <ol>
 *   <li>先把【新】密钥加到网关的 additional-secrets（此时仍用旧密钥签发）；</li>
 *   <li>再把 data-server 的 jwt.secret 换成新密钥（新登录发新令牌，旧令牌仍可验）；</li>
 *   <li>等旧令牌自然过期（12h）后，把网关 jwt.secret 改成新值并清空 additional-secrets。</li>
 * </ol>
 * 若不在乎踢人下线，直接两边同时换成新值即可，此项留空。
 */
@Slf4j
@Component
public class JwtSecretProvider {

    private static final int MIN_LENGTH = 32;

    @Value("${jwt.secret:}")
    private String secret;

    /** 仅验签用的历史密钥，逗号分隔。轮转期用，平时留空。 */
    @Value("${jwt.additional-secrets:}")
    private String additionalSecrets;

    private final List<byte[]> acceptKeys = new ArrayList<>();

    @PostConstruct
    void init() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "未配置 jwt.secret：网关验签密钥必须由配置提供（Nacos gateway.yml 的 jwt.secret，"
                            + "或环境变量 JWT_SECRET），且必须与 data-server 取同一个值。"
                            + "此处刻意不提供默认值——回落到内置密钥等于没有鉴权。"
                            + "生成方式：openssl rand -base64 48");
        }
        if (secret.length() < MIN_LENGTH) {
            throw new IllegalStateException(
                    "jwt.secret 过短（" + secret.length() + " < " + MIN_LENGTH + "）：请用至少 "
                            + MIN_LENGTH + " 字符的随机值，例如 openssl rand -base64 48");
        }
        acceptKeys.add(secret.getBytes(StandardCharsets.UTF_8));
        if (additionalSecrets != null && !additionalSecrets.isBlank()) {
            for (String s : additionalSecrets.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    acceptKeys.add(t.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        log.info("JWT 验签密钥已装载：主密钥 1 个 + 轮转期附加 {} 个", acceptKeys.size() - 1);
    }

    /**
     * 验签：主密钥优先，其次依次尝试轮转期的附加密钥。
     * 任一通过即算有效；全部不通过才算失败。
     */
    public boolean verify(String token) {
        for (byte[] key : acceptKeys) {
            try {
                if (JWTUtil.verify(token, key)) {
                    return true;
                }
            } catch (Exception ignored) {
                // 单个密钥验不过就试下一个；全部失败由调用方按"验签不通过"处理。
            }
        }
        return false;
    }
}
