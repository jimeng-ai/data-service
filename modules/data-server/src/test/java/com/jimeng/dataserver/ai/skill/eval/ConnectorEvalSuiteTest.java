package com.jimeng.dataserver.ai.skill.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.jimeng.common.core.utils.CommonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@code skills/connector/evals/evals.json}：能被 {@link EvalSuite} 正确反序列化、字段齐全、id 唯一。
 *
 * <h3>为什么这条测试很实在</h3>
 * 这份 JSON <b>不参与任何启动期校验</b>：{@code SkillPackageLoaderService} 只认 SKILL.md 与 tools.json，
 * evals.json 要等到 {@code SkillEvalController.parseSuite} 才第一次被解析——而那一刻已经是有人点了
 * 「跑评测」。写坏一个逗号、把 {@code expectations} 拼成 {@code expectation}，都不会在编译期或启动期
 * 报错，只会在真跑的时候炸；而真跑要 docker + 真 LLM + 真库，<b>那时候钱已经烧掉了</b>。
 *
 * <p>断言走<b>与生产完全相同的解析路径</b>（{@code CommonUtil.getObjectMapper()} + {@code EvalSuite}），
 * 而不是自己拿 Jackson 现搓一个 mapper：生产那个 mapper 上挂着模块与配置，换一个来测等于没测。
 *
 * <h3>为什么要校验「非空」而不只是「能解析」</h3>
 * {@link EvalSuite} 标了 {@code @JsonIgnoreProperties(ignoreUnknown = true)}（刻意如此：官方
 * skill-creator 的 schema 会加字段，加一个就让所有人的评测解析失败是更糟的设计）。代价是
 * <b>字段名拼错不会报错，只会解析成 null</b>——一条断言全没了的用例照样跑，照样出绿灯。
 * 所以这里逐条查 id / prompt / expectations 非空。
 */
class ConnectorEvalSuiteTest {

    private static final String SUITE_PATH = "skills/connector/evals/evals.json";

    /** 与 {@code SkillEvalController.EVALS_PATH} 同一个字面量：用例在 skill 包里的固定位置。 */
    private static final String EVALS_PATH_IN_PACKAGE = "evals/evals.json";

    /**
     * 定位这份 JSON。与 {@code SkillPackageLoaderService.resolveRootPath} 同一套回落：
     * 在模块目录下跑（mvn -pl modules/data-server test）时 user.dir 就是模块目录；
     * 从仓库根跑时再往 modules/data-server 找一层。
     */
    private static Path locateSuite() {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path direct = cwd.resolve(SUITE_PATH).normalize();
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path fallback = cwd.resolve("modules").resolve("data-server").resolve(SUITE_PATH).normalize();
        if (Files.isRegularFile(fallback)) {
            return fallback;
        }
        return direct;      // 返回首选路径，让断言报出一个看得懂的路径
    }

    private static EvalSuite parse() throws IOException {
        Path p = locateSuite();
        assertTrue(Files.isRegularFile(p),
                "connector 技能包缺少 " + EVALS_PATH_IN_PACKAGE + "（找过：" + p + "）");
        String json = Files.readString(p, StandardCharsets.UTF_8);
        // 与 SkillEvalController.parseSuite 逐字相同的解析方式。
        return CommonUtil.getObjectMapper().readValue(json, new TypeReference<EvalSuite>() {});
    }

    @Test
    @DisplayName("evals.json 能被 EvalSuite 反序列化，且 skillName 与 SKILL.md 的 name 一致")
    void deserializesWithMatchingSkillName() throws IOException {
        EvalSuite suite = parse();
        assertNotNull(suite, "解析结果为 null");
        // 名字必须是 connector：SkillEvalService 靠它判定「这轮要不要强制要求 agentId + conn_* 工具」，
        // 写错了会静默退化成「跑一个没有连接器工具的 run」，也就是一个假红灯。
        assertEquals("connector", suite.getSkillName(),
                "skillName 必须与 SKILL.md frontmatter 的 name 一致");
    }

