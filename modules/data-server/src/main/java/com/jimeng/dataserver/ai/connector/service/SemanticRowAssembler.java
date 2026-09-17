package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「一段模型产出 → 能落进 connector_semantic 的行」这一步，从 {@link ConnectorSemanticDeriveService} 里原样抽出来的纯函数。
 *
 * <h3>为什么要抽出来</h3>
 * 两条路要走<b>同一套</b>过滤：Java 单次推导（整库一次调用，落库走 {@code replaceInferred} / {@code upsertInferred}），
 * 以及语义层生成 agent 的逐表提交（{@code generation.callback}，一次一张表）。三道过滤——GUESS 丢、名字对不上快照丢、
 * 撞唯一键先合并——任何一道在两条路上分叉，表现都是「同一份产出，一条路进了库、另一条路没进」，没有任何地方会报错。
 * 所以这里只有一份实现，推导类原位置改成委托。
 *
 * <h3>它比原来多做的一件事：逐条结果</h3>
 * 推导只需要计数（写进管理台那一行字），agent 需要知道<b>哪一条</b>为什么没进库，才能照着改了重提。
 * 于是原来的 {@code Stats} 换成 {@link AssemblyReport}：计数器一个不少，另外每个「跳过这一条」的分支和每次同键合并
 * 都记一条 {@link EntryOutcome}。计数与逐条结果由同一个记录方法一起写，两者不可能对不上。
 * 原来「缺 name / gloss 直接 continue、不计数」的分支现在记 {@link #CODE_MISSING_REQUIRED}，并有自己的计数器，
 * 但它<b>不进</b>任何推导摘要——推导的对外行为逐字不变（{@code SemanticRowAssemblerTest} 用抽取前录下的金样钉住）。
 *
 * <h3>纯函数，不碰库、不碰租户</h3>
 * 全部方法都是 static，只读入参。租户隔离、CAS 认领、「只动 INFERRED 行」这些不变式留在调用方（推导类与提交写入器），
 * 不在这里——这里不知道、也不该知道行最后写到哪张表。
 */
@Slf4j
public final class SemanticRowAssembler {

    /** gloss 列是 varchar(1000)。超了严格模式下报错、非严格模式下<b>静默截断</b>，两种都不能接受。 */
    public static final int GLOSS_MAX = 1000;

    /**
     * {@code object_name} / {@code field_name} / {@code term} 三列都是 varchar(<b>191</b>)。
     *
     * <p>191 不是随手写的：这三列进 {@code uk_connector_semantic}，utf8mb4 每字符 4 字节，
     * 按 255 建索引会超过 InnoDB 单索引 3072 字节的上限，<b>整张表建不出来</b>
     * （mysql:8.0 实测 ERROR 1071）。改这个数之前先去读那条迁移里的注释。
     *
     * <p>这三列<b>只丢不截</b>：它们全都要拿去做<b>精确匹配</b>（表名/列名要对上快照才算得出锚点，
     * 词条要对上才注入得了）。截短的名字一个都匹配不上，却长得和一条真的一模一样——
     * 那是比少一条更糟的东西。
     */
    public static final int NAME_MAX = 191;

    /**
     * detail_json 里那些<b>模型自由发挥</b>的文本的上限。
     *
     * <p>detail_json 是 TEXT（64KB）。模型返回什么由不得我们，而超长写不进去的后果不是「少一行」：
     * {@code replaceInferred} 是一个事务，插到一半报错就整批回滚、旧行已经删了——
     * 和撞唯一键是同一个坑（见 {@link #dedupKey}）。所以进 detail 之前统一收口。
     */
    public static final int DETAIL_TEXT_MAX = 500;

    /** 同上：typical_questions 这类数组也得有个头，不然 10 条变 1000 条一样能把 TEXT 撑爆。 */
    public static final int DETAIL_LIST_MAX = 10;

    public static final Set<String> CARDINALITIES = Set.of("1:1", "1:N", "N:1", "N:N");

    // ── 条目种类：就是模型产出（以及 agent 提交）里那四个数组的键名，逐条结果的 path 是「种类[下标]」 ──
    public static final String KIND_OBJECTS = "objects";
    public static final String KIND_FIELDS = "fields";
    public static final String KIND_JOINS = "joins";
    public static final String KIND_AMBIGUITIES = "ambiguities";
    /** S1 从客户 SQL 语料挖出来的关系行。不是模型产出，只在推导路径上出现。 */
    public static final String KIND_CORPUS_JOINS = "corpus_joins";

    // ── 逐条结果的状态。与回调接口 entries[].status 同一组字面量（设计 7.8） ──
    /** 违反规则、可以修正：重提时交这张表的完整版本。 */
    public static final String STATUS_REJECTED = "rejected";
    /** 按规则不入库，不需要重提。 */
    public static final String STATUS_DROPPED = "dropped";

    // ── 本类产出的结果码。稳定的机器码，会写进 entries[].code 与 generation_table.last_reject_reason，不许改名 ──
    public static final String CODE_EVIDENCE_GUESS = "EVIDENCE_GUESS";
    public static final String CODE_NAME_NOT_IN_SNAPSHOT = "NAME_NOT_IN_SNAPSHOT";
    public static final String CODE_MISSING_REQUIRED = "MISSING_REQUIRED";
    public static final String CODE_KEY_TOO_LONG = "KEY_TOO_LONG";
    public static final String CODE_CAVEAT_TERM_ANSWERED = "CAVEAT_TERM_ANSWERED";
    public static final String CODE_DUPLICATE_MERGED = "DUPLICATE_MERGED";

    private SemanticRowAssembler() {
    }

    // ================================================================ 结构摘要

    /** 从快照的 detail_json 里把字段还原成 {@link FieldDetail}——算锚点要用它，拼摘要也要用它。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, FieldDetail>> parseFields(List<ConnectorSchema> rows) {
        Map<String, Map<String, FieldDetail>> out = new LinkedHashMap<>();
        for (ConnectorSchema r : rows) {
            Map<String, FieldDetail> cols = new LinkedHashMap<>();
            out.put(r.getObjectName(), cols);
            if (r.getDetailJson() == null || r.getDetailJson().isBlank()) {
                continue;
            }
            try {
                Map<String, Object> m = CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
                Object f = m.get("fields");
                if (!(f instanceof List<?> list)) {
                    continue;
                }
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> fm)) {
                        continue;
                    }
                    Object name = fm.get("name");
                    if (name == null) {
                        continue;
                    }
                    // nullable 缺失时按「可空」处理：宁可让锚点多算一次不一致，也不要把一个未知说成 NOT NULL。
                    cols.put(String.valueOf(name), new FieldDetail(
                            String.valueOf(name),
                            asString(fm.get("type")),
                            !Boolean.FALSE.equals(fm.get("nullable")),
                            asString(fm.get("comment")),
                            asString(fm.get("extra"))));
                }
            } catch (Exception e) {
                // 单张表的快照 JSON 坏了不该让整次推导失败：这张表没字段可推，其余的照推。
                log.warn("解析结构快照失败，本次跳过该对象的字段 objectName={}", r.getObjectName(), e);
            }
        }
        return out;
    }

    /**
     * 一张表一段。列注释为空时照样留出那一格——「客户没写注释」本身就是模型要知道的事实
     * （没有 COMMENT 这一档依据可用），不能让它看起来像是我们没给。
     */
    public static String renderObject(ConnectorSchema r, Map<String, FieldDetail> cols) {
        StringBuilder b = new StringBuilder(renderObjectHeader(r));
        if (cols == null || cols.isEmpty()) {
            // 账号权限只到部分表时，ConnectorSchemaService 会存一条只有名字的行。
            // 如实说出来，别让模型把「没取到」读成「这是张空表」。
            b.append("(本表字段未取到，不要为它写字段或关系)\n");
        } else {
            for (FieldDetail f : cols.values()) {
                b.append(renderField(f));
            }
        }
        b.append('\n');
        return b.toString();
    }

    /**
     * {@link #renderObject} 的对象头。宽表预算器会把列行预渲染一次后按前缀长度选截断点，必须复用这里，
     * 否则「完整摘要」和「截断摘要」可能因两份格式代码悄悄分叉。
     */
    public static String renderObjectHeader(ConnectorSchema r) {
        return "## " + r.getObjectName()
                + " [" + (r.getObjectType() == null ? "TABLE" : r.getObjectType()) + "] "
                + (blank(r.getObjectComment()) ? "(无表注释)" : r.getObjectComment())
                + '\n';
    }

    /** 同一原因，把单列的规范文本也收在唯一实现里；返回值始终以换行结束。 */
    public static String renderField(FieldDetail f) {
        return f.name() + '|'
                + nz(f.type()) + '|'
                + (f.nullable() ? "NULL" : "NOT NULL") + '|'
                + nz(f.comment()) + '|'
                + nz(f.extra()) + '\n';
    }

    // ================================================================ 落成语义行

    /**
     * 把模型产出摊成 {@link ConnectorSemantic} 行。<b>三道过滤，一道都不能省：</b>
     * <ol>
     *   <li><b>GUESS 直接丢</b>——没有外部依据的断言不该进说明书。分界线是「有没有依据」，
     *       不是「模型说它确不确定」。</li>
     *   <li><b>名字对不上快照的丢</b>——模型发明出来的表/列既算不出锚点，本身也就是幻觉。</li>
     *   <li><b>撞唯一键的丢</b>——{@code uk_connector_semantic} 是
     *       (租户,连接,scope,表,字段,词条)。JOIN 行的键里只有左侧列，同一列指向两张表就会撞；
     *       撞了不是报一条错，而是 {@code replaceInferred} 那个事务整个回滚、一行都写不进去。
     *       所以必须在进库之前先合并掉。</li>
     * </ol>
     *
     * <h3>逐条结果里的下标</h3>
     * {@link EntryOutcome#index()} 是条目在<b>原数组</b>里的位置，数组里不是 JSON 对象的元素照旧静默跳过（不记结果），
     * 但占着位置。这是刻意的：提交流水线在一致性规则之后才调本方法，被规则退回的条目可以在原位置换成 {@code null}
     * 传进来，其余条目的下标仍与 agent 提交的数组对得上，path 不用再换算一遍。
     *
     * @param answeredTerms 人已经在对话里答过的口径词条（折叠过大小写）。已经有答案的问题不再重提，
     *                      理由见下面 ambiguities 那一段。
     * @param report        计数与逐条结果都记在这里。调用方自己 new，一次调用一个
     * @param corpusJoins   S1 从客户自己写的视图 / 存储过程里挖出来的关系，已经按快照对齐过大小写。
     *                      它们和模型产出走<b>同一套去重</b>（否则一样会在 replaceInferred 里撞唯一键），
     *                      但在撞键时<b>压过</b>模型那条，见 {@link #putCorpusJoin}。agent 路径传 {@code List.of()}。
     */
    public static List<ConnectorSemantic> toRows(Map<String, Object> parsed,
                                                 Map<String, Map<String, FieldDetail>> fieldsByObject,
                                                 Set<String> answeredTerms,
                                                 AssemblyReport report,
                                                 List<ConnectorSemantic> corpusJoins) {
        Map<String, ConnectorSemantic> byKey = new LinkedHashMap<>();

        // ── OBJECT：不锚结构。connector_schema.content_hash 覆盖每一列，客户加一个无关列它就变，
        //    而加一列并不改变「这张表是订单主表」这句话——锚上去等于给自己造一堆假 STALE。
        for (Item it : items(parsed, KIND_OBJECTS)) {
            Map<String, Object> o = it.entry();
            EntryRef ref = refOf(KIND_OBJECTS, it);
            String name = str(o, "name");
            String gloss = str(o, "gloss");
            if (name == null || gloss == null) {
                report.missingRequired(ref, missing(o, "name", "gloss"));
                continue;
            }
            if (!fieldsByObject.containsKey(name)) {
                report.unknownName(ref, "快照里没有表 " + name + "。表名必须原样照抄快照里的写法（大小写敏感），不要补前缀");
                continue;
            }
            String ev = evidenceOf(KIND_OBJECTS, o);
            if (ConnectorSemanticService.EV_GUESS.equals(ev)) {
                report.guess(ref);
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            // ★ 只认四个枚举值。认不出来的不写——落成 OTHER 等于替模型编了一个判断。
            //   来源标 MODEL：这是模型看结构的推测；验证阶段会对疑似键值对表做数据测量。
            TableShape.parse(str(o, "table_shape")).ifPresent(shape -> {
                detail.put(TableShape.KEY_SHAPE, shape.name());
                detail.put(TableShape.KEY_SOURCE, TableShape.SOURCE_MODEL);
            });
            detail.put("typical_questions", detailList(strList(o, "typical_questions")));

            ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_OBJECT, name, "", "");
            row.setGloss(clip(gloss, GLOSS_MAX));
            row.setDetailJson(toJson(detail));
            row.setEvidence(ev);
            row.setConfidence(confidence(o));
            row.setAnchorKind(ConnectorSemanticService.ANCHOR_NONE);
            row.setAnchorHash(null);
            report.track(row, ref);
            put(byKey, row, report);
        }

        // ── FIELD：锚这一列自己的指纹，改别的列不影响它。
        for (Item it : items(parsed, KIND_FIELDS)) {
            Map<String, Object> f = it.entry();
            EntryRef ref = refOf(KIND_FIELDS, it);
            String obj = str(f, "object");
            String col = str(f, "name");
            String gloss = str(f, "gloss");
            if (obj == null || col == null || gloss == null) {
                report.missingRequired(ref, missing(f, "object", "name", "gloss"));
                continue;
            }
            FieldDetail fd = lookup(fieldsByObject, obj, col);
            if (fd == null) {
                report.unknownName(ref, "快照里没有列 " + obj + "." + col
                        + "。表名列名必须原样照抄快照里的写法（大小写敏感）");
                continue;
            }
            String ev = evidenceOf(KIND_FIELDS, f);
            if (ConnectorSemanticService.EV_GUESS.equals(ev)) {
                report.guess(ref);
                continue;
            }
            ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_FIELD, obj, col, "");
            row.setGloss(clip(gloss, GLOSS_MAX));
            row.setEvidence(ev);
            row.setConfidence(confidence(f));
            row.setAnchorKind(ConnectorSemanticService.ANCHOR_FIELD);
            row.setAnchorHash(ConnectorSemanticService.fieldAnchor(fd));
            report.track(row, ref);
            put(byKey, row, report);
        }

        // ── JOIN：P1 <b>没有</b>采样验证（那是 P2），所以每一条都是未经验证的推测。
        //    verified=NONE + basis 写死「未经数据验证」，注入层据此照实告诉模型，
        //    而不是让它看到一条关系就当外键用。
        for (Item it : items(parsed, KIND_JOINS)) {
            Map<String, Object> j = it.entry();
            EntryRef ref = refOf(KIND_JOINS, it);
            String obj = str(j, "object");
            String col = str(j, "column");
            String toObj = str(j, "to_object");
            String toCol = str(j, "to_column");
            if (obj == null || col == null || toObj == null || toCol == null) {
                report.missingRequired(ref, missing(j, "object", "column", "to_object", "to_column"));
                continue;
            }
            FieldDetail left = lookup(fieldsByObject, obj, col);
            FieldDetail right = lookup(fieldsByObject, toObj, toCol);
            if (left == null || right == null) {
                List<String> absent = new ArrayList<>(2);
                if (left == null) {
                    absent.add(obj + "." + col);
                }
                if (right == null) {
                    absent.add(toObj + "." + toCol);
                }
                report.unknownName(ref, "快照里没有列 " + String.join("、", absent)
                        + "。表名列名必须原样照抄快照里的写法（大小写敏感）");
                continue;
            }
            // 关系缺依据标签时默认按 NAME 处理，与 OBJECT/FIELD 的「缺标签即 GUESS」刻意不对称：
            // 一条关系带着 verified=NONE 和「未经数据验证」的 basis 一起注入，读它的模型知道要自己核；
            // 而一条字段含义是被当成事实读的，错了没有任何地方看得出来。
            // 前者失手的代价有边界，后者没有——不对称的处置对应的是不对称的代价。见 evidenceOf。
            String ev = evidenceOf(KIND_JOINS, j);
            if (ConnectorSemanticService.EV_GUESS.equals(ev)) {
                report.guess(ref);
                continue;
            }
            String card = str(j, "cardinality");
            card = card == null ? null : card.toUpperCase(Locale.ROOT);
            if (card != null && !CARDINALITIES.contains(card)) {
                card = null;
            }
            String note = clip(str(j, "note"), DETAIL_TEXT_MAX);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("to_object", toObj);
            detail.put("to_column", toCol);
            detail.put("cardinality", card);
            detail.put("basis", "未经数据验证");
            if (note != null) {
                detail.put("note", note);
            }

            ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_JOIN, obj, col, "");
            row.setGloss(clip("关联 " + toObj + "." + toCol
                    + (card == null ? "" : "（" + card + "）")
                    + (note == null ? "" : "。" + note)
                    + "。本条未经数据验证，join 前建议先看该列的取值分布。", GLOSS_MAX));
            row.setDetailJson(toJson(detail));
            row.setEvidence(ev);
            row.setConfidence(confidence(j));
            row.setAnchorKind(ConnectorSemanticService.ANCHOR_JOIN);
            row.setAnchorHash(ConnectorSemanticService.joinAnchor(left, right));
            row.setVerified(ConnectorSemanticService.V_NONE);
            report.track(row, ref);
            put(byKey, row, report);
        }

        // ── S1：客户自己写的 SQL 里的关系。★ 放在模型的 joins 【之后】灌进来，
        //    这样撞键时 putCorpusJoin 能看到模型那条并把它顶掉（顺序反过来就顶不掉了）。
        for (int i = 0; i < corpusJoins.size(); i++) {
            ConnectorSemantic row = corpusJoins.get(i);
            report.track(row, new EntryRef(KIND_CORPUS_JOINS, i, row.getObjectName() + "." + row.getFieldName()));
            putCorpusJoin(byKey, row, report);
        }

        // ── 歧义 → CAVEAT，<b>不是 METRIC</b>。METRIC 的含义是「已经澄清的口径」，只能由人在对话里
        //    回答出来（{@code ConnectorSemanticService.defineMetric}）。把一个还没人回答的问题写成
        //    METRIC，等于平台自己编了一条口径——这正是整套设计最不许发生的那件事。
        for (Item it : items(parsed, KIND_AMBIGUITIES)) {
            Map<String, Object> c = it.entry();
            EntryRef ref = refOf(KIND_AMBIGUITIES, it);
            String term = str(c, "term");
            String question = str(c, "question");
            if (term == null || question == null) {
                report.missingRequired(ref, missing(c, "term", "question"));
                continue;
            }
            // ★ 人已经答过的口径不再作为「待确认」重提。否则 conn_catalog 会把【答案】和【同一个问题】
            //   一起注入，模型只能二选一——那是我们自己制造的矛盾，而且是在「已经花过一次人力澄清」
            //   之后制造的。注入层另有一道同样的抑制（那边归另一个人管），这里也拦一道：
            //   重新推导不该把一个已经有答案的问题复活。
            if (answeredTerms.contains(fold(term))) {
                report.answered(ref, term);
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("applies_to", detailList(strList(c, "applies_to")));

            // term 不截断：超长的整条丢（在 put 里），理由见 NAME_MAX。
            ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_CAVEAT, "", "", term);
            row.setGloss(clip(question, GLOSS_MAX));
            row.setDetailJson(toJson(detail));
            // evidence 留空：歧义不是一条断言，它恰恰是「没有依据、必须问人」的那一类。
            // 给它贴任何一个依据标签都是把话说反了。
            row.setEvidence(null);
            row.setAnchorKind(ConnectorSemanticService.ANCHOR_NONE);
            report.track(row, ref);
            put(byKey, row, report);
        }

        // 计数按实际留下来的行数来，而不是在循环里累加——去重会把已经计过的行换掉。
        for (ConnectorSemantic r : byKey.values()) {
            switch (r.getScope()) {
                case ConnectorSemanticService.SCOPE_OBJECT -> report.objects++;
                case ConnectorSemanticService.SCOPE_FIELD -> report.fields++;
                case ConnectorSemanticService.SCOPE_JOIN -> report.joins++;
                default -> report.caveats++;
            }
        }
        return new ArrayList<>(byKey.values());
    }

    public static ConnectorSemantic base(String scope, String objectName, String fieldName, String term) {
        ConnectorSemantic row = new ConnectorSemantic();
        row.setScope(scope);
        // 这三列是 NOT NULL DEFAULT ''：写 null 会让它们在唯一键上「不参与去重」，务必给空串。
        row.setObjectName(objectName);
        row.setFieldName(fieldName);
        row.setTerm(term);
        row.setStatus(ConnectorSemanticService.ST_DRAFT);
        row.setVerified(ConnectorSemanticService.V_NONE);
        return row;
    }

    /** 按唯一键合并，撞了留 confidence 高的那条。两条都留不是多一行，是整批插入回滚、一行都不剩。 */
    public static void put(Map<String, ConnectorSemantic> byKey, ConnectorSemantic row, AssemblyReport report) {
        if (tooLong(row, report)) {
            return;
        }
        String key = dedupKey(row);
        ConnectorSemantic old = byKey.get(key);
        if (old == null) {
            byKey.put(key, row);
            return;
        }
        int a = old.getConfidence() == null ? -1 : old.getConfidence();
        int b = row.getConfidence() == null ? -1 : row.getConfidence();
        if (b > a) {
            byKey.put(key, row);
            report.merged(old, row, "只保留了置信度较高的那一条");
        } else {
            report.merged(row, old, "只保留了置信度较高（或先到）的那一条");
        }
        log.debug("语义层推导产出撞唯一键，已合并 key={}", key);
    }

    /**
     * S1 产出的关系行入表。与 {@link #put} 的唯一区别：撞键时<b>不比 confidence，直接顶掉</b>模型那条。
     *
     * <h3>为什么不能比 confidence</h3>
     * 两边的 confidence 根本不是一把尺子。模型那个数是它<b>自己报的把握</b>（而且它对
     * {@code cid → users.id} 这种名字像的关系报 90 是常态）；S1 那个数是
     * <b>「这条 join 在客户自己写的 SQL 里出现过几处」</b>。拿两个不同量纲的数比大小，
     * 结果是一条人真的写过的关系被一条模型猜出来的关系挤掉——而 JOIN 行的唯一键里只有左侧列
     *（{@code (scope, 左表, 左列)}），同一列指向两张不同的表就会撞，所以这不是罕见情形。
     *
     * <p>判据回到那条老分界线：<b>有没有外部依据</b>。视图里的 {@code a.x = b.y} 是客户写在自己库里的
     * 一句话（{@code EV_COMMENT}），模型的 {@code EV_NAME} 是「名字像」。前者压后者。
     *
     * <p>两条都来自语料时才比 confidence——那时两个数同量纲，比的是「几处见过它」。
     */
    public static void putCorpusJoin(Map<String, ConnectorSemantic> byKey, ConnectorSemantic row,
                                     AssemblyReport report) {
        if (tooLong(row, report)) {
            return;
        }
        String key = dedupKey(row);
        ConnectorSemantic old = byKey.get(key);
        if (old == null) {
            byKey.put(key, row);
            return;
        }
        if (ConnectorSemanticService.EV_COMMENT.equals(old.getEvidence())) {
            int a = old.getConfidence() == null ? -1 : old.getConfidence();
            int b = row.getConfidence() == null ? -1 : row.getConfidence();
            if (b > a) {
                byKey.put(key, row);
                report.merged(old, row, "只保留了在客户 SQL 语料里出现次数较多的那一条");
            } else {
                report.merged(row, old, "只保留了在客户 SQL 语料里出现次数较多（或先到）的那一条");
            }
            return;
        }
        log.debug("语料关系顶掉了模型推测的同键关系 key={}", key);
        byKey.put(key, row);
        report.merged(old, row, "客户自己写的视图/存储过程里有同一列的关系，以客户 SQL 语料为准");
    }

    /**
     * ★ 去重键<b>必须按唯一键的排序规则折叠大小写</b>。
     *
     * <p>{@code uk_connector_semantic} 落在 utf8mb4_unicode_ci 上，{@code ord_id} 和 {@code ORD_ID}
     * 在库里<b>是同一个键</b>（mysql:8.0 实测：{@code ERROR 1062 Duplicate entry
     * 't1-100-JOIN-t_ord_dtl-ORD_ID-'}），而模型完全可能一条写小写、一条写大写。
     * 用大小写敏感的键去重，这两条会双双通过这道闸，然后在 {@code replaceInferred} 里撞唯一键——
     * 那是个 {@code @Transactional} 方法，撞一次不是少一行，是<b>旧行已删、新行一条没插</b>，
     * 整条连接的语义层当场清空。
     *
     * <p>折叠只用在<b>键</b>上；行里存的仍然是模型原样给的大小写，因为表名列名要和快照对得上。
     * 尾随空格不用管：所有取值都在 {@link #str} 里 trim 过，而 utf8mb4_unicode_ci 是 PAD SPACE。
     */
    public static String dedupKey(ConnectorSemantic row) {
        return fold(row.getScope()) + '/' + fold(row.getObjectName()) + '/'
                + fold(row.getFieldName()) + '/' + fold(row.getTerm());
    }

    /**
     * 三个键列超过 varchar(191) 的整行丢掉，<b>不截</b>。
     *
     * <p>它们全都要拿去做精确匹配：表名列名对不上快照就算不出锚点，词条对不上就永远注入不了。
     * 一条被截短的行既起不了作用，看起来又和真的一模一样——比少一条糟得多。
     * 落库那一侧同样不能指望：非严格模式下 MySQL 会<b>静默</b>截断，严格模式下整批插入报错回滚。
     */
    public static boolean tooLong(ConnectorSemantic row, AssemblyReport report) {
        if (len(row.getObjectName()) > NAME_MAX || len(row.getFieldName()) > NAME_MAX
                || len(row.getTerm()) > NAME_MAX) {
            report.tooLong(row);
            log.warn("语义层推导产出的键列超过 {} 字符，整条丢弃 scope={} object={} field={} term={}",
                    NAME_MAX, row.getScope(), abbrev(row.getObjectName()),
                    abbrev(row.getFieldName()), abbrev(row.getTerm()));
            return true;
        }
        return false;
    }

    /** 人已经答过的口径词条（{@code METRIC} + {@code CONFIRMED}），折叠大小写后用于比对。 */
    public static Set<String> answeredTerms(List<ConnectorSemantic> existing) {
        Set<String> out = new HashSet<>();
        if (existing == null) {
            return out;
        }
        for (ConnectorSemantic r : existing) {
            if (ConnectorSemanticService.SCOPE_METRIC.equals(r.getScope())
                    && ConnectorSemanticService.ST_CONFIRMED.equals(r.getStatus())
                    && r.getTerm() != null && !r.getTerm().isBlank()) {
                out.add(fold(r.getTerm()));
            }
        }
        return out;
    }

    /**
     * 一个条目归一后的依据标签：OBJECT / FIELD 缺标签按 GUESS，JOIN 缺标签按 NAME，ambiguities 没有依据标签（{@code null}）。
     *
     * <p>单独成一个方法，是为了让提交流水线的一致性规则（「归一后不是 GUESS 的条目才跑规则」）和 {@link #toRows}
     * 用的是<b>同一份</b>缺省：两边各写一遍，哪天一边改了，规则就会去审一条 toRows 注定要丢的条目，或者反过来。
     * 关系与字段的缺省为什么不对称，见 {@link #toRows} 里 JOIN 那一段。
     */
    public static String evidenceOf(String kind, Map<String, Object> entry) {
        return switch (kind) {
            case KIND_OBJECTS, KIND_FIELDS -> evidence(entry, ConnectorSemanticService.EV_GUESS);
            case KIND_JOINS -> evidence(entry, ConnectorSemanticService.EV_NAME);
            default -> null;
        };
    }

    /**
     * 条目的可读键，进逐条结果的 {@code key}：表 / 表.列 / 左表.左列 / 口径词。缺的部分留空，不编。
     * JOIN 只写左列，与唯一键里 JOIN 只含左列一致（右端写在文案里）。
     */
    public static String entryKey(String kind, Map<String, Object> entry) {
        return switch (kind) {
            case KIND_OBJECTS -> nullToEmpty(str(entry, "name"));
            case KIND_FIELDS -> nullToEmpty(str(entry, "object")) + "." + nullToEmpty(str(entry, "name"));
            case KIND_JOINS -> nullToEmpty(str(entry, "object")) + "." + nullToEmpty(str(entry, "column"));
            case KIND_AMBIGUITIES -> nullToEmpty(str(entry, "term"));
            default -> "";
        };
    }

    // ================================================================ 小工具

    public static FieldDetail lookup(Map<String, Map<String, FieldDetail>> byObject, String obj, String col) {
        Map<String, FieldDetail> cols = byObject.get(obj);
        return cols == null ? null : cols.get(col);
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> arr(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : list) {
            if (e instanceof Map<?, ?> em) {
                out.add((Map<String, Object>) em);
            }
        }
        return out;
    }

    public static String str(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    public static List<String> strList(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object e : list) {
            if (e != null) {
                out.add(String.valueOf(e));
            }
        }
        return out;
    }

    /** 认不出来的依据标签一律当 GUESS。宁可少一条，也不要一条来路不明的。 */
    public static String evidence(Map<String, Object> m, String whenMissing) {
        String e = str(m, "evidence");
        if (e == null) {
            return whenMissing;
        }
        String up = e.toUpperCase(Locale.ROOT);
        return switch (up) {
            case ConnectorSemanticService.EV_COMMENT,
                 ConnectorSemanticService.EV_DATA,
                 ConnectorSemanticService.EV_NAME,
                 ConnectorSemanticService.EV_GUESS -> up;
            default -> ConnectorSemanticService.EV_GUESS;
        };
    }

    /**
     * 置信度归一到 0-100。模型有时给 0.8，有时给 80，两种都得认。
     *
     * <p>{@code <= 1} 一律当小数放大：「置信度 1 分」不是任何人会想表达的意思，
     * 而把 0.8 存成 1，会让一条中等把握的断言在界面上看起来完全不可信。
     */
    public static Integer confidence(Map<String, Object> m) {
        String raw = str(m, "confidence");
        if (raw == null) {
            return null;
        }
        double d;
        try {
            d = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
        long v = d <= 1.0d ? Math.round(d * 100) : Math.round(d);
        return (int) Math.max(0, Math.min(100, v));
    }

    public static String toJson(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            log.warn("序列化语义 detail 失败", e);
            return null;
        }
    }

    public static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    /** detail_json 里的数组：条数和每条长度都要收口，理由见 {@link #DETAIL_TEXT_MAX}。 */
    public static List<String> detailList(List<String> in) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (out.size() >= DETAIL_LIST_MAX) {
                break;
            }
            out.add(clip(s, DETAIL_TEXT_MAX));
        }
        return out;
    }

    /** 折叠大小写，用于按 utf8mb4_unicode_ci 的口径比对键与词条。 */
    public static String fold(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    public static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /** 摘要是按行、按竖线分列的，列注释里真出现换行或竖线会把这个格式撑破，就地换掉。 */
    public static String nz(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    public static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 日志里带上超长值的头一截就够定位了，整段打出来只会把日志刷爆。 */
    private static String abbrev(String s) {
        return s == null ? "" : (s.length() <= 40 ? s : s.substring(0, 40) + "…(" + s.length() + ")");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 缺了哪几个必填项，原样列出键名——agent 照着补，比一句「形状不对」有用。 */
    private static String missing(Map<String, Object> entry, String... required) {
        List<String> absent = new ArrayList<>(required.length);
        for (String k : required) {
            if (str(entry, k) == null) {
                absent.add(k);
            }
        }
        return "缺少必填项 " + String.join("、", absent) + "（空串与纯空白按缺少处理），本条不入库";
    }

    /** 数组元素连同它在<b>原数组</b>里的位置。非 JSON 对象的元素跳过但占位，理由见 {@link #toRows}。 */
    @SuppressWarnings("unchecked")
    private static List<Item> items(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<Item> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof Map<?, ?> em) {
                out.add(new Item(i, (Map<String, Object>) em));
            }
        }
        return out;
    }

    private static EntryRef refOf(String kind, Item it) {
        return new EntryRef(kind, it.index(), entryKey(kind, it.entry()));
    }

    private record Item(int index, Map<String, Object> entry) {
    }

    // ================================================================ 形状

    /**
     * 一个条目在提交里的位置。纯内部形状，不出 HTTP（jackson 2.11.1 序列化不了 record，出网的是回调包里的 DTO）。
     *
     * @param kind  {@link #KIND_OBJECTS} 等；调用方绕过 {@link #toRows} 直接 put 进来的行没有来源，为 {@code null}
     * @param index 在原数组里的下标；没有来源时为 -1
     * @param key   {@link #entryKey}
     */
    public record EntryRef(String kind, int index, String key) {

        /** {@code fields[5]}：与 agent 提交的数组逐字对得上的定位。 */
        public String path() {
            return kind == null ? "" : kind + "[" + index + "]";
        }
    }

    /**
     * 一个没被接受的条目：为什么、该不该重提。只记 {@link #STATUS_REJECTED} 与 {@link #STATUS_DROPPED}，
     * 被接受的不记——一张 200 列的表逐条回显会把工具结果撑爆（设计 7.8）。
     *
     * @param code    稳定机器码，本类的见 {@code CODE_*}；一致性规则与写入器有各自的码
     * @param message 给 agent 看的一句中文，能照着改
     */
    public record EntryOutcome(String kind, int index, String key, String status, String code, String message) {

        static EntryOutcome of(EntryRef ref, String status, String code, String message) {
            return new EntryOutcome(ref.kind(), ref.index(), ref.key(), status, code, message);
        }

        public String path() {
            return kind == null ? "" : kind + "[" + index + "]";
        }
    }

    /**
     * 过程计数器 + 逐条结果。纯内部可变状态，不序列化，一次 {@link #toRows} 一个，不是线程安全的。
     *
     * <p>计数器原样保留原来 {@code Stats} 的九个（推导摘要与 {@code DeriveResult} 读它们），另加
     * {@link #missingRequired}。每个计数器只有<b>一个</b>地方加一，那个地方同时写逐条结果——两者对不上的唯一办法是改这个类。
     */
    @Getter
    public static final class AssemblyReport {
        private int objects;
        private int fields;
        private int joins;
        private int caveats;
        private int droppedGuess;
        private int droppedUnknown;
        private int droppedDup;
        /** 键列超过 varchar(191)，整条丢掉的条数。 */
        private int droppedTooLong;
        /** 人已经答过、因此没有再提一遍的口径问题条数。 */
        private int droppedAnswered;
        /** 缺必填项（name、gloss 这类）跳过的条数。原来直接 continue 不计数，推导摘要里至今不出现它。 */
        private int missingRequired;

        @Getter(AccessLevel.NONE)
        private final List<EntryOutcome> outcomes = new ArrayList<>();

        /**
         * 进了去重表的行 → 它来自哪一条。按<b>引用</b>记：两条内容相同的行是两个条目。
         * 提交写入器靠它把「撞了人工行」「落库撞键」这些发生在本类之后的结局，对回 agent 提交里的 path。
         */
        @Getter(AccessLevel.NONE)
        private final Map<ConnectorSemantic, EntryRef> origins = new IdentityHashMap<>();

        /** 逐条结果，按发生顺序。只读视图。 */
        public List<EntryOutcome> outcomes() {
            return Collections.unmodifiableList(outcomes);
        }

        /** 这一行来自哪个条目；不是经 {@link #toRows} 产出的行返回 {@code null}。 */
        public EntryRef originOf(ConnectorSemantic row) {
            return origins.get(row);
        }

        private void track(ConnectorSemantic row, EntryRef ref) {
            origins.put(row, ref);
        }

        private void missingRequired(EntryRef ref, String message) {
            missingRequired++;
            outcomes.add(EntryOutcome.of(ref, STATUS_REJECTED, CODE_MISSING_REQUIRED, message));
        }

        private void unknownName(EntryRef ref, String message) {
            droppedUnknown++;
            outcomes.add(EntryOutcome.of(ref, STATUS_REJECTED, CODE_NAME_NOT_IN_SNAPSHOT, message));
        }

        private void guess(EntryRef ref) {
            droppedGuess++;
            outcomes.add(EntryOutcome.of(ref, STATUS_DROPPED, CODE_EVIDENCE_GUESS,
                    "依据是 GUESS、没写或认不出（只认 COMMENT、DATA、NAME）：只靠常识推测的条目不入库，不需要重提"));
        }

        private void answered(EntryRef ref, String term) {
            droppedAnswered++;
            outcomes.add(EntryOutcome.of(ref, STATUS_DROPPED, CODE_CAVEAT_TERM_ANSWERED,
                    "口径『" + term + "』已由业务方在对话里回答，不再作为待确认问题提出，不需要重提。"));
        }

        private void tooLong(ConnectorSemantic row) {
            droppedTooLong++;
            EntryRef ref = refOrAnonymous(origins.remove(row), row);
            outcomes.add(EntryOutcome.of(ref, STATUS_REJECTED, CODE_KEY_TOO_LONG,
                    "表名、列名或口径词超过 " + NAME_MAX + " 字符，整条不入库（这几项只做精确匹配，截短了就对不上）"));
        }

        /** 同键合并：{@code dropped} 被 {@code kept} 顶掉。被合并掉的那一条记 dropped，留下的那条不记。 */
        private void merged(ConnectorSemantic dropped, ConnectorSemantic kept, String why) {
            droppedDup++;
            EntryRef ref = refOrAnonymous(origins.remove(dropped), dropped);
            EntryRef keptRef = origins.get(kept);
            String with = keptRef == null || keptRef.kind() == null ? "另一条" : keptRef.path();
            outcomes.add(EntryOutcome.of(ref, STATUS_DROPPED, CODE_DUPLICATE_MERGED,
                    "与 " + with + " 同键（表名、列名或口径词不分大小写相同，一个键只能有一条），" + why + "，不需要重提"));
        }

        private static EntryRef refOrAnonymous(EntryRef ref, ConnectorSemantic row) {
            if (ref != null) {
                return ref;
            }
            String key = ConnectorSemanticService.SCOPE_CAVEAT.equals(row.getScope())
                    ? nullToEmpty(row.getTerm())
                    : nullToEmpty(row.getObjectName())
                    + (blank(row.getFieldName()) ? "" : "." + row.getFieldName());
            return new EntryRef(null, -1, key);
        }
    }
}
