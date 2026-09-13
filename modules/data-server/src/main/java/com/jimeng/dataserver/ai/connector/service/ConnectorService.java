package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorInstanceLoader;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProbeService;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ParamField;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 连接器实例的录入与维护（类型感知）。
 *
 * <h3>与 ConnectionService 的关系：两个入口，一张表</h3>
 * 旧的 {@code ConnectionService} / {@code /data/admin/connections} <b>原样保留</b>，
 * 它服务沙箱 egress 那条链路，写出来的行 {@code kind} 默认是 {@code HTTP}。
 * 本类是类型感知的新入口，覆盖全部 kind。两者读写<b>同一张 {@code connection} 表</b>——
 * 这正是「演化而不是并存」这个决策的落点：两个 API 入口，一张表，<b>一套横切</b>。
 *
 * <h3>★ HTTP 类型必须双写</h3>
 * {@code kind=HTTP} 时，参数除了写进 {@code config_json}，还要<b>同时写回旧列</b>
 * （{@code base_url} / {@code auth_scheme} / {@code allow_methods} / {@code allow_paths}）。
 * 因为 {@code ConnectionResolver}（下发给沙箱边车的那条路）读的是旧列——不双写，
 * 从新界面建的 HTTP 连接在沙箱里<b>就是不存在的</b>，而且不报错。
 * 这是「演化」必须付的代价，<b>改这里之前先想清楚沙箱那条路会不会断</b>。
 *
 * <h3>新建必须探测通过才准保存</h3>
 * 配错的东西必须<b>当场报错</b>，而不是等到 Agent 回答不对时才发现。
 * 这个系统吃过好几次同一个形状的亏：配错了不报错，只是悄悄降级成某种能用但不对的状态，
 * 排查成本远大于修复成本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorService {

    /** 与 egress 代理的 {@code /api/<name>/} 路由正则一致，也是模型调工具时的寻址键。 */
    private static final Pattern NAME_RE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final ConnectionMapper connectionMapper;
    private final AgentConnectionMapper agentConnectionMapper;
    private final ConnectorSchemaMapper connectorSchemaMapper;
    private final ConnectorRegistry registry;
    private final ConnectorInstanceLoader loader;
    private final ConnectorProbeService probeService;
    private final CustomerDataSourceManager dataSourceManager;
    private final CredentialCipher cipher;

    // ================================================================ 类型元数据

    /**
     * 所有连接器类型 + 各自的表单 schema。
     *
     * <p><b>这是「新增一种连接器类型，前端零改动」的兑现点。</b>前端按这份 schema 渲染表单，
     * 不硬编码任何字段名、不写 {@code if (kind === 'MYSQL')}。
     * 如果哪天前端不得不为某个类型写分支，那说明 {@code ParamSpec} 表达力不够，
     * 该扩的是 {@code ParamField}，不是在前端加 if。
     */
    public List<Map<String, Object>> kinds() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Connector c : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", c.kind());
            m.put("displayName", c.displayName());
            m.put("capabilities", c.declaredCapabilities().stream()
                    .map(x -> x.name().toLowerCase(Locale.ROOT)).toList());
            m.put("fields", c.paramSpec().toFormSchema());
            out.add(m);
        }
        return out;
    }

    // ================================================================ 读

    public List<ConnectorView> list() {
        return connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                        .orderByDesc(Connection::getCreateTime))
                .stream().map(this::toView).collect(Collectors.toList());
    }

    public ConnectorView get(Long id) {
        return toView(requireRow(id));
    }

    // ================================================================ 写

    @Transactional
    public ConnectorView create(ConnectorUpsert req) {
        validateBasics(req, true);
        Connector connector = registry.require(req.getKind());

        Connection row = new Connection();
        row.setKind(connector.kind());
        row.setStatus("ACTIVE");
        row.setTransport("direct");
        apply(row, req, connector, null);

        // 保存前先探。探不过就不落库——一条探不过的连接留在库里，只会在某次对话里静默失败。
        ConnectorProbeService.ProbeReport report = probeOrThrow(row);
        writeProbeResult(row, report);

        connectionMapper.insert(row);
        log.info("创建连接器实例 id={} kind={} name={}", row.getId(), row.getKind(), row.getName());
        return toView(row);
    }

    @Transactional
    public ConnectorView update(Long id, ConnectorUpsert req) {
        Connection row = requireRow(id);
        // kind 不可改：config_json 的形状是按类型定的，改 kind 等于把一堆 MySQL 参数
        // 交给 HTTP 连接器去解释。要换类型就删了重建。
        if (req.getKind() != null && !req.getKind().isBlank()
                && !ConnectorRegistry.normalize(req.getKind()).equals(ConnectorRegistry.normalize(row.getKind()))) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "不能修改连接器类型。请删除后重新创建");
        }
        validateBasics(req, false);
        Connector connector = registry.require(row.getKind());
        apply(row, req, connector, row);

        ConnectorProbeService.ProbeReport report = probeOrThrow(row);
        writeProbeResult(row, report);

        connectionMapper.updateById(row);
        // 参数或凭据可能变了，旧池必须作废——否则改了密码之后旧池还在用旧凭据，
        // 表现为「改了没生效」，而且要等池自然过期才恢复。
        dataSourceManager.invalidate(id);
        log.info("更新连接器实例 id={} kind={} name={}", id, row.getKind(), row.getName());
        return toView(row);
    }

    /** 重新探测并回填。给管理台的「测试连接」按钮用。 */
    @Transactional
    public ConnectorView test(Long id) {
        Connection row = requireRow(id);
        ConnectorProbeService.ProbeReport report = probeService.probe(loader.load(row));
        writeProbeResult(row, report);
        connectionMapper.updateById(row);
        if (!report.ok()) {
            // 探测失败不抛异常：这是「测试」接口，失败本身就是它要返回的结果。
            // 抛异常会让前端拿不到回填后的健康态。
            log.info("连接器测试未通过 id={} reason={}", id, report.failureReason());
        }
        return toView(row);
    }

    @Transactional
    public void setStatus(Long id, String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "status 只能是 ACTIVE / DISABLED");
        }
        Connection row = requireRow(id);
        row.setStatus(status);
        connectionMapper.updateById(row);
        if (!"ACTIVE".equals(status)) {
            dataSourceManager.invalidate(id);
        }
    }

    @Transactional
    public void delete(Long id) {
        Connection row = requireRow(id);
        // 顺序照抄 ConnectionService.delete 的理由：先摘授权再删连接。反过来的话，
        // 中间失败会留下指向不存在连接的授权行，而解析那一步对这种行只会跳过——又一处静默失效。
        agentConnectionMapper.delete(new LambdaQueryWrapper<AgentConnection>()
                .eq(AgentConnection::getConnectionId, id));
        // 自描述缓存走物理删除：它的唯一键不含 deleted，软删的行会占住键位，
        // 下次同名连接建起来刷新 schema 就撞键。
        // 注意这条 SQL 绕过了租户拦截器，所以上面 requireRow(id) 的租户校验是它的前提。
        connectorSchemaMapper.physicalDeleteByConnector(id);
        connectionMapper.deleteById(id);
        dataSourceManager.invalidate(id);
        log.info("删除连接器实例 id={} name={}", id, row.getName());
    }

    // ================================================================ 内部

    private Connection requireRow(Long id) {
        // 租户过滤由 MyBatis 拦截器注入（connection 在 TENANT_AWARE_TABLES 白名单里）。
        Connection row = connectionMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        }
        return row;
    }

    private void validateBasics(ConnectorUpsert req, boolean creating) {
        if (req == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请求体不能为空");
        }
        if (creating) {
            if (req.getKind() == null || !registry.supports(req.getKind())) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                        "不支持的连接器类型：" + req.getKind());
            }
            if (req.getName() == null || !NAME_RE.matcher(req.getName().trim()).matches()) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                        "name 必须匹配 ^[A-Za-z0-9_-]{1,64}$（它会直接出现在 URL 路径里，也是模型寻址用的键）");
            }
        }
        if (req.getTransport() != null && !req.getTransport().isBlank()
                && !"direct".equalsIgnoreCase(req.getTransport())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "内网隧道（tunnel）尚未实现，当前只支持 direct");
        }
    }

    /**
     * 把录入值落到实体上。<b>校验全部走 {@code ParamSpec.validate()}</b>——
     * 不在这里写一套 if 判断，否则每加一种类型都要改本方法，直接违反「加一种类型只写一个实现类」。
     *
     * @param existing 编辑时的原行，用于「敏感参数留空 = 沿用原值」
     */
    private void apply(Connection row, ConnectorUpsert req, Connector connector, Connection existing) {
        if (req.getName() != null && !req.getName().isBlank()) {
            row.setName(req.getName().trim());
        }
        if (req.getDisplayName() != null) {
            row.setDisplayName(req.getDisplayName());
        }

        Map<String, Object> incoming = req.getParams() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(req.getParams());
        Set<String> secretNames = Set.copyOf(connector.paramSpec().secretNames());

        // 编辑时敏感参数留空 = 沿用原值。为了让 validate 通过，先把「原来有值」这件事补进去：
        // 用一个占位串参与校验，随后再剔除——不能把真实密文或明文放进来，它会流进校验失败的错误文案。
        boolean reuseSecret = false;
        if (existing != null) {
            for (String s : secretNames) {
                Object v = incoming.get(s);
                if (v == null || (v instanceof String str && str.isBlank())) {
                    if (existing.getCredentialCipher() != null && !existing.getCredentialCipher().isBlank()) {
                        incoming.put(s, "__KEEP__");
                        reuseSecret = true;
                    }
                }
            }
        }

        List<String> errs = connector.paramSpec().validate(incoming);
        if (!errs.isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, String.join("；", errs));
        }

        // 非敏感 → config_json
        Map<String, Object> nonSecret = connector.paramSpec().normalizeNonSecret(incoming);
        row.setConfigJson(toJson(nonSecret));

        // 敏感 → 密文
        if (!reuseSecret) {
            String plaintext = buildSecretPayload(secretNames, incoming);
            if (plaintext != null) {
                if (!cipher.isAvailable()) {
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                            "未配置 CONNECTION_CREDENTIAL_KEY，无法保存凭据（不会退化为明文存储）");
                }
                row.setCredentialCipher(cipher.encrypt(plaintext));
                row.setEncryptionVersion(CredentialCipher.CURRENT_VERSION);
            }
        }

        applyLegacyColumnsForHttp(row, connector, nonSecret);
    }

    /**
     * 多个敏感参数时序列化成一段 JSON 再整体加密；<b>单个时直接存那个值</b>。
     *
     * <p>单值不套 JSON 是刻意的：现有的 HTTP 连接（沙箱那条路）存的就是裸令牌，
     * {@code ConnectionResolver} 直接把它当 token 下发。套上 JSON 会让所有存量行解不出来。
     */
    private String buildSecretPayload(Set<String> secretNames, Map<String, Object> incoming) {
        if (secretNames.isEmpty()) {
            return null;
        }
        if (secretNames.size() == 1) {
            Object v = incoming.get(secretNames.iterator().next());
            return v == null ? null : String.valueOf(v);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (String s : secretNames) {
            Object v = incoming.get(s);
            if (v != null) m.put(s, v);
        }
        return m.isEmpty() ? null : toJson(m);
    }

    /**
     * ★ HTTP 类型双写旧列。见类注释——不写，沙箱那条路就看不到这条连接，且不报错。
     */
    private void applyLegacyColumnsForHttp(Connection row, Connector connector, Map<String, Object> params) {
        if (!"HTTP".equalsIgnoreCase(connector.kind())) {
            return;
        }
        row.setBaseUrl(str(params.get(ConnectorInstanceLoader.P_BASE_URL)));
        row.setAuthScheme(str(params.getOrDefault(ConnectorInstanceLoader.P_AUTH_SCHEME, "bearer")));
        Object methods = params.get(ConnectorInstanceLoader.P_ALLOW_METHODS);
        row.setAllowMethods(methods instanceof List<?> l && !l.isEmpty()
                ? l.stream().map(String::valueOf).map(s -> s.toUpperCase(Locale.ROOT))
                   .distinct().collect(Collectors.joining(","))
                : "GET");
        Object paths = params.get(ConnectorInstanceLoader.P_ALLOW_PATHS);
        row.setAllowPaths(paths instanceof List<?> l && !l.isEmpty() ? toJson(l) : null);
    }

    private ConnectorProbeService.ProbeReport probeOrThrow(Connection row) {
        ConnectorInstance inst;
        try {
            inst = loader.load(row);
        } catch (ConnectorException e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    e.getSafeDetail() == null ? e.getCode().title() : e.getSafeDetail());
        }
        ConnectorProbeService.ProbeReport report = probeService.probe(inst);
        if (!report.ok()) {
            // 探测失败的文案已经在 ProbeService 里做成可操作的了（「请改用只读账号」「请检查 host 和端口」）。
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, report.failureReason());
        }
        return report;
    }

    private void writeProbeResult(Connection row, ConnectorProbeService.ProbeReport report) {
        Date now = new Date();
        row.setHealthState(report.ok() ? "HEALTHY" : "UNHEALTHY");
        row.setHealthCheckedAt(now);
        row.setHealthReason(report.ok() ? null : report.failureReason());
        row.setCapabilityFlags(report.capabilities() == null || report.capabilities().isEmpty()
                ? null
                : report.capabilities().stream().map(Capability::name).collect(Collectors.joining(",")));
        if (report.readOnly() != null && report.readOnly().acceptable()) {
            row.setReadonlyVerifiedAt(now);
        } else if (!report.ok()) {
            // 探测没过就把只读验证时间清掉：留着一个旧的通过时间，会让界面显示「已验证只读」，
            // 而实际这条连接现在的状态是未知的。
            row.setReadonlyVerifiedAt(null);
        }
    }

    private ConnectorView toView(Connection row) {
        final Map<String, Object> params = new LinkedHashMap<>();
        try {
            params.putAll(loader.readParams(row));
        } catch (ConnectorException e) {
            // 参数坏了也要能看到这条连接（否则用户连删都删不掉），但要显式告诉他坏了。
            params.clear();
            params.put("__error__", "参数已损坏，无法解析。请重新保存配置");
        }
        String kind = ConnectorInstanceLoader.normalizeKind(row.getKind());
        // 保险起见再剔一遍敏感字段：loader 只读 config_json 与旧列，理论上不含敏感值，
        // 但「理论上不含」不是可以不删的理由——这类地方漏一次就是凭据出网。
        registry.find(kind).ifPresent(c -> c.paramSpec().secretNames().forEach(params::remove));

        return ConnectorView.builder()
                .id(row.getId() == null ? null : String.valueOf(row.getId()))
                .name(row.getName())
                .displayName(row.getDisplayName())
                .kind(kind)
                .kindLabel(registry.find(kind).map(Connector::displayName).orElse(kind))
                .params(params)
                .transport(row.getTransport() == null ? "direct" : row.getTransport())
                .status(row.getStatus())
                .capabilities(row.getCapabilityFlags() == null || row.getCapabilityFlags().isBlank()
                        ? List.of()
                        : List.of(row.getCapabilityFlags().split(",")))
                .healthState(row.getHealthState() == null ? "UNKNOWN" : row.getHealthState())
                .healthCheckedAt(row.getHealthCheckedAt())
                .healthReason(row.getHealthReason())
                .readonlyVerified(row.getReadonlyVerifiedAt() != null)
                .readonlyVerifiedAt(row.getReadonlyVerifiedAt())
                .createTime(row.getCreateTime())
                .build();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String toJson(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "参数序列化失败");
        }
    }

    /** 供 {@link ParamField} 的敏感字段判定使用，避免外部重复拼装。 */
    public Set<String> secretNamesOf(String kind) {
        return Set.copyOf(registry.require(kind).paramSpec().secretNames());
    }
}
