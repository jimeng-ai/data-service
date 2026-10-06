package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import org.springframework.stereotype.Component;

/** 语义层 agent 生成在排队、运行和异常终止阶段写给管理台的安全说明模板。 */
@Component
public class SemanticGenerationNotes {

    private static final int NOTE_MAX = 500;
    private static final int REASON_MAX = 80;

    public String queued(long aheadInTenant) {
        return aheadInTenant > 0 ? "已排队，前面还有 " + aheadInTenant + " 个" : "已排队，等待生成";
    }

    public String progress(ConnectorSemanticGeneration generation, ProgressCounts progress) {
        StringBuilder note = new StringBuilder("正在生成：已完成 ")
                .append(progress.doneTables()).append('/').append(number(generation.getTotalTables()))
                .append(" 张表");
        if (progress.gaveUpTables() > 0) {
            note.append("，").append(progress.gaveUpTables()).append(" 张未成功");
        }
        return safe(note.toString());
    }

    /** READY 说明；四类语义行计数由收尾事务聚合后显式传入。 */
    public String ready(ConnectorSemanticGeneration generation, ReadyStats stats) {
        StringBuilder note = new StringBuilder("覆盖 ")
                .append(number(generation.getDoneTables())).append('/')
                .append(number(generation.getTotalTables()))
                .append(" 张表：表说明 ").append(stats.objectCount())
                .append("、字段 ").append(stats.fieldCount())
                .append("、关系 ").append(stats.joinCount())
                .append("（未经数据核对）。");
        if (number(generation.getGaveUpTables()) > 0) {
            note.append("另有 ").append(number(generation.getGaveUpTables())).append(" 张未能生成。");
        }
        if (stats.snapshotTruncated()) {
            note.append("表太多，只覆盖了最重要的 200 张。");
        }
        return safe(note.toString());
    }

    public String interrupted(ConnectorSemanticGeneration generation, String reason) {
        int done = number(generation.getDoneTables());
        int total = number(generation.getTotalTables());
        if (staged(generation)) {
            return safe("重新生成中断（" + shortReason(reason) + "）：已完成 " + done + "/" + total
                    + " 张，仍用上一版。点「重新生成」继续。");
        }
        // 还没列出任何表就中断时（total=0），「已完成 0/0」只会让人困惑，直接不说。
        String progress = total > 0 ? "已完成 " + done + "/" + total + " 张表。" : "";
        return safe("生成中断（" + shortReason(reason) + "）：" + progress + "点「重新生成」补其余。");
    }

    public String failed(ConnectorSemanticGeneration generation, String reason) {
        if (staged(generation)) {
            return safe("重新生成失败：" + shortReason(reason) + "。仍用上一版。");
        }
        int total = number(generation.getTotalTables());
        String progress = total > 0 ? "已完成 " + number(generation.getDoneTables()) + "/" + total + " 张表。" : "";
        return safe("生成失败：" + shortReason(reason) + "。" + progress);
    }

    /** 回落的内部原因只留在批次行里，不在界面说明上复述：超管看到也没法处理。 */
    public String fellBack(String reason) {
        return safe("已改用备用方式生成。");
    }

    private static boolean staged(ConnectorSemanticGeneration generation) {
        return "STAGED".equals(generation.getMode());
    }

    private static int number(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static String shortReason(String value) {
        String reason = value == null || value.isBlank() ? "未知原因" : value.replace('｜', '|');
        return reason.length() <= REASON_MAX ? reason : reason.substring(0, REASON_MAX);
    }

    private static String safe(String note) {
        String cleaned = note.replace('｜', '|');
        return cleaned.length() <= NOTE_MAX ? cleaned : cleaned.substring(0, NOTE_MAX);
    }

    /** 心跳从每表状态聚合出的权威进度。 */
    public record ProgressCounts(int doneTables, int gaveUpTables) {
        public ProgressCounts {
            doneTables = Math.max(0, doneTables);
            gaveUpTables = Math.max(0, gaveUpTables);
        }
    }

    /** READY 说明所需的四类语义行计数，以及本批次快照是否在 200 个对象处截断。 */
    public record ReadyStats(int objectCount, int fieldCount, int joinCount, int caveatCount,
                             boolean snapshotTruncated) {
        public ReadyStats {
            objectCount = Math.max(0, objectCount);
            fieldCount = Math.max(0, fieldCount);
            joinCount = Math.max(0, joinCount);
            caveatCount = Math.max(0, caveatCount);
        }
    }
}