    @Test
    @DisplayName("每条用例的 id / prompt / expectations 都在，且 id 唯一")
    void everyCaseIsComplete() throws IOException {
        EvalSuite suite = parse();
        List<EvalSuite.EvalCase> cases = suite.getEvals();
        assertNotNull(cases, "evals 为 null —— 多半是字段名写错了（@JsonIgnoreProperties 会把它静默吃掉）");
        assertFalse(cases.isEmpty(), "evals 为空：SkillEvalService.start 会直接拒绝这一轮");

        Set<Integer> ids = new HashSet<>();
        for (EvalSuite.EvalCase c : cases) {
            assertNotNull(c.getId(), "有用例缺 id");
            assertTrue(ids.add(c.getId()),
                    "用例 id 重复：" + c.getId() + "（结果按 id 归集，重复会让两条用例的结论互相覆盖）");
            assertNotNull(c.getPrompt(), "用例 " + c.getId() + " 缺 prompt");
            assertFalse(c.getPrompt().isBlank(), "用例 " + c.getId() + " 的 prompt 是空串");
            assertNotNull(c.getExpectedOutput(), "用例 " + c.getId() + " 缺 expectedOutput");
            assertNotNull(c.getExpectations(),
                    "用例 " + c.getId() + " 缺 expectations —— 没有断言的用例只会判 skillInvoked，"
                            + "其余契约一条都测不到");
            assertFalse(c.getExpectations().isEmpty(), "用例 " + c.getId() + " 的 expectations 是空数组");
            for (String e : c.getExpectations()) {
                assertNotNull(e, "用例 " + c.getId() + " 的 expectations 里有 null");
                assertFalse(e.isBlank(), "用例 " + c.getId() + " 的 expectations 里有空串");
            }
        }
    }

    @Test
    @DisplayName("RECALL 模式的前提：prompt 是用户原话，不能提到 skill 名字")
    void promptsNeverMentionTheSkillName() throws IOException {
        // RECALL 模式把 prompt 原样当提示词用（EvalPrompts.recallPrompt），测的是「模型会不会自己想到用」。
        // prompt 里出现 connector 或 conn_xxx，等于把答案写在了题面上：召回率会虚高，
        // 而 description 写坏了这件事照样测不出来——正是这套评测存在的理由被悄悄架空。
        for (EvalSuite.EvalCase c : parse().getEvals()) {
            String lower = c.getPrompt().toLowerCase();
            assertFalse(lower.contains("connector"),
                    "用例 " + c.getId() + " 的 prompt 提到了 skill 名 connector，RECALL 模式下这是作弊");
            assertFalse(lower.contains("conn_"),
                    "用例 " + c.getId() + " 的 prompt 提到了 conn_* 工具名，RECALL 模式下这是作弊");
            assertFalse(lower.contains("skill"),
                    "用例 " + c.getId() + " 的 prompt 提到了 skill，RECALL 模式下这是作弊");
        }
    }

    @Test
    @DisplayName("SKILL.md 里那几条「跳过了就会出错、而且不报错」的契约都有用例覆盖")
    void coversTheSilentlyFailingContracts() throws IOException {
        String all = CommonUtil.getObjectMapper().writeValueAsString(parse()).toLowerCase();
        // 逐条对着 SKILL.md 的高危契约查关键字。做得粗但有用：它拦的是「重写用例时顺手删掉了一整类断言」，
        // 而那种删除不会有任何征兆——评测照样跑、照样绿。
        assertTrue(all.contains("conn_list"), "缺「先 conn_list 再查」的覆盖");
        assertTrue(all.contains("conn_describe"), "缺「不猜表名列名、先 conn_describe」的覆盖");
        assertTrue(all.contains("statement"), "缺「答案必须带 statement」的覆盖");
        assertTrue(all.contains("row_count"), "缺「答案必须带 row_count」的覆盖");
        assertTrue(all.contains("truncated"), "缺「truncated=true 时不得下结论」的覆盖");
        assertTrue(all.contains("conn_define_metric"),
                "缺「用户答不上来时不得替他选、也不得记口径」的覆盖");
        assertTrue(all.contains("basis"), "缺「引用存过的口径必须亮出 basis」的覆盖");
        assertTrue(all.contains("guard_blocked"),
                "缺「guard_blocked 要改写语句、不得让用户去申请更宽权限」的覆盖");
        assertTrue(all.contains("pending_approval"),
                "缺「pending_approval 不等于已完成」的覆盖");
    }
}
