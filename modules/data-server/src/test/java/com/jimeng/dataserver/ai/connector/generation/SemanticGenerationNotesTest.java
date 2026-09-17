package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticGenerationNotesTest {

    private final SemanticGenerationNotes notes = new SemanticGenerationNotes();

    @Test
    @DisplayName("进度说明使用真正 startedAt，并包含 k/N 与 i/M")
    void progressUsesRealStartedAt() {
        ConnectorSemanticGeneration generation = generation("DIRECT");
        generation.setStartedAt(Date.from(Instant.parse("2026-09-17T01:02:00Z")));
        generation.setTotalTables(18);
        generation.setCurrentSliceNo(3);
        generation.setSliceCount(9);

        String note = notes.progress(generation, new SemanticGenerationNotes.ProgressCounts(7, 2));

        assertTrue(note.contains("已完成 7/18 张表（第 3/9 片）"), note);
        assertTrue(note.contains("开始于 09-17"), note);
        assertTrue(note.contains("放弃 2 张"), note);
        assertFalse(note.contains("｜"), note);
    }

    @Test
    @DisplayName("排队说明只在前面有本租户批次时带数量")
    void queued() {
        assertEquals("语义层生成已排队（agent，全平台串行执行）", notes.queued(0));
        assertEquals("语义层生成已排队（agent，全平台串行执行），本租户前面还有 2 个", notes.queued(2));
    }

    @Test
    @DisplayName("DIRECT 与 STAGED 的中断和失败说明明确可见性差异")
    void terminalModes() {
        ConnectorSemanticGeneration direct = generation("DIRECT");
        ConnectorSemanticGeneration staged = generation("STAGED");

        assertTrue(notes.interrupted(direct, "服务关停").contains("已生成的部分照常可用"));
        assertTrue(notes.interrupted(staged, "服务关停").contains("暂存中，未替换"));
        assertTrue(notes.failed(direct, "没有任何表生成成功").contains("已完成 4/10 张表"));
        assertTrue(notes.failed(staged, "没有任何表生成成功").contains("上一版原样保留"));
    }

    @Test
    @DisplayName("DIRECT READY 说明严格汇总覆盖表和四类语义行")
    void readyDirect() {
        ConnectorSemanticGeneration direct = generation("DIRECT");
        direct.setGaveUpTables(2);

        String note = notes.ready(direct, new SemanticGenerationNotes.ReadyStats(3, 12, 4, 2, false));

        assertEquals("覆盖 4/10 张表：表用途 3、字段含义 12、关系 4（均未经数据验证）、待确认口径 2 条；放弃 2 张。"
                + "由 agent 分 5 片生成，未做采样验证。", note);
    }

    @Test
    @DisplayName("STAGED READY 说明明确旧机器版已替换、人工口径未动")
    void readyStaged() {
        ConnectorSemanticGeneration staged = generation("STAGED");

        String note = notes.ready(staged, new SemanticGenerationNotes.ReadyStats(1, 2, 3, 4, false));

        assertTrue(note.endsWith("上一版机器生成的说明已替换，人工口径未动。"), note);
    }

    @Test
    @DisplayName("快照截断时 READY 说明追加 200 对象限制与影响")
    void readySnapshotTruncated() {
        ConnectorSemanticGeneration direct = generation("DIRECT");

        String note = notes.ready(direct, new SemanticGenerationNotes.ReadyStats(1, 2, 3, 4, true));

        assertTrue(note.endsWith("结构快照在 200 个对象处按重要性截断，其余表没有进过快照，也不会有语义。"), note);
    }

    @Test
    @DisplayName("超长外部原因不会把进度、可用性和下一步挤出前 240 字符")
    void longReasonKeepsCriticalInformationFirst() {
        String reason = "上游返回的超长原因".repeat(100);
        ConnectorSemanticGeneration direct = generation("DIRECT");
        ConnectorSemanticGeneration staged = generation("STAGED");

        String directInterrupted = head(notes.interrupted(direct, reason));
        assertTrue(directInterrupted.contains("已完成 4/10 张表"), directInterrupted);
        assertTrue(directInterrupted.contains("已生成的部分照常可用"), directInterrupted);
        assertTrue(directInterrupted.contains("点「重新生成」只补其余表"), directInterrupted);

        String stagedInterrupted = head(notes.interrupted(staged, reason));
        assertTrue(stagedInterrupted.contains("新一轮已完成 4/10 张表"), stagedInterrupted);
        assertTrue(stagedInterrupted.contains("当前仍是上一版说明书"), stagedInterrupted);
        assertTrue(stagedInterrupted.contains("点「重新生成」继续"), stagedInterrupted);

        assertTrue(head(notes.failed(direct, reason)).contains("已完成 4/10 张表"));
        assertTrue(head(notes.failed(staged, reason)).contains("上一版原样保留"));
        assertTrue(head(notes.fellBack(reason)).contains("已改走单次推导"));
    }

    private static String head(String note) {
        return note.substring(0, Math.min(240, note.length()));
    }

    private static ConnectorSemanticGeneration generation(String mode) {
        ConnectorSemanticGeneration generation = new ConnectorSemanticGeneration();
        generation.setMode(mode);
        generation.setDoneTables(4);
        generation.setTotalTables(10);
        generation.setCurrentSliceNo(2);
        generation.setSliceCount(5);
        generation.setGaveUpTables(0);
        return generation;
    }
}
