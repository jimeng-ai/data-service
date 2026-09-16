package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 规则 2：关系两端的类型要兼容（设计文档 7.9）。
 *
 * <h3>判定</h3>
 * 两端按 {@link TypeFamily} 归族。通过：同族；整数与定点、整数与 bit 之间；任一端未知。
 * 字符与整数之间<b>只在 evidence=COMMENT 时</b>通过——客户注释明写了「这一列存的是 users.id」才算数，
 * 名字像不算：{@code order_no varchar} 对 {@code orders.id bigint} 是最常见的一类「看名字像、其实连不上」。
 * 其余组合一律退回。
 *
 * <p>依据：飞书定稿 §3 S3「验收取向刻意偏向 P 高于 R」、§4「不确定的关系不注入」。第 1 档不读数据值，
 * 类型是少数几个能在本地确定地否掉一条关系的信号。
 *
 * <p>任一端列不在快照里时放行：那是规则 1 与 toRows 的事，这里再退回一遍只是重复。
 */
@Component
@Order(200)
public class JoinTypeCompatibleRule implements SemanticConsistencyRule {

    public static final String CODE = "JOIN_TYPE_INCOMPATIBLE";

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
        return "关系两端类型要同族（整数与定点、bit 互通）；字符对整数只在 evidence=COMMENT 且注释写明时允许";
    }

    @Override
    public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
        String obj = ctx.objectOf(entry);
        String col = entry.str("column");
        String toObj = entry.str("to_object");
        String toCol = entry.str("to_column");
        FieldDetail left = ctx.column(obj, col);
        FieldDetail right = ctx.column(toObj, toCol);
        if (left == null || right == null) {
            return List.of();
        }
        TypeFamily lf = TypeFamily.of(left.type());
        TypeFamily rf = TypeFamily.of(right.type());
        if (compatible(lf, rf, entry.evidence())) {
            return List.of();
        }
        String a = obj + "." + col + "（" + left.type().trim() + "）";
        String b = toObj + "." + toCol + "（" + right.type().trim() + "）";
        if (pair(lf, rf, TypeFamily.STRING, TypeFamily.INTEGER)) {
            return List.of(RuleViolation.reject("关系 " + a + "→ " + b + "类型不兼容。只有列注释明确写了 "
                    + obj + "." + col + " 存的是 " + toObj + "." + toCol
                    + " 时才能提交，此时 evidence 标 COMMENT 并在 note 引用注释原文；否则不要提交这条关系。"));
        }
        return List.of(RuleViolation.reject("关系 " + a + "→ " + b
                + "类型不兼容：两端存的不是同一类值，连不上。不要提交这条关系。"));
    }

    /** 兼容表。改它之前先想清楚：多放行一种组合，就多一类「看名字像」的关系会被当成事实注入。 */
    static boolean compatible(TypeFamily a, TypeFamily b, String evidence) {
        if (a == TypeFamily.UNKNOWN || b == TypeFamily.UNKNOWN || a == b) {
            return true;
        }
        if (pair(a, b, TypeFamily.INTEGER, TypeFamily.DECIMAL) || pair(a, b, TypeFamily.INTEGER, TypeFamily.BIT_BOOL)) {
            return true;
        }
        if (pair(a, b, TypeFamily.STRING, TypeFamily.INTEGER)) {
            return ConnectorSemanticService.EV_COMMENT.equals(evidence);
        }
        return false;
    }

    private static boolean pair(TypeFamily a, TypeFamily b, TypeFamily x, TypeFamily y) {
        return (a == x && b == y) || (a == y && b == x);
    }
}
