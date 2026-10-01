package com.jimeng.dataserver.ai.connector.graph;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.businessview.BusinessTextRules;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer.NamedKey;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.ColumnRef;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Field;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.Relation;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SelfReference;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.SystemGraph;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableCard;
import com.jimeng.dataserver.ai.connector.graph.DataGraphViews.TableDetail;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.RowEstimateNote;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.RelationCandidates;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 数据星图的全部规则（设计文档 §7）：把一条连接的结构快照、语义层 JOIN 行和业务视图投影成给人看的对象关系图。
 *
 * <p>纯函数：不访问数据库、不依赖 Spring，同样的输入永远得到同样的输出（顺序也一样）。
 * 它不产生任何新结论，只做三件事——按固定顺序筛关系、给对象和关系配上业务文字（缺了按 §6.3 兜底）、
 * 把结构元数据留给折叠的技术信息区。
 */
public final class DataGraphProjector {

    public static final String TIER_CONFIRMED = "CONFIRMED";
    public static final String TIER_INFERRED = "INFERRED";
    public static final String BY_DATA = "DATA";
    public static final String BY_BUSINESS = "BUSINESS";
    public static final String MANY_TO_ONE = "MANY_TO_ONE";
    public static final String ONE_TO_ONE = "ONE_TO_ONE";
    public static final String KEY_PRIMARY = "PRIMARY";
    public static final String KEY_UNIQUE = "UNIQUE";
    /** 对象标题来自哪一档（§6.3）：业务视图 → 像名称的表注释 → 表名。 */
    public static final String NAME_BUSINESS_VIEW = "BUSINESS_VIEW";
    public static final String NAME_COMMENT = "COMMENT";
    public static final String NAME_PHYSICAL = "PHYSICAL";

    /** 表注释「像名称」的长度上限（字符数），超过就只当说明、不当标题。 */
    static final int NAME_MAX_CHARS = 16;
    private static final String SENTENCE_MARKS = "，。；,;.\n\r";

    // detail_json 里的结构键。写入方是 SemanticJoinValidator.JoinVerdict#structuralPatch；
    // 各读取方（推导、工具执行器）都各自声明常量，这里同样。
    private static final String KEY_JOIN_KIND = "join_kind";
    private static final String KEY_DISCRIMINATOR_COLUMN = "discriminator_column";
    /** 关系是谁提出来的（{@code RelationCandidates.ORIGIN_*}），写入方是关系发现。 */
    private static final String KEY_ORIGIN = "origin";

    private static final Comparator<Relation> RELATION_ORDER = Comparator
            .comparing(Relation::getFromTable)
            .thenComparing(Relation::getFromColumn)
            .thenComparing(Relation::getToTable)
            .thenComparing(Relation::getToColumn);

    private DataGraphProjector() {
    }

    /** {@code TABLE} / {@code BASE TABLE} → {@code TABLE}，{@code VIEW} → {@code VIEW}，其余（含 null）不进星图。 */
    public static String objectType(String raw) {
        if (raw == null) {
            return null;
        }
        String type = raw.trim().toUpperCase(Locale.ROOT);
        if ("TABLE".equals(type) || "BASE TABLE".equals(type)) {
            return "TABLE";
        }
        return "VIEW".equals(type) ? "VIEW" : null;
    }

