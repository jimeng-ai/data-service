package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticJoinValidator;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则 7（批内规则）：同一次提交里，一列只能指向一个目标（设计文档 7.9）。
 *
 * <h3>为什么不能像 toRows 那样静默留置信度高的</h3>
 * JOIN 的唯一键里只有左列（{@code (scope, 左表, 左列)}），同一列指向两张表，toRows 的 put 只会留一条、另一条记「重复」。
 * 对推导来说这是防撞键的兜底；但对逐表提交的 agent，「一列指两处」本身就是信号：要么是多态外键（本表有 {@code x_type}
 * 这类判别列，一列随类型指向不同的表——飞书定稿 §4 多态外键不注入），要么是它拿不准。两种情况都不该由服务端替它挑一条。
 * 所以冲突的条目<b>一起退回</b>：每一条在自己被检查时都会命中。
 *
 * <h3>判定</h3>
 * 在本次提交的全部 joins 里，按 {@link ConnectorSemanticService#ciFold} 折叠左表与左列（与唯一键的排序规则一致）后相同、
 * 折叠后的目标 {@code to_object.to_column} 有两个或以上不同值，就是冲突。GUESS 条目不算：toRows 会把它丢掉，它不会与谁撞。
 * 目标只差大小写不算冲突（toRows 会按 DUPLICATE_MERGED 合并）；反引号不做归一（反引号写法本身会被规则 1 退回）。
 * 左表有判别列（{@link SemanticJoinValidator#discriminatorFor}）时文案改为多态外键提示。
 */
@Component
@Order(700)
public class JoinSingleTargetRule implements SemanticConsistencyRule {

    public static final String CODE = "JOIN_MULTIPLE_TARGETS";

    /** 拼折叠键的分隔符：不可打印的 U+0001，理由同推导类的 KEY_SEP（表名列名里合法地含点与下划线）。 */
    private static final String SEP = String.valueOf((char) 1);

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
        return "同一次提交里一列只能指向一个目标；多态外键不提交关系，写进本表列的字段说明";
    }

    @Override
    public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
        String col = entry.str("column");
        String toObj = entry.str("to_object");
        String toCol = entry.str("to_column");
        if (col == null || toObj == null || toCol == null) {
            return List.of();
        }
        String obj = ctx.objectOf(entry);
        String left = foldPair(obj, col);

        Set<String> folded = new HashSet<>();
        Set<String> shown = new LinkedHashSet<>();
        folded.add(foldPair(toObj, toCol));
        shown.add(toObj + "." + toCol);
        for (ProposedEntry other : ctx.submission()) {
            if (!SemanticRowAssembler.KIND_JOINS.equals(other.kind()) || other.guess()) {
                continue;
            }
            String oc = other.str("column");
            String ot = other.str("to_object");
            String otc = other.str("to_column");
            if (oc == null || ot == null || otc == null || !left.equals(foldPair(ctx.objectOf(other), oc))) {
                continue;
            }
            if (folded.add(foldPair(ot, otc))) {
                shown.add(ot + "." + otc);
            }
        }
        if (folded.size() < 2) {
            return List.of();
        }
        String targets = RuleText.joinAnd(shown);
        String disc = SemanticJoinValidator.discriminatorFor(ctx.fieldsByObject().get(obj), col);
        if (disc != null) {
            return List.of(RuleViolation.reject(obj + "." + col + " 同时指向了 " + targets + "，而本表有判别列 " + disc
                    + "：这是多态外键，这一列随 " + disc + " 指向不同的表，一列只能有一条关系。不要提交关系，写进 "
                    + obj + "." + col + " 的字段说明（说明它按 " + disc + " 分别指向哪几张表）。"));
        }
        return List.of(RuleViolation.reject(obj + "." + col + " 同时指向了 " + targets + "。一列只能有一条关系。"
                + "如果是多态外键（本表有 x_type 这类判别列），不要提交关系，写进 " + obj + "." + col
                + " 的字段说明；否则只保留依据最强的那一条重新提交。"));
    }

    private static String foldPair(String object, String column) {
        return ConnectorSemanticService.ciFold(object) + SEP + ConnectorSemanticService.ciFold(column);
    }
}
