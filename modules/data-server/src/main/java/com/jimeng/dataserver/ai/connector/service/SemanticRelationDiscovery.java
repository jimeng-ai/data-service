package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer.NamedKey;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.RowEstimateNote;
import com.jimeng.dataserver.ai.connector.service.RelationCandidates.Candidate;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.fold;

/**
 * 语义层的关系发现（数据星图设计 v3 §5）：规则候选 + 整库模型一遍 → 新增或替换 JOIN 行。
 *
 * <h3>为什么要单独一遍</h3>
 * 语义层推导按 6 万字符分批、按表名字母序装批，提示词要求「没出现的表不要写关系」，跨批次的关系在规则上就被排除了；
 * 输出顺序又是 表用途 → 字段含义 → 关系，max_tokens 一截断最先丢关系。这一遍只做关系，而且<b>每一片都带着全部表的目标索引</b>，
 * 保证模型看得见所有可能的终点。
 *
 * <h3>三步</h3>
 * <ol>
 *   <li>确定性候选：声明的外键、命名规律（{@link RelationCandidates}），不花一分钱；</li>
 *   <li>模型一遍：只问「像引用、还没有关系、也没有规则候选」的列。输入没变（{@code lastPassFingerprint}）就不再问；</li>
 *   <li>全部经 {@link SemanticRowAssembler#toRows} 的同一套过滤（两端都在快照里、丢 GUESS、每列去重），
 *       再交给 {@link ConnectorSemanticService#mergeDiscoveredJoins} 按 §5.4 的表写入。</li>
 * </ol>
 * 模型那一遍失败不影响规则候选落库：失败原因记进结果，指纹不前移，下次照样再问。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticRelationDiscovery {

    /** 模型那一遍的提示词版本。改了提示词就改它：它在指纹里，改了之后每条连接都会重问一次。 */
    static final String PROMPT_VERSION = "relation-pass-2026-10-01c";
    /** 每片交给模型的最大字符数（含目标索引、已知关系和待判列）。 */
    static final int PASS_MAX_CHARS = 60000;
    static final int PASS_MAX_TOKENS = 8000;
    /** 待判列：列名（去掉 {@code _}、转小写）以这些结尾的才像引用。 */
    static final List<String> REFERENCE_SUFFIXES = List.of("id", "code", "no", "num", "key");
    /** 一片里「已知关系」最多占多少字符：它只是给模型的上下文，不能挤掉待判列。 */
    static final int KNOWN_MAX_CHARS = 8000;
    /** 目标索引本身就很长（表多、注释长）时，每片至少还留这么多给待判列：宁可一片超一点，也不要一列一片地问。 */
    static final int MIN_CHUNK_CHARS = 4000;
    /** 每片最多问多少列：答案也要装进 {@link #PASS_MAX_TOKENS}，一片问得太多，回答会被截断。 */
    static final int MAX_CHUNK_COLUMNS = 200;
    /**
     * 模型自己报的把握低于这个数的关联不收（没报的也不收）。
     *
     * <p>这不是拿模型的自信当依据——依据仍是 NAME / COMMENT。它挡的是「占位条目」：2026-10-01 用真实库跑，模型会把找不到对象的列
     * 也写进 joins，随手指向公司代码表的主键，备注写着「库中无对应主数据表」「宁缺毋滥」「仅为占位」，把握报 0–50。
     * 高于这个数的也不全对（同一次试跑里「币种 → 公司代码」报了 70），所以这一遍的产出只进语义层当线索：
     * 星图要等采样核对通过、或业务方确认之后才画（{@code DataGraphProjector} 判定表第 7′ 行）。
     */
    static final int MIN_MODEL_CONFIDENCE = 60;

    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final ConnectorSemanticService semanticService;
    private final SemanticModelCall modelCall;

    /**
     * 跑一遍关系发现。必须在真实租户上下文里调（写库靠租户拦截器，见 {@link ConnectorSemanticService#requireOwned}）。
     *
     * @param lastPassFingerprint 上次模型那一遍的输入指纹；与这次相同就不再问模型
     */
    public Result discover(Long connectorId, String lastPassFingerprint) {
        Connection conn = semanticService.requireOwned(connectorId);
        List<ConnectorSchema> schemas = schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getConnectorId, connectorId));
        List<ConnectorSchema> objects = schemas.stream()
                .filter(r -> r != null && r.getObjectName() != null)
                .sorted(Comparator.comparing(ConnectorSchema::getObjectName))
                .toList();
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(objects);

        // ① 确定性候选
        List<Candidate> rules = RelationCandidates.of(objects);

        // ② 模型一遍：只问还没有关系、也没有规则候选的「像引用」的列
        PassInput input = passInput(objects, fields, selectSemantic(connectorId), rules);

        List<Map<String, Object>> proposed = new ArrayList<>();
        boolean asked = false;
        String modelError = null;
        if (!input.pending().isEmpty() && !input.fingerprint().equals(lastPassFingerprint)) {
            asked = true;
            try {
                proposed = askModel(connectorId, conn, input.index(), input.known(), input.pending());
            } catch (RuntimeException e) {
                modelError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("关系发现的模型那一遍失败，规则候选照常写入 connectorId={}: {}", connectorId, modelError, e);
            }
        }

        // ③ 组装：规则候选与模型产出走同一套过滤
        Map<String, String> originByColumn = new HashMap<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Candidate c : rules) {
            entries.add(ruleEntry(c));
            originByColumn.put(columnKey(c.fromTable(), c.fromColumn()), c.origin());
        }
        int accepted = 0;
        for (Map<String, Object> m : proposed) {
            entries.add(m);
            originByColumn.putIfAbsent(columnKey(SemanticRowAssembler.str(m, "object"),
                    SemanticRowAssembler.str(m, "column")), RelationCandidates.ORIGIN_RELATION_PASS);
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put(SemanticRowAssembler.KIND_JOINS, entries);
        SemanticRowAssembler.AssemblyReport report = new SemanticRowAssembler.AssemblyReport();
        List<ConnectorSemantic> rows = SemanticRowAssembler.toRows(parsed, fields, Set.of(), report, List.of());
        for (ConnectorSemantic row : rows) {
            String origin = originByColumn.get(columnKey(row.getObjectName(), row.getFieldName()));
            row.setDetailJson(withOrigin(row.getDetailJson(), origin));
            if (RelationCandidates.ORIGIN_RELATION_PASS.equals(origin)) {
                accepted++;
            }
        }

        // ④ 写入
        ConnectorSemanticService.DiscoveryWrite write =
                semanticService.mergeDiscoveredJoins(connectorId, rows, singleUniqueColumns(objects));
        // 存下的指纹按写完之后的样子算：这一遍自己写下的关系（规则候选、模型产出）下一轮会进「已知关系」、把那些列挪出待判列。
        // 按写之前的输入存，下一轮不管因为什么重跑（刷新结构、采样核对、业务方确认）都对不上，模型答过的列和没把握不答的列
        // 会被再问一遍，多出来的随机产出还要再去客户库上采样核对。模型那一遍失败时仍存上一次的，下次照样再问。
        String passFingerprint = modelError == null
                ? passInput(objects, fields, selectSemantic(connectorId), rules).fingerprint()
                : lastPassFingerprint;
        int fk = (int) rules.stream().filter(c -> RelationCandidates.ORIGIN_FK.equals(c.origin())).count();
        Result result = new Result(fk, rules.size() - fk, proposed.size(), accepted, asked, passFingerprint,
                modelError, write);
        log.info("关系发现完成 connectorId={} {}", connectorId, result.note());
        return result;
    }

    private List<ConnectorSemantic> selectSemantic(Long connectorId) {
        return semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .in(ConnectorSemantic::getScope, ConnectorSemanticService.SCOPE_OBJECT,
                        ConnectorSemanticService.SCOPE_JOIN));
    }

    /** 模型那一遍的输入：目标索引、已知关系（语义层现有的 JOIN 行 + 规则候选）、待判列，以及由这三样算出的指纹。 */
    private static PassInput passInput(List<ConnectorSchema> objects, Map<String, Map<String, FieldDetail>> fields,
                                       List<ConnectorSemantic> semantic, List<Candidate> rules) {
        Set<String> covered = new HashSet<>();
        List<String> known = new ArrayList<>();
        for (ConnectorSemantic j : semantic) {
            if (ConnectorSemanticService.SCOPE_JOIN.equals(j.getScope())) {
                covered.add(columnKey(j.getObjectName(), j.getFieldName()));
                String[] to = target(j);
                if (to != null) {
                    known.add(j.getObjectName() + "." + j.getFieldName() + " → " + to[0] + "." + to[1]);
                }
            }
        }
        for (Candidate c : rules) {
            covered.add(columnKey(c.fromTable(), c.fromColumn()));
            known.add(c.fromTable() + "." + c.fromColumn() + " → " + c.toTable() + "." + c.toColumn());
        }
        List<Pending> pending = pending(objects, fields, covered);
        String index = targetIndex(objects, semantic);
        return new PassInput(index, known, pending, fingerprint(index, known, pending));
    }

    private record PassInput(String index, List<String> known, List<Pending> pending, String fingerprint) {
    }

    // ------------------------------------------------------------------ 模型一遍

    private List<Map<String, Object>> askModel(Long connectorId, Connection conn, String index, List<String> known,
                                               List<Pending> pending) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<List<Pending>> chunks = chunks(index, pending);
        for (int i = 0; i < chunks.size(); i++) {
            List<Pending> chunk = chunks.get(i);
            Set<String> tables = new HashSet<>();
            Set<String> allowed = new HashSet<>();
            for (Pending p : chunk) {
                tables.add(p.table());
                allowed.add(p.table() + '\u0001' + p.column());
            }
            String user = userPrompt(conn, index, knownFor(known, tables), chunk, i + 1, chunks.size());
            Map<String, Object> reply = modelCall.askJson("关系发现（第 " + (i + 1) + "/" + chunks.size() + " 片）",
                    connectorId, SYSTEM_PROMPT, user, PASS_MAX_TOKENS);
            for (Map<String, Object> m : SemanticRowAssembler.arr(reply, SemanticRowAssembler.KIND_JOINS)) {
                String object = SemanticRowAssembler.str(m, "object");
                String column = SemanticRowAssembler.str(m, "column");
                // 只收这一片问到的列：模型顺手写的别的列，既没有经过「还没有关系」的筛选，也可能改写已有的关系。
                if (object == null || column == null || !allowed.contains(object + '\u0001' + column)) {
                    continue;
                }
                String toObject = SemanticRowAssembler.str(m, "to_object");
                String toColumn = SemanticRowAssembler.str(m, "to_column");
                if (object.equals(toObject) && column.equals(toColumn)) {
                    continue;
                }
                Integer confidence = SemanticRowAssembler.confidence(m);
                if (confidence == null || confidence < MIN_MODEL_CONFIDENCE) {
                    continue;
                }
                // 依据只收 NAME / COMMENT（spec §5.3）。这一遍模型没看过任何数据，写 DATA 就是编的：Agent 会读到
                // 「依据：库里的数据」，推导合并时它还会压住依据更弱的关系。GUESS、没写、写了别的，一律不收。
                String evidence = SemanticRowAssembler.evidence(m, ConnectorSemanticService.EV_GUESS);
                if (!ConnectorSemanticService.EV_NAME.equals(evidence)
                        && !ConnectorSemanticService.EV_COMMENT.equals(evidence)) {
                    continue;
                }
                out.add(m);
            }
        }
        return out;
    }

    /** 按待判列分片，每一片都带完整的目标索引。 */
    private List<List<Pending>> chunks(String index, List<Pending> pending) {
        int budget = Math.max(MIN_CHUNK_CHARS, PASS_MAX_CHARS - index.length() - KNOWN_MAX_CHARS);
        List<List<Pending>> out = new ArrayList<>();
        List<Pending> current = new ArrayList<>();
        int size = 0;
        for (Pending p : pending) {
            int line = p.line().length() + 1;
            if (!current.isEmpty() && (size + line > budget || current.size() >= MAX_CHUNK_COLUMNS)) {
                out.add(current);
                current = new ArrayList<>();
                size = 0;
            }
            current.add(p);
            size += line;
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    static final String SYSTEM_PROMPT = """
            你在帮一家企业整理他们业务数据库里「表与表之间的关联」。给你三样东西：
            1. 这个库里全部的表（表名 | 表注释 | 一句话说明 | 唯一键）；
            2. 已经知道的关联，不要重复写；
            3. 一批还没找到关联对象的列（表.列 | 列注释）。

            请判断每一个待判列是不是在引用另一张表的某一列。只输出一个 JSON 对象，不要任何别的文字：
            {"joins":[{"object":"起点表","column":"起点列","to_object":"终点表","to_column":"终点列",
            "evidence":"NAME 或 COMMENT","confidence":0到100的整数,"note":"不超过 20 个字的理由"}]}

            规则：
            - 只写你能从列名或列注释里明确看出指向的关联，宁缺毋滥。看不出来的列直接不写，不要写成低把握的条目。
            - 终点表必须就是这一列所说的那个业务对象本身（比如「付款方」「售达方」指向客户表），不能只是「有点相关」的表。
              这一列说的对象（比如工厂、币种、物料、部门、客户端）如果在清单里没有对应的表，就不要写，不要拿公司、组织这类表凑数。
            - 只为待判列写关联；表名、列名必须原样照抄给你的写法（大小写敏感），不要补前缀。
            - 终点列通常是终点表的主键或唯一键。
            - 明细表指向所属单据头的列也要找出来：表名成对出现（例如「某某明细」与「某某单据头」、XXX_DTL 与 XXX_HEAD），
              明细表里常用 SOID、POID、HEADID、BILLID 这类列指向单据头的主键；看得出是哪一列才写。
            - evidence 只能是 NAME（列名与表名的命名规律，包括表名成对出现的规律）或 COMMENT（列注释写明了指向哪里）。
              不要写 GUESS。
            - 每一列最多写一个终点；不要让一列指向它自己。
            """;

    private static String userPrompt(Connection conn, String index, String known, List<Pending> chunk,
                                     int no, int total) {
        StringBuilder s = new StringBuilder();
        s.append("连接：").append(conn.getName() == null ? "(未命名)" : conn.getName());
        if (total > 1) {
            s.append("（待判列分 ").append(total).append(" 片给你，这是第 ").append(no).append(" 片；表的清单每片都是全的）");
        }
        s.append("\n\n【全部的表】（表名 | 表注释 | 一句话说明 | 唯一键）\n").append(index);
        s.append("\n【已知关联】\n").append(known.isEmpty() ? "（无）\n" : known);
        s.append("\n【待判列】（表.列 | 列注释）\n");
        for (Pending p : chunk) {
            s.append(p.line()).append('\n');
        }
        return s.toString();
    }

    /** 只带和这一片待判列同表的已知关联，而且有上限：它是上下文，不能挤掉待判列。 */
    private static String knownFor(List<String> known, Set<String> tables) {
        StringBuilder s = new StringBuilder();
        for (String k : known) {
            String from = k.substring(0, Math.max(0, k.indexOf('.')));
            if (!tables.contains(from)) {
                continue;
            }
            if (s.length() + k.length() + 1 > KNOWN_MAX_CHARS) {
                break;
            }
            s.append(k).append('\n');
        }
        return s.toString();
    }

    /** 全部表的目标索引：表名 | 表注释（去掉平台加的估算行数）| 语义层一句话说明 | 唯一键。 */
    private static String targetIndex(List<ConnectorSchema> objects, List<ConnectorSemantic> semantic) {
        Map<String, String> gloss = new HashMap<>();
        for (ConnectorSemantic s : semantic) {
            if (ConnectorSemanticService.SCOPE_OBJECT.equals(s.getScope()) && s.getGloss() != null) {
                gloss.putIfAbsent(fold(s.getObjectName()), ConnectorSemanticService.shortGloss(s.getGloss()));
            }
        }
        StringBuilder s = new StringBuilder();
        for (ConnectorSchema r : objects) {
            s.append(r.getObjectName()).append(" | ")
                    .append(oneLine(RowEstimateNote.strip(r.getObjectComment()), 40)).append(" | ")
                    .append(oneLine(gloss.get(fold(r.getObjectName())), 80)).append(" | ")
                    .append(keysText(SemanticTableRenderer.namedUniqueKeys(r))).append('\n');
        }
        return s.toString();
    }

    private static String keysText(List<NamedKey> keys) {
        if (keys == null) {
            return "未知";
        }
        if (keys.isEmpty()) {
            return "无";
        }
        List<String> out = new ArrayList<>();
        for (NamedKey k : keys) {
            out.add((k.primary() ? "主键" : "唯一") + "(" + String.join(",", k.columns()) + ")");
        }
        return String.join(" ", out);
    }

    /** 待判列：像引用（名字以 id/code/no/num/key 结尾）、不是本表的单列唯一键、还没有关系、也没有规则候选。 */
    private static List<Pending> pending(List<ConnectorSchema> objects, Map<String, Map<String, FieldDetail>> fields,
                                         Set<String> covered) {
        List<Pending> out = new ArrayList<>();
        for (ConnectorSchema r : objects) {
            Set<String> ownKeys = new HashSet<>();
            List<NamedKey> keys = SemanticTableRenderer.namedUniqueKeys(r);
            if (keys != null) {
                for (NamedKey k : keys) {
                    if (k.columns().size() == 1) {
                        ownKeys.add(fold(k.columns().get(0)));
                    }
                }
            }
            for (FieldDetail f : fields.getOrDefault(r.getObjectName(), Map.of()).values()) {
                String n = RelationCandidates.norm(f.name());
                if (REFERENCE_SUFFIXES.stream().noneMatch(n::endsWith) || ownKeys.contains(fold(f.name()))
                        || covered.contains(columnKey(r.getObjectName(), f.name()))) {
                    continue;
                }
                out.add(new Pending(r.getObjectName(), f.name(),
                        r.getObjectName() + "." + f.name() + " | " + oneLine(f.comment(), 60)));
            }
        }
        return out;
    }

    private static String fingerprint(String index, List<String> known, List<Pending> pending) {
        StringBuilder s = new StringBuilder(PROMPT_VERSION).append('\n').append(index).append('\n');
        known.stream().sorted().forEach(k -> s.append(k).append('\n'));
        s.append('\n');
        for (Pending p : pending) {
            s.append(p.line()).append('\n');
        }
        return ConnectorSemanticService.sha256(s.toString());
    }

    // ------------------------------------------------------------------ 组装

    /** 规则候选写成与模型产出同形的条目：外键是客户写在库里的约束（COMMENT），命名规律是名字像（NAME）。 */
    private static Map<String, Object> ruleEntry(Candidate c) {
        boolean fk = RelationCandidates.ORIGIN_FK.equals(c.origin());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("object", c.fromTable());
        m.put("column", c.fromColumn());
        m.put("to_object", c.toTable());
        m.put("to_column", c.toColumn());
        m.put("cardinality", "N:1");
        m.put("evidence", fk ? ConnectorSemanticService.EV_COMMENT : ConnectorSemanticService.EV_NAME);
        m.put("confidence", fk ? 95 : 80);
        m.put("note", fk ? "数据库里声明了这条外键" : "按列名与表名的命名规律推出");
        return m;
    }

    @SuppressWarnings("unchecked")
    private static String withOrigin(String detailJson, String origin) {
        if (origin == null) {
            return detailJson;
        }
        try {
            Map<String, Object> d = new LinkedHashMap<>(
                    CommonUtil.getObjectMapper().readValue(detailJson, Map.class));
            d.put(ConnectorSemanticService.KEY_ORIGIN, origin);
            String json = SemanticRowAssembler.toJson(d);
            return json == null ? detailJson : json;
        } catch (Exception e) {
            return detailJson;
        }
    }

    /** 快照里每张表的单列唯一键（表名、列名折叠过）。唯一键未知的表不进来：写入侧按「终点不是唯一键」处理。 */
    private static Map<String, Set<String>> singleUniqueColumns(List<ConnectorSchema> objects) {
        Map<String, Set<String>> out = new HashMap<>();
        for (ConnectorSchema r : objects) {
            List<NamedKey> keys = SemanticTableRenderer.namedUniqueKeys(r);
            Set<String> cols = new LinkedHashSet<>();
            if (keys != null) {
                for (NamedKey k : keys) {
                    if (k.columns().size() == 1) {
                        cols.add(fold(k.columns().get(0)));
                    }
                }
            }
            out.put(fold(r.getObjectName()), cols);
        }
        return out;
    }

    private static String[] target(ConnectorSemantic j) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> d = CommonUtil.getObjectMapper()
                    .readValue(j.getDetailJson(), Map.class);
            String to = SemanticRowAssembler.str(d, ConnectorSemanticService.KEY_TO_OBJECT);
            String col = SemanticRowAssembler.str(d, ConnectorSemanticService.KEY_TO_COLUMN);
            return to == null || col == null ? null : new String[]{to, col};
        } catch (Exception e) {
            return null;
        }
    }

    private static String columnKey(String table, String column) {
        return fold(table) + '\u0001' + fold(column);
    }

    private static String oneLine(String s, int max) {
        if (s == null || s.isBlank()) {
            return "";
        }
        String t = SemanticRowAssembler.nz(s).trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private record Pending(String table, String column, String line) {
    }

    /**
     * 一次关系发现的结果。
     *
     * @param passFingerprint 下次比对用的模型输入指纹；模型那一遍失败时仍是上一次的（这样下次照样会再问）
     * @param modelError      模型那一遍失败的原因；{@code null} = 没失败（含「没问」）
     */
    public record Result(int foreignKeyCandidates, int nameRuleCandidates, int modelProposed, int modelAccepted,
                         boolean modelAsked, String passFingerprint, String modelError,
                         ConnectorSemanticService.DiscoveryWrite write) {

        /** 写进 {@code connector_enrichment_state.relation_note} 的一句话。只给排查用，不上屏。 */
        public String note() {
            StringBuilder s = new StringBuilder("新增 ").append(write.inserted()).append(" 条、替换 ")
                    .append(write.replaced()).append(" 条；候选：外键 ").append(foreignKeyCandidates)
                    .append("、命名规律 ").append(nameRuleCandidates).append("、模型 ").append(modelAccepted);
            if (!modelAsked) {
                s.append("（模型的输入没变，这次没问）");
            }
            if (modelError != null) {
                s.append("；模型那一遍失败：").append(modelError);
            }
            return s.toString();
        }
    }
}
