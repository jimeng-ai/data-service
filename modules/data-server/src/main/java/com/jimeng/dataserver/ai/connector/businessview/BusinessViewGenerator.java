package com.jimeng.dataserver.ai.connector.businessview;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.RowEstimateNote;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticModelCall;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorBusinessView;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectorBusinessViewMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

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
import java.util.Map;
import java.util.Set;

import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.fold;

/**
 * 业务视图的生成（数据星图设计 v3 §6.2）：业务领域 → 对象名称与说明 → 关系角色名。
 *
 * <h3>纪律</h3>
 * <ul>
 *   <li><b>只补缺的和输入变了的</b>（{@code input_hash}）。重跑一遍的代价是几次空查询，不是几十次模型调用。</li>
 *   <li><b>{@code HUMAN} 行一个字不碰</b>，也不替它补领域：那是客户自己写的。</li>
 *   <li>模型产出过 {@link BusinessTextRules} 这一关；不合格的带着原因重试一次，仍不合格就不写，页面按兜底显示。</li>
 *   <li>对象或关系从快照、语义层里消失了，对应的 {@code MODEL} 行物理删除（唯一键不含 deleted，理由见实体注释）。</li>
 * </ul>
 * 每一批写完就落库：跑到一半失败，已经写下的不白花。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessViewGenerator {

    /** 提示词版本，记进每一行的 {@code prompt_version}。 */
    static final String PROMPT_VERSION = "business-view-2026-10-01";
    static final int NAME_BATCH = 50;
    static final int ROLE_BATCH = 150;
    static final int MAX_TOKENS = 8000;

    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectorBusinessViewMapper viewMapper;
    private final ConnectorSemanticService semanticService;
    private final SemanticModelCall modelCall;

    /** 每次调模型之前回调一次：补全链用它续期认领，认领丢了就抛出去中止这一轮。 */
    @FunctionalInterface
    public interface Progress {
        void beforeModelCall();
    }

    /**
     * 为一条连接补齐业务文字。必须在真实租户上下文里调。
     *
     * @throws RuntimeException 模型调用失败等。已经写下的批次保留
     */
    public Result generate(Long connectorId, Progress progress) {
        Connection conn = semanticService.requireOwned(connectorId);
        Snapshot s = load(connectorId);
        Counter c = new Counter();

        cleanup(conn, s, c);
        assignDomains(conn, s, progress, c);
        nameObjects(conn, s, progress, c);
        nameRelations(conn, s, progress, c);

        Result result = new Result(c.domains, c.names, c.roles, c.rejected, c.deleted);
        log.info("业务文字生成完成 connectorId={} {}", connectorId, result.note());
        return result;
    }

    // ------------------------------------------------------------------ 读

    private Snapshot load(Long connectorId) {
        List<ConnectorSchema> schemas = schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getConnectorId, connectorId));
        List<ConnectorSchema> rows = schemas.stream()
                .filter(r -> r != null && r.getObjectName() != null)
                .sorted(Comparator.comparing(ConnectorSchema::getObjectName))
                .toList();
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(rows);
        List<Obj> objects = new ArrayList<>();
        for (ConnectorSchema r : rows) {
            if (!fields.getOrDefault(r.getObjectName(), Map.of()).isEmpty()) {
                objects.add(new Obj(r.getObjectName(), RowEstimateNote.strip(r.getObjectComment())));
            }
        }
        List<ConnectorSemantic> semantic = semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .in(ConnectorSemantic::getScope, ConnectorSemanticService.SCOPE_OBJECT,
                        ConnectorSemanticService.SCOPE_FIELD, ConnectorSemanticService.SCOPE_JOIN));
        Map<String, String> objectGloss = new HashMap<>();
        Map<String, String> fieldGloss = new HashMap<>();
        List<Rel> relations = new ArrayList<>();
        Set<String> tableNames = new LinkedHashSet<>();
        objects.forEach(o -> tableNames.add(fold(o.name())));
        for (ConnectorSemantic row : semantic) {
            switch (row.getScope()) {
                case ConnectorSemanticService.SCOPE_OBJECT ->
                        objectGloss.putIfAbsent(fold(row.getObjectName()), ConnectorSemanticService.shortGloss(row.getGloss()));
                case ConnectorSemanticService.SCOPE_FIELD ->
                        fieldGloss.putIfAbsent(key(row.getObjectName(), row.getFieldName()),
                                ConnectorSemanticService.shortGloss(row.getGloss()));
                default -> {
                    Rel rel = relation(row, fields);
                    if (rel != null) {
                        relations.add(rel);
                    }
                }
            }
        }
        relations.sort(Comparator.comparing(Rel::from).thenComparing(Rel::column));
        List<String> columns = new ArrayList<>();
        fields.values().forEach(cols -> columns.addAll(cols.keySet()));
        Set<String> identifiers = BusinessTextRules.identifiers(objects.stream().map(Obj::name).toList(), columns);

        Map<String, ConnectorBusinessView> views = new LinkedHashMap<>();
        for (ConnectorBusinessView v : viewMapper.selectList(new LambdaQueryWrapper<ConnectorBusinessView>()
                .eq(ConnectorBusinessView::getConnectorId, connectorId))) {
            views.putIfAbsent(viewKey(v.getKind(), v.getObjectName(), v.getFieldName()), v);
        }
        return new Snapshot(objects, relations, fields, objectGloss, fieldGloss, identifiers, views);
    }

    /**
     * 要起角色名的关系：两端都在快照里、没过期、业务方没说无关、采样没否掉。
     * 比星图实际画出来的略宽（终点不是唯一键、还没核对的也在）：它们一旦核对通过就会画出来，名字先备好。
     */
    private static Rel relation(ConnectorSemantic row, Map<String, Map<String, FieldDetail>> fields) {
        Map<String, Object> d = detail(row.getDetailJson());
        String to = SemanticRowAssembler.str(d, ConnectorSemanticService.KEY_TO_OBJECT);
        String toColumn = SemanticRowAssembler.str(d, ConnectorSemanticService.KEY_TO_COLUMN);
        if (to == null || toColumn == null || SemanticRowAssembler.lookup(fields, row.getObjectName(), row.getFieldName()) == null
                || SemanticRowAssembler.lookup(fields, to, toColumn) == null
                || ConnectorSemanticService.ST_STALE.equals(row.getStatus())
                || ConnectorSemanticService.HV_UNRELATED.equals(d.get(ConnectorSemanticService.KEY_HUMAN_VERDICT))
                || ConnectorSemanticService.V_REJECTED.equals(row.getVerified())
                || ConnectorSemanticService.V_WEAK.equals(row.getVerified())) {
            return null;
        }
        FieldDetail f = SemanticRowAssembler.lookup(fields, row.getObjectName(), row.getFieldName());
        return new Rel(row.getObjectName(), row.getFieldName(), to, toColumn, f.comment());
    }

    // ------------------------------------------------------------------ 清理

    /** 对象或关系已经不在了的 MODEL 行物理删除；HUMAN 行留着，等客户自己决定。 */
    private void cleanup(Connection conn, Snapshot s, Counter c) {
        Set<String> liveObjects = new LinkedHashSet<>();
        s.objects().forEach(o -> liveObjects.add(fold(o.name())));
        Set<String> liveRelations = new LinkedHashSet<>();
        s.relations().forEach(r -> liveRelations.add(key(r.from(), r.column())));
        List<String> gone = new ArrayList<>();
        for (Map.Entry<String, ConnectorBusinessView> e : s.views().entrySet()) {
            ConnectorBusinessView v = e.getValue();
            if (!ConnectorBusinessView.SOURCE_MODEL.equals(v.getSource())) {
                continue;
            }
            boolean live = ConnectorBusinessView.KIND_OBJECT.equals(v.getKind())
                    ? liveObjects.contains(fold(v.getObjectName()))
                    : liveRelations.contains(key(v.getObjectName(), v.getFieldName()));
            if (!live) {
                viewMapper.physicalDeleteRow(conn.getTenantId(), conn.getId(), v.getId());
                gone.add(e.getKey());
                c.deleted++;
            }
        }
        gone.forEach(s.views()::remove);
    }

    // ------------------------------------------------------------------ ① 业务领域

    private void assignDomains(Connection conn, Snapshot s, Progress progress, Counter c) {
        List<String> domains = new ArrayList<>();
        List<Obj> targets = new ArrayList<>();
        for (Obj o : s.objects()) {
            ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_OBJECT, o.name(), ""));
            if (v != null && v.getDomain() != null && !domains.contains(v.getDomain())) {
                domains.add(v.getDomain());
            }
            if (v == null || (ConnectorBusinessView.SOURCE_MODEL.equals(v.getSource()) && v.getDomain() == null)) {
                targets.add(o);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        List<Obj> pending = targets;
        for (int attempt = 1; attempt <= 2 && !pending.isEmpty(); attempt++) {
            progress.beforeModelCall();
            Map<String, Object> reply = modelCall.askJson("业务领域", conn.getId(), DOMAIN_SYSTEM,
                    domainPrompt(s, pending, domains, reasons), MAX_TOKENS);
            for (String d : SemanticRowAssembler.strList(reply, "domains")) {
                String name = d.trim();
                if (BusinessTextRules.problem(name, BusinessTextRules.DOMAIN_MAX, s.identifiers()) == null
                        && !domains.contains(name) && domains.size() < BusinessTextRules.MAX_DOMAINS) {
                    domains.add(name);
                }
            }
            Map<String, String> assigned = new HashMap<>();
            for (Map<String, Object> m : SemanticRowAssembler.arr(reply, "assign")) {
                String table = SemanticRowAssembler.str(m, "name");
                String domain = SemanticRowAssembler.str(m, "domain");
                if (table != null && domain != null) {
                    assigned.putIfAbsent(table, domain.trim());
                }
            }
            reasons = new LinkedHashMap<>();
            List<Obj> failed = new ArrayList<>();
            for (Obj o : pending) {
                String domain = assigned.get(o.name());
                String problem = domain == null ? "没有给出领域"
                        : !domains.contains(domain) ? "领域「" + domain + "」不合格或超出了 " + BusinessTextRules.MAX_DOMAINS + " 个"
                        : null;
                if (problem != null) {
                    failed.add(o);
                    reasons.put(o.name(), problem);
                    continue;
                }
                ConnectorBusinessView v = objectRow(conn, s, o.name());
                v.setDomain(domain);
                save(v, s);
                c.domains++;
            }
            pending = failed;
        }
        c.rejected += pending.size();
    }

    static final String DOMAIN_SYSTEM = """
            你在帮一家企业的业务人员和产品看懂他们的业务系统。给你这个系统里的一批数据表（表名 | 表注释 | 一句话说明），
            请把它们归到业务领域里。只输出一个 JSON 对象，不要任何别的文字：
            {"domains":["领域一","领域二"],"assign":[{"name":"表名","domain":"领域一"}]}

            要求：
            - 领域用业务叫法，比如「采购」「销售」「财务」「库存」「主数据」，每个不超过 8 个字；整个系统最多 8 个领域。
            - 已经有的领域会列给你，优先沿用，确实放不进去才新增；domains 里只写新增的。
            - 每张表只归一个领域；表名原样照抄给你的写法。
            - 不要出现表名、字段名、英文代码，也不要写「疑似」「推测」这类措辞。
            """;

    private static String domainPrompt(Snapshot s, List<Obj> targets, List<String> domains, Map<String, String> reasons) {
        StringBuilder b = new StringBuilder();
        b.append("已有领域：").append(domains.isEmpty() ? "（还没有）" : String.join("、", domains)).append('\n');
        b.append("还能新增 ").append(Math.max(0, BusinessTextRules.MAX_DOMAINS - domains.size())).append(" 个领域。\n\n");
        b.append("【表】（表名 | 表注释 | 一句话说明）\n");
        for (Obj o : targets) {
            b.append(o.name()).append(" | ").append(nz(o.comment())).append(" | ")
                    .append(nz(s.objectGloss().get(fold(o.name())))).append('\n');
        }
        appendReasons(b, reasons);
        return b.toString();
    }

    // ------------------------------------------------------------------ ② 对象名称与说明

    private void nameObjects(Connection conn, Snapshot s, Progress progress, Counter c) {
        List<Obj> targets = new ArrayList<>();
        Map<String, String> used = new HashMap<>();
        for (Obj o : s.objects()) {
            ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_OBJECT, o.name(), ""));
            String hash = objectHash(s, o);
            boolean fresh = v != null && v.getDisplayName() != null && hash.equals(v.getInputHash());
            if (v != null && (ConnectorBusinessView.SOURCE_HUMAN.equals(v.getSource()) || fresh)) {
                if (v.getDisplayName() != null) {
                    used.put(v.getDisplayName(), o.name());
                }
                continue;
            }
            targets.add(o);
        }
        for (int from = 0; from < targets.size(); from += NAME_BATCH) {
            List<Obj> pending = targets.subList(from, Math.min(targets.size(), from + NAME_BATCH));
            Map<String, String> reasons = new LinkedHashMap<>();
            for (int attempt = 1; attempt <= 2 && !pending.isEmpty(); attempt++) {
                progress.beforeModelCall();
                Map<String, Object> reply = modelCall.askJson("业务名称", conn.getId(), NAME_SYSTEM,
                        namePrompt(s, pending, used, reasons), MAX_TOKENS);
                Map<String, Map<String, Object>> byName = new HashMap<>();
                for (Map<String, Object> m : SemanticRowAssembler.arr(reply, "objects")) {
                    String name = SemanticRowAssembler.str(m, "name");
                    if (name != null) {
                        byName.putIfAbsent(name, m);
                    }
                }
                reasons = new LinkedHashMap<>();
                List<Obj> failed = new ArrayList<>();
                for (Obj o : pending) {
                    Map<String, Object> m = byName.get(o.name());
                    String display = m == null ? null : trim(SemanticRowAssembler.str(m, "display_name"));
                    String summary = m == null ? null : trim(SemanticRowAssembler.str(m, "summary"));
                    String problem = m == null ? "没有给出"
                            : prefixed("业务名", BusinessTextRules.problem(display, BusinessTextRules.NAME_MAX, s.identifiers()));
                    if (problem == null) {
                        problem = prefixed("说明", BusinessTextRules.problem(summary, BusinessTextRules.SUMMARY_MAX, s.identifiers()));
                    }
                    if (problem == null && used.containsKey(display) && !used.get(display).equals(o.name())) {
                        problem = "业务名「" + display + "」和别的表重名了，表头和明细要能区分开";
                    }
                    if (problem != null) {
                        failed.add(o);
                        reasons.put(o.name(), problem);
                        continue;
                    }
                    ConnectorBusinessView v = objectRow(conn, s, o.name());
                    v.setDisplayName(display);
                    v.setSummary(summary);
                    v.setInputHash(objectHash(s, o));
                    save(v, s);
                    used.put(display, o.name());
                    c.names++;
                }
                pending = failed;
            }
            c.rejected += pending.size();
        }
    }

    static final String NAME_SYSTEM = """
            你在帮一家企业的业务人员和产品看懂他们的业务系统。给你一批数据表（表名 | 表注释 | 一句话说明 | 所属领域），
            请为每张表起一个业务名，并写一句话说明。只输出一个 JSON 对象，不要任何别的文字：
            {"objects":[{"name":"表名","display_name":"业务名","summary":"一句话说明"}]}

            要求：
            - 业务名是业务人员平时的叫法，比如「采购订单」「采购订单明细」「供应商」「会计科目」，不超过 12 个字。
              同一个系统里不要重名，表头和明细要能区分开；已经用过的名字会列给你。
            - 一句话说明写「一条记录代表什么、主要记了什么」，不超过 50 个字。
            - 不要出现表名、字段名、英文代码；不要写「疑似」「推测」「未经验证」这类措辞，
              也不要提数据库、字段、主键、外键这些技术词。
            - 表名原样照抄给你的写法，每张表一条。
            """;

    private static String namePrompt(Snapshot s, List<Obj> targets, Map<String, String> used,
                                     Map<String, String> reasons) {
        StringBuilder b = new StringBuilder();
        if (!used.isEmpty()) {
            b.append("已经用过的业务名（不要重名）：").append(String.join("、", used.keySet().stream().sorted().toList()))
                    .append("\n\n");
        }
        b.append("【表】（表名 | 表注释 | 一句话说明 | 所属领域）\n");
        for (Obj o : targets) {
            ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_OBJECT, o.name(), ""));
            b.append(o.name()).append(" | ").append(nz(o.comment())).append(" | ")
                    .append(nz(s.objectGloss().get(fold(o.name())))).append(" | ")
                    .append(v == null || v.getDomain() == null ? BusinessTextRules.UNCLASSIFIED : v.getDomain()).append('\n');
        }
        appendReasons(b, reasons);
        return b.toString();
    }

    // ------------------------------------------------------------------ ③ 关系角色名

    private void nameRelations(Connection conn, Snapshot s, Progress progress, Counter c) {
        List<Rel> targets = new ArrayList<>();
        for (Rel r : s.relations()) {
            ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_RELATION, r.from(), r.column()));
            if (v != null && (ConnectorBusinessView.SOURCE_HUMAN.equals(v.getSource())
                    || (v.getDisplayName() != null && relationHash(s, r).equals(v.getInputHash())))) {
                continue;
            }
            targets.add(r);
        }
        for (int from = 0; from < targets.size(); from += ROLE_BATCH) {
            List<Rel> pending = targets.subList(from, Math.min(targets.size(), from + ROLE_BATCH));
            Map<String, String> reasons = new LinkedHashMap<>();
            for (int attempt = 1; attempt <= 2 && !pending.isEmpty(); attempt++) {
                progress.beforeModelCall();
                Map<String, Object> reply = modelCall.askJson("关系角色名", conn.getId(), ROLE_SYSTEM,
                        rolePrompt(s, pending, reasons), MAX_TOKENS);
                Map<String, String> byColumn = new HashMap<>();
                for (Map<String, Object> m : SemanticRowAssembler.arr(reply, "relations")) {
                    String object = SemanticRowAssembler.str(m, "object");
                    String column = SemanticRowAssembler.str(m, "column");
                    String role = SemanticRowAssembler.str(m, "role");
                    if (object != null && column != null && role != null) {
                        byColumn.putIfAbsent(object + '\u0001' + column, role.trim());
                    }
                }
                reasons = new LinkedHashMap<>();
                List<Rel> failed = new ArrayList<>();
                for (Rel r : pending) {
                    String role = byColumn.get(r.from() + '\u0001' + r.column());
                    String problem = role == null ? "没有给出"
                            : BusinessTextRules.problem(role, BusinessTextRules.ROLE_MAX, s.identifiers());
                    if (problem != null) {
                        failed.add(r);
                        reasons.put(r.from() + "." + r.column(), problem);
                        continue;
                    }
                    ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_RELATION, r.from(), r.column()));
                    if (v == null) {
                        v = newRow(conn, ConnectorBusinessView.KIND_RELATION, r.from(), r.column());
                    }
                    v.setDisplayName(role);
                    v.setInputHash(relationHash(s, r));
                    save(v, s);
                    c.roles++;
                }
                pending = failed;
            }
            c.rejected += pending.size();
        }
    }

    static final String ROLE_SYSTEM = """
            你在帮一家企业的业务人员和产品看懂业务对象之间的关联。给你一批关联：起点表、起点列、这一列的说明、
            起点对象的业务名、终点对象的业务名。请为每条关联起一个角色名，说清「终点对象在这里扮演什么角色」。
            只输出一个 JSON 对象，不要任何别的文字：
            {"relations":[{"object":"起点表","column":"起点列","role":"角色名"}]}

            要求：
            - 角色名不超过 10 个字，用业务叫法，比如「供应商」「收款供应商」「总账科目」「所属订单」「上级科目」。
            - 角色名里不要出现表名、字段名、英文代码，也不要写「疑似」「推测」这类措辞。
            - object、column 原样照抄给你的写法，每条关联一条。
            """;

    private static String rolePrompt(Snapshot s, List<Rel> targets, Map<String, String> reasons) {
        StringBuilder b = new StringBuilder("【关联】（起点表 | 起点列 | 列的说明 | 起点对象 | 终点对象）\n");
        for (Rel r : targets) {
            b.append(r.from()).append(" | ").append(r.column()).append(" | ").append(nz(columnText(s, r)))
                    .append(" | ").append(title(s, r.from())).append(" | ").append(title(s, r.to())).append('\n');
        }
        appendReasons(b, reasons);
        return b.toString();
    }

    // ------------------------------------------------------------------ 输入指纹

    /** 名称与说明的输入：表名、表注释、语义层说明。领域不在里面：它只在缺的时候分配一次。 */
    private static String objectHash(Snapshot s, Obj o) {
        return sha256(o.name() + '\u0001' + nz(o.comment()) + '\u0001' + nz(s.objectGloss().get(fold(o.name()))));
    }

    /** 角色名的输入：起点对象的业务名、起点列及其说明、终点表列、终点对象的业务名。 */
    private static String relationHash(Snapshot s, Rel r) {
        return sha256(title(s, r.from()) + '\u0001' + r.column() + '\u0001' + nz(columnText(s, r)) + '\u0001'
                + r.to() + '.' + r.toColumn() + '\u0001' + title(s, r.to()));
    }

    /** 列的说明：语义层的字段说明优先，没有就用客户的列注释。 */
    private static String columnText(Snapshot s, Rel r) {
        String gloss = s.fieldGloss().get(key(r.from(), r.column()));
        return gloss != null && !gloss.isBlank() ? gloss : r.comment();
    }

    /** 对象这会儿的叫法：业务名；还没有就用表注释；再没有就是表名。只用来喂模型，不上屏。 */
    private static String title(Snapshot s, String table) {
        ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_OBJECT, table, ""));
        if (v != null && v.getDisplayName() != null) {
            return v.getDisplayName();
        }
        for (Obj o : s.objects()) {
            if (o.name().equals(table) && o.comment() != null && !o.comment().isBlank()) {
                return o.comment();
            }
        }
        return table;
    }

    // ------------------------------------------------------------------ 写

    private ConnectorBusinessView objectRow(Connection conn, Snapshot s, String table) {
        ConnectorBusinessView v = s.views().get(viewKey(ConnectorBusinessView.KIND_OBJECT, table, ""));
        return v != null ? v : newRow(conn, ConnectorBusinessView.KIND_OBJECT, table, "");
    }

    private static ConnectorBusinessView newRow(Connection conn, String kind, String object, String field) {
        ConnectorBusinessView v = new ConnectorBusinessView();
        v.setTenantId(conn.getTenantId());
        v.setConnectorId(conn.getId());
        v.setKind(kind);
        v.setObjectName(object);
        v.setFieldName(field);
        v.setSource(ConnectorBusinessView.SOURCE_MODEL);
        return v;
    }

    /** 插入或更新一行。更新带 {@code source = MODEL} 条件：跑的过程中客户刚改成 HUMAN 的行不覆盖。 */
    private void save(ConnectorBusinessView v, Snapshot s) {
        v.setModelCode(modelCall.modelCode());
        v.setPromptVersion(PROMPT_VERSION);
        if (v.getId() == null) {
            try {
                viewMapper.insert(v);
                s.views().put(viewKey(v.getKind(), v.getObjectName(), v.getFieldName()), v);
            } catch (DuplicateKeyException e) {
                log.warn("业务视图写入撞唯一键，本条跳过 connectorId={} kind={} object={} field={}",
                        v.getConnectorId(), v.getKind(), v.getObjectName(), v.getFieldName());
            }
            return;
        }
        viewMapper.update(null, new LambdaUpdateWrapper<ConnectorBusinessView>()
                .eq(ConnectorBusinessView::getId, v.getId())
                .eq(ConnectorBusinessView::getSource, ConnectorBusinessView.SOURCE_MODEL)
                .set(ConnectorBusinessView::getDisplayName, v.getDisplayName())
                .set(ConnectorBusinessView::getSummary, v.getSummary())
                .set(ConnectorBusinessView::getDomain, v.getDomain())
                .set(ConnectorBusinessView::getInputHash, v.getInputHash())
                .set(ConnectorBusinessView::getModelCode, v.getModelCode())
                .set(ConnectorBusinessView::getPromptVersion, v.getPromptVersion()));
    }

    // ------------------------------------------------------------------ 小工具

    private static void appendReasons(StringBuilder b, Map<String, String> reasons) {
        if (reasons.isEmpty()) {
            return;
        }
        b.append("\n上一次这几条不合格，请按原因改写：\n");
        reasons.forEach((k, v) -> b.append(k).append("：").append(v).append('\n'));
    }

    private static String prefixed(String what, String problem) {
        return problem == null ? null : what + problem;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : SemanticRowAssembler.nz(s).trim();
    }

    static String viewKey(String kind, String object, String field) {
        return kind + '\u0001' + fold(object) + '\u0001' + fold(field == null ? "" : field);
    }

    private static String key(String table, String column) {
        return fold(table) + '\u0001' + fold(column);
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
            return Map.of();
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Obj(String name, String comment) {
    }

    private record Rel(String from, String column, String to, String toColumn, String comment) {
    }

    private record Snapshot(List<Obj> objects, List<Rel> relations, Map<String, Map<String, FieldDetail>> fields,
                            Map<String, String> objectGloss, Map<String, String> fieldGloss, Set<String> identifiers,
                            Map<String, ConnectorBusinessView> views) {
    }

    private static final class Counter {
        int domains;
        int names;
        int roles;
        int rejected;
        int deleted;
    }

    /** 一次生成的结果。{@link #note()} 写进 {@code connector_enrichment_state.view_note}，只给排查用，不上屏。 */
    public record Result(int domainsAssigned, int namesWritten, int rolesWritten, int rejected, int deleted) {
        public String note() {
            return "业务名 " + namesWritten + " 条、角色名 " + rolesWritten + " 条、领域 " + domainsAssigned
                    + " 条；两次都不合格退回兜底 " + rejected + " 条；清理 " + deleted + " 条";
        }
    }
}
