package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 规则 4：「1」端的列只是组合唯一键的一员、自己没有单列唯一键时，不能当单列关系提交（设计文档 7.9）。
 *
 * <h3>为什么一律退回，而不是标个「复合键」照样入库</h3>
 * {@code order_item.shop_code → shop.code}，而 shop 的唯一键是 {@code (tenant_id, code)}：单独用 code join，
 * 一行订单明细会连出每个租户下同 code 的门店，金额被放大，而且不报错。飞书定稿 §4「复合键关系不注入」；
 * {@code MySqlSession} 在组合键成员列的 extra 上写「组合键成员，单独这一列可能重复」就是为了这件事。
 * 正确的写法是把「要同时匹配哪几列」写进本表那一列的字段说明。子项目 2 评估改为「保留行、标复合键、不注入」（设计 11.1）。
 *
 * <h3>判定</h3>
 * 看「1」端：{@code N:1} 看右端，{@code 1:N} 看左端，{@code 1:1} 两端都看，<b>没写基数按右端看</b>（外键式的关系默认指向右端的键），
 * {@code N:N} 没有「1」端、放行。组合键成员的判定直接调 {@link SemanticJoinValidator#compositeKeyFor}，与采样验证的结构判定同一个口径；
 * 唯一键未知时它返回 null，放行。
 */
@Component
@Order(400)
public class JoinCompositeMemberRule implements SemanticConsistencyRule {

    public static final String CODE = "JOIN_COMPOSITE_MEMBER";

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
        return "「1」端的列若只是组合唯一键的一员，不能当单列关系提交，改写进本表列的字段说明";
    }

    @Override
    public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
        String card = entry.cardinality();
        if ("N:N".equals(card)) {
            return List.of();
        }
        String obj = ctx.objectOf(entry);
        String col = entry.str("column");
        String toObj = entry.str("to_object");
        String toCol = entry.str("to_column");
        if (ctx.column(obj, col) == null || ctx.column(toObj, toCol) == null) {
            return List.of();
        }
        List<RuleViolation> out = new ArrayList<>(2);
        if ("1:N".equals(card) || "1:1".equals(card)) {
            member(ctx, obj, col, obj, col, out);
        }
        if (card == null || "N:1".equals(card) || "1:1".equals(card)) {
            member(ctx, toObj, toCol, obj, col, out);
        }
        return out;
    }

    private static void member(RuleContext ctx, String object, String column, String ownObject, String ownColumn,
                               List<RuleViolation> out) {
        List<String> composite = SemanticJoinValidator.compositeKeyFor(ctx.uniqueKeysByObject().get(object), column);
        if (composite == null) {
            return;
        }
        out.add(RuleViolation.reject(object + "." + column + " 只是组合唯一键 (" + String.join(", ", composite)
                + ") 的一员，单独用这一列 join 会一行连出多行，金额被放大且不报错。不要把它当单列关系提交；"
                + "如果确有对应，写进 " + ownObject + "." + ownColumn + " 的字段说明，说明要同时匹配 "
                + RuleText.joinAnd(composite) + "。"));
    }
}
