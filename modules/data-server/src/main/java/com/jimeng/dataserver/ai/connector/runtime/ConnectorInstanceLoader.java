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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把一行 {@code connection} 载入成连接器实现能直接用的 {@link ConnectorInstance}：
 * 解析参数、解密凭据。<b>不做任何授权判断</b>——那是 {@link ConnectorGateway} 的事，
 * 两者分开是为了让「能不能用」和「怎么用」不会互相遮蔽。
 *
 * <h3>★ 本类存在的全部理由：把「演化」的代价挡在这一层</h3>
 * 我们选择<b>演化 {@code connection} 表</b>而不是新建平行表（后者 = 两套注册表 → 横切层写两遍 →
 * 行为分叉）。代价是 HTTP 类型的参数存量在旧列里（{@code base_url} / {@code auth_scheme} /
 * {@code allow_methods} / {@code allow_paths}），而新类型在 {@code config_json} 里。
 *
 * <p>这层归一把两边合并成一份扁平 params，于是 {@code HttpConnector} 看到的和
 * {@code MySqlConnector} 看到的是同一种形状，<b>上层一行 {@code if (kind == HTTP)} 都不需要</b>。
 * 如果哪天这个 if 出现在了能力层或工具层，说明这层归一漏了东西，应该回来补，而不是在上面加分支。
 *
 * <h3>解析失败一律报错，不回落</h3>
 * {@code ConnectionResolver.readPaths} 在 JSON 解析失败时返回空、边车随后按
 * {@code ["/**"]} 放行——那是往<b>放宽</b>方向的静默回落，是仓库里一个已知的坑。
 * 这里反过来：{@code config_json} 坏了就抛 {@code CONFIG_ERROR}，让人当场看见。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorInstanceLoader {

    /** HTTP 类型的旧列 → 统一参数名。改这里等于改 {@code HttpConnector.paramSpec()}，两边必须同时改。 */
    public static final String P_BASE_URL = "baseUrl";
    public static final String P_AUTH_SCHEME = "authScheme";
    public static final String P_ALLOW_METHODS = "allowMethods";
    public static final String P_ALLOW_PATHS = "allowPaths";

    private final CredentialCipher cipher;

    /**
     * 解析参数。HTTP 类型把旧列合并进来；其余类型只读 {@code config_json}。
     *
     * <p>合并顺序是<b>先旧列后 config_json</b>：同名时以 config_json 为准。理由是新管理面
     * （{@code /data/admin/connectors}）两边都写，而旧管理面（{@code /data/admin/connections}）
     * 只写旧列——让新的那份赢，才能保证「从新界面改过的值立刻生效」。
     */
    public Map<String, Object> readParams(Connection row) {
        if (row == null) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "连接不存在");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (isHttp(row.getKind())) {
            putIfPresent(params, P_BASE_URL, row.getBaseUrl());
            putIfPresent(params, P_AUTH_SCHEME, row.getAuthScheme());
            params.put(P_ALLOW_METHODS, splitMethods(row.getAllowMethods()));
            params.put(P_ALLOW_PATHS, readPathsStrict(row.getAllowPaths(), row.getId()));
        }
        params.putAll(readConfigJson(row));
        return params;
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
     * 读 {@code allow_paths}。<b>与 {@code ConnectionResolver.readPaths} 的关键区别：解析失败要抛。</b>
     * 那边失败返回 null、边车按「全放行」处理，这里失败就是配置坏了，必须让人看见。
     */
    private List<String> readPathsStrict(String json, Long id) {
        if (json == null || json.isBlank()) {
            // 留空是一个明确的语义：等价于 ["/**"]。它和「解析失败」是两回事，不能合并。
            return List.of();
        }
        try {
            return CommonUtil.getObjectMapper().readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("连接器 allow_paths 解析失败 connectorId={}", id, e);
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "这条连接的路径白名单已损坏，无法解析。请在管理台重新保存一次配置");
        }
    }

    private static List<String> splitMethods(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of("GET");    // 与建表默认值一致：默认只读
        }
        List<String> out = new ArrayList<>();
        for (String s : Arrays.asList(csv.split(","))) {
            String m = s.trim().toUpperCase(Locale.ROOT);
            if (!m.isEmpty()) out.add(m);
        }
        return out.isEmpty() ? List.of("GET") : out;
    }

    private static void putIfPresent(Map<String, Object> m, String k, String v) {
        if (v != null && !v.isBlank()) m.put(k, v);
    }

    public static boolean isHttp(String kind) {
        return kind == null || kind.isBlank() || "HTTP".equalsIgnoreCase(kind.trim());
    }

    /** kind 为空按 HTTP 处理：存量行是在 kind 列存在之前建的，回填脚本没跑到的也不该失效。 */
    public static String normalizeKind(String kind) {
        return kind == null || kind.isBlank() ? "HTTP" : kind.trim().toUpperCase(Locale.ROOT);
    }
}
