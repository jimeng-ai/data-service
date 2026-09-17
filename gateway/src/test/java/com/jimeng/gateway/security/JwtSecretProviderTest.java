package com.jimeng.gateway.security;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.signers.JWTSignerUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 网关 JWT 验签必须固定为系统签发所用的 HS256，不能让令牌头自行选择算法。 */
class JwtSecretProviderTest {

    private static final String PRIMARY = "0123456789abcdef0123456789abcdef";
    private static final String ROTATED = "fedcba9876543210fedcba9876543210";

    private JwtSecretProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = new JwtSecretProvider();
        setField("secret", PRIMARY);
        setField("additionalSecrets", ROTATED);
        provider.init();
    }

    @Test
    @DisplayName("主密钥与轮转密钥签发的 HS256 令牌都可通过")
    void acceptsConfiguredHs256Keys() {
        assertTrue(provider.verify(JWTUtil.createToken(Map.of("id", "1"), bytes(PRIMARY))));
        assertTrue(provider.verify(JWTUtil.createToken(Map.of("id", "1"), bytes(ROTATED))));
    }

    @Test
    @DisplayName("alg none 且签名段为空的令牌必须拒绝")
    void rejectsUnsignedToken() {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"id\":\"1\",\"tenant_id\":\"any\"}");

        assertFalse(provider.verify(header + "." + payload + "."));
    }

    @Test
    @DisplayName("即使签名有效，非 HS256 算法也必须拒绝")
    void rejectsOtherAlgorithms() {
        String token = JWT.create()
                .setPayload("id", "1")
                .setSigner(JWTSignerUtil.hs512(bytes(PRIMARY)))
                .sign();

        assertFalse(provider.verify(token));
    }

    @Test
    @DisplayName("篡改签名与畸形 token 都必须拒绝")
    void rejectsBadSignatureAndMalformedToken() {
        String valid = JWTUtil.createToken(Map.of("id", "1"), bytes(PRIMARY));
        String tampered = valid.substring(0, valid.length() - 1)
                + (valid.endsWith("a") ? "b" : "a");

        assertFalse(provider.verify(tampered));
        assertFalse(provider.verify("not-a-jwt"));
        assertFalse(provider.verify(null));
    }

    private void setField(String name, String value) throws Exception {
        Field field = JwtSecretProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(provider, value);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(value));
    }
}