    /** 注释「像一个名称」：不超过 {@value #NAME_MAX_CHARS} 个字符，且不含句子标点和换行。 */
    public static boolean looksLikeName(String comment) {
        if (comment == null || comment.isEmpty() || comment.length() > NAME_MAX_CHARS) {
            return false;
        }
        for (int i = 0; i < comment.length(); i++) {
            if (SENTENCE_MARKS.indexOf(comment.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** 结构快照是否达到了对象数上限（上限只有一个来源：{@link ConnectorSchemaService#MAX_OBJECTS}）。 */
    public static boolean truncated(int snapshotObjects) {
        return snapshotObjects >= ConnectorSchemaService.MAX_OBJECTS;
    }

    /**
     * @param schemas    这条连接的全部快照行（含 TABLE / VIEW 之外的对象：它们也占快照的名额）
     * @param views      这条连接的业务视图行
     * @param viewStatus 业务文字的整理状态，原样带出（{@link DataGraphViews.SystemSummary}）
     */
    public static SystemGraph system(Connection connection, List<ConnectorSchema> schemas,
                                     List<ConnectorSemantic> joins, List<ConnectorBusinessView> views,
                                     String viewStatus) {
        Projection p = project(schemas, joins, views);
        List<TableCard> cards = new ArrayList<>(p.tables().size());
        for (Table t : p.tables().values()) {
            cards.add(card(t, p));
        }
        return new SystemGraph(String.valueOf(connection.getId()), connection.getName(),
                connection.getDisplayName(), connection.getSemanticStatus(),
                truncated(schemas == null ? 0 : schemas.size()), viewStatus, List.copyOf(cards), p.relations());
    }

    /** @return 表不在快照里（或不是 TABLE / VIEW）时为 {@code null} */
    public static TableDetail table(List<ConnectorSchema> schemas, List<ConnectorSemantic> joins,
                                    List<ConnectorBusinessView> views, String name) {
        Projection p = project(schemas, joins, views);
        Table t = name == null ? null : p.tables().get(name);
        if (t == null) {
            return null;
        }
        List<Relation> incident = p.relations().stream()
                .filter(r -> r.getFromTable().equals(name) || r.getToTable().equals(name))
                .toList();
        List<SelfReference> selfRefs = p.selfReferences().getOrDefault(name, List.of());
        Set<String> involved = new LinkedHashSet<>();
        for (Relation r : incident) {
            if (r.getFromTable().equals(name)) {
                involved.add(r.getFromColumn());
            }
            if (r.getToTable().equals(name)) {
                involved.add(r.getToColumn());
            }
        }
        for (SelfReference s : selfRefs) {
            involved.add(s.getFromColumn());
            involved.add(s.getToColumn());
        }
        Set<String> primary = primaryColumns(t);
        List<Field> fields = new ArrayList<>(t.fields().size());
        for (FieldDetail f : t.fields().values()) {
            String key = primary.contains(f.name()) ? KEY_PRIMARY
                    : singleColumnUnique(t, f.name()) ? KEY_UNIQUE : null;
            fields.add(new Field(f.name(), f.type(), f.nullable(), text(f.comment()), key,
                    involved.contains(f.name())));
        }
        return new TableDetail(t.name(), t.displayName(), t.nameSource(), t.summary(), t.domain(), t.comment(),
                t.objectType(), selfRefs, List.copyOf(fields), incident);
    }

    // ------------------------------------------------------------------ 投影

    private static Projection project(List<ConnectorSchema> schemas, List<ConnectorSemantic> joins,
                                      List<ConnectorBusinessView> views) {
        Map<String, ConnectorBusinessView> byKey = new HashMap<>();
        for (ConnectorBusinessView v : views == null ? List.<ConnectorBusinessView>of() : views) {
            if (v != null && v.getKind() != null) {
                byKey.putIfAbsent(viewKey(v.getKind(), v.getObjectName(), v.getFieldName()), v);
            }
        }
        Words words = new Words(byKey, identifiers(schemas));
        Map<String, Table> tables = tables(schemas, words);
        Map<String, List<SelfReference>> selfRefs = new LinkedHashMap<>();
        List<Relation> relations = new ArrayList<>();
        for (ConnectorSemantic row : joins == null ? List.<ConnectorSemantic>of() : joins) {
            Judged j = judge(row, tables);
            if (j == null) {
                continue;
            }
            Table from = tables.get(j.fromTable());
            if (j.fromTable().equals(j.toTable())) {
                selfRefs.computeIfAbsent(j.fromTable(), k -> new ArrayList<>())
                        .add(new SelfReference(j.fromColumn(), j.toColumn(), j.tier(), j.confirmedBy(),
                                words.role(from, j.fromColumn())));
            } else {
                relations.add(relation(j, tables, words));
            }
        }
        relations.sort(RELATION_ORDER);
        Map<String, List<SelfReference>> sortedSelf = new LinkedHashMap<>();
        selfRefs.forEach((table, refs) -> {
            refs.sort(Comparator.comparing(SelfReference::getFromColumn));
            sortedSelf.put(table, List.copyOf(refs));
        });
        return new Projection(tables, List.copyOf(relations), sortedSelf);
    }

    /** 只收 TABLE / VIEW；按 {@code importance_rank} 升序（空值排最后）再按表名，保证输出顺序确定。 */
    private static Map<String, Table> tables(List<ConnectorSchema> schemas, Words words) {
        List<ConnectorSchema> rows = (schemas == null ? List.<ConnectorSchema>of() : schemas).stream()
                .filter(r -> r != null && r.getObjectName() != null && objectType(r.getObjectType()) != null)
                .sorted(Comparator.comparing(ConnectorSchema::getImportanceRank,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(ConnectorSchema::getObjectName))
                .toList();
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(rows);
        Map<String, Table> out = new LinkedHashMap<>();
        for (ConnectorSchema r : rows) {
            String comment = RowEstimateNote.strip(r.getObjectComment());
            ConnectorBusinessView view = words.object(r.getObjectName());
            String displayName;
            String nameSource;
            if (view != null && text(view.getDisplayName()) != null) {
                displayName = text(view.getDisplayName());
                nameSource = NAME_BUSINESS_VIEW;
            } else if (looksLikeName(comment) && BusinessTextRules.fitsFallback(comment, words.identifiers())) {
                displayName = comment;
                nameSource = NAME_COMMENT;
            } else {
                displayName = null;
                nameSource = NAME_PHYSICAL;
            }
            out.put(r.getObjectName(), new Table(r.getObjectName(), objectType(r.getObjectType()), comment,
                    displayName, nameSource, view == null ? null : text(view.getSummary()),
                    view == null ? null : text(view.getDomain()),
                    fields.getOrDefault(r.getObjectName(), Map.of()),
                    SemanticTableRenderer.namedUniqueKeys(r)));
        }
        return out;
    }

    /**
     * §7.1 的判定表，按顺序、先命中者为准。{@code confidence}、{@code source}、{@code evidence} 不参与。
     *
     * <p>★ 业务方的任何一次确认（{@code annotateJoin} → {@code upsertHuman}）都会把 {@code status} 写成 CONFIRMED、
     * {@code verified} 写成 NONE，否认只记在 {@code human_verdict=UNRELATED}——所以绝不能用 {@code status} 判「已确认」。
     *
     * @return {@code null} = 不画
     */
    private static Judged judge(ConnectorSemantic row, Map<String, Table> tables) {
        if (row == null) {
            return null;
        }
        Map<String, Object> detail = detail(row.getDetailJson());
        String fromTable = row.getObjectName();
        String fromColumn = row.getFieldName();
        String toTable = text(detail.get(ConnectorSemanticService.KEY_TO_OBJECT));
        String toColumn = text(detail.get(ConnectorSemanticService.KEY_TO_COLUMN));
        Table from = fromTable == null ? null : tables.get(fromTable);
        Table to = toTable == null ? null : tables.get(toTable);
        // 1. 两端的表和列都得在当前快照里
        if (from == null || to == null || fromColumn == null || toColumn == null
                || !from.fields().containsKey(fromColumn) || !to.fields().containsKey(toColumn)) {
            return null;
        }
        // 1′. 同一张表的同一列：抽样时这种行的包含率永远是 100%，画出来是一个指向自己的圈
        if (fromTable.equals(toTable) && fromColumn.equals(toColumn)) {
            return null;
        }
        // 2. 结构已变、待重判
        if (ConnectorSemanticService.ST_STALE.equals(row.getStatus())) {
            return null;
        }
        Object verdict = detail.get(ConnectorSemanticService.KEY_HUMAN_VERDICT);
        // 3. 业务方说没关系
        if (ConnectorSemanticService.HV_UNRELATED.equals(verdict)) {
            return null;
        }
        boolean polymorphic = SemanticJoinValidator.KIND_POLYMORPHIC.equals(text(detail.get(KEY_JOIN_KIND)));
        String discriminator = polymorphic ? text(detail.get(KEY_DISCRIMINATOR_COLUMN)) : null;
        // 4. 业务方确认
        if (ConnectorSemanticService.HV_RELATED.equals(verdict)) {
            return new Judged(fromTable, fromColumn, toTable, toColumn, TIER_CONFIRMED, BY_BUSINESS, discriminator);
        }
        String verified = row.getVerified();
        // 5. 采样核对通过（包含率 ≥ 0.9）
        if (ConnectorSemanticService.V_CONFIRMED.equals(verified)) {
            return new Judged(fromTable, fromColumn, toTable, toColumn, TIER_CONFIRMED, BY_DATA, discriminator);
        }
        // 6. 数据否定（< 0.5）或只部分成立（0.5–0.9）
        if (ConnectorSemanticService.V_REJECTED.equals(verified) || ConnectorSemanticService.V_WEAK.equals(verified)) {
            return null;
        }
        // 7. 多态关系只在判别列取特定值时成立，未确认前画成无条件的线会误导
        if (polymorphic) {
            return null;
        }
        // 7′. 关系发现里模型那一遍推出的：只给 Agent 当线索。真实库试跑里它把「币种 → 公司代码」这类错线也报了高把握，
        //     终点又常是主键——不挡的话第 8 行会把它们画成虚线。核对通过（第 5 行）或业务方确认（第 4 行）之后照常画。
        if (RelationCandidates.ORIGIN_RELATION_PASS.equals(text(detail.get(KEY_ORIGIN)))) {
            return null;
        }
        // 8. 结构上成立：终点列单独构成终点表的一个唯一键
        if (singleColumnUnique(to, toColumn)) {
            return new Judged(fromTable, fromColumn, toTable, toColumn, TIER_INFERRED, null, null);
        }
        // 9. 其余不画
        return null;
    }

    private static Relation relation(Judged j, Map<String, Table> tables, Words words) {
        Table from = tables.get(j.fromTable());
        Table to = tables.get(j.toTable());
        return new Relation(relationId(j.fromTable(), j.fromColumn(), j.toTable(), j.toColumn()),
                j.fromTable(), j.fromColumn(), j.toTable(), j.toColumn(),
                cardinality(from, j.fromColumn(), to, j.toColumn()), j.tier(), j.confirmedBy(),
                words.role(from, j.fromColumn()), j.discriminatorColumn());
    }

    /** 终点列单独唯一：起点列也单独唯一为一对一，否则多对一；终点列不唯一（只会出现在已确认关系上）为 {@code null}。 */
    private static String cardinality(Table from, String fromColumn, Table to, String toColumn) {
        if (!singleColumnUnique(to, toColumn)) {
            return null;
        }
        return singleColumnUnique(from, fromColumn) ? ONE_TO_ONE : MANY_TO_ONE;
    }

    /** 唯一键未知（旧快照没有 {@code extra.unique_keys}）按「不是」处理；只是组合键的一部分也不算。 */
    private static boolean singleColumnUnique(Table t, String column) {
        if (t.keys() == null) {
            return false;
        }
        for (NamedKey k : t.keys()) {
            if (k.columns().size() == 1 && k.columns().get(0).equals(column)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> primaryColumns(Table t) {
        Set<String> out = new LinkedHashSet<>();
        if (t.keys() != null) {
            for (NamedKey k : t.keys()) {
                if (k.primary()) {
                    out.addAll(k.columns());
                }
            }
        }
        return out;
    }

    /** 主键列；没有主键时取列数最少的唯一键（并列取键名最小）；都没有为空。 */
    private static List<String> keyColumns(Table t) {
        if (t.keys() == null || t.keys().isEmpty()) {
            return List.of();
        }
        for (NamedKey k : t.keys()) {
            if (k.primary()) {
                return k.columns();
            }
        }
        return t.keys().stream()
                .min(Comparator.comparingInt((NamedKey k) -> k.columns().size()).thenComparing(NamedKey::name))
                .map(NamedKey::columns)
                .orElse(List.of());
    }

    private static TableCard card(Table t, Projection p) {
        TreeSet<String> relationColumns = new TreeSet<>();
        boolean related = false;
        for (Relation r : p.relations()) {
            if (r.getFromTable().equals(t.name())) {
                relationColumns.add(r.getFromColumn());
                related = true;
            }
            if (r.getToTable().equals(t.name())) {
                relationColumns.add(r.getToColumn());
                related = true;
            }
        }
        return new TableCard(t.name(), t.displayName(), t.nameSource(), t.summary(), t.domain(), t.comment(),
                t.objectType(), related,
                p.selfReferences().getOrDefault(t.name(), List.of()),
                keyColumns(t).stream().map(c -> columnRef(t, c)).toList(),
                relationColumns.stream().map(c -> columnRef(t, c)).toList(),
                t.fields().size());
    }

    private static ColumnRef columnRef(Table t, String column) {
        FieldDetail f = t.fields().get(column);
        return new ColumnRef(column, f == null ? null : text(f.comment()));
    }

    /** 四段做长度前缀编码后取 SHA-256 的前 16 位十六进制：表名、列名里的任何字符都不会让两条关系撞成同一个 id。 */
    private static String relationId(String fromTable, String fromColumn, String toTable, String toColumn) {
        StringBuilder source = new StringBuilder();
        for (String part : List.of(fromTable, fromColumn, toTable, toColumn)) {
            source.append(part.length()).append(':').append(part).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 这个系统的物理标识符：兜底用的客户注释里夹着它们就不拿来当业务文字（§6.3）。 */
    private static Set<String> identifiers(List<ConnectorSchema> schemas) {
        List<ConnectorSchema> rows = schemas == null ? List.of() : schemas.stream()
                .filter(r -> r != null && r.getObjectName() != null).toList();
        List<String> columns = new ArrayList<>();
        SemanticRowAssembler.parseFields(rows).values().forEach(cols -> columns.addAll(cols.keySet()));
        return BusinessTextRules.identifiers(rows.stream().map(ConnectorSchema::getObjectName).toList(), columns);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(json, Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            // 单行 detail_json 坏了只让这一行「没有对端」、被丢弃，不影响整张图。
            return Map.of();
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static String viewKey(String kind, String object, String field) {
        return kind + '\u0001' + SemanticRowAssembler.fold(object) + '\u0001'
                + SemanticRowAssembler.fold(field == null ? "" : field);
    }

    /** 业务视图的查法与兜底。 */
    private record Words(Map<String, ConnectorBusinessView> views, Set<String> identifiers) {

        ConnectorBusinessView object(String table) {
            return views.get(viewKey(ConnectorBusinessView.KIND_OBJECT, table, ""));
        }

        /**
         * 关系角色名：业务视图的优先；缺了退回起点列的客户注释（不能等于列名，还得过 {@link BusinessTextRules#fitsFallback}：
         * 不夹代码、不带禁用词），再缺为 null。
         */
        String role(Table from, String column) {
            ConnectorBusinessView v = views.get(viewKey(ConnectorBusinessView.KIND_RELATION, from.name(), column));
            if (v != null && text(v.getDisplayName()) != null) {
                return text(v.getDisplayName());
            }
            FieldDetail f = from.fields().get(column);
            String comment = f == null ? null : text(f.comment());
            if (comment == null || comment.equalsIgnoreCase(column) || !BusinessTextRules.fitsFallback(comment, identifiers)) {
                return null;
            }
            return comment;
        }
    }

    private record Table(String name, String objectType, String comment, String displayName, String nameSource,
                         String summary, String domain, Map<String, FieldDetail> fields, List<NamedKey> keys) {
    }

    private record Judged(String fromTable, String fromColumn, String toTable, String toColumn,
                          String tier, String confirmedBy, String discriminatorColumn) {
    }

    private record Projection(Map<String, Table> tables, List<Relation> relations,
                              Map<String, List<SelfReference>> selfReferences) {
    }
}
