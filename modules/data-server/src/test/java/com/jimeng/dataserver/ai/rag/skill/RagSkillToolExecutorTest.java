package com.jimeng.dataserver.ai.rag.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：工具名归一后，执行器必须匹配模型实际收到/回传的下划线名（rag_search / rag_kb_list）。
 * 之前执行器用点号 rag.search 比对，而 Anthropic 工具名不允许点号、被 normalizeToolName 改成下划线，
 * 导致 supports 永远返回 false → 「未找到可执行的tool: rag_search」。
 */
class RagSkillToolExecutorTest {

    // supports() 是纯逻辑，不触及依赖，构造传 null 即可
    private final RagSkillToolExecutor executor = new RagSkillToolExecutor(null, null, null, null);

    @Test
    void supports_underscoreNames() {
        assertTrue(executor.supports("rag_search"), "模型实际调用的下划线名必须匹配");
        assertTrue(executor.supports("rag_kb_list"));
    }

    @Test
    void supports_dottedNames_defensive() {
        // 防御性归一：点号写法也认，避免将来再踩点/下划线漂移
        assertTrue(executor.supports("rag.search"));
        assertTrue(executor.supports("rag.kb.list"));
    }

    @Test
    void supports_rejectsUnknownAndNull() {
        assertFalse(executor.supports("other_tool"));
        assertFalse(executor.supports(null));
    }
}
