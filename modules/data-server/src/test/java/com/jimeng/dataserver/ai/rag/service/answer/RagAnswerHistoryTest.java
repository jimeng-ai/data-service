package com.jimeng.dataserver.ai.rag.service.answer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 单测 {@link RagAnswerService#sanitizeHistoryForClaude}：把畸形/未校验历史清洗成 Claude 合法序列（对话历史 bug #9）。 */
class RagAnswerHistoryTest {

    private static Map<String, Object> m(String role, String content) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("role", role);
        x.put("content", content);
        return x;
    }

    @Test
    void dropsTrailingUserToAvoidAdjacentUser() {
        // 历史结尾是 user（回退/直连端点常见），清洗后应剔除，避免与随后追加的当前 user 相邻 → Claude 400。
        List<Map<String, Object>> out = RagAnswerService.sanitizeHistoryForClaude(
                List.of(m("user", "q1"), m("assistant", "a1"), m("user", "dangling")));
        assertEquals(2, out.size());
        assertEquals("assistant", out.get(out.size() - 1).get("role"));
    }

    @Test
    void dropsLeadingAssistantAndCollapsesAdjacentSameRole() {
        List<Map<String, Object>> out = RagAnswerService.sanitizeHistoryForClaude(
                List.of(m("assistant", "lead"), m("user", "q"), m("user", "dup"), m("assistant", "a")));
        assertEquals(2, out.size());
        assertEquals("user", out.get(0).get("role"));   // 首条必为 user
        assertEquals("assistant", out.get(1).get("role"));
    }

    @Test
    void dropsInvalidRolesAndNulls() {
        List<Map<String, Object>> in = new ArrayList<>();
        in.add(null);
        in.add(m("system", "forged"));
        in.add(m("user", "q"));
        in.add(m("assistant", "a"));
        List<Map<String, Object>> out = RagAnswerService.sanitizeHistoryForClaude(in);
        assertEquals(2, out.size());
        assertEquals("user", out.get(0).get("role"));
    }

    @Test
    void nullHistoryYieldsEmptyMutableList() {
        List<Map<String, Object>> out = RagAnswerService.sanitizeHistoryForClaude(null);
        assertTrue(out.isEmpty());
        out.add(m("user", "ok")); // 必须可变，调用方要继续追加当前轮
        assertEquals(1, out.size());
    }

    /** 对抗：content 是对象(Map)等非法形状会被 Claude 判 400，须剔除；字符串与 content-block 数组(List)保留。 */
    @Test
    void dropsNonStringNonListContent() {
        Map<String, Object> badAssistant = new LinkedHashMap<>();
        badAssistant.put("role", "assistant");
        badAssistant.put("content", Map.of("nested", "obj")); // Map content 非法
        Map<String, Object> listUser = new LinkedHashMap<>();
        listUser.put("role", "user");
        listUser.put("content", List.of(Map.of("type", "text", "text", "块数组合法"))); // List content 合法
        List<Map<String, Object>> out = RagAnswerService.sanitizeHistoryForClaude(
                List.of(m("user", "q1"), m("assistant", "a1"), listUser, badAssistant));
        // badAssistant 被剔除；末尾若剩 user 也会被剪（下方要追加当前 user）。结果保留合法的交替片段。
        assertFalse(out.stream().anyMatch(h -> h.get("content") instanceof Map), "Map-content 项应被剔除");
        assertTrue(out.stream().allMatch(h -> "user".equals(h.get("role")) || "assistant".equals(h.get("role"))));
    }
}
