package com.jimeng.dataserver.ai.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolDescDisplay}：工具描述下发前只留首句。
 *
 * <p>钉住的是一条边界——<b>写给模型的负向约束不得出现在客户眼前</b>。
 * 原先 connector 的 7 条描述共 5724 字全量下发并落库，其中包括
 * 「这是在改<b>客户的</b>生产数据」这种第三人称句，而读到它的正是那个「客户」。
 */
class ToolDescDisplayTest {

    private static final Path TOOLS_JSON =
            Path.of("skills/connector/tools.json");

    @Test
    @DisplayName("★ 只留首句：模型指令被挡在首句之后")
    void 只留首句() {
        String desc = "在某个连接器上执行一条【会改变数据】的语句（INSERT / UPDATE / DELETE）。"
                + "请如实告知用户，不要编造数据。绝不能说「已完成」「已修改」。"
                + "这是在改客户的生产数据。";

        String out = ToolDescDisplay.firstSentence(desc);

        assertEquals("在某个连接器上执行一条【会改变数据】的语句（INSERT / UPDATE / DELETE）", out);
        assertFalse(out.contains("不要编造数据"));
        assertFalse(out.contains("绝不能"));
        assertFalse(out.contains("客户的生产数据"));
    }

    @Test
    @DisplayName("「【】」是句中强调，不能当分隔符——否则句子被腰斩")
    void 方括号不切() {
        assertEquals("在某个连接器上执行一条【会改变数据】的语句",
                ToolDescDisplay.firstSentence("在某个连接器上执行一条【会改变数据】的语句。后面是给模型的。"));
    }

    @Test
    @DisplayName("换行也算句末")
    void 换行切() {
        assertEquals("列出当前 Agent 被授权使用的外部系统连接器",
                ToolDescDisplay.firstSentence("列出当前 Agent 被授权使用的外部系统连接器\n不要猜连接器名字。"));
    }

    @Test
    @DisplayName("首句本身超长也要截——不能把边界交给写描述的人")
    void 超长首句截断() {
        String longOne = "很长的描述".repeat(100);   // 500 字，无句号
        String out = ToolDescDisplay.firstSentence(longOne);
        assertEquals(ToolDescDisplay.MAX_LEN, out.length());
    }

    @Test
    @DisplayName("null / 空白原样返回，交给调用方决定是否下发该字段")
    void 空值() {
        assertNull(ToolDescDisplay.firstSentence(null));
        assertEquals("   ", ToolDescDisplay.firstSentence("   "));
    }

    @Test
    @DisplayName("以句号开头时退回整段，不返回空标题")
    void 句号开头() {
        assertEquals("。。", ToolDescDisplay.firstSentence("。。"));
    }

    @Test
    @DisplayName("★ 真实 tools.json：7 条描述 5724 字 → 全部收敛到 MAX_LEN 以内")
    @EnabledIf("toolsJsonExists")
    void 真实描述全部收敛() throws Exception {
        JsonNode tools = new ObjectMapper().readTree(Files.readString(TOOLS_JSON));
        int rawTotal = 0;
        int shortTotal = 0;
        for (JsonNode t : tools) {
            String desc = t.path("description").asText("");
            String out = ToolDescDisplay.firstSentence(desc);
            rawTotal += desc.length();
            shortTotal += out == null ? 0 : out.length();
            assertTrue(out != null && out.length() <= ToolDescDisplay.MAX_LEN,
                    t.path("name").asText() + " 的首句应收敛到 " + ToolDescDisplay.MAX_LEN + " 字以内");
        }
        // 原始体量确实是「几千字」量级，截断后是「几百字」量级——这正是要消除的差额。
        assertTrue(rawTotal > 5000, "真实描述总量应在 5000 字以上，实际 " + rawTotal);
        assertTrue(shortTotal < rawTotal / 10,
                "截断后应不足原来的 1/10，实际 " + shortTotal + " / " + rawTotal);
    }

    @SuppressWarnings("unused")
    static boolean toolsJsonExists() {
        return Files.exists(TOOLS_JSON);
    }
}
