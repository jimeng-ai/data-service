package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 规则 1：关系两端必须在快照里<b>原样</b>存在，目标表还得有列结构（设计文档 7.9）。
 *
 * <h3>toRows 本来就会丢，为什么还要一条规则</h3>
 * toRows 只说「名字对不上结构」，agent 不知道是哪一端、差在哪。这里把它说成能照着改的话：缺的是哪一列、
 * 快照里的写法是什么（大小写或反引号之差）、或者目标表只有表名没有列——后者 toRows 同样会丢，但原因完全不同，
 * agent 该做的是换一个目标，而不是去改拼写。依据：飞书定稿 §3 S2「名字原样照抄」；{@code SemanticPrompts} 结尾那句
 * 「表名和列名必须原样照抄」；{@code SemanticJoinValidator.checkIdentifiers} 同一判据。
 *
 * <p>判定始终大小写敏感（与 toRows 一致）：MySQL 列名本身大小写不敏感，但快照是按原样存的，
 * 锚点与注入都按原样匹配，差一个大小写就对不上。
 */
@Component
@Order(100)
public class JoinEndpointExistsRule implements SemanticConsistencyRule {

    public static final String CODE = "JOIN_ENDPOINT_MISSING";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(SemanticRowAssembler.KIND_JOINS);
    }

    @Override
    public String summary() {
        return "关系两端的表名、列名必须在快照里原样存在（大小写敏感），目标表必须有列结构";
    }

    @Override
    public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
        String col = entry.str("column");
        String toObj = entry.str("to_object");
        String toCol = entry.str("to_column");
        if (col == null || toObj == null || toCol == null) {
            return List.of();   // 缺必填由 toRows 报 MISSING_REQUIRED
        }
        String obj = ctx.objectOf(entry);
        String arrow = RuleText.arrow(obj, col, toObj, toCol);
        List<RuleViolation> out = new ArrayList<>(2);

        if (ctx.column(obj, col) == null) {
            out.add(RuleViolation.reject("关系 " + arrow + " 被退回：快照里没有列 " + obj + "." + col + "。"
                    + spelling(ctx.suggestColumn(obj, col))
                    + "可以用 get_table_metadata 查看 " + obj + " 的列。"));
        }

        if (!ctx.hasTable(toObj)) {
            out.add(RuleViolation.reject("关系 " + arrow + " 被退回：快照里没有表 " + toObj + "。"
                    + spelling(ctx.suggestTable(toObj))
                    + "可以用 list_tables 查找表名。"));
        } else if (ctx.columnsOf(toObj).isEmpty()) {
            out.add(RuleViolation.reject("关系 " + arrow + " 被退回：" + toObj
                    + " 只有表名，没有列结构，不能作为关系目标。"));
        } else if (ctx.column(toObj, toCol) == null) {
            out.add(RuleViolation.reject("关系 " + arrow + " 被退回：快照里没有列 " + toObj + "." + toCol + "。"
                    + spelling(ctx.suggestColumn(toObj, toCol))
                    + "可以用 get_table_metadata 查看 " + toObj + " 的列。"));
        }
        return out;
    }

    private static String spelling(String snapshotSpelling) {
        return snapshotSpelling == null ? "" : "快照里的写法是 " + snapshotSpelling + "，表名列名必须原样照抄。";
    }
}
