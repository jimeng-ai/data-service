package com.jimeng.dataserver.ai.connection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 外部连接的录入与维护。
 *
 * <p><b>凭据是只写的</b>：所有读接口返回的 {@link Connection} 的 {@code credentialCipher}
 * 一律被抹成 null。密文本身也不该出网——它配上泄露的密钥就是明文，且能用来判断两条连接
 * 是否共用同一凭据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionService {

    /** 与 egress 代理的 /api/&lt;name&gt;/ 路由正则保持一致。不一致会出现"存得进去、调不到"。 */
    private static final Pattern NAME_RE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<String> SCHEMES = Set.of("bearer", "api-key");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");

    private final ConnectionMapper connectionMapper;
    private final AgentConnectionMapper agentConnectionMapper;
    private final CredentialCipher cipher;

    public List<Connection> list() {
        List<Connection> rows = connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .orderByDesc(Connection::getCreateTime));
        rows.forEach(this::stripSecret);
        return rows;
    }

    public Connection get(Long id) {
        Connection c = connectionMapper.selectById(id);
        if (c == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        stripSecret(c);
        return c;
    }

    @Transactional
    public Connection create(ConnectionUpsert req) {
        validate(req, true);
        Connection c = new Connection();
        apply(c, req);
        c.setStatus("ACTIVE");
        connectionMapper.insert(c);
        log.info("创建外部连接 name={} baseUrl={} methods={}", c.getName(), c.getBaseUrl(), c.getAllowMethods());
        stripSecret(c);
        return c;
    }

    @Transactional
    public Connection update(Long id, ConnectionUpsert req) {
        Connection c = connectionMapper.selectById(id);
        if (c == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        validate(req, false);
        apply(c, req);
        connectionMapper.updateById(c);
        log.info("更新外部连接 id={} name={}", id, c.getName());
        stripSecret(c);
        return c;
    }

    @Transactional
    public void setStatus(Long id, String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "status 只能是 ACTIVE / DISABLED");
        }
        Connection c = connectionMapper.selectById(id);
        if (c == null) throw new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        c.setStatus(status);
        connectionMapper.updateById(c);
    }

    @Transactional
    public void delete(Long id) {
        // 先摘授权再删连接：反过来的话，中间若失败会留下指向不存在连接的授权行，
        // 而 ConnectionResolver 对这种行只会 warn 跳过——又一处静默失效。
        agentConnectionMapper.delete(new LambdaQueryWrapper<AgentConnection>()
                .eq(AgentConnection::getConnectionId, id));
        connectionMapper.deleteById(id);
    }

    private void apply(Connection c, ConnectionUpsert req) {
        c.setName(req.getName().trim());
        c.setDisplayName(req.getDisplayName());
        c.setBaseUrl(req.getBaseUrl().trim());
        c.setAuthScheme(req.getAuthScheme() == null ? "bearer" : req.getAuthScheme().toLowerCase(Locale.ROOT));
        c.setAllowMethods(normalizeMethods(req.getAllowMethods()));
        c.setAllowPaths(normalizePaths(req.getAllowPaths()));
        c.setTransport("direct");
        // 凭据留空 = 保持原值不变（编辑连接时不必重填密钥）。传了才覆盖。
        if (req.getCredential() != null && !req.getCredential().isBlank()) {
            c.setCredentialCipher(cipher.encrypt(req.getCredential()));
            c.setEncryptionVersion(CredentialCipher.CURRENT_VERSION);
        }
    }

    private void validate(ConnectionUpsert req, boolean creating) {
        if (req == null) throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请求体不能为空");
        if (req.getName() == null || !NAME_RE.matcher(req.getName().trim()).matches()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "name 必须匹配 ^[A-Za-z0-9_-]{1,64}$（它会直接出现在 URL 路径里）");
        }
        if (req.getBaseUrl() == null || req.getBaseUrl().isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "base_url 不能为空");
        }
        URI u;
        try {
            u = URI.create(req.getBaseUrl().trim());
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "base_url 不是合法 URL");
        }
        if (u.getScheme() == null || !(u.getScheme().equals("http") || u.getScheme().equals("https"))) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "base_url 只支持 http/https");
        }
        if (u.getHost() == null) throw new ServiceException(ExceptionCode.INVALID_REQUEST, "base_url 缺少主机名");
        if (u.getUserInfo() != null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "base_url 不允许带 user:pass");
        }
        if (req.getAuthScheme() != null && !SCHEMES.contains(req.getAuthScheme().toLowerCase(Locale.ROOT))) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "auth_scheme 只支持 bearer / api-key");
        }
        if (creating && (req.getCredential() == null || req.getCredential().isBlank())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "新建连接必须提供凭据");
        }
        if (req.getCredential() != null && !req.getCredential().isBlank() && !cipher.isAvailable()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "未配置 CONNECTION_CREDENTIAL_KEY，无法保存凭据（不会退化为明文存储）");
        }
        // 私网地址不在这里拦：egress 代理注册时会 resolveAndValidate 并拒绝，那才是权威判定
        // （DNS 可能在录入后改变）。这里拦只会给出"录入时通过、运行时 403"的错觉。
    }

    private String normalizeMethods(List<String> in) {
        if (in == null || in.isEmpty()) return "GET";      // 默认只读
        for (String m : in) {
            if (!METHODS.contains(m.trim().toUpperCase(Locale.ROOT))) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "不支持的 HTTP 方法: " + m);
            }
        }
        return in.stream().map(x -> x.trim().toUpperCase(Locale.ROOT)).distinct()
                .reduce((a, b) -> a + "," + b).orElse("GET");
    }

    private String normalizePaths(List<String> in) {
        if (in == null || in.isEmpty()) return null;        // null → 边车按 ["/**"] 处理
        for (String p : in) {
            if (p == null || !p.startsWith("/")) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "allow_paths 每一项都必须以 / 开头: " + p);
            }
        }
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(in);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "allow_paths 序列化失败");
        }
    }

    /** 密文永不出网：配上泄露的密钥即明文，且可用于判断两条连接是否共用同一凭据。 */
    private void stripSecret(Connection c) {
        if (c != null) c.setCredentialCipher(null);
    }

    /** 仅用于把 JSON 数组字符串读回列表，供前端回显 allow_paths。 */
    public List<String> readPaths(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return CommonUtil.getObjectMapper().readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
