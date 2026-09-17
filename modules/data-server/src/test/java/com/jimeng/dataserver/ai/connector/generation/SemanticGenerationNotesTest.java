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
        generation.setDoneTables(7);
        generation.setTotalTables(18);
        generation.setCurrentSliceNo(3);
        generation.setSliceCount(9);
        generation.setGaveUpTables(2);

        String note = notes.progress(generation);

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
