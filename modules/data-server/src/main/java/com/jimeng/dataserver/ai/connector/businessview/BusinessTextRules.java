package com.jimeng.dataserver.ai.connector.businessview;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 给人看的业务文字的合格标准（数据星图设计 v3 §6.2、附录 B）。纯函数。
 *
 * <p>看的人是业务人员和产品：名字要短、要是业务叫法；不能出现表名、字段名这类代码；也不能带 AI 口吻和平台内部用语。
 * 模型的产出、以及兜底时拿来顶替的客户注释，都要过这一关。
 */
public final class BusinessTextRules {

    /** 对象的业务名最多几个字。 */
    public static final int NAME_MAX = 12;
    /** 一句话说明最多几个字。 */
    public static final int SUMMARY_MAX = 50;
    /** 业务领域最多几个字。 */
    public static final int DOMAIN_MAX = 8;
    /** 关系角色名最多几个字。 */
    public static final int ROLE_MAX = 10;
    /** 一个系统最多几个业务领域。 */
    public static final int MAX_DOMAINS = 8;
    /** 没有领域时的兜底。 */
    public static final String UNCLASSIFIED = "未分类";

    /**
     * 附录 B：AI 口吻或平台内部用语。「可能」不在其中——「一张订单可能有多条明细」是正常的业务描述。
     */
    public static final List<String> BANNED = List.of("判不出", "未经", "验证", "样本", "采样", "置信", "估算",
            "InnoDB", "疑似", "或许", "大概", "推测", "语义层", "说明书", "主键", "外键", "字段");

    /** 列名至少几个字符才算「代码」：{@code ID}、{@code NO} 这种太短，拦了会误伤正常说法。表名不论长短都算。 */
    static final int CODE_MIN_COLUMN = 4;

    private static final Pattern ASCII_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_$]+$");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_$]+");

    private BusinessTextRules() {
    }

    /**
     * 一个系统的物理标识符（小写）：全部表名 + 长度 ≥ {@value #CODE_MIN_COLUMN} 的列名。
     * 只收纯 ASCII 的标识符：中文表名、中文列名本身就是业务叫法，不算代码。
     */
    public static Set<String> identifiers(Collection<String> tables, Collection<String> columns) {
        Set<String> out = new HashSet<>();
        for (String t : tables) {
            if (t != null && ASCII_IDENTIFIER.matcher(t).matches()) {
                out.add(t.toLowerCase(Locale.ROOT));
            }
        }
        for (String c : columns) {
            if (c != null && c.length() >= CODE_MIN_COLUMN && ASCII_IDENTIFIER.matcher(c).matches()) {
                out.add(c.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * @return 不合格的原因（给模型重试时原样带回去）；{@code null} = 合格
     */
    public static String problem(String text, int maxChars, Set<String> identifiers) {
        if (text == null || text.isBlank()) {
            return "为空";
        }
        String t = text.trim();
        int length = t.codePointCount(0, t.length());
        if (length > maxChars) {
            return "超过 " + maxChars + " 个字（现在 " + length + " 个）";
        }
        String lower = t.toLowerCase(Locale.ROOT);
        for (String word : BANNED) {
            if (lower.contains(word.toLowerCase(Locale.ROOT))) {
                return "含「" + word + "」，这是 AI 口吻或平台内部用语";
            }
        }
        String code = codeIn(t, identifiers);
        if (code != null) {
            return "含代码「" + code + "」，要用业务叫法";
        }
        return null;
    }

    /**
     * 兜底时能不能拿客户注释顶替业务文字（§6.3）：不为空、不夹代码、不带禁用词。不看字数——注释不受业务文字的字数约束，
     * 长短由调用方按用途判断（标题只收「像名称」的短注释）。注释里常夹着字段名，也可能写着「估算」「主键」这类词。
     */
    public static boolean fitsFallback(String text, Set<String> identifiers) {
        return text != null && !text.isBlank() && problem(text, Integer.MAX_VALUE, identifiers) == null;
    }

    /** 按完整单词、不分大小写找物理标识符：单词是连续的字母、数字、下划线、{@code $}。 */
    private static String codeIn(String text, Set<String> identifiers) {
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (identifiers.contains(m.group().toLowerCase(Locale.ROOT))) {
                return m.group();
            }
        }
        return null;
    }
}
