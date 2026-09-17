package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 语义层 agent 生成在排队、运行和异常终止阶段写给管理台的安全说明模板。 */
@Component
public class SemanticGenerationNotes {

    private static final int NOTE_MAX = 500;
    private static final DateTimeFormatter STARTED_AT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    public String queued(long aheadInTenant) {
        String note = "语义层生成已排队（agent，全平台串行执行）";
        return aheadInTenant > 0 ? note + "，本租户前面还有 " + aheadInTenant + " 个" : note;
    }

    public String progress(ConnectorSemanticGeneration generation) {
        String started = generation.getStartedAt() == null
                ? "未知"
                : STARTED_AT.format(generation.getStartedAt().toInstant());
        StringBuilder note = new StringBuilder("正在生成语义层（agent，开始于 ")
                .append(started)
                .append("）：已完成 ")
                .append(number(generation.getDoneTables())).append('/').append(number(generation.getTotalTables()))
                .append(" 张表（第 ")
                .append(number(generation.getCurrentSliceNo())).append('/')
                .append(number(generation.getSliceCount())).append(" 片）");
        if (number(generation.getGaveUpTables()) > 0) {
            note.append("，放弃 ").append(generation.getGaveUpTables()).append(" 张");
        }
        return safe(note.toString());
    }

    public String interrupted(ConnectorSemanticGeneration generation, String reason) {
        int done = number(generation.getDoneTables());
        int total = number(generation.getTotalTables());
        if (staged(generation)) {
            return safe("重新生成已中断（" + clean(reason) + "）：新一轮已完成 " + done + "/" + total
                    + " 张表（暂存中，未替换），当前仍是上一版说明书。点「重新生成」继续。");
        }
        return safe("语义层生成已中断（" + clean(reason) + "）：已完成 " + done + "/" + total
                + " 张表，已生成的部分照常可用。点「重新生成」只补其余表。");
    }

    public String failed(ConnectorSemanticGeneration generation, String reason) {
        if (staged(generation)) {
            return safe("重新生成未完成（agent）：" + clean(reason) + "。新说明已丢弃，上一版原样保留。");
        }
        return safe("语义层生成失败（agent）：" + clean(reason) + "。已完成 "
                + number(generation.getDoneTables()) + "/" + number(generation.getTotalTables()) + " 张表。");
    }

    public String fellBack(String reason) {
        return safe("agent 生成未进行：" + clean(reason) + "，已改走单次推导。");
    }

    private static boolean staged(ConnectorSemanticGeneration generation) {
        return "STAGED".equals(generation.getMode());
    }

    private static int number(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? "未知原因" : value.replace('｜', '|');
    }

    private static String safe(String note) {
        String cleaned = note.replace('｜', '|');
        return cleaned.length() <= NOTE_MAX ? cleaned : cleaned.substring(0, NOTE_MAX);
    }
}
