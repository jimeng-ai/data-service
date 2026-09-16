package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 规则 3：声明了「1」端，那一端就必须是单列主键或单列唯一键（设计文档 7.9）。
 *
 * <h3>判定</h3>
 * {@code N:1} 看右端，{@code 1:N} 看左端，{@code 1:1} 两端都看。基数没写、认不出（toRows 会落成 null）或 {@code N:N}：通过。
 * 那张表的唯一键<b>未知</b>（旧快照没读到索引）：通过；唯一键是<b>空列表</b>（确实没有）：退回。
 * 列名按大小写不敏感比对唯一键，与 {@code SemanticJoinValidator.compositeKeyFor} 同口径。
 *
 * <h3>为什么「不知道」要放行</h3>
 * 基数是决定一条关系能不能自动接进 join path 的那一项，声明错了 SUM 会凭空变大；但旧快照上的表几乎全都「不知道唯一键」，
 * 按不知道退回等于在刷新结构之前一条 N:1 都交不上。子项目 2 评估改为「降置信度」（设计 11.1）。
 *
 * <p>依据：飞书定稿 §2 JOIN 的 cardinality、§3 S3 主键判定与「N:N 只标注」；{@code SemanticJoinValidator} 组合键判定。
 * 括号里的唯一键文本直接取 {@link RuleContext#uniqueKeyTextOf}，与 table-metadata 给模型看的那一行同源，不在规则里再解析。
 */
@Component
@Order(300)
public class JoinOneSideUniqueRule implements SemanticConsistencyRule {

    public static final String CODE = "JOIN_ONE_SIDE_NOT_UNIQUE";

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
        return "声明 N:1、1:N 或 1:1 时，「1」端必须是单列主键或唯一键";
    }

    @Override
    public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
        String card = entry.cardinality();
        if (card == null || "N:N".equals(card)) {
            return List.of();
        }
        String obj = ctx.objectOf(entry);
        String col = entry.str("column");
        String toObj = entry.str("to_object");
        String toCol = entry.str("to_column");
        if (ctx.column(obj, col) == null || ctx.column(toObj, toCol) == null) {
            return List.of();   // 端点对不上是规则 1 的事
        }
        String arrow = RuleText.arrow(obj, col, toObj, toCol);
        List<RuleViolation> out = new ArrayList<>(2);
        if (("1:N".equals(card) || "1:1".equals(card)) && notUnique(ctx, obj, col)) {
            out.add(RuleViolation.reject("关系 " + arrow + " 声明为 " + card + "，「1」端在左边，但 " + obj + "." + col
                    + " 不是单列主键或唯一键" + keysNote(ctx, obj) + "。请先确认方向（外键列所在的一边通常是 N 端，应写 N:1）；"
                    + "或把 cardinality 改为 N:N 或不写，并在 note 说明依据。"));
        }
        if (("N:1".equals(card) || "1:1".equals(card)) && notUnique(ctx, toObj, toCol)) {
            out.add(RuleViolation.reject("关系 " + arrow + " 声明为 " + card + "，但 " + toObj + "." + toCol
                    + " 不是单列主键或唯一键" + keysNote(ctx, toObj) + "。请改为指向 " + toObj
                    + " 的单列唯一键；或把 cardinality 改为 N:N 或不写，并在 note 说明依据。"));
        }
        return out;
    }

    /** 唯一键已知、且没有一个是恰好这一列的单列键。未知返回 false（放行）。 */
    private static boolean notUnique(RuleContext ctx, String object, String column) {
        List<List<String>> keys = ctx.uniqueKeysByObject().get(object);
        if (keys == null) {
            return false;
        }
        for (List<String> k : keys) {
            if (k != null && k.size() == 1 && column.equalsIgnoreCase(k.get(0))) {
                return false;
            }
        }
        return true;
    }

    /** （B 的唯一键：PRIMARY(id)）；没有唯一键时（B：本表没有主键或唯一键）。 */
    private static String keysNote(RuleContext ctx, String object) {
        List<List<String>> keys = ctx.uniqueKeysByObject().get(object);
        String text = ctx.uniqueKeyTextOf(object);
        return keys == null || keys.isEmpty()
                ? "（" + object + "：" + text + "）"
                : "（" + object + " 的唯一键：" + text + "）";
    }
}
