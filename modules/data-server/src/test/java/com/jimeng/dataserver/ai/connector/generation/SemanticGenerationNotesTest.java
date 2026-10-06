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
    @DisplayName("进度说明只说已完成几张、几张没成功，不带内部分片信息")
    void progressShowsDoneAndFailedOnly() {
        ConnectorSemanticGeneration generation = generation("DIRECT");
        generation.setStartedAt(Date.from(Instant.parse("2026-09-17T01:02:00Z")));
        generation.setTotalTables(18);
        generation.setCurrentSliceNo(3);
        generation.setSliceCount(9);

        String note = notes.progress(generation, new SemanticGenerationNotes.ProgressCounts(7, 2));

        assertEquals("正在生成：已完成 7/18 张表，2 张未成功", note);
        assertFalse(note.contains("｜"), note);
        assertFalse(note.contains("片"), "分片是内部机制，不给超管看：" + note);
        assertFalse(note.contains("agent"), note);
    }

    @Test
    @DisplayName("没有放弃的表时进度里不出现「未成功」")
    void progressWithoutGaveUp() {
        ConnectorSemanticGeneration generation = generation("DIRECT");
        generation.setTotalTables(18);

        assertEquals("正在生成：已完成 7/18 张表",
                notes.progress(generation, new SemanticGenerationNotes.ProgressCounts(7, 0)));
    }

    @Test
    @DisplayName("排队说明只在前面有本租户批次时带数量")
    void queued() {
        assertEquals("已排队，等待生成", notes.queued(0));
        assertEquals("已排队，前面还有 2 个", notes.queued(2));
    }

    @Test
    @DisplayName("DIRECT 与 STAGED 的中断和失败说明明确可见性差异")
    void terminalModes() {
        ConnectorSemanticGeneration direct = generation("DIRECT");
        ConnectorSemanticGeneration staged = generation("STAGED");

        assertTrue(notes.interrupted(direct, "服务重启").contains("点「重新生成」补其余"));
        assertTrue(notes.interrupted(staged, "服务重启").contains("仍用上一版"));
        assertTrue(notes.failed(direct, "没有任何表生成成功").contains("已完成 4/10 张表"));
        assertTrue(notes.failed(staged, "没有任何表生成成功").contains("仍用上一版"));
    }

    @Test
    @DisplayName("还没列出任何表就中断/失败时，不说「已完成 0/0 张表」")
    void noProgressWhenNoTableListed() {
        ConnectorSemanticGeneration direct = generation("DIRECT");
        direct.setTotalTables(0);
        direct.setDoneTables(0);

        assertEquals("生成失败：读取表结构失败。", notes.failed(direct, "读取表结构失败"));
        assertEquals("生成中断（连接已停用）：点「重新生成」补其余。", notes.interrupted(direct, "连接已停用"));
    }

    @Test
    @DisplayName("中断与失败说明是一两句短话：整条不超过 45 字符")
    void terminalNotesAreShort() {
        for (String mode : new String[]{"DIRECT", "STAGED"}) {
            ConnectorSemanticGeneration generation = generation(mode);
            for (String note : new String[]{
                    notes.interrupted(generation, "服务繁忙或不可用"),
                    notes.failed(generation, "读取表结构失败")}) {
                assertTrue(note.length() <= 45, mode + " 说明太长：" + note);
            }
        }
    }

    @Test
    @DisplayName("DIRECT READY 说明汇总覆盖表和三类语义行，放弃的表单独交代")
    void readyDirect() {
        ConnectorSemanticGeneration direct = generation("DIRECT");
        direct.setGaveUpTables(2);

        String note = notes.ready(direct, new SemanticGenerationNotes.ReadyStats(3, 12, 4, 2, false));

        assertEquals("覆盖 4/10 张表：表说明 3、字段 12、关系 4（未经数据核对）。另有 2 张未能生成。", note);
    }

    @Test
    @DisplayName("没有放弃的表时 READY 说明就是一句覆盖统计")
    void readyWithoutGaveUp() {
        ConnectorSemanticGeneration direct = generation("DIRECT");

        String note = notes.ready(direct, new SemanticGenerationNotes.ReadyStats(3, 12, 4, 2, false));

        assertEquals("覆盖 4/10 张表：表说明 3、字段 12、关系 4（未经数据核对）。", note);
    }

    @Test
    @DisplayName("STAGED READY 说明与 DIRECT 一样：替换上一版是内部过程，不复述")
    void readyStaged() {
        ConnectorSemanticGeneration staged = generation("STAGED");

        String note = notes.ready(staged, new SemanticGenerationNotes.ReadyStats(1, 2, 3, 4, false));

        assertEquals("覆盖 4/10 张表：表说明 1、字段 2、关系 3（未经数据核对）。", note);
    }

    @Test
    @DisplayName("快照截断时 READY 说明追加「只覆盖了最重要的 200 张」")
    void readySnapshotTruncated() {
        ConnectorSemanticGeneration direct = generation("DIRECT");

        String note = notes.ready(direct, new SemanticGenerationNotes.ReadyStats(1, 2, 3, 4, true));

        assertTrue(note.endsWith("表太多，只覆盖了最重要的 200 张。"), note);
    }

    @Test
    @DisplayName("超长外部原因不会把进度、可用性和下一步挤出前 240 字符")
    void longReasonKeepsCriticalInformationFirst() {
        String reason = "上游返回的超长原因".repeat(100);
        ConnectorSemanticGeneration direct = generation("DIRECT");
        ConnectorSemanticGeneration staged = generation("STAGED");

        String directInterrupted = head(notes.interrupted(direct, reason));
        assertTrue(directInterrupted.contains("已完成 4/10 张表"), directInterrupted);
        assertTrue(directInterrupted.contains("点「重新生成」补其余"), directInterrupted);

        String stagedInterrupted = head(notes.interrupted(staged, reason));
        assertTrue(stagedInterrupted.contains("已完成 4/10 张"), stagedInterrupted);
        assertTrue(stagedInterrupted.contains("仍用上一版"), stagedInterrupted);
        assertTrue(stagedInterrupted.contains("点「重新生成」继续"), stagedInterrupted);

        assertTrue(head(notes.failed(direct, reason)).contains("已完成 4/10 张表"));
        assertTrue(head(notes.failed(staged, reason)).contains("仍用上一版"));
        assertEquals("已改用备用方式生成。", notes.fellBack(reason));
    }

    @Test
    @DisplayName("回落说明不复述内部原因：没有 agent、sandbox、单次推导这类字眼")
    void fellBackHidesInternalReason() {
        String note = notes.fellBack("降级原因：sandbox 持续繁忙或不可用。");

        assertFalse(note.contains("sandbox"), note);
        assertFalse(note.contains("agent"), note);
        assertFalse(note.contains("单次推导"), note);
        assertFalse(note.contains("降级"), note);
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
