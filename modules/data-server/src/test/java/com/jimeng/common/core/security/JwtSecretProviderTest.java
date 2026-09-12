package com.jimeng.common.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 密钥装载的 fail-closed 行为。
 *
 * <p>这几条用例钉的是一个<b>安全不变式</b>：没有正确配置密钥时，应用必须启动失败，
 * 而不是回落到任何内置值。历史上这个密钥硬编码在源码里（且 common-core 与 gateway 各一份），
 * 等于任何能读仓库的人都能签发任意租户/用户的令牌。回落 = 把这个洞重新打开，
 * 所以"没配就拒启动"必须有用例守住，不能只靠注释。
 */
class JwtSecretProviderTest {

    private JwtSecretProvider providerWith(String secret) {
        JwtSecretProvider p = new JwtSecretProvider();
        ReflectionTestUtils.setField(p, "secret", secret);
        return p;
    }

    @Test
    @DisplayName("未配置密钥 → 拒绝启动（不得回落到任何默认值）")
    void blankSecretRefusesStartup() {
        for (String bad : new String[]{null, "", "   "}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> providerWith(bad).validate(),
                    "空密钥必须抛异常，值=" + bad);
            assertTrue(e.getMessage().contains("jwt.secret"),
                    "异常信息要指明是哪个配置项，便于排查：" + e.getMessage());
        }
    }

    @Test
    @DisplayName("密钥过短 → 拒绝启动（短密钥可被暴力破解，签名失去意义）")
    void shortSecretRefusesStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> providerWith("short-key-31-chars-only-xxxxxxx").validate());
        assertTrue(e.getMessage().contains("过短"), e.getMessage());
    }

    @Test
    @DisplayName("合规密钥 → 正常装载，key() 返回其 UTF-8 字节")
    void validSecretLoads() {
        String secret = "0123456789abcdef0123456789abcdef";   // 恰好 32 位
        JwtSecretProvider p = providerWith(secret);
        assertDoesNotThrow(p::validate);
        assertArrayEquals(secret.getBytes(StandardCharsets.UTF_8), p.key());
    }
}
