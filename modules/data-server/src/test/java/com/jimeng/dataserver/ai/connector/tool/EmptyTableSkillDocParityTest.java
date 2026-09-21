package com.jimeng.dataserver.ai.connector.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.jimeng.common.core.utils.CommonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空表标记（缺陷 B19）的<b>键名</b>在代码与说明书之间对得上。
 *
 * <h3>为什么值得单写一条</h3>
 * {@code SKILL.md} 与 {@code tools.json} 的 description 是模型唯一能读到的「这个标记怎么用」，
 * 而且是<b>两个平面共用</b>的同一份真相源（宿主平面的 {@code ConnectorToolExecutor} 与沙箱平面的
 * {@code conn_*} 代理读的是同一个 skill 包）。所以把 {@code KEY_EMPTY_TABLE} 改个名、
 * 或者把说明书里这一段删掉，代码照样编译、测试照样绿、工具照样返回——
 * 只是模型再也不知道自己收到的那个 key 是什么意思，<b>又回到「把空集当答案」的老路上</b>，
 * 而且没有任何一处会报错。
 *
 * <p>只钉<b>键名</b>与「这句话在不在」，不钉整段措辞：措辞要能改（评测会推着它改），
 * 键名不能悄悄分叉。
 */
class EmptyTableSkillDocParityTest {

    private static final String SKILL_MD = "skills/connector/SKILL.md";
    private static final String TOOLS_JSON = "skills/connector/tools.json";

    /**
     * 与 {@code ConnectorEvalSuiteTest.locateSuite} 同一套回落：在模块目录下跑时 user.dir 就是模块目录，
     * 从仓库根跑时再往 modules/data-server 找一层。
     */
    private static String read(String relative) throws IOException {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path direct = cwd.resolve(relative).normalize();
        Path path = Files.isRegularFile(direct)
                ? direct
                : cwd.resolve("modules").resolve("data-server").resolve(relative).normalize();
        assertTrue(Files.isRegularFile(path), "找不到 " + relative + "，实际找过: " + direct + " 和 " + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** tools.json 里某个工具的 description。 */
    private static String toolDescription(String toolName) throws IOException {
        JsonNode tools = CommonUtil.getObjectMapper().readTree(read(TOOLS_JSON));
        for (JsonNode t : tools) {
            if (toolName.equals(t.path("name").asText())) {
                String desc = t.path("description").asText(null);
                assertNotNull(desc, toolName + " 没有 description");
                return desc;
            }
        }
        throw new AssertionError("tools.json 里没有 " + toolName);
    }

    // ================================================================ tools.json

    /** 选表就发生在 {@code conn_catalog} 这一刻——这里不说，模型等拿到 0 行再想已经晚了。 */
    @Test
    @DisplayName("★ conn_catalog 的 description 讲清了 empty_table 两个键")
    void 目录工具的描述里有空表标记() throws IOException {
        String desc = toolDescription(ConnectorToolExecutor.TOOL_CATALOG);

        assertTrue(desc.contains(ConnectorToolExecutor.KEY_EMPTY_TABLE),
                "conn_catalog 返回里会出现这个 key，描述里必须说它是什么意思");
        assertTrue(desc.contains(ConnectorToolExecutor.KEY_EMPTY_TABLE_AT),
                "少了观测时刻这一栏，标记会被当成永恒真理");
        assertTrue(desc.contains("一行数据都没有"), "要让模型能直接把这句话说给用户。实际: " + desc);
        assertTrue(desc.contains("没有这个标记不等于表里有数据"),
                "缺了这一句，模型会把「没标记」读成「平台确认有数据」——那是我们从没说过的话");
    }

    @Test
    @DisplayName("★ conn_describe 的 description 讲清了 empty_table 两个键")
    void 描述工具的描述里有空表标记() throws IOException {
        String desc = toolDescription(ConnectorToolExecutor.TOOL_DESCRIBE);

        assertTrue(desc.contains(ConnectorToolExecutor.KEY_EMPTY_TABLE));
        assertTrue(desc.contains(ConnectorToolExecutor.KEY_EMPTY_TABLE_AT));
        assertTrue(desc.contains("一行数据都没有"), "实际: " + desc);
        assertTrue(desc.contains("没有这个标记不等于表里有数据"), "实际: " + desc);
    }

    // ================================================================ SKILL.md

    /**
     * SKILL.md 里那一节是「零结果 ≠ 没有」<b>唯一的例外</b>：那条规则说不知道时不许断言，
     * 这一节说已经知道它是空的，此时含糊其辞反而把一个确定的事实说成了一次可能的失败。
     * 两句话必须同时在，少哪一句模型都会走偏。
     */
    @Test
    @DisplayName("★ SKILL.md 有 empty_table 这一节，且与「零结果 ≠ 没有」并存")
    void 说明书里有空表这一节() throws IOException {
        String md = read(SKILL_MD);

        assertTrue(md.contains(ConnectorToolExecutor.KEY_EMPTY_TABLE),
                "SKILL.md 是模型读到的主文档，键名不在里面等于这个标记没有说明书");
        assertTrue(md.contains(ConnectorToolExecutor.KEY_EMPTY_TABLE_AT), "观测时刻这一栏同样要讲");
        assertTrue(md.contains("一行数据都没有"), "要让模型能直接把这句话说给用户");
        assertTrue(md.contains("零结果 ≠ 没有"),
                "这一节是那条规则的例外，不是替代品——那条规则被删掉，模型会把每一个 0 行都当成「没有」");
        assertTrue(md.contains("没有这个标记，不等于表里有数据")
                        || md.contains("没有这个标记不等于表里有数据"),
                "缺了这一句，模型会把「没标记」读成「平台确认有数据」");
    }
}
