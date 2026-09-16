package com.jimeng.dataserver.ai.connector.generation.consistency;

import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则 6：gloss（关系是 note）里写了「码值 → 含义」，快照注释里就必须有同一组对照（设计文档 7.9）。
 *
 * <h3>这是整套纪律里最容易破功的一条</h3>
 * 看到 {@code status tinyint}，模型会非常自然地写出「0=待支付，1=已支付」——读起来专业、合理，而且可能整个是错的，
 * 没有任何验证能发现它错：SQL 语法对、能跑、返回一个数。飞书定稿 §3「S2 的三条硬约束」第 1 条；{@code SemanticPrompts} 第二节。
 * 第 1 档不读数据值，唯一能作证的就是客户自己的注释。
 *
 * <h3>判定</h3>
 * 先去掉基数写法（{@code 1:N} 这类，否则关系说明里的每个基数都会被当成码值），再用 {@link #CODE_MAPPING} 抽「码值 → 含义」。
 * 抽到的每个码值都必须出现在<b>相关注释</b>的对照里：FIELD 看本列注释加表注释；OBJECT 看表注释加本表全部列注释；
 * JOIN 看两端列注释。ambiguities 不审（question 本来就是问句）。表或列不在快照里时放行（toRows 与规则 1 会报）。
 * 只比码值、不比含义的措辞：模型复述注释时换个说法（「未删」写成「未删除」）是正常的，按措辞比会把照抄注释的 gloss 也退回。
 *
 * <h3>注释一侧多认一种紧凑写法</h3>
 * 注释里除了与 gloss 同一个正则，还认「码值紧跟中文」：{@code 帐号状态（0正常 1停用）}、{@code 逻辑删除 0未删 1已删}。
 * 这是中文库里极常见的注释写法（若依一类脚手架的默认风格，dev 库的 product_feedback.deleted 就是这样写的），
 * 只用 gloss 的正则去认，模型照着注释写出的「0=正常」会被误退回。
 * gloss 一侧<b>不</b>认紧凑写法：{@code 保留2位小数} 这种句子会被当成码值，误报代价是 agent 被迫删掉一句正确的话。
 * 这条放宽只会让规则少退回（对照的是注释里确实写着的码值），不会放过注释里根本没有的码值。
 *
 * <p>误报与漏报在客户库上未实测（设计 12.2）；{@code CodeValueMappingRuleTest} 用表驱动把已知的两类边界钉住。
 */
@Component
@Order(600)
public class CodeValueMappingRule implements SemanticConsistencyRule {

    public static final String CODE = "GLOSS_CODE_VALUE_NOT_IN_COMMENT";

    /** 基数写法 {@code 1:1 / 1:N / N:1 / N:N}（含全角冒号），抽码值之前先去掉。 */
    static final Pattern CARDINALITY = Pattern.compile("(?<![A-Za-z0-9])[1N]\\s*[:：]\\s*[1N](?![A-Za-z0-9])");

    /** 设计文档 7.9 给定的「码值 → 含义」正则，原样照抄：码值是 1 到 4 位整数（可带负号）或单个大写字母，后面接分隔符再接中文或字母。 */
    static final Pattern CODE_MAPPING = Pattern.compile(
            "(?<![A-Za-z0-9_.])(-?\\d{1,4}|[A-Z])\\s*(?:=|＝|:|：|->|→|-|—|表示|代表)\\s*(?=[\\p{IsHan}A-Za-z])");

    /** 只用于注释一侧的紧凑写法：码值后面<b>紧跟</b>中文，中间没有分隔符也没有空白。理由见类注释。 */
    static final Pattern COMPACT_MAPPING_IN_COMMENT = Pattern.compile("(?<![A-Za-z0-9_.])(-?\\d{1,4}|[A-Z])(?=\\p{IsHan})");

    /** 文案里举例时，含义最多带几个字。 */
    private static final int SNIPPET_MEANING_MAX = 8;

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
        return "gloss 与关系 note 里写码值含义（如 0=待支付）时，相关注释里必须有同一组对照；码值含义不能推断";
    }

    @Override
    public List<RuleViolation> check(ProposedEntry entry, RuleContext ctx) {
        boolean join = SemanticRowAssembler.KIND_JOINS.equals(entry.kind());
        String text = entry.str(join ? "note" : "gloss");
        if (text == null) {
            return List.of();
        }
        Map<String, String> claimed = mappings(text);
        if (claimed.isEmpty()) {
            return List.of();
        }
        List<String> comments = relatedComments(entry, ctx);
        if (comments == null) {
            return List.of();
        }
        Set<String> documented = new HashSet<>();
        for (String c : comments) {
            documented.addAll(codesInComment(c));
        }
        for (Map.Entry<String, String> e : claimed.entrySet()) {
            if (!documented.contains(e.getKey())) {
                return List.of(RuleViolation.reject((join ? "note" : "gloss") + " 里写了码值含义（如 " + e.getValue()
                        + "），但快照注释里没有这组对照。码值含义不能推断：改写成『取值含义未知，使用前必须确认』，或者删掉这句。"));
            }
        }
        return List.of();
    }

    /** 码值 → 文案里举例用的片段（如 {@code 0=待支付}），按出现顺序；同一个码值只记第一次。 */
    static Map<String, String> mappings(String text) {
        String s = CARDINALITY.matcher(text).replaceAll(" ");
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = CODE_MAPPING.matcher(s);
        while (m.find()) {
            int stop = m.end();
            while (stop < s.length() && stop - m.end() < SNIPPET_MEANING_MAX && Character.isLetter(s.charAt(stop))) {
                stop++;
            }
            out.putIfAbsent(m.group(1), s.substring(m.start(), stop).replaceAll("\\s+", ""));
        }
        return out;
    }

    /** 注释里以对照形式出现的码值：与 gloss 同一个正则，外加紧凑写法。 */
    static Set<String> codesInComment(String comment) {
        Set<String> out = new HashSet<>();
        if (comment == null || comment.isBlank()) {
            return out;
        }
        String s = CARDINALITY.matcher(comment).replaceAll(" ");
        Matcher m = CODE_MAPPING.matcher(s);
        while (m.find()) {
            out.add(m.group(1));
        }
        Matcher c = COMPACT_MAPPING_IN_COMMENT.matcher(s);
        while (c.find()) {
            out.add(c.group(1));
        }
        return out;
    }

    /** 相关注释；表或列不在快照里返回 {@code null}（放行）。 */
    private static List<String> relatedComments(ProposedEntry entry, RuleContext ctx) {
        String obj = ctx.objectOf(entry);
        List<String> out = new ArrayList<>();
        switch (entry.kind()) {
            case SemanticRowAssembler.KIND_OBJECTS -> {
                if (!ctx.hasTable(obj)) {
                    return null;
                }
                out.add(ctx.objectCommentOf(obj));
                for (FieldDetail f : ctx.columnsOf(obj).values()) {
                    out.add(f.comment());
                }
            }
            case SemanticRowAssembler.KIND_FIELDS -> {
                FieldDetail f = ctx.column(obj, entry.str("name"));
                if (f == null) {
                    return null;
                }
                out.add(f.comment());
                out.add(ctx.objectCommentOf(obj));
            }
            case SemanticRowAssembler.KIND_JOINS -> {
                FieldDetail left = ctx.column(obj, entry.str("column"));
                FieldDetail right = ctx.column(entry.str("to_object"), entry.str("to_column"));
                if (left == null || right == null) {
                    return null;
                }
                out.add(left.comment());
                out.add(right.comment());
            }
            default -> {
                return null;
            }
        }
        return out;
    }
}
