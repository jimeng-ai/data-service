package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.Builder;
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
import java.util.List;
import java.util.Map;

/**
 * 语义层的读写。<b>只碰我们自己的库</b>，不碰客户系统、不叫模型。
 *
 * <h3>为什么这个类必须保持"无外部依赖"</h3>
 * 它会被 {@code ConnectorToolExecutor} 注入，而那个类的构造器有一条硬约束：
 * 不准注入任何能回到 {@code ProviderRegistry} / {@code ClaudeService} / {@code ChatClient} 的 bean，
 * 否则闭合 {@code ProviderRegistry → ChatClient → AiConversationLoop → SkillRuntimeService →
 * SkillToolExecutorRegistryService → ConnectorToolExecutor} 这条链，启动期构造循环依赖直接失败
 * （仓库里已经被咬过两次）。
 * <b>推导（要叫模型）单独放在 {@link ConnectorSemanticDeriveService}，永远不要合并进来。</b>
 *
 * <h3>注入是叠加注解，不是一道闸</h3>
 * 读失败一律返回空、不抛——没有语义层时 conn_catalog / conn_describe 必须照常可用。
 * 用一个可选增强的故障去否决一个必要功能是错的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorSemanticService {

    // ── scope ──
    public static final String SCOPE_OBJECT = "OBJECT";
    public static final String SCOPE_FIELD = "FIELD";
    public static final String SCOPE_JOIN = "JOIN";
    public static final String SCOPE_METRIC = "METRIC";
    public static final String SCOPE_CAVEAT = "CAVEAT";

    // ── source ──
    public static final String SOURCE_INFERRED = "INFERRED";
    public static final String SOURCE_HUMAN = "HUMAN";
    public static final String SOURCE_IMPORTED = "IMPORTED";

    // ── evidence：分界线是"有没有依据"，不是"能不能验证" ──
    public static final String EV_COMMENT = "COMMENT";
    public static final String EV_DATA = "DATA";
    public static final String EV_NAME = "NAME";
    public static final String EV_GUESS = "GUESS";

    // ── status ──
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_STALE = "STALE";

    // ── verified ──
    public static final String V_CONFIRMED = "CONFIRMED";
    public static final String V_WEAK = "WEAK";
    public static final String V_REJECTED = "REJECTED";
    public static final String V_UNDECIDABLE = "UNDECIDABLE";
    public static final String V_NONE = "NONE";

    // ── anchorKind ──
    public static final String ANCHOR_NONE = "NONE";
    public static final String ANCHOR_FIELD = "FIELD";
    public static final String ANCHOR_JOIN = "JOIN";
    public static final String ANCHOR_COLUMN_SET = "COLUMN_SET";

    /** 注入 conn_catalog 时 gloss 的截断长度。选表那一刻需要的就是一句话，完整版在 describe 给。 */
    static final int CATALOG_GLOSS_MAX = 80;

    /** 变更留痕保留条数。只为取证，不做时间机器。 */
    static final int HISTORY_MAX = 20;

    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectionMapper connectionMapper;

    // ================================================================ 读（注入路径）

    /** conn_catalog 需要的：表用途（按表名索引）+ 口径表。 */
    public CatalogSemantics forCatalog(Long connectorId) {
        try {
            List<ConnectorSemantic> rows = semanticMapper.selectList(
                    new LambdaQueryWrapper<ConnectorSemantic>()
                            .eq(ConnectorSemantic::getConnectorId, connectorId)
                            .in(ConnectorSemantic::getScope, List.of(SCOPE_OBJECT, SCOPE_METRIC)));
            Map<String, ConnectorSemantic> objects = new LinkedHashMap<>();
            List<ConnectorSemantic> glossary = new ArrayList<>();
            for (ConnectorSemantic r : rows) {
                if (SCOPE_OBJECT.equals(r.getScope())) {
                    objects.put(r.getObjectName(), r);
                } else {
                    glossary.add(r);
                }
            }
            return CatalogSemantics.builder().objects(objects).glossary(glossary).build();
        } catch (Exception e) {
            // 读语义层失败不该让"看目录"整个挂掉——它是叠加的注解，不是必需品。
            log.warn("读取语义层失败（catalog），本次不注入 connectorId={}", connectorId, e);
            return CatalogSemantics.builder().objects(Map.of()).glossary(List.of()).build();
        }
    }

    /** conn_describe 需要的：某张表的字段含义（按列名索引）+ 从这张表出发的关联。 */
    public ObjectSemantics forObject(Long connectorId, String objectName) {
        try {
            List<ConnectorSemantic> rows = semanticMapper.selectList(
                    new LambdaQueryWrapper<ConnectorSemantic>()
                            .eq(ConnectorSemantic::getConnectorId, connectorId)
                            .eq(ConnectorSemantic::getObjectName, objectName)
                            .in(ConnectorSemantic::getScope, List.of(SCOPE_FIELD, SCOPE_JOIN)));
            Map<String, ConnectorSemantic> fields = new LinkedHashMap<>();
            List<ConnectorSemantic> joins = new ArrayList<>();
            for (ConnectorSemantic r : rows) {
                if (SCOPE_FIELD.equals(r.getScope())) {
                    fields.put(r.getFieldName(), r);
                } else {
                    joins.add(r);
                }
            }
            return ObjectSemantics.builder().fields(fields).joins(joins).build();
        } catch (Exception e) {
            log.warn("读取语义层失败（describe），本次不注入 connectorId={} object={}", connectorId, objectName, e);
            return ObjectSemantics.builder().fields(Map.of()).joins(List.of()).build();
        }
    }

    /** 管理台用：某条连接的全部语义行。 */
    public List<ConnectorSemantic> all(Long connectorId) {
        requireOwned(connectorId);
        return semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .orderByAsc(ConnectorSemantic::getScope)
                .orderByAsc(ConnectorSemantic::getObjectName)
                .orderByAsc(ConnectorSemantic::getFieldName));
    }

    // ================================================================ 写（推导路径）

    /**
     * 用一批新推断的行替换旧的推断结果。<b>只动 INFERRED，HUMAN / IMPORTED 一行不碰。</b>
     *
     * <p>先按 connectorId 查出连接确认归属再删，是必须的：{@code physicalDeleteInferred} 那条
     * 裸 DELETE 在 {@code runAsSystem} 下<b>不带任何 tenant_id 条件</b>，传错 id 就抹掉别人的语义层。
     */
    @Transactional
    public int replaceInferred(Long connectorId, List<ConnectorSemantic> fresh) {
        Connection conn = requireOwned(connectorId);
        semanticMapper.physicalDeleteInferred(connectorId);
        int n = 0;
        for (ConnectorSemantic r : fresh) {
            r.setId(null);
            r.setTenantId(conn.getTenantId());
            r.setConnectorId(connectorId);
            r.setSource(SOURCE_INFERRED);
            if (r.getStatus() == null) r.setStatus(ST_DRAFT);
            if (r.getVerified() == null) r.setVerified(V_NONE);
            semanticMapper.insert(r);
            n++;
        }
        return n;
    }

    // ================================================================ 写（口径沉淀路径）

    /**
     * 沉淀一条业务口径。<b>这是 §5 的全部价值所在：模型现在本来就在问，缺的是问完之后记住。</b>
     *
     * <p>为什么必须显式记 {@code answeredBy} 而不靠 {@code BaseEntity.createUser}：口径是在对话流里
     * 沉淀的，对话跑在 {@code streamExecutor} 上，{@code MdcAsyncSupport.wrap} 不传
     * {@code RequestContextHolder}，于是 {@code MyMetaObjectHandler} 拿不到用户、create_user 是 null。
     *
     * <p>覆盖既有口径时把<b>旧值</b>追加进 history_json。平台不提供管理台的口径纠正入口，
     * 任何能对话的人都能覆盖口径且不做权限区分——这是那个已知代价的唯一取证材料。
     */
    @Transactional
    public ConnectorSemantic defineMetric(Long connectorId, String term, String gloss,
                                          Map<String, Object> detail,
                                          String answeredBy, String answeredName, String traceId) {
        if (term == null || term.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "口径词条不能为空");
        }
        if (gloss == null || gloss.isBlank()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "口径说明不能为空");
        }
        Connection conn = requireOwned(connectorId);
        String t = term.trim();

        ConnectorSemantic existing = semanticMapper.selectOne(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .eq(ConnectorSemantic::getScope, SCOPE_METRIC)
                .eq(ConnectorSemantic::getObjectName, "")
                .eq(ConnectorSemantic::getFieldName, "")
                .eq(ConnectorSemantic::getTerm, t)
                .last("limit 1"));

        Date now = new Date();
        if (existing == null) {
            ConnectorSemantic row = new ConnectorSemantic();
            row.setTenantId(conn.getTenantId());
            row.setConnectorId(connectorId);
            row.setScope(SCOPE_METRIC);
            row.setObjectName("");
            row.setFieldName("");
            row.setTerm(t);
            row.setGloss(gloss.trim());
            row.setDetailJson(toJson(detail));
            row.setSource(SOURCE_HUMAN);
            // 人答的口径依据是"人说的"，不是数据也不是注释。它恰恰是数据库里查不到答案的那一类。
            row.setEvidence(EV_GUESS);
            row.setStatus(ST_CONFIRMED);
            row.setVerified(V_NONE);
            row.setAnchorKind(ANCHOR_NONE);
            row.setAnsweredBy(answeredBy);
            row.setAnsweredName(answeredName);
            row.setAnsweredAt(now);
            row.setTraceId(traceId);
            semanticMapper.insert(row);
            return row;
        }

        // 覆盖：先把旧值留痕，再改。
        existing.setHistoryJson(appendHistory(existing, answeredBy, answeredName, traceId, now));
        existing.setGloss(gloss.trim());
        if (detail != null) existing.setDetailJson(toJson(detail));
        existing.setSource(SOURCE_HUMAN);
        existing.setStatus(ST_CONFIRMED);
        existing.setAnsweredBy(answeredBy);
        existing.setAnsweredName(answeredName);
        existing.setAnsweredAt(now);
        existing.setTraceId(traceId);
        semanticMapper.updateById(existing);
        return existing;
    }

    @SuppressWarnings("unchecked")
    private String appendHistory(ConnectorSemantic row, String by, String byName, String traceId, Date at) {
        List<Object> hist = new ArrayList<>();
        if (row.getHistoryJson() != null && !row.getHistoryJson().isBlank()) {
            try {
                Object parsed = CommonUtil.getObjectMapper().readValue(row.getHistoryJson(), List.class);
                hist.addAll((List<Object>) parsed);
            } catch (Exception e) {
                // 历史坏了不该挡住这次覆盖——留痕是取证材料，不是前置条件。
                log.warn("解析口径变更留痕失败，本次从空开始 id={}", row.getId(), e);
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("at", at);
        entry.put("by", by);
        entry.put("by_name", byName);
        entry.put("from_gloss", row.getGloss());
        entry.put("from_detail", row.getDetailJson());
        entry.put("trace_id", traceId);
        hist.add(entry);
        while (hist.size() > HISTORY_MAX) {
            hist.remove(0);
        }
        return toJson(hist);
    }

    // ================================================================ 漂移

    /**
     * 结构变了之后重挂锚点。<b>粒度按 scope 分，不能统一按表。</b>
     *
     * <p>{@code connector_schema.content_hash} 是整表指纹（表名|类型 + 每列
     * name:type:nullable:comment），客户加一列它就变。若 OBJECT 行也锚它，「这张表是订单主表」
     * 会因为一个无关列被标成 STALE——而加一列并不改变这句话。所以：
     * OBJECT 不锚结构（只有整张表消失才失效）、FIELD 锚自己那一列、JOIN 锚两端列。
     *
     * @param fieldsByObject 刷新后每张表的字段（表名 → 列名 → FieldDetail）
     * @param removedObjects 本次刷新中消失的表名
     */
    @Transactional
    public StaleResult applyDrift(Long connectorId,
                                  Map<String, Map<String, FieldDetail>> fieldsByObject,
                                  List<String> removedObjects) {
        requireOwned(connectorId);
        List<ConnectorSemantic> rows = semanticMapper.selectList(
                new LambdaQueryWrapper<ConnectorSemantic>().eq(ConnectorSemantic::getConnectorId, connectorId));

        int staled = 0;
        int revived = 0;
        for (ConnectorSemantic r : rows) {
            boolean shouldStale;
            if (removedObjects.contains(r.getObjectName()) && !r.getObjectName().isEmpty()) {
                // 表没了：挂在它上面的一切都失效，与锚点粒度无关。
                shouldStale = true;
            } else if (ANCHOR_NONE.equals(r.getAnchorKind()) || r.getAnchorKind() == null) {
                // OBJECT / CAVEAT / 纯人工口径：不锚结构，加列改列都不动它。
                shouldStale = false;
            } else {
                String now = recomputeAnchor(r, fieldsByObject);
                // 算不出来（列没了）也算失效；算出来但对不上同样失效。
                shouldStale = now == null || !now.equals(r.getAnchorHash());
            }

            if (shouldStale && !ST_STALE.equals(r.getStatus())) {
                r.setStatus(ST_STALE);
                semanticMapper.updateById(r);
                staled++;
            } else if (!shouldStale && ST_STALE.equals(r.getStatus())) {
                // 结构改回来了：STALE 应该能撤销，否则一次误报会永久污染这条语义。
                r.setStatus(SOURCE_HUMAN.equals(r.getSource()) ? ST_CONFIRMED : ST_DRAFT);
                semanticMapper.updateById(r);
                revived++;
            }
        }
        return new StaleResult(staled, revived);
    }

    private String recomputeAnchor(ConnectorSemantic r, Map<String, Map<String, FieldDetail>> fieldsByObject) {
        Map<String, FieldDetail> cols = fieldsByObject.get(r.getObjectName());
        if (cols == null) return null;
        FieldDetail left = cols.get(r.getFieldName());
        if (left == null) return null;

        if (ANCHOR_FIELD.equals(r.getAnchorKind())) {
            return fieldAnchor(left);
        }
        if (ANCHOR_JOIN.equals(r.getAnchorKind())) {
            Map<String, Object> d = readDetail(r);
            Object toObj = d.get("to_object");
            Object toCol = d.get("to_column");
            if (toObj == null || toCol == null) return null;
            Map<String, FieldDetail> rightCols = fieldsByObject.get(String.valueOf(toObj));
            if (rightCols == null) return null;
            FieldDetail right = rightCols.get(String.valueOf(toCol));
            if (right == null) return null;
            return joinAnchor(left, right);
        }
        // COLUMN_SET 留给 P2 的口径 SQL 片段；现在没有产生它的路径。
        return r.getAnchorHash();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDetail(ConnectorSemantic r) {
        if (r.getDetailJson() == null || r.getDetailJson().isBlank()) return Map.of();
        try {
            return CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
        } catch (Exception e) {
            log.debug("解析语义 detail 失败 id={}", r.getId(), e);
            return Map.of();
        }
    }

    // ================================================================ 锚点指纹

    /**
     * 一列自己的指纹。与 {@code ConnectorSchemaService.structureFingerprint} 里每列那一段
     * <b>刻意同形</b>（name:type:nullable:comment），这样两边对"什么算结构变了"的判断一致。
     */
    public static String fieldAnchor(FieldDetail f) {
        return sha256(f.name() + ":" + f.type() + ":" + f.nullable() + ":" + nullToEmpty(f.comment()));
    }

    /** 关联的指纹：两端列任一变化都让这条关系不可信。 */
    public static String joinAnchor(FieldDetail left, FieldDetail right) {
        return sha256(fieldAnchor(left) + ">" + fieldAnchor(right));
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ================================================================ 杂项

    private Connection requireOwned(Long connectorId) {
        Connection c = connectionMapper.selectById(connectorId);
        if (c == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        }
        return c;
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            log.warn("序列化语义 detail 失败", e);
            return null;
        }
    }

    /** 注入 catalog 时用的短版本。 */
    public static String shortGloss(String gloss) {
        if (gloss == null) return null;
        String g = gloss.trim();
        return g.length() <= CATALOG_GLOSS_MAX ? g : g.substring(0, CATALOG_GLOSS_MAX) + "…";
    }

    // ================================================================ 返回形状

    @Data
    @Builder
    public static class CatalogSemantics {
        /** 表名 → OBJECT 行 */
        private Map<String, ConnectorSemantic> objects;
        /** METRIC 行。口径必须在"选表之前"被看见——扣不扣退款决定了要不要 join 退款表。 */
        private List<ConnectorSemantic> glossary;
    }

    @Data
    @Builder
    public static class ObjectSemantics {
        /** 列名 → FIELD 行 */
        private Map<String, ConnectorSemantic> fields;
        /** 从这张表出发的 JOIN 行 */
        private List<ConnectorSemantic> joins;
    }

    public record StaleResult(int staled, int revived) {}
}
