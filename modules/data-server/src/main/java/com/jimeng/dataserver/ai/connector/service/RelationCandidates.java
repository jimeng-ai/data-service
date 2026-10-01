package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer;
import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer.NamedKey;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.persistence.entity.ConnectorSchema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 关系发现的确定性候选（数据星图设计 v3 §5.2）：声明的外键 + 通用命名规律。
 *
 * <p>纯函数：不查库、不调模型，同样的快照永远得到同样的候选（顺序也一样）。<b>不写死任何表名或业务词</b>：
 * 规则只看「列名去掉 {@code id} 后的词干」和「别的表名的后缀」之间的字面关系，换一个行业的库照样成立。
 *
 * <p>2026-10-01 用本地两个系统的真实快照实测：一个库推出 33 条、没有一条多余，另一个推出 16 条。
 * 单测里的同形夹具就是这两个库按固定词表换了名字的版本，见 {@code RelationRuleFixtures}。
 */
public final class RelationCandidates {

    /** 快照 {@code detail_json.extra} 里声明外键的键名。与 {@code MySqlSession.EXTRA_FOREIGN_KEYS} 刻意同名，由单测钉住。 */
    public static final String EXTRA_FOREIGN_KEYS = "foreign_keys";

    /** JOIN 行 {@code detail_json.origin} 的取值：这条关系是谁提出来的。 */
    public static final String ORIGIN_FK = "FK";
    public static final String ORIGIN_NAME_RULE = "NAME_RULE";
    public static final String ORIGIN_RELATION_PASS = "RELATION_PASS";

    /** 词干（列名去掉 {@code id} 之后）至少几个字符：{@code OID}、{@code SOID} 这种短名字什么都说明不了。 */
    static final int MIN_STEM = 4;
    /** 对上的表名后缀至少几个字符：{@code org}、{@code dtl} 这种短后缀会到处误连。 */
    static final int MIN_SUFFIX = 4;

    private static final Comparator<Candidate> ORDER = Comparator.comparing(Candidate::fromTable)
            .thenComparing(Candidate::fromColumn);

    private RelationCandidates() {
    }

    /** 一条候选关系。{@code origin} 是 {@link #ORIGIN_FK} 或 {@link #ORIGIN_NAME_RULE}。 */
    public record Candidate(String fromTable, String fromColumn, String toTable, String toColumn, String origin) {
    }

    /**
     * 全部确定性候选：同一列既有声明外键又有命名规律时，<b>外键优先</b>（客户自己写下的约束压过名字像）。
     * 按起点表、起点列排序。
     */
    public static List<Candidate> of(List<ConnectorSchema> schemas) {
        Map<String, Candidate> byColumn = new LinkedHashMap<>();
        for (Candidate c : foreignKeys(schemas)) {
            byColumn.putIfAbsent(columnKey(c.fromTable(), c.fromColumn()), c);
        }
        for (Candidate c : namingRule(schemas)) {
            byColumn.putIfAbsent(columnKey(c.fromTable(), c.fromColumn()), c);
        }
        List<Candidate> out = new ArrayList<>(byColumn.values());
        out.sort(ORDER);
        return out;
    }

    /**
     * 声明的外键，只取单列外键。组合外键写不成一条「这一列指向那一列」的关系，交给语义层自己的组合键处理；
     * 被引用的表或列不在快照里（快照在 200 个对象处截断过）的丢掉。
     */
    public static List<Candidate> foreignKeys(List<ConnectorSchema> schemas) {
        List<Table> tables = tables(schemas);
        Map<String, Table> byName = new LinkedHashMap<>();
        for (Table t : tables) {
            byName.putIfAbsent(fold(t.name()), t);
        }
        List<Candidate> out = new ArrayList<>();
        for (Table t : tables) {
            for (Map<String, Object> fk : t.foreignKeys()) {
                List<String> cols = strings(fk.get("columns"));
                List<String> refCols = strings(fk.get("ref_columns"));
                Object refTable = fk.get("ref_table");
                if (cols.size() != 1 || refCols.size() != 1 || refTable == null) {
                    continue;
                }
                Table target = byName.get(fold(String.valueOf(refTable)));
                String from = t.column(cols.get(0));
                String to = target == null ? null : target.column(refCols.get(0));
                if (from != null && to != null) {
                    out.add(new Candidate(t.name(), from, target.name(), to, ORIGIN_FK));
                }
            }
        }
        out.sort(ORDER);
        return out;
    }

    /**
     * 命名规律。同时满足以下条件的列提出一条候选（设计文档 §5.2 第 2 条）：
     * <ol>
     *   <li>列名去掉 {@code _}、转小写后以 {@code id} 结尾，去掉这个 {@code id} 的词干至少 {@value #MIN_STEM} 个字符；</li>
     *   <li>这一列本身不是本表的单列唯一键（{@code PROJECTID} 在项目表里是项目自己的编号，不是指向谁）；</li>
     *   <li>另一张表（不含本表）的某个表名后缀等于词干、或是词干的结尾，后缀至少 {@value #MIN_SUFFIX} 个字符；
     *       以 {@code s} 结尾的后缀，去掉 {@code s} 的形式也参与比较；</li>
     *   <li>多张表都对得上时取对上的后缀最长的那张，<b>最长的并列就放弃</b>，留给模型那一遍；</li>
     *   <li>目标列取那张表的单列主键；没有主键时取它唯一的那个单列唯一键；都没有（含唯一键未知）就放弃。</li>
     * </ol>
     * 规则不连本表：试过允许连本表，会冒出「项目表的 PROJECTID 指向项目表」这类误报。自关联只来自语义层和模型那一遍。
     */
    public static List<Candidate> namingRule(List<ConnectorSchema> schemas) {
        List<Table> tables = tables(schemas);
        List<Candidate> out = new ArrayList<>();
        for (Table t : tables) {
            Set<String> ownKeys = singleColumnKeys(t);
            for (String column : t.columns()) {
                String n = norm(column);
                if (!n.endsWith("id") || n.length() - 2 < MIN_STEM || ownKeys.contains(fold(column))) {
                    continue;
                }
                String stem = n.substring(0, n.length() - 2);
                Table best = null;
                int bestLength = 0;
                boolean tie = false;
                for (Table other : tables) {
                    if (other == t) {
                        continue;
                    }
                    int length = longestMatch(stem, other.suffixes());
                    if (length == 0) {
                        continue;
                    }
                    if (length > bestLength) {
                        best = other;
                        bestLength = length;
                        tie = false;
                    } else if (length == bestLength && other != best) {
                        tie = true;
                    }
                }
                if (best == null || tie) {
                    continue;
                }
                String target = targetColumn(best);
                if (target != null) {
                    out.add(new Candidate(t.name(), column, best.name(), target, ORIGIN_NAME_RULE));
                }
            }
        }
        out.sort(ORDER);
        return out;
    }

