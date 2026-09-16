package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.generation.SemanticTableRenderer;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import com.jimeng.persistence.entity.ConnectorSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一致性规则看得到的只读上下文（设计文档 7.9）。全部来自<b>同一份</b>结构快照和平台自己的库，不碰客户库、不含任何数据值。
 *
 * @param table                 本次提交的表（principal 与 B 步已保证它在快照里）
 * @param fieldsByObject        整个快照的列，来自 {@link SemanticRowAssembler#parseFields}：表名大小写敏感，没有列的表是空 Map
 * @param uniqueKeysByObject    来自 {@link SemanticJoinValidator#uniqueKeysByObject}：表不在 Map 里 = 唯一键未知，空列表 = 没有唯一键；
 *                              只有列清单，不含键名与是否主键
 * @param uniqueKeyTextByObject 表名 → 唯一键那一行的文本，由 {@link SemanticTableRenderer#uniqueKeyText} 生成，
 *                              与 table-metadata 给模型看的那一行同一段代码，规则文案里原样引用
 * @param objectComments        表名 → 快照里的表注释原文（可能带平台追加的估算行数，用 {@link #objectCommentOf} 取去掉之后的）
 * @param answeredTerms         已答口径，折叠口径同 {@link SemanticRowAssembler#answeredTerms}
 * @param submission            本次提交的全部条目（批内规则要用）
 */
public record RuleContext(String table,
                          Map<String, Map<String, FieldDetail>> fieldsByObject,
                          Map<String, List<List<String>>> uniqueKeysByObject,
                          Map<String, String> uniqueKeyTextByObject,
                          Map<String, String> objectComments,
                          Set<String> answeredTerms,
                          List<ProposedEntry> submission) {

    /**
     * MySQL 连接器把 {@code TABLE_ROWS} 估算值追加在表注释末尾（{@code MySqlSession} 列目录那一段，
     * 原文「（约 N 行，InnoDB 估算值，不可当作准确计数）」）。这段是<b>平台</b>写的，不是客户的注释：
     * 不去掉它，一张没写注释的表会被当成「有注释」，evidence=COMMENT 的判定就失效了。
     * 这里不 import 连接器实现（语义层不该依赖某一个连接器），按文案形状匹配，改那边的措辞要同步这里。
     */
    private static final Pattern ROW_ESTIMATE_SUFFIX = Pattern.compile("\\s*（约\\s*\\d+\\s*行，InnoDB\\s*估算值[^）]*）\\s*$");

    /** 从快照行装出上下文。四张 Map 都保持快照顺序。 */
    public static RuleContext of(String table, List<ConnectorSchema> snapshot, Set<String> answeredTerms,
                                 List<ProposedEntry> submission) {
        Map<String, String> comments = new LinkedHashMap<>();
        for (ConnectorSchema r : snapshot) {
            if (r != null && r.getObjectName() != null) {
                comments.put(r.getObjectName(), r.getObjectComment());
            }
        }
        return new RuleContext(table,
                SemanticRowAssembler.parseFields(snapshot),
                SemanticJoinValidator.uniqueKeysByObject(snapshot),
                SemanticTableRenderer.uniqueKeyTextByObject(snapshot),
                comments,
                answeredTerms == null ? Set.of() : answeredTerms,
                submission == null ? List.of() : submission);
    }

    /** 表在快照里（大小写敏感，与 toRows 同口径），不论有没有列。 */
    public boolean hasTable(String object) {
        return object != null && fieldsByObject.containsKey(object);
    }

    /** 表的列；表不在快照里时为空 Map。 */
    public Map<String, FieldDetail> columnsOf(String object) {
        Map<String, FieldDetail> cols = object == null ? null : fieldsByObject.get(object);
        return cols == null ? Map.of() : cols;
    }

    /** 精确查列（大小写敏感）；查不到为 {@code null}。 */
    public FieldDetail column(String object, String column) {
        return column == null ? null : columnsOf(object).get(column);
    }

    /**
     * 条目的左表：fields / joins 用条目自己的 {@code object}（提交流水线 C 步已补成本表），没有时退回 {@link #table}。
     * OBJECT 条目用 {@code name}。
     */
    public String objectOf(ProposedEntry entry) {
        String own = SemanticRowAssembler.KIND_OBJECTS.equals(entry.kind()) ? entry.str("name") : entry.str("object");
        return own != null ? own : table;
    }

    /** 表注释，去掉平台追加的估算行数；没有注释为 {@code null}。 */
    public String objectCommentOf(String object) {
        String c = object == null ? null : objectComments.get(object);
        if (c == null) {
            return null;
        }
        String stripped = ROW_ESTIMATE_SUFFIX.matcher(c).replaceFirst("").trim();
        return stripped.isEmpty() ? null : stripped;
    }

    /** 唯一键文本，三态之一；快照里没有这张表时按「未知」说。 */
    public String uniqueKeyTextOf(String object) {
        String t = object == null ? null : uniqueKeyTextByObject.get(object);
        return t == null ? SemanticTableRenderer.UNIQUE_KEYS_UNKNOWN : t;
    }

    /**
     * 快照里与 {@code name} 只差大小写或反引号 / 双引号的表名（快照的写法）；没有为 {@code null}。
     * 只用于文案提示「快照里的写法是……」，判定本身始终大小写敏感。
     */
    public String suggestTable(String name) {
        String want = unquote(name);
        if (want == null) {
            return null;
        }
        for (String t : fieldsByObject.keySet()) {
            if (t.equalsIgnoreCase(want) && !t.equals(name)) {
                return t;
            }
        }
        return null;
    }

    /**
     * 与 {@code object.column} 只差大小写或引号的快照写法，形如 {@code users.ID}；表名本身也可以只差大小写。没有为 {@code null}。
     */
    public String suggestColumn(String object, String column) {
        String want = unquote(column);
        if (want == null) {
            return null;
        }
        String t = hasTable(object) ? object : suggestTable(object);
        if (t == null) {
            return null;
        }
        for (String c : columnsOf(t).keySet()) {
            if (c.equalsIgnoreCase(want) && !(t.equals(object) && c.equals(column))) {
                return t + "." + c;
            }
        }
        return null;
    }

    private static String unquote(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() >= 2 && (t.startsWith("`") && t.endsWith("`") || t.startsWith("\"") && t.endsWith("\""))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t.isEmpty() ? null : t;
    }
}
