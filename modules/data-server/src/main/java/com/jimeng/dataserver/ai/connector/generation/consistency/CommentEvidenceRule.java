package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticService;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 规则 5：标了 evidence=COMMENT，对应的注释就必须非空（设计文档 7.9）。
 *
 * <h3>为什么这条值得一条规则</h3>
 * COMMENT 是四档依据里最硬的一档——「客户库自己写了」，注入层与读说明书的模型都按一手事实对待它（{@code SemanticPrompts}
 * 第一节）。模型最常见的越级就是把「名字像」标成 COMMENT：这不会让任何一句话变错，却把一条推测抬成了事实，而且事后看不出来。
 * 注释空不空是快照里确定可查的，没有理由放它过去。飞书定稿 §6.4「分界线是有没有外部依据」。
 *
 * <h3>判定</h3>
 * OBJECT 看表注释，但先去掉平台追加的「（约 N 行，InnoDB 估算值……）」（{@link RuleContext#objectCommentOf}）——那段是我们写的，
 * 不是客户的注释；FIELD 看该列注释；JOIN 要求左右两列<b>至少一列</b>注释非空。表或列不在快照里时放行（toRows 与规则 1 会报）。
 */
@Component
@Order(500)
public class CommentEvidenceRule implements SemanticConsistencyRule {

    public static final String CODE = "EVIDENCE_COMMENT_WITHOUT_COMMENT";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<String> kinds() {
        return Set.of(SemanticRowAssembler.KIND_OBJECTS, SemanticRowAssembler.KIND_FIELDS, SemanticRowAssembler.KIND_JOINS);
    }

    @Override
    public String summary() {
        return "evidence=COMMENT 时对应的注释必须非空：表用途看表注释，字段看列注释，关系看两端列注释（至少一端）";
    }

    @Override
    public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
        if (!ConnectorSemanticService.EV_COMMENT.equals(entry.evidence())) {
            return List.of();
        }
        String obj = ctx.objectOf(entry);
        String subject;
        switch (entry.kind()) {
            case SemanticRowAssembler.KIND_OBJECTS -> {
                if (!ctx.hasTable(obj) || ctx.objectCommentOf(obj) != null) {
                    return List.of();
                }
                subject = "表 " + obj + " 的用途";
            }
            case SemanticRowAssembler.KIND_FIELDS -> {
                String col = entry.str("name");
                FieldDetail f = ctx.column(obj, col);
                if (f == null || !blank(f.comment())) {
                    return List.of();
                }
                subject = "字段 " + obj + "." + col;
            }
            case SemanticRowAssembler.KIND_JOINS -> {
                String col = entry.str("column");
                String toObj = entry.str("to_object");
                String toCol = entry.str("to_column");
                FieldDetail left = ctx.column(obj, col);
                FieldDetail right = ctx.column(toObj, toCol);
                if (left == null || right == null || !blank(left.comment()) || !blank(right.comment())) {
                    return List.of();
                }
                subject = "关系 " + RuleText.arrow(obj, col, toObj, toCol);
            }
            default -> {
                return List.of();
            }
        }
        return List.of(RuleViolation.reject(subject + " 标了 evidence=COMMENT，但快照里对应的注释是空的。"
                + "依据若是名字，请改标 NAME；若是类型或约束，改标 DATA；否则不要提交。"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
