package com.jimeng.dataserver.ai.connector.spi;

import java.util.Map;

/**
 * 一个已解析的连接器实例——连接器实现类拿到的全部输入。
 *
 * <p><b>{@link #credential()} 是明文，且只在内存里短暂存在。</b>它由
 * {@code CredentialCipher} 在网关层解密后注入，随调用结束一起消失：不落库、不进日志、
 * 不进工具返回值、不进模型上下文。
 *
 * @param id          {@code connection.id}
 * @param tenantId    租户
 * @param kind        连接器类型标识，对应 {@link Connector#kind()}
 * @param name        实例名（租户内唯一）
 * @param displayName 给人看的名字
 * @param params      非敏感参数，来自 {@code config_json}，已按 {@link ParamSpec} 归一
 * @param credential  敏感参数明文。单值型连接器直接是那个值；多值型是一段 JSON
 * @param transport   {@code direct} | {@code tunnel}
 */
public record ConnectorInstance(
        Long id,
        String tenantId,
        String kind,
        String name,
        String displayName,
        Map<String, Object> params,
        String credential,
        String transport
) {

    public ConnectorInstance {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public String str(String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public String str(String key, String fallback) {
        String v = str(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    public int intVal(String key, int fallback) {
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean boolVal(String key, boolean fallback) {
        Object v = params.get(key);
        if (v instanceof Boolean b) return b;
        if (v == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /** 用于连接池复用判定：参数或凭据一变，旧池必须作废重建。 */
    public String poolKey() {
        return id + ":" + Integer.toHexString(params.hashCode())
                + ":" + Integer.toHexString(credential == null ? 0 : credential.hashCode());
    }
}
