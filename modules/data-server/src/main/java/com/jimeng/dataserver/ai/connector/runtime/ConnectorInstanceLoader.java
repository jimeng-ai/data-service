package com.jimeng.dataserver.ai.connector.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.WritePolicy;
import com.jimeng.persistence.entity.Connection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 把一行 {@code connection} 载入成连接器实现能直接用的 {@link ConnectorInstance}：
 * 解析参数、解密凭据。<b>不做任何授权判断</b>——那是 {@link ConnectorGateway} 的事，
 * 两者分开是为了让「能不能用」和「怎么用」不会互相遮蔽。
 *
 * <h3>参数只从 config_json 来</h3>
 * 2026-10 之前，这里还要把 HTTP 类型存在旧列（{@code base_url} / {@code auth_scheme} /
 * {@code allow_methods} / {@code allow_paths}）里的参数合并进来。HTTP 类连接器下线之后，
 * 现存类型的参数全部在 {@code config_json} 里，旧列不再被读。
 *
 * <h3>解析失败一律报错，不回落</h3>
 * {@code config_json} 坏了就抛 {@code CONFIG_ERROR}，让人当场看见——
 * 往「放宽」方向静默回落（比如解析失败就当成没有限制）是这个仓库踩过的坑。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorInstanceLoader {

    private final CredentialCipher cipher;

    /** 解析参数：只读 {@code config_json}。返回可变 Map，调用方可以往里补默认值。 */
    public Map<String, Object> readParams(Connection row) {
        if (row == null) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "连接不存在");
        }
        return new LinkedHashMap<>(readConfigJson(row));
    }

    /**
     * 载入并解密。
     *
     * <p>返回对象里的 {@code credential} 是<b>明文</b>，只应存在于一次调用的栈上：
     * 不落库、不进日志、不进工具返回值、不进模型上下文。
     */
    public ConnectorInstance load(Connection row) {
        Map<String, Object> params = readParams(row);
        String credential = decrypt(row);
        return new ConnectorInstance(
                row.getId(),
                row.getTenantId(),
                normalizeKind(row.getKind()),
                row.getName(),
                row.getDisplayName(),
                params,
                credential,
                row.getTransport() == null || row.getTransport().isBlank() ? "direct" : row.getTransport(),
                // 解析不出来一律落到 FORBIDDEN——见 WritePolicy.parse。
                WritePolicy.parse(row.getWritePolicy()));
    }

    // ---------------------------------------------------------------- 内部

    private String decrypt(Connection row) {
        if (row.getCredentialCipher() == null || row.getCredentialCipher().isBlank()) {
            // 没有凭据不一定是错误（将来可能有免认证的内网连接器），所以返回 null 而不是抛。
            // 需要凭据的连接器实现自己在 open() 里判空并给出可操作的提示。
            return null;
        }
        int version = row.getEncryptionVersion() == null ? 0 : row.getEncryptionVersion();
        try {
            // CredentialCipher.decrypt 对 version < 1 会直接抛（拒绝明文回落），这里不做任何兜底——
            // 兜底就等于把「这条其实没加密」这件事永久埋掉。
            return cipher.decrypt(row.getCredentialCipher(), version);
        } catch (ServiceException e) {
            // ★ 归类必须在这里做。CredentialCipher 抛的是 ServiceException（common-core 的 Web 层异常），
            // 不是 ConnectorException，于是它会一路穿到调用方的兜底 catch，把一条【可操作】的信息
            // （「密钥轮换过，请重新填写凭据」）降级成「探测失败，请点测试连接查看详情」——
            // 而点了测试连接才看到真正的原因，等于让人多绕一圈。
            //
            // 归到 AUTH_FAILED 而不是 CONFIG_ERROR：从使用者的角度，这就是「这把凭据用不了」。
            log.warn("连接器凭据解密失败 connectorId={} encryptionVersion={}", row.getId(), version);
            throw ConnectorException.of(ConnectorErrorCode.AUTH_FAILED,
                    "连接凭据无法解密（多半是加密密钥已轮换），请在管理台重新填写凭据");
        }
    }

    private Map<String, Object> readConfigJson(Connection row) {
        String json = row.getConfigJson();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return CommonUtil.getObjectMapper().readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            // 原始异常里带着 config_json 的片段，只准进日志。
            log.warn("连接器 config_json 解析失败 connectorId={}", row.getId(), e);
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "这条连接的参数已损坏，无法解析。请在管理台重新保存一次配置");
        }
    }

    /**
     * kind 为空归一成 HTTP：kind 列出现之前建的存量行都是 HTTP 类。HTTP 类 2026-10 已下线，
     * 所以这样的行按已下线类型对待（不探测、不能授权、不列给模型）。
     */
    public static String normalizeKind(String kind) {
        return kind == null || kind.isBlank() ? "HTTP" : kind.trim().toUpperCase(Locale.ROOT);
    }
}