    /** 表名按 {@code _} 切开后的每个后缀（去掉 {@code _}、转小写），不足 {@value #MIN_SUFFIX} 个字符的不要。 */
    static Set<String> suffixes(String table) {
        String[] parts = table.split("_", -1);
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < parts.length; i++) {
            String suffix = norm(String.join("_", Arrays.copyOfRange(parts, i, parts.length)));
            if (suffix.length() >= MIN_SUFFIX) {
                out.add(suffix);
            }
            if (suffix.endsWith("s") && suffix.length() - 1 >= MIN_SUFFIX) {
                out.add(suffix.substring(0, suffix.length() - 1));
            }
        }
        return out;
    }

    private static int longestMatch(String stem, Set<String> suffixes) {
        int best = 0;
        for (String s : suffixes) {
            if ((stem.equals(s) || stem.endsWith(s)) && s.length() > best) {
                best = s.length();
            }
        }
        return best;
    }

    /** 单列主键；没有主键时取唯一的那个单列唯一键；都没有、有好几个、或唯一键未知时为 {@code null}。 */
    private static String targetColumn(Table t) {
        if (t.keys() == null) {
            return null;
        }
        List<String> singles = new ArrayList<>();
        for (NamedKey k : t.keys()) {
            if (k.columns().size() != 1) {
                continue;
            }
            if (k.primary()) {
                return k.columns().get(0);
            }
            singles.add(k.columns().get(0));
        }
        return singles.size() == 1 ? singles.get(0) : null;
    }

    /** 本表的单列唯一键（折叠过大小写）。唯一键未知时为空：宁可多提一条交给采样核对，也不凭空认定它是主键。 */
    private static Set<String> singleColumnKeys(Table t) {
        Set<String> out = new HashSet<>();
        if (t.keys() != null) {
            for (NamedKey k : t.keys()) {
                if (k.columns().size() == 1) {
                    out.add(fold(k.columns().get(0)));
                }
            }
        }
        return out;
    }

    /** 列名 / 表名的比较形态：去掉 {@code _}、转小写。 */
    static String norm(String s) {
        return s.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String fold(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String columnKey(String table, String column) {
        return fold(table) + '\u0001' + fold(column);
    }

    // ------------------------------------------------------------------ 快照解析

    private static List<Table> tables(List<ConnectorSchema> schemas) {
        List<ConnectorSchema> rows = (schemas == null ? List.<ConnectorSchema>of() : schemas).stream()
                .filter(r -> r != null && r.getObjectName() != null)
                .sorted(Comparator.comparing(ConnectorSchema::getObjectName))
                .toList();
        Map<String, Map<String, FieldDetail>> fields = SemanticRowAssembler.parseFields(rows);
        List<Table> out = new ArrayList<>(rows.size());
        for (ConnectorSchema r : rows) {
            Map<String, FieldDetail> cols = fields.getOrDefault(r.getObjectName(), Map.of());
            if (cols.isEmpty()) {
                continue;
            }
            out.add(new Table(r.getObjectName(), List.copyOf(cols.keySet()),
                    SemanticTableRenderer.namedUniqueKeys(r), foreignKeysOf(r), suffixes(r.getObjectName())));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> foreignKeysOf(ConnectorSchema row) {
        if (row.getDetailJson() == null || row.getDetailJson().isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(row.getDetailJson(), Map.class);
            if (!(m.get("extra") instanceof Map<?, ?> extra) || !(extra.get(EXTRA_FOREIGN_KEYS) instanceof List<?> list)) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> fk) {
                    out.add((Map<String, Object>) fk);
                }
            }
            return out;
        } catch (Exception e) {
            // 单张表的快照坏了只让它没有外键候选，不影响别的表。
            return List.of();
        }
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object e : list) {
            if (e == null) {
                return List.of();
            }
            out.add(String.valueOf(e));
        }
        return out;
    }

    /**
     * @param keys {@code null} = 唯一键未知（旧快照没有 {@code extra.unique_keys}）
     */
    private record Table(String name, List<String> columns, List<NamedKey> keys,
                         List<Map<String, Object>> foreignKeys, Set<String> suffixes) {

        /** 按快照里的写法取列名；大小写不一致时按折叠形态兜底。找不到为 {@code null}。 */
        String column(String name) {
            for (String c : columns) {
                if (c.equals(name)) {
                    return c;
                }
            }
            for (String c : columns) {
                if (fold(c).equals(fold(name))) {
                    return c;
                }
            }
            return null;
        }
    }
}
