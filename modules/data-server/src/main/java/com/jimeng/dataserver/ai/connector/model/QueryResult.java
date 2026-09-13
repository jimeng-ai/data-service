package com.jimeng.dataserver.ai.connector.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次查询的结果。
 *
 * <p><b>{@code truncated} 必须如实回传给模型。</b>静默截断会让模型把「前 1000 行」当成全集，
 * 然后给出一个自信的错误结论——这正是本项目反复栽过的那类静默降级。
 *
 * @param columns           列名，顺序与每行的值一一对应
 * @param rows              行数据。数值类型已按平台契约（全局 write_numbers_as_strings）序列化
 * @param truncated         结果是否被截断
 * @param truncateReason    截断原因（行数上限 / 字节上限），未截断为 null
 * @param effectiveStatement 平台实际执行的语句（可能被注入了 LIMIT）。<b>必须回传，查数要亮出过程</b>
 * @param elapsedMs         耗时
 */
public record QueryResult(
        List<String> columns,
        List<List<Object>> rows,
        boolean truncated,
        String truncateReason,
        String effectiveStatement,
        long elapsedMs
) {

    public int rowCount() {
        return rows == null ? 0 : rows.size();
    }

    /** 摊平成给模型看的形状。 */
    public Map<String, Object> toModelPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("row_count", rowCount());
        out.put("elapsed_ms", elapsedMs);
        // 查询语句必须回传：产品方案第 8 节「查数必须亮出过程」——只给数字会让口径错误彻底失去被发现的机会。
        out.put("statement", effectiveStatement);
        out.put("truncated", truncated);
        if (truncated) {
            out.put("truncate_reason", truncateReason);
            out.put("warning", "结果不完整，只返回了前 " + rowCount() + " 行。不要把它当成全集下结论——"
                    + "请加聚合、加过滤条件或缩小时间范围后重新查询。");
        }
        return out;
    }
}
