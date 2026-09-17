package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 语义层 agent 生成在排队、运行和异常终止阶段写给管理台的安全说明模板。 */
@Component
public class SemanticGenerationNotes {

    private static final int NOTE_MAX = 500;
    private static final int REASON_MAX = 80;
    private static final DateTimeFormatter STARTED_AT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    public String queued(long aheadInTenant) {
        String note = "语义层生成已排队（agent，全平台串行执行）";
        return aheadInTenant > 0 ? note + "，本租户前面还有 " + aheadInTenant + " 个" : note;
    }

    public String progress(ConnectorSemanticGeneration generation, ProgressCounts progress) {
        String started = generation.getStartedAt() == null
                ? "未知"
                : STARTED_AT.format(generation.getStartedAt().toInstant());
        StringBuilder note = new StringBuilder("正在生成语义层（agent，开始于 ")
                .append(started)
                .append("）：已完成 ")
                .append(progress.doneTables()).append('/').append(number(generation.getTotalTables()))
                .append(" 张表（第 ")
                .append(number(generation.getCurrentSliceNo())).append('/')
                .append(number(generation.getSliceCount())).append(" 片）");
        if (progress.gaveUpTables() > 0) {
            note.append("，放弃 ").append(progress.gaveUpTables()).append(" 张");
        }
        return safe(note.toString());
    }

    /** READY 说明；四类语义行计数由收尾事务聚合后显式传入。 */
    public String ready(ConnectorSemanticGeneration generation, ReadyStats stats) {
        StringBuilder note = new StringBuilder("覆盖 ")
                .append(number(generation.getDoneTables())).append('/')
                .append(number(generation.getTotalTables()))
                .append(" 张表：表用途 ").append(stats.objectCount())
                .append("、字段含义 ").append(stats.fieldCount())
                .append("、关系 ").append(stats.joinCount())
                .append("（均未经数据验证）、待确认口径 ").append(stats.caveatCount())
                .append(" 条；放弃 ").append(number(generation.getGaveUpTables()))
                .append(" 张。由 agent 分 ").append(number(generation.getSliceCount()))
                .append(" 片生成，未做采样验证。");
        if (stats.snapshotTruncated()) {
            note.append("结构快照在 200 个对象处按重要性截断，其余表没有进过快照，也不会有语义。");
        }
        if (staged(generation)) {
            note.append("上一版机器生成的说明已替换，人工口径未动。");
        }
        return safe(note.toString());
    }

    public String interrupted(ConnectorSemanticGeneration generation, String reason) {
        int done = number(generation.getDoneTables());
        int total = number(generation.getTotalTables());
        if (staged(generation)) {
            return safe("重新生成已中断（" + shortReason(reason) + "）：新一轮已完成 " + done + "/" + total
                    + " 张表（暂存中，未替换），当前仍是上一版说明书。点「重新生成」继续。");
        }
        return safe("语义层生成已中断（" + shortReason(reason) + "）：已完成 " + done + "/" + total
                + " 张表，已生成的部分照常可用。点「重新生成」只补其余表。");
    }

    public String failed(ConnectorSemanticGeneration generation, String reason) {
        if (staged(generation)) {
            return safe("重新生成未完成（agent）：" + shortReason(reason) + "。新说明已丢弃，上一版原样保留。");
        }
        return safe("语义层生成失败（agent）：" + shortReason(reason) + "。已完成 "
                + number(generation.getDoneTables()) + "/" + number(generation.getTotalTables()) + " 张表。");
    }

    public String fellBack(String reason) {
        return safe("agent 生成未进行：" + shortReason(reason) + "，已改走单次推导。");
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
