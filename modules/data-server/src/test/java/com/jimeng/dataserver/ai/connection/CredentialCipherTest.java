package com.jimeng.dataserver.ai.connection;

import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.LogCapture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 凭据加密的两种解密入口：{@code decrypt}（数据连接凭据用，解不开就抛、记 ERROR，行为和以前一样）和
 * {@code decryptOrNull}（「解不开属于预期情况」的调用方用：skill 的 key 在 R27 下按 key 处理，Task 6 的网关按 R31 处理）。
 */
class CredentialCipherTest {

    private static final String SECRET = "conn-password-0123456789";

    private static CredentialCipher cipherWithKey(byte fill) {
        CredentialCipher c = new CredentialCipher();
        byte[] key = new byte[32];
        Arrays.fill(key, fill);
        ReflectionTestUtils.setField(c, "keyBase64", Base64.getEncoder().encodeToString(key));
        ReflectionTestUtils.invokeMethod(c, "init");
        return c;
    }

    @Test
    @DisplayName("★ 数据连接凭据解不开（密钥换过）：decrypt 照旧抛「连接凭据解密失败，可能是密钥已轮换」，照旧记一条 WARN 和一条 ERROR——行为和以前一样")
    void decryptStillThrowsAndLogsAnError() {
        String ciphertext = cipherWithKey((byte) 0).encrypt(SECRET);
        CredentialCipher rotated = cipherWithKey((byte) 1);
        try (LogCapture cipherLog = LogCapture.of(CredentialCipher.class); LogCapture thrown = LogCapture.of(ServiceException.class)) {
            assertThatThrownBy(() -> rotated.decrypt(ciphertext, CredentialCipher.CURRENT_VERSION))
                    .isInstanceOf(ServiceException.class).hasMessageContaining("连接凭据解密失败，可能是密钥已轮换");
            assertThat(cipherLog.warnings()).singleElement().satisfies(m -> assertThat(m).startsWith("连接凭据解密失败: "));
            assertThat(thrown.errors()).singleElement().satisfies(m -> assertThat(m).contains("连接凭据解密失败，可能是密钥已轮换"));
        }
    }

    @Test
    @DisplayName("decrypt 的其余行为也不变：null 进 null 出；版本 0（明文）照旧拒绝；没配密钥照旧抛「未配置」；没配密钥、密文又不是 base64 时照旧是「解密失败」（先解 base64、用到密钥时才取）")
    void decryptKeepsItsOtherBehaviours() {
        CredentialCipher cipher = cipherWithKey((byte) 0);
        assertThat(cipher.decrypt(null, CredentialCipher.CURRENT_VERSION)).isNull();
        assertThat(cipher.decrypt(cipher.encrypt(SECRET), CredentialCipher.CURRENT_VERSION)).isEqualTo(SECRET);
        assertThatThrownBy(() -> cipher.decrypt(cipher.encrypt(SECRET), 0))
                .isInstanceOf(ServiceException.class).hasMessageContaining("encryption_version=0 不受支持");

        CredentialCipher noKey = new CredentialCipher();
        String valid = cipher.encrypt(SECRET);
        assertThatThrownBy(() -> noKey.decrypt(valid, CredentialCipher.CURRENT_VERSION))
                .isInstanceOf(ServiceException.class).hasMessageContaining("未配置 CONNECTION_CREDENTIAL_KEY");
        assertThatThrownBy(() -> noKey.decrypt("not-even-base64", CredentialCipher.CURRENT_VERSION))
                .isInstanceOf(ServiceException.class).hasMessageContaining("连接凭据解密失败，可能是密钥已轮换");
    }

    @Test
    @DisplayName("★ decryptOrNull：解不开（密钥换过、密文坏了、不是 base64、太短、版本不受支持、null）就返回 null——不抛、不构造 ServiceException、不记任何 WARN / ERROR")
    void decryptOrNullReturnsNullQuietly() {
        String ciphertext = cipherWithKey((byte) 0).encrypt(SECRET);
        CredentialCipher rotated = cipherWithKey((byte) 1);
        try (LogCapture logs = LogCapture.everything()) {
            assertThat(rotated.decryptOrNull(ciphertext, CredentialCipher.CURRENT_VERSION)).isNull();                         // 密钥换过（tag 对不上）
            assertThat(rotated.decryptOrNull("bm90LWEtdmFsaWQtY2lwaGVydGV4dC1hdC1hbGw=", CredentialCipher.CURRENT_VERSION)).isNull();   // 密文坏了
            assertThat(rotated.decryptOrNull("not-even-base64", CredentialCipher.CURRENT_VERSION)).isNull();
            assertThat(rotated.decryptOrNull("AAAA", CredentialCipher.CURRENT_VERSION)).isNull();                            // 比 IV 还短
            assertThat(rotated.decryptOrNull(ciphertext, 0)).isNull();                                                        // 明文版本不接受
            assertThat(rotated.decryptOrNull(null, CredentialCipher.CURRENT_VERSION)).isNull();

            assertThat(logs.warnings()).isEmpty();
            assertThat(logs.errors()).isEmpty();
        }
    }

    @Test
    @DisplayName("decryptOrNull 解得开的照常给明文；没配密钥也只是返回 null（不抛）——要把没配密钥当部署错误拒绝的调用方，自己先查 isAvailable()")
    void decryptOrNullRoundTripsAndLeavesTheMissingKeyToTheCaller() {
        CredentialCipher cipher = cipherWithKey((byte) 0);
        String ciphertext = cipher.encrypt(SECRET);
        assertThat(cipher.decryptOrNull(ciphertext, CredentialCipher.CURRENT_VERSION)).isEqualTo(SECRET);

        CredentialCipher noKey = new CredentialCipher();
        try (LogCapture logs = LogCapture.everything()) {
            assertThat(noKey.isAvailable()).isFalse();
            assertThat(noKey.decryptOrNull(ciphertext, CredentialCipher.CURRENT_VERSION)).isNull();
            assertThat(logs.errors()).isEmpty();
        }
    }
}
