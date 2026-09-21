package com.jimeng.dataserver.ai.connector.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.ConnectorAdminController;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「写工具」这个概念在四个平面上的对齐。
 *
 * <h3>为什么要有这一条（它补的不是某个 bug，是一类 bug）</h3>
 * {@code ConnectorAgentToolNameParityTest} 已经钉住了<b>工具名的集合</b>三方一致。但「哪些工具是
 * <b>写</b>工具」是另一个维度，而它在四个平面上各有一份副本，<b>每一处漏改都不报错</b>：
 *
 * <ol>
 *   <li><b>宿主</b> {@link ConnectorToolExecutor#WRITE_TOOLS} —— 本系统的唯一来源；</li>
 *   <li><b>沙箱</b> {@code jm-agent-sandbox/src/mcp/connectorTools.ts} 的 {@code WRITE_TOOL_NAMES}
 *       —— 漏改，新工具按读策略跑（{@code retries=1}）：超时自动重发，而服务端那一条很可能已经
 *       成功了，于是它被原样再盖一遍；这几十秒里若有业务方改了同一行，那次修改被<b>无痕盖掉</b>；</li>
 *   <li><b>评测</b> {@code evals/evals.json} —— 漏改，这个写工具一条行为契约都没有被覆盖。
 *       它抓到过一个真实的洞：{@code conn_annotate} 上线后在 evals.json 里出现过 <b>0</b> 次；</li>
 *   <li><b>说明书</b> {@code tools.json} 的 description —— 漏改，模型不知道再调一次是<b>覆盖</b>
 *       而不是新增，于是同一个位置被写成两条各说各话的说明。</li>
 * </ol>
 *
 * <p>这四处的共同点是：错了以后<b>一切照常运行</b>。没有异常、没有降级、没有日志，
 * 只是某个不变量从此不再成立。所以它们只能靠对拍，不能靠 review。
 *
 * <h3>★ 加一个新工具时，这条测试会怎么逼你</h3>
 * 先进 {@code READ_TOOLS} 或 {@code WRITE_TOOLS}（不进就不在 {@code TOOLS} 里，
 * {@code supports()} 直接不认它，那个工具从头到尾不生效）；若进的是写侧，
 * 下面四条会依次把沙箱名单、评测覆盖、覆盖语义说明一并逼出来。
 */
class ConnectorWriteToolContractTest {

    private static final String TOOLS_JSON = "skills/connector/tools.json";
    private static final String SKILL_MD = "skills/connector/SKILL.md";
    private static final String EVALS_JSON = "skills/connector/evals/evals.json";

    /**
     * 沙箱那份名单的位置。它在<b>另一个仓库</b>里，所以只在同工作区能读到时才对拍——
     * 与沙箱侧 {@code json-schema-to-zod-test.mjs} 的「漂移核对」同一条取舍：
     * CI 只 checkout 单个仓库，读不到就跳过，而不是把测试写成必须双仓库才能跑。
     */
    private static final String[] SANDBOX_CANDIDATES = {
            "../../../jm-agent-sandbox/src/mcp/connectorTools.ts",
            "../jm-agent-sandbox/src/mcp/connectorTools.ts"
    };

    /**
     * 这两个是「沉淀类」写工具：它们不碰客户的系统，只写平台自己那份说明书。
     *
     * <p>把它们单独拎出来，是因为它们的失败模式与 {@code conn_execute} <b>相反</b>：
     * {@code conn_execute} 的事故是「不该改的时候改了」，所以评测该钉的是<b>克制</b>；
     * 这两个的事故是「该记的时候没记」——用户答完口径，模型不调工具，这一轮答案照样对，
     * 没有任何征兆，而下一轮换个人问，同一个问题要从头再问一遍。
     * 所以只有这两个<b>额外</b>要求正向覆盖。
     */
    private static final Set<String> SEMANTIC_WRITE_TOOLS = Set.of(
            ConnectorToolExecutor.TOOL_DEFINE_METRIC, ConnectorToolExecutor.TOOL_ANNOTATE);

    // ================================================================ ① 分类必须完整

    @Test
    @DisplayName("★ 读 / 写两张表的并集，必须正好是 tools.json 里的全部工具")
    void 每个工具都被分了类() throws IOException {
        Set<String> classified = new TreeSet<>(ConnectorToolExecutor.READ_TOOLS);
        classified.addAll(ConnectorToolExecutor.WRITE_TOOLS);
        Set<String> declared = new TreeSet<>(toolsJson().keySet());

        assertEquals(declared, classified,
                "有工具没被分类，或分类里有 tools.json 里不存在的名字。"
                        + "只在 tools.json 里=" + minus(declared, classified)
                        + "，只在分类里=" + minus(classified, declared)
                        + "。没被分类的工具不在 TOOLS 里，supports() 不认它，表现是这个工具从头到尾不生效");
    }

    @Test
    @DisplayName("读表与写表不得相交——同一个工具两边的超时处置是相反的")
    void 读写两张表不相交() {
        Set<String> both = new TreeSet<>(ConnectorToolExecutor.READ_TOOLS);
        both.retainAll(ConnectorToolExecutor.WRITE_TOOLS);
        assertTrue(both.isEmpty(), "同时登记为读和写：" + both);
    }

    // ================================================================ ② 沙箱那份副本

    @Test
    @DisplayName("★ 沙箱的 WRITE_TOOL_NAMES 与宿主的 WRITE_TOOLS 逐字一致")
    void 沙箱写工具名单不漂移() throws IOException {
        Path ts = firstExisting(SANDBOX_CANDIDATES);
        Assumptions.assumeTrue(ts != null,
                "读不到同工作区的 jm-agent-sandbox（CI 只 checkout 本仓库），跳过这条跨仓库对拍");

        String src = Files.readString(ts, StandardCharsets.UTF_8);
        Matcher block = Pattern.compile("WRITE_TOOL_NAMES[^=]*=\\s*new Set\\(\\[(.*?)]\\)", Pattern.DOTALL)
                .matcher(src);
        assertTrue(block.find(),
                "在 " + ts + " 里找不到 WRITE_TOOL_NAMES = new Set([...])；"
                        + "它要么被改名了，要么写法变了——无论哪种，这条对拍都已经失效，必须同步改这里");

        Set<String> sandbox = new TreeSet<>();
        Matcher name = Pattern.compile("[\"']([^\"']+)[\"']").matcher(block.group(1));
        while (name.find()) {
            sandbox.add(name.group(1));
        }

        assertEquals(new TreeSet<>(ConnectorToolExecutor.WRITE_TOOLS), sandbox,
                "宿主与沙箱的写工具名单分叉了。漏在沙箱一侧的工具会按【读】策略跑："
                        + "超时自动重发，而服务端那一条很可能已经写成功，重发把它原样再盖一遍；"
                        + "若这期间有人改了同一行，那次修改被无痕盖掉。"
                        + "只在宿主=" + minus(new TreeSet<>(ConnectorToolExecutor.WRITE_TOOLS), sandbox)
                        + "，只在沙箱=" + minus(sandbox, new TreeSet<>(ConnectorToolExecutor.WRITE_TOOLS)));
    }

    // ================================================================ ③ 评测覆盖

    @Test
    @DisplayName("★ 每个写工具都要有评测覆盖——conn_annotate 曾经是 0 条")
    void 写工具都被评测提到() throws IOException {
        List<String> all = allExpectations();
        for (String tool : new TreeSet<>(ConnectorToolExecutor.WRITE_TOOLS)) {
            assertTrue(all.stream().anyMatch(e -> e.contains(tool)),
                    "写工具 " + tool + " 在 evals.json 里一条断言都没有。"
                            + "写工具的失败是静默的（调错了照样有答案，不调也照样有答案），"
                            + "评测是唯一能发现它的地方");
        }
    }

    @Test
    @DisplayName("★ 沉淀类写工具必须【正反】双向覆盖：该记时记，不该记时不记")
    void 沉淀类写工具正反双向覆盖() throws IOException {
        List<String> all = allExpectations();
        for (String tool : new TreeSet<>(SEMANTIC_WRITE_TOOLS)) {
            assertTrue(ConnectorToolExecutor.WRITE_TOOLS.contains(tool),
                    tool + " 应当同时在 WRITE_TOOLS 里");

            List<String> about = all.stream().filter(e -> e.contains(tool)).toList();

            assertTrue(about.stream().anyMatch(e -> e.contains("调用过") || e.contains("必须调")),
                    "缺【正向】用例：没有任何一条断言要求「用户明确答过之后必须调用 " + tool + "」。"
                            + "而漏记是完全静默的——本轮答案照样对，只是下一轮换个人问要从头再问一遍。"
                            + "现有与该工具相关的断言：" + about);

            assertTrue(about.stream().anyMatch(e ->
                            e.contains("没有调用") || e.contains("不得调") || e.contains("一次都不调")
                                    || e.contains("没有把")),
                    "缺【负向】用例：没有任何一条断言要求「没人确认过的时候不许调用 " + tool + "」。"
                            + "猜出来的口径 / 含义写进去比不写危险得多：它会被后面每一轮对话当成已确认的事实。"
                            + "现有与该工具相关的断言：" + about);
        }
    }

    // ================================================================ ④ 说明书

    @Test
    @DisplayName("★ 每个写工具的 description 都要讲清「再调一次是覆盖，旧值留痕」")
    void 写工具的描述讲了覆盖语义() throws IOException {
        for (String tool : new TreeSet<>(SEMANTIC_WRITE_TOOLS)) {
            String desc = toolsJson().get(tool);
            assertNotNull(desc, tools_jsonMissing(tool));
            assertTrue(desc.contains("覆盖"),
                    tool + " 的描述没说再调一次是覆盖：模型会以为是新增，于是「销售额」和「销售金额」"
                            + "变成两条各说各话的口径，以后谁也不知道该信哪条");
            assertTrue(desc.contains("留痕"),
                    tool + " 的描述没说旧值会留痕。留痕是「口径被改坏了」之后唯一的取证材料");
        }
    }

    /**
     * 纠正入口这件事，说明书必须跟着<b>代码</b>走。
     *
     * <p>这条钉的不是措辞，而是<b>一个会漂移的事实</b>：删除入口是后来才加的
     * （{@code DELETE /admin/connectors/&#123;id&#125;/semantic/&#123;rowId&#125;}），
     * 而当时 {@code SKILL.md} 改了、{@code tools.json} 没改，于是同一轮上下文里下发给模型的两份文档
     * 一个说「有纠正入口」、一个说「平台没有纠正入口」。模型据后者回答时，
     * 业务方就被告知「没办法改」——而那恰恰是他唯一的纠错路径。
     *
     * <p>所以断言从<b>方法在不在</b>出发：入口还在，文档里就不许出现否定表述，
     * 且必须说清只有企业超管点得到（这是「去找超管」这半句的依据）。哪天这个接口被删或被放开，
     * 这条会红，逼着把两份文档一起改回来。
     */
    @Test
    @DisplayName("★ 「口径能不能纠正」在代码与两份说明书之间不得自相矛盾")
    void 纠正入口的说法与代码一致() throws Exception {
        boolean entryExists = java.util.Arrays.stream(ConnectorAdminController.class.getDeclaredMethods())
                .anyMatch(m -> "deleteSemanticRow".equals(m.getName()));
        assertTrue(entryExists,
                "ConnectorAdminController.deleteSemanticRow 不见了。它要是真被删了，"
                        + "请把 tools.json 与 SKILL.md 里「找企业超管去管理台删掉」那几句一并改掉，再改这条测试");

        String tools = read(TOOLS_JSON);
        String skill = read(SKILL_MD);
        for (String denial : List.of(
                "没有提供管理台的口径纠正入口",
                "没有管理台的口径纠正入口",
                "没有纠正入口",
                "未提供纠正入口")) {
            assertFalse(tools.contains(denial),
                    "tools.json 里仍写着「" + denial + "」，而 deleteSemanticRow 就在代码里。"
                            + "这一句是下发给模型的，会让业务方以为记错的口径没法纠正");
            assertFalse(skill.contains(denial),
                    "SKILL.md 里仍写着「" + denial + "」，而 deleteSemanticRow 就在代码里");
        }

        assertTrue(skill.contains("企业超管"),
                "SKILL.md 必须说清纠正入口只有企业超管点得到——业务方点不到，"
                        + "「去找超管删」这半句才是他唯一能走的路");
        assertTrue(toolsJson().get(ConnectorToolExecutor.TOOL_DEFINE_METRIC).contains("超管"),
                "conn_define_metric 的描述必须提到超管：它是这条纠错链的落点");
    }

    // ---------------------------------------------------------------- 取数

    /** tools.json：工具名 → description。 */
    private static java.util.Map<String, String> toolsJson() throws IOException {
        JsonNode arr = CommonUtil.getObjectMapper().readTree(read(TOOLS_JSON));
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (JsonNode t : arr) {
            out.put(t.path("name").asText(), t.path("description").asText(null));
        }
        return out;
    }

    /** evals.json 里全部 expectations 拍平——断言只关心「这句话在不在」，不关心它属于哪条用例。 */
    private static List<String> allExpectations() throws IOException {
        JsonNode suite = CommonUtil.getObjectMapper().readTree(read(EVALS_JSON));
        List<String> out = new ArrayList<>();
        for (JsonNode c : suite.path("evals")) {
            for (JsonNode e : c.path("expectations")) {
                out.add(e.asText(""));
            }
        }
        assertFalse(out.isEmpty(), "evals.json 一条 expectation 都没读到，多半是字段名变了");
        return out;
    }

    /** 与 {@code EmptyTableSkillDocParityTest.read} 同一套工作目录回落。 */
    private static String read(String relative) throws IOException {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path direct = cwd.resolve(relative).normalize();
        Path path = Files.isRegularFile(direct)
                ? direct
                : cwd.resolve("modules").resolve("data-server").resolve(relative).normalize();
        assertTrue(Files.isRegularFile(path), "找不到 " + relative + "，实际找过: " + direct + " 和 " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path firstExisting(String[] candidates) {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        for (String c : candidates) {
            Path p = cwd.resolve(c).normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static String tools_jsonMissing(String tool) {
        return "tools.json 里没有 " + tool + " 的 description";
    }
}
