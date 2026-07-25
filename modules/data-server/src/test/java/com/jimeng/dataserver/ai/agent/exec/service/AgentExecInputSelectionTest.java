package com.jimeng.dataserver.ai.agent.exec.service;

import com.jimeng.persistence.entity.AgentArtifact;
import com.jimeng.persistence.entity.AgentInputFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入选取（AgentExecService.selectInputs）的对抗测试。
 *
 * 这段逻辑的两个坑都是真踩过的：
 *   1. 按 id 一刀切"丢最旧的" -> 产物 id 更大，结果把用户最早上传的【源数据】挤掉、只留中间产物；
 *   2. 超过边车 LIMIT_MAX_INPUT_FILES 时边车是 400 拒掉【整轮】而不是截断，裁不到位 = 整轮全废。
 */
class AgentExecInputSelectionTest {

    private static AgentInputFile upload(long id, String name) {
        AgentInputFile f = new AgentInputFile();
        f.setId(id);
        f.setFilename(name);
        return f;
    }

    private static AgentArtifact artifact(long id, String name) {
        AgentArtifact a = new AgentArtifact();
        a.setId(id);
        a.setFilename(name);
        return a;
    }

    private static List<String> names(List<? extends Object> list) {
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            out.add(o instanceof AgentInputFile f ? f.getFilename() : ((AgentArtifact) o).getFilename());
        }
        return out;
    }

    @Test
    @DisplayName("未超上限时全部保留，且上传在前、产物在后，各自按先后顺序")
    void keepsEverythingUnderBudget() {
        List<AgentInputFile> uploads = List.of(upload(1, "a.csv"), upload(2, "b.csv"));
        // 产物入参约定为 新->旧
        List<AgentArtifact> artifacts = List.of(artifact(20, "new.png"), artifact(10, "old.png"));

        AgentExecService.InputSelection s = AgentExecService.selectInputs(uploads, Set.of(), artifacts, 20);

        assertEquals(List.of("a.csv", "b.csv"), names(s.uploads()));
        assertEquals(List.of("old.png", "new.png"), names(s.artifacts()), "产物应回到生成先后顺序");
    }

    @Test
    @DisplayName("【关键回归】超上限时先裁产物，绝不能挤掉用户最早上传的源数据")
    void trimsArtifactsBeforeUploads() {
        // 1 个源数据上传 + 5 个产物，上限 3
        List<AgentInputFile> uploads = List.of(upload(1, "源数据.xlsx"));
        List<AgentArtifact> artifacts = new ArrayList<>();
        for (int i = 5; i >= 1; i--) { // 新->旧
            artifacts.add(artifact(100 + i, "chart" + i + ".png"));
        }

        AgentExecService.InputSelection s = AgentExecService.selectInputs(uploads, Set.of(), artifacts, 3);

        assertTrue(names(s.uploads()).contains("源数据.xlsx"), "源数据必须活下来（此前的实现会把它挤掉）");
        assertEquals(2, s.artifacts().size(), "剩余名额给产物");
        assertEquals(List.of("chart4.png", "chart5.png"), names(s.artifacts()), "留下的是最新的两个产物");
        assertEquals(3, s.uploads().size() + s.artifacts().size(), "总数不超上限");
    }

    @Test
    @DisplayName("上传超上限时丢最旧的，本轮显式带的必留")
    void keepsExplicitAndNewestUploads() {
        List<AgentInputFile> uploads = List.of(
                upload(1, "老1.csv"), upload(2, "老2.csv"), upload(3, "老3.csv"), upload(9, "刚点的.csv"));

        AgentExecService.InputSelection s =
                AgentExecService.selectInputs(uploads, Set.of(9L), List.of(), 2);

        assertEquals(List.of("老3.csv", "刚点的.csv"), names(s.uploads()),
                "显式项 + 最新的历史上传；最旧的被丢");
        assertTrue(s.artifacts().isEmpty());
    }

    @Test
    @DisplayName("显式文件本身就超上限时全部保留（用户明确要的不能私自丢），且不再追加产物")
    void explicitFilesAreNeverDropped() {
        List<AgentInputFile> uploads = List.of(upload(1, "x1"), upload(2, "x2"), upload(3, "x3"));

        AgentExecService.InputSelection s = AgentExecService.selectInputs(
                uploads, Set.of(1L, 2L, 3L), List.of(artifact(9, "art.png")), 2);

        assertEquals(3, s.uploads().size(), "显式项不被裁（边车会拒，但那是显式行为，不该静默丢用户的文件）");
        assertTrue(s.artifacts().isEmpty(), "名额已满，不再追加产物");
    }

    @Test
    @DisplayName("边界：上限为 0 / 无候选 都不炸")
    void degenerateInputs() {
        AgentExecService.InputSelection zero = AgentExecService.selectInputs(
                List.of(upload(1, "a")), Set.of(), List.of(artifact(2, "b")), 0);
        assertTrue(zero.uploads().isEmpty() && zero.artifacts().isEmpty());

        AgentExecService.InputSelection empty =
                AgentExecService.selectInputs(List.of(), Set.of(), List.of(), 20);
        assertTrue(empty.uploads().isEmpty() && empty.artifacts().isEmpty());
    }
}
