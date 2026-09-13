package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorInstanceLoader;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自描述结果快照与<b>结构漂移检测</b>。
 *
 * <h3>为什么要单独存一份，而工具又不读它</h3>
 * 技术架构 §13 把自描述结果单列一张表，理由是它「可能很大、会独立刷新、<b>而且要能对比前后变化
 * ——客户加了一个字段，我们应该知道</b>」。第三条才是重点：语义层（指标口径、业务名、
 * 样例问答）都挂在具体的表和列上，客户悄悄改了结构，挂在上面的口径就跟着失效，
 * 而这件事今天没有任何机制会发现。
 *
 * <p><b>但 {@code conn_catalog} / {@code conn_describe} 仍然读实时的，不读这张表。</b>
 * 这是刻意的：实测 catalog 11ms、describe 6ms，没有性能问题要解决；
 * 而引入一层带有效期的缓存，等于为了一个不存在的问题新开一个「悄悄给出过期结构」的失败面——
 * 模型拿着过期结构写出的 SQL 会报「列不存在」，或者更糟，命中一个语义已经变了的同名列。
 * <b>正确性优先于省那几毫秒。</b>
 *
 * <p>所以这张表的三个真实用途是：结构漂移检测、语义层的挂载锚点（P2）、
 * 以及将来 DESCRIBE 能力降级时「人工录入的结构」的落脚处（技术架构 §7.4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorSchemaService {

    /**
     * 一次快照最多描述多少个对象。
     *
     * <p>不设上限的话，一个 500 张表的库就是 500 次 {@code information_schema} 查询打在
     * <b>客户的生产库</b>上。截断了要明说——静默只快照前 200 张，会让漂移检测对其余的表
     * 永远沉默，而「沉默」会被读成「没有变化」。
     */
    private static final int MAX_OBJECTS = 200;

    private final ConnectionMapper connectionMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorRegistry registry;
    private final ConnectorInstanceLoader loader;

    /**
     * 对象级变化。{@code details} 对 CHANGED 而言是列级差异。
     *
     * <p><b>刻意不是 record。</b>本仓库实际生效的 jackson-databind 是 <b>2.11.1</b>
     * （由 {@code logstash-logback-encoder:7.4} 传递带入，压过了 Spring Boot BOM），
     * 而 Java record 的序列化支持是 <b>2.12+</b> 才有的——2.11 会报
     * 「No serializer found ... no properties discovered」。
     * 任何要跨 HTTP 出去的 DTO 在这个仓库里都必须是 Lombok 类，不能是 record。
     * 内部用的 record（{@code model/*}）不受影响，它们从不直接序列化。
     */
    @Data
    @AllArgsConstructor
    public static class ObjectDiff {
        private String objectName;
        /** ADDED | REMOVED | CHANGED */
        private String change;
        private List<String> details;
    }

    /** 同上，不是 record。 */
    @Data
    @AllArgsConstructor
    public static class SnapshotResult {
        private int objectCount;
        private int totalObjects;
        /** 对象数超过上限，本次只覆盖了一部分 */
        private boolean truncated;
        /** 第一次快照——此时「全是新增」没有信息量，界面不该把它当成结构漂移报警 */
        private boolean firstSnapshot;
        private List<ObjectDiff> diffs;
        private Date syncedAt;
    }

    // ================================================================ 读

    /** 内部用：拿原始行做差异比对。 */
    List<ConnectorSchema> currentRows(Long connectorId) {
        return schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getConnectorId, connectorId)
                .orderByAsc(ConnectorSchema::getObjectName));
    }

    /** 对外用：视图 DTO，不含 tenantId，且 detail 已解析好。 */
    public List<ConnectorSchemaView> current(Long connectorId) {
        requireRow(connectorId);
        return currentRows(connectorId).stream().map(ConnectorSchemaService::toView).toList();
    }

    @SuppressWarnings("unchecked")
    private static ConnectorSchemaView toView(ConnectorSchema r) {
        List<Map<String, Object>> fields = List.of();
        String error = null;
        if (r.getDetailJson() != null && !r.getDetailJson().isBlank()) {
            try {
                Map<String, Object> m = CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
                Object f = m.get("fields");
                if (f instanceof List<?> list) {
                    fields = (List<Map<String, Object>>) list;
                }
                Object extra = m.get("extra");
                if (extra instanceof Map<?, ?> em && em.get("__error__") != null) {
                    error = String.valueOf(em.get("__error__"));
                }
            } catch (Exception e) {
                // 存量行的 JSON 坏了不该让整个列表打不开——如实标出来，让人看得见哪一行有问题。
                log.warn("解析结构快照失败 objectName={}", r.getObjectName(), e);
                error = "结构数据损坏，请重新刷新结构";
            }
        }
        return ConnectorSchemaView.builder()
                .objectType(r.getObjectType())
                .objectName(r.getObjectName())
                .objectComment(r.getObjectComment())
                .fields(fields)
                .error(error)
                .syncedAt(r.getSyncedAt())
                .build();
    }

    // ================================================================ 刷新

    /**
     * 重新拉取结构、与上一份快照对比、落库。
     *
     * <p>这是<b>管理面</b>操作，不是 Agent 操作，所以不走 {@code ConnectorGateway}——
     * 网关的授权是「这个 Agent 被授予了这条连接吗」，而这里根本没有 Agent。
     * 但状态与 transport 的检查不能省，下面自己做。
     */
    @Transactional
    public SnapshotResult refresh(Long connectorId) {
        Connection row = requireRow(connectorId);
        assertUsable(row);

        ConnectorInstance inst = loader.load(row);
        Connector connector = registry.require(inst.kind());
        if (!connector.declaredCapabilities().contains(Capability.DESCRIBE)) {
            throw new ServiceException(ExceptionCode.OPERATION_UNSUPPORTED,
                    "这种连接器类型不支持自描述，无法拉取结构");
        }

        List<ConnectorSchema> fresh;
        CatalogView catalog;
        try (ConnectorSession session = connector.open(inst)) {
            if (!(session instanceof DescribeCapable describe)) {
                throw new ServiceException(ExceptionCode.OPERATION_UNSUPPORTED,
                        "这条连接的自描述能力不可用，请先点「测试连接」重新探测");
            }
            catalog = describe.catalog();
            fresh = pullDetails(describe, catalog, row);
        } catch (ConnectorException e) {
            // 已归一、已脱敏，直接转成业务异常给管理台看。
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    e.getSafeDetail() == null ? e.getCode().title() : e.getSafeDetail());
        }

        Map<String, ConnectorSchema> previous = indexByName(currentRows(connectorId));
        boolean first = previous.isEmpty();
        List<ObjectDiff> diffs = first ? List.of() : diff(previous, fresh);

        // 物理删除重插：唯一键不含 deleted，软删的行会占住键位，下次刷新就撞键。
        // 注意这条 SQL 绕过租户拦截器，所以上面 requireRow 的租户校验是它的前提。
        schemaMapper.physicalDeleteByConnector(connectorId);
        fresh.forEach(schemaMapper::insert);

        if (!diffs.isEmpty()) {
            log.info("连接器结构发生变化 connectorId={} name={} 变化数={}", connectorId, row.getName(), diffs.size());
        }
        return new SnapshotResult(fresh.size(), catalog.total(),
                catalog.truncated() || fresh.size() < catalog.total(), first, diffs, new Date());
    }

    // ================================================================ 内部

    private List<ConnectorSchema> pullDetails(DescribeCapable describe, CatalogView catalog, Connection row) {
        List<CatalogEntry> entries = catalog.entries() == null ? List.of() : catalog.entries();
        List<ConnectorSchema> out = new ArrayList<>();
        Date now = new Date();
        int n = 0;
        for (CatalogEntry e : entries) {
            if (n++ >= MAX_OBJECTS) {
                log.warn("连接器结构快照达到对象上限 {}，connectorId={} 共 {} 个对象，本次只覆盖前 {} 个",
                        MAX_OBJECTS, row.getId(), catalog.total(), MAX_OBJECTS);
                break;
            }
            ObjectDetail detail;
            try {
                detail = describe.describe(e.name());
            } catch (ConnectorException ex) {
                // 单个对象取不到不该让整次刷新失败（权限可能只到部分表）。
                // 但也不能当它不存在——存一条只有名字的行，界面上看得出来「这张表没取到结构」。
                log.warn("连接器对象结构获取失败，跳过细节 connectorId={} object={} code={}",
                        row.getId(), e.name(), ex.getCode());
                detail = new ObjectDetail(e.name(), e.type(), e.comment(), List.of(),
                        Map.of("__error__", "结构获取失败：" + ex.getCode().title()));
            }
            out.add(toRow(row, e, detail, now));
        }
        return out;
    }

    private ConnectorSchema toRow(Connection row, CatalogEntry entry, ObjectDetail detail, Date now) {
        ConnectorSchema s = new ConnectorSchema();
        s.setTenantId(row.getTenantId());
        s.setConnectorId(row.getId());
        s.setObjectType(entry.type() == null ? "TABLE" : entry.type());
        s.setObjectName(entry.name());
        s.setObjectComment(entry.comment());
        s.setDetailJson(toJson(toDetailMap(detail)));
        // 哈希算的是【结构】而不是整个 JSON：注释里带的「约 N 行」是估算值，每次都在变，
        // 拿它进哈希会让每次刷新都报「变了」，报警变成噪音之后没人会看。
        s.setContentHash(sha256(structureFingerprint(detail)));
        s.setSyncedAt(now);
        return s;
    }

    /** 只取会影响查询正确性的部分：字段名、类型、可空、注释。行数估算这类波动值不进指纹。 */
    static String structureFingerprint(ObjectDetail detail) {
        StringBuilder sb = new StringBuilder();
        sb.append(detail.name()).append('|').append(detail.type()).append('|');
        if (detail.fields() != null) {
            for (FieldDetail f : detail.fields()) {
                sb.append(f.name()).append(':').append(f.type()).append(':')
                        .append(f.nullable()).append(':').append(f.comment() == null ? "" : f.comment())
                        .append(';');
            }
        }
        return sb.toString();
    }

    List<ObjectDiff> diff(Map<String, ConnectorSchema> previous, List<ConnectorSchema> fresh) {
        Map<String, ConnectorSchema> now = indexByName(fresh);
        List<ObjectDiff> out = new ArrayList<>();

        for (ConnectorSchema f : fresh) {
            ConnectorSchema p = previous.get(f.getObjectName());
            if (p == null) {
                out.add(new ObjectDiff(f.getObjectName(), "ADDED", List.of()));
            } else if (!java.util.Objects.equals(p.getContentHash(), f.getContentHash())) {
                out.add(new ObjectDiff(f.getObjectName(), "CHANGED", fieldDiff(p, f)));
            }
        }
        for (String name : previous.keySet()) {
            if (!now.containsKey(name)) {
                // 表被删掉比加字段严重得多：挂在它上面的指标口径、样例问答会直接失效。
                out.add(new ObjectDiff(name, "REMOVED", List.of()));
            }
        }
        return out;
    }

    /** 列级差异。取不到旧细节时退化成「结构有变化」，不编造具体内容。 */
    List<String> fieldDiff(ConnectorSchema before, ConnectorSchema after) {
        Map<String, String> b = fieldsOf(before);
        Map<String, String> a = fieldsOf(after);
        if (b.isEmpty() && a.isEmpty()) {
            return List.of("结构有变化（无法解析细节）");
        }
        List<String> out = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>(a.keySet());
        all.addAll(b.keySet());
        for (String col : all) {
            String bt = b.get(col);
            String at = a.get(col);
            if (bt == null) {
                out.add("新增列 " + col + " " + at);
            } else if (at == null) {
                out.add("删除列 " + col);
            } else if (!bt.equals(at)) {
                out.add("列 " + col + " 由 " + bt + " 变为 " + at);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fieldsOf(ConnectorSchema s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s.getDetailJson() == null || s.getDetailJson().isBlank()) {
            return out;
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(s.getDetailJson(), Map.class);
            Object fields = m.get("fields");
            if (fields instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> fm) {
                        Object name = fm.get("name");
                        if (name != null) {
                            out.put(String.valueOf(name), String.valueOf(fm.get("type")));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析历史结构失败 objectName={}", s.getObjectName(), e);
        }
        return out;
    }

    static Map<String, ConnectorSchema> indexByName(List<ConnectorSchema> rows) {
        Map<String, ConnectorSchema> m = new LinkedHashMap<>();
        for (ConnectorSchema r : rows) {
            m.put(r.getObjectName(), r);
        }
        return m;
    }

    private Connection requireRow(Long id) {
        Connection row = connectionMapper.selectById(id);   // 租户过滤由拦截器注入
        if (row == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        }
        return row;
    }

    private void assertUsable(Connection row) {
        if (!"ACTIVE".equals(row.getStatus())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "连接已停用，无法拉取结构");
        }
        String t = row.getTransport();
        if (t != null && !t.isBlank() && !"direct".equalsIgnoreCase(t)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "内网隧道尚未实现");
        }
    }

    /**
     * 把 {@link ObjectDetail} 摊成 Map 再序列化。
     *
     * <p>两个理由：一是 jackson 2.11.1 序列化不了 record（见 {@link ObjectDiff} 的注释）；
     * 二是这份 JSON 要长期躺在库里、还要被后续版本读回来做结构比对——
     * 让它的形状由这里<b>显式</b>决定，而不是隐式跟着 record 的字段名走。
     * record 改个字段名，历史快照就读不出来了，而且不报错、只是「所有对象都显示为变更」。
     */
    static Map<String, Object> toDetailMap(ObjectDetail detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", detail.name());
        m.put("type", detail.type());
        m.put("comment", detail.comment());
        List<Map<String, Object>> fields = new ArrayList<>();
        if (detail.fields() != null) {
            for (FieldDetail f : detail.fields()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", f.name());
                fm.put("type", f.type());
                fm.put("nullable", f.nullable());
                fm.put("comment", f.comment());
                fm.put("extra", f.extra());
                fields.add(fm);
            }
        }
        m.put("fields", fields);
        m.put("extra", detail.extra());
        return m;
    }

    private String toJson(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            // 原始异常必须进日志。第一版这里只抛了一句「结构序列化失败」就把 cause 吞了，
            // 结果线上看到的是一条没有任何线索的 5000——正是本项目反复吃亏的那种静默。
            log.error("连接器结构序列化失败 type={}", o == null ? null : o.getClass().getName(), e);
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "结构序列化失败");
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "计算结构指纹失败");
        }
    }
}
