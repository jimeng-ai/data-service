package com.jimeng.dataserver.ai.connection;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 外部连接凭据的静态加密。AES-256-GCM，密钥来自环境变量。
 *
 * <h3>为什么不重用 PluginCredentialService 的做法</h3>
 * 那边 {@code credential_data TEXT COMMENT '明文 JSON'}，且 {@code encryption_version != 0} 时
 * 打一条 warn 然后<b>按明文继续解析</b>。结果是：字段和版本号都留了，加密一直没实现，
 * 而代码路径永远不会因此失败——没有任何信号提示"这里是明文"。本类反过来：
 * <b>密钥缺失就抛异常</b>，读写都不降级。宁可功能不可用，不要悄悄存明文。
 *
 * <h3>密钥为什么从环境变量而不是 Nacos</h3>
 * 部署用的 Nacos 容器是 {@code NACOS_AUTH_ENABLE=false} 且端口对外发布的。把解密密钥放进
 * 一个免认证的配置中心，等于没加密。环境变量至少与配置中心的暴露面解耦。
 * 这不是理想方案（理想是 KMS），但它是这套基础设施里【真实可得】的最强选项。
 *
 * <h3>轮换</h3>
 * {@code encryption_version} 是为轮换留的：新版本用新密钥写，读时按行上的版本选密钥。
 * 当前只有 v1；加 v2 时在这里加分支，不要复用 v1 的槽位。
 */
@Slf4j
@Component
public class CredentialCipher {

    /** 当前写入使用的版本。读取时按行上的值分派。 */
    public static final int CURRENT_VERSION = 1;

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;      // GCM 推荐 96-bit IV
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();

    /** base64 编码的 32 字节密钥。留空则本实例无法读写连接凭据（功能不可用，但不会静默存明文）。 */
    @Value("${connection.credential-key:${CONNECTION_CREDENTIAL_KEY:}}")
    private String keyBase64;

    private SecretKey key;

    @PostConstruct
    void init() {
        if (keyBase64 == null || keyBase64.isBlank()) {
            // 不 fail-fast 整个应用：连接是新增能力，缺密钥不该拖垮对话/RAG 等既有功能。
            // 但任何一次读写凭据都会抛异常，且这里留下一条醒目日志。
            log.warn("⚠️  CONNECTION_CREDENTIAL_KEY 未配置：外部连接凭据无法读写，相关功能不可用。"
                    + "生成方式：openssl rand -base64 32");
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(keyBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("CONNECTION_CREDENTIAL_KEY 不是合法 base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("CONNECTION_CREDENTIAL_KEY 解码后必须是 32 字节（AES-256），实际 " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
        log.info("连接凭据加密已启用（AES-256-GCM, v{}）", CURRENT_VERSION);
    }

    public boolean isAvailable() {
        return key != null;
    }

    private SecretKey requireKey() {
        if (key == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "未配置 CONNECTION_CREDENTIAL_KEY，无法读写连接凭据");
        }
        return key;
    }

    /** 加密为 base64(iv || ciphertext+tag)。 */
    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "凭据加密失败: " + e.getMessage());
        }
    }

    /**
     * 解密。version 必须 >= 1 —— <b>0（明文）一律拒绝</b>，不做任何回落。
     * 这是与 PluginCredentialService 最本质的区别：那边回落明文，于是"没加密"永远不会被发现。
     */
    public String decrypt(String cipherBase64, int version) {
        if (cipherBase64 == null) return null;
        if (version < CURRENT_VERSION) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "连接凭据 encryption_version=" + version + " 不受支持（不接受明文回落），请重新录入凭据");
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherBase64);
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文过短");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            // 不把异常细节带出去：解密失败的原因（tag 不匹配 / 密钥不对）对调用方无用，对攻击者有用。
            log.warn("连接凭据解密失败: {}", e.getMessage());
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "连接凭据解密失败，可能是密钥已轮换");
        }
    }
}
