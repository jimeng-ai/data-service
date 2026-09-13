package com.jimeng.dataserver.ai.connector.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次写操作的结果。
 *
 * @param affectedRows       实际影响行数
 * @param effectiveStatement 平台实际执行的语句
 * @param elapsedMs          耗时
 */
public record WriteResult(int affectedRows, String effectiveStatement, long elapsedMs) {

    public Map<String, Object> toModelPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("affected_rows", affectedRows);
        // 与查询一样：写操作更要亮出过程。改了几行、改的是哪条语句，是事后唯一能核对的东西。
        out.put("statement", effectiveStatement);
        out.put("elapsed_ms", elapsedMs);
        out.put("committed", true);
        if (affectedRows == 0) {
            // 0 行不是「成功了但没效果」，通常是 WHERE 条件没命中——模型很容易把它读成「改好了」。
            out.put("warning", "这条语句没有影响任何行。通常意味着 WHERE 条件没有命中任何记录，"
                    + "请先用查询确认目标数据存在，不要向用户报告「已完成修改」。");
        }
        return out;
    }
}
