package com.jimeng.dataserver.ai.connector.model;

import java.util.regex.Pattern;

/**
 * 表注释末尾那段平台追加的估算行数：「（约 N 行，InnoDB 估算值，不可当作准确计数）」。
 *
 * <p>这段是<b>平台</b>写的，不是客户的注释。写入方（MySQL 连接器列目录）和剥离方（语义层一致性规则、数据星图）
 * 从前各写一份文案，改一边另一边静默失效；现在只在这里。放在 model 包：语义层不该依赖某一个连接器实现，
 * 两边都只依赖这个值对象级的小工具。
 *
 * <p>{@link #strip} 认「估算值」之后到右括号的任意内容，所以已落库的旧措辞（如「（约 5 行，InnoDB 估算值）」）
 * 不必重拉快照也能去掉。
 */
public final class RowEstimateNote {

    private static final Pattern SUFFIX = Pattern.compile("\\s*（约\\s*\\d+\\s*行，InnoDB\\s*估算值[^）]*）\\s*$");

    private RowEstimateNote() {
    }

    /**
     * 在注释后追加估算行数。行数未知（{@code null}）和 0 都不写：前者没数可写，后者可能只是没统计过，
     * 写「约 0 行」会让模型断言这张表是空的。
     *
     * @return 既没有注释也不写行数时为 {@code null}
     */
    public static String append(String comment, Long rows) {
        String base = comment == null || comment.isBlank() ? "" : comment;
        String withRows = base + (rows != null && rows > 0
                ? "（约 " + rows + " 行，InnoDB 估算值，不可当作准确计数）" : "");
        return withRows.isBlank() ? null : withRows;
    }

    /** 去掉末尾的估算行数并 trim；剩下为空（客户根本没写注释）返回 {@code null}。 */
    public static String strip(String comment) {
        if (comment == null) {
            return null;
        }
        String stripped = SUFFIX.matcher(comment).replaceFirst("").trim();
        return stripped.isEmpty() ? null : stripped;
    }
}
