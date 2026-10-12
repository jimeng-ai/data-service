package com.jimeng.dataserver.ai.agent.exec.dto;

import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 运行请求契约对拍（设计文档 §8.1 第 1 条）：Java 这边。
 *
 * <p>{@link #full()} 把每个字段都填上值，按派发同一条路径（Hutool {@code JSONUtil.toJsonStr}）序列化，必须与金样
 * {@code contract/sidecar-run-request.golden.json} 逐字段相同。沙箱仓库有一份逐字节相同的金样，那边用 TypeScript 编译器
 * 读出 RunRequest 的类型树逐层比对——两边各自对着金样，金样把两边钉在一起。
 *
 * <p>改了运行请求的字段：带 {@code -Dcontract.regenerate=true} 跑一次本测试重新生成金样，看过 diff 后复制到
 * 沙箱的 {@code test/fixtures/}，两边一起提交。
 */
class SidecarRunPayloadContractTest {

    private static final Path GOLDEN = Path.of("src/test/resources/contract/sidecar-run-request.golden.json");
    private static final Path IP_CASES = Path.of("src/test/resources/contract/ip-block-cases.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 每个字段都填了值的载荷。新加字段忘了在这里填，{@link #没有漏填的字段} 会红。 */
    static SidecarRunPayload full() {
        SidecarRunPayload p = new SidecarRunPayload();
        p.setRunId("2099066794181541899");
        p.setTenantId("t_contract");
        p.setUserId("1900000000000000001");
        p.setTraceId("trace-contract");
        p.setAgentId("2099066794181541890");
        p.setPrompt("对拍用的一句话");
        SidecarRunPayload.History h = new SidecarRunPayload.History();
        h.setRole("user");
        h.setContent("上一句");
        p.setHistory(List.of(h));
        SidecarRunPayload.InputFile f = new SidecarRunPayload.InputFile();
        f.setObjectName("agent-input/a.csv");
        f.setFilename("a.csv");
        f.setBucket("rag");
        f.setSizeBytes(12L);
        p.setInputFiles(List.of(f));
        p.setArtifactBucket("rag");
        SidecarRunPayload.RagContext rag = new SidecarRunPayload.RagContext();
        rag.setKbId("2099066794181541801");
        rag.setTopK(5);
        rag.setRerank(true);
        rag.setAccessToken("RAG-SENTINEL");
        p.setRagContext(rag);
        SidecarRunPayload.Llm llm = new SidecarRunPayload.Llm();
        llm.setBaseUrl("https://api.302.ai/v1");
        llm.setAuthToken("LLM-SENTINEL");
        llm.setModel("claude-opus-4-8");
        llm.setAuthScheme("bearer");
        p.setLlm(llm);
        p.setSystemPrompt("你是对拍助手");
        SidecarRunPayload.ImageGen ig = new SidecarRunPayload.ImageGen();
        ig.setBaseUrl("https://img.example.com");
        ig.setAuthToken("IMG-SENTINEL");
        ig.setModel("gpt-image-2");
        ig.setAuthScheme("bearer");
        ig.setProvider("openai");
        ig.setBatchConcurrency(2);
        p.setImageGen(ig);
        SidecarRunPayload.WebSearch ws = new SidecarRunPayload.WebSearch();
        ws.setBaseUrl("https://search.example.com");
        ws.setAuthToken("WEB-SENTINEL");
        ws.setProvider("anysearch");
        ws.setMaxResults(5);
        ws.setAuthScheme("bearer");
        p.setWebSearch(ws);
        SidecarRunPayload.Limits limits = new SidecarRunPayload.Limits();
        limits.setWallClockSec(300);
        limits.setMaxTurns(25);
        limits.setMaxBudgetUsd(2.5);
        p.setLimits(limits);
        SidecarRunPayload.SkillFile sf = new SidecarRunPayload.SkillFile();
        sf.setObjectName("skills/7/2/SKILL.md");
        sf.setRelPath("SKILL.md");
        sf.setBucket("rag");
        SidecarRunPayload.SkillRef sr = new SidecarRunPayload.SkillRef();
        sr.setName("crm");
        sr.setFiles(List.of(sf));
        p.setSkills(List.of(sr));
        p.setRunProfile("default");
        SidecarRunPayload.SemanticContext sc = new SidecarRunPayload.SemanticContext();
        sc.setCallbackBaseUrl("http://localhost:10011/data");
        sc.setAccessToken("SEM-SENTINEL");
        sc.setGenerationId("2099066794181541890");
        sc.setConnectorId("2099066794181541891");
        sc.setSliceNo(1);
        p.setSemanticContext(sc);
        SidecarRunPayload.ToolSchema ts = new SidecarRunPayload.ToolSchema();
        ts.setName("conn_query");
        ts.setDescription("只读查询");
        ts.setInputSchema(Map.of("type", "object"));
        SidecarRunPayload.ConnectorContext cc = new SidecarRunPayload.ConnectorContext();
        cc.setCallbackBaseUrl("http://localhost:10011/data");
        cc.setAccessToken("CONN-SENTINEL");
        cc.setAgentId("2099066794181541890");
        cc.setTools(List.of(ts));
        p.setConnectorContext(cc);
        SidecarRunPayload.Workspace w = new SidecarRunPayload.Workspace();
        w.setBucket("rag");
        w.setPrefix("skill-builder/1/");
        p.setWorkspace(w);
        SidecarRunPayload.Candidate cand = new SidecarRunPayload.Candidate();
        cand.setName("crm");
        cand.setDescription("处理退款");
        SidecarRunPayload.TriggerEval te = new SidecarRunPayload.TriggerEval();
        te.setHeader("技能列表");
        te.setLineTemplate("- **{name}**: {description}");
        te.setCandidates(List.of(cand));
        te.setTools(List.of(new LinkedHashMap<>(Map.of("name", "activate_skills"))));
        te.setModel("claude-opus-4-8");
        te.setMaxTokens(256);
        te.setActivateToolName("activate_skills");
        SidecarRunPayload.SkillBuilder sb = new SidecarRunPayload.SkillBuilder();
        sb.setTriggerEval(te);
        sb.setSkillTypeOverride("PROMPT");
        sb.setCliMaxWorkers(2);
        p.setSkillBuilder(sb);
        SidecarRunPayload.HttpKeyDecl k = new SidecarRunPayload.HttpKeyDecl();
        k.setSkill("crm");
        k.setName("CRM_TOKEN");
        k.setHosts(List.of("api.customer.com"));
        k.setMethods(List.of("GET"));
        k.setPaths(List.of("/v1/orders/**"));
        SidecarRunPayload.HttpContext hc = new SidecarRunPayload.HttpContext();
        hc.setCallbackBaseUrl("http://localhost:10011/data");
        hc.setAccessToken("HTTP-SENTINEL");
        hc.setTimeoutSeconds(90);
        hc.setKeys(List.of(k));
        hc.setHosts(List.of("cdn.example.com"));
        hc.setWeb(true);
        p.setHttpContext(hc);
        return p;
    }

    private static JsonNode wire() throws IOException {
        return JSON.readTree(JSONUtil.toJsonStr(full()));
    }

    @Test
    @DisplayName("Java 序列化结果与金样逐字段相同")
    void 与金样一致() throws IOException {
        if (Boolean.getBoolean("contract.regenerate")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wire()) + "\n", StandardCharsets.UTF_8);
            fail("金样已重新生成：" + GOLDEN.toAbsolutePath() + "。看过 diff、复制到沙箱的 test/fixtures/ 之后，去掉 -Dcontract.regenerate 再跑。");
        }
        assertThat(Files.isRegularFile(GOLDEN)).as("运行请求金样必须先由实际 Hutool 序列化生成：%s", GOLDEN).isTrue();
        assertThat(wire()).isEqualTo(JSON.readTree(GOLDEN.toFile()));
    }

    @Test
    @DisplayName("没有漏填的字段：漏填的字段 Hutool 不输出，对拍就钉不住它")
    void 没有漏填的字段() throws IllegalAccessException {
        List<String> missing = new ArrayList<>();
        walk(full(), "payload", missing);
        assertThat(missing).as("这些字段在 full() 里没填值").isEmpty();
    }

    private static void walk(Object o, String path, List<String> missing) throws IllegalAccessException {
        for (Field f : o.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object v = f.get(o);
            String p = path + "." + f.getName();
            if (v == null) {
                missing.add(p);
            } else if (v instanceof List<?> l) {
                if (l.isEmpty()) missing.add(p + "（空列表）");
                else {
                    for (int i = 0; i < l.size(); i++) {
                        Object item = l.get(i);
                        if (item == null) missing.add(p + "[" + i + "]");
                        else if (ours(item)) walk(item, p + "[" + i + "]", missing);
                    }
                }
            } else if (ours(v)) {
                walk(v, p, missing);
            }
        }
    }

    private static boolean ours(Object v) {
        return v.getClass().getName().startsWith(SidecarRunPayload.class.getName());
    }

    // ------------------------------------------------------------------ 本机工作区里并排的沙箱仓库

    private static Path sibling(String rel) {
        return sibling(Paths.get("").toAbsolutePath(), rel);
    }

    private static Path sibling(Path start, String rel) {
        Path peer = null;
        Path dir = start;
        for (int i = 0; dir != null && i < 6; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("jm-agent-sandbox");
            if (Files.isDirectory(candidate)) {
                peer = candidate;
                break;
            }
        }
        assumeTrue(peer != null, "本机工作区没有并排的沙箱仓库，跳过");
        Path fixture = peer.resolve(rel);
        assertThat(Files.isRegularFile(fixture))
                .as("最近的沙箱仓库缺少契约用例文件：%s", fixture).isTrue();
        return fixture;
    }

    @Test
    @DisplayName("两个仓库的运行请求金样逐字节相同（只在本机工作区并排时检查）")
    void 两份金样相同() throws IOException {
        Path other = sibling("test/fixtures/sidecar-run-request.golden.json");
        assertThat(Files.readAllBytes(other)).as(other.toString()).isEqualTo(Files.readAllBytes(GOLDEN));
    }

    @Test
    @DisplayName("两个仓库的内网判定用例逐字节相同（只在本机工作区并排时检查）")
    void 两份内网用例相同() throws IOException {
        Path other = sibling("test/fixtures/ip-block-cases.json");
        assertThat(Files.readAllBytes(other)).as(other.toString()).isEqualTo(Files.readAllBytes(IP_CASES));
    }


    // 六层候选目录都在临时目录内，回归测试不动真实的相邻仓库或它的文件。
    @TempDir
    Path temporary;

    private Path isolatedStart() throws IOException {
        return Files.createDirectories(temporary.resolve("outer/inner/data-service/modules/data-server"));
    }

    @Test
    void 相邻仓库缺运行金样不能跳过() throws IOException {
        assertMissingPeerFixture("test/fixtures/sidecar-run-request.golden.json");
    }

    @Test
    void 相邻仓库缺内网用例不能跳过() throws IOException {
        assertMissingPeerFixture("test/fixtures/ip-block-cases.json");
    }

    private void assertMissingPeerFixture(String rel) throws IOException {
        Path start = isolatedStart();
        Path peer = Files.createDirectories(temporary.resolve("outer/inner/jm-agent-sandbox"));
        assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> sibling(start, rel))
                .withMessageContaining(peer.resolve(rel).toString());
    }

    @Test
    void 最近仓库缺文件不能借用外层仓库的文件() throws IOException {
        Path start = isolatedStart();
        Path nearest = Files.createDirectories(temporary.resolve("outer/inner/jm-agent-sandbox"));
        for (String rel : List.of("test/fixtures/sidecar-run-request.golden.json", "test/fixtures/ip-block-cases.json")) {
            Path outer = temporary.resolve("outer/jm-agent-sandbox").resolve(rel);
            Files.createDirectories(outer.getParent());
            Files.writeString(outer, "outer fixture");
            assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> sibling(start, rel))
                    .withMessageContaining(nearest.resolve(rel).toString());
        }
    }

    @Test
    void 最近仓库有文件时只读最近仓库() throws IOException {
        Path start = isolatedStart();
        String rel = "test/fixtures/sidecar-run-request.golden.json";
        Path nearest = temporary.resolve("outer/inner/jm-agent-sandbox").resolve(rel);
        Path outer = temporary.resolve("outer/jm-agent-sandbox").resolve(rel);
        Files.createDirectories(nearest.getParent());
        Files.createDirectories(outer.getParent());
        Files.writeString(nearest, "nearest fixture");
        Files.writeString(outer, "outer fixture");
        assertThat(Files.readString(sibling(start, rel))).isEqualTo("nearest fixture");
    }

    @Test
    void 没有相邻仓库时保留独立CI跳过() throws IOException {
        Path start = isolatedStart();
        assertThatExceptionOfType(TestAbortedException.class)
                .isThrownBy(() -> sibling(start, "test/fixtures/sidecar-run-request.golden.json"))
                .withMessageContaining("本机工作区没有并排的沙箱仓库");
    }
}
