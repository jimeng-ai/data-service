package com.jimeng.dataserver.ai.skill.model;

import com.jimeng.common.core.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code requires:} 的解析。
 *
 * <p>全部四条都是「错了不会报错」的那种，所以逐条钉住——尤其第三条：
 * frontmatter 对未知键一向静默忽略（{@code argument-hint:} 今天就被默默丢着），
 * 如果 {@code requires} 也照办，一个拼错的值会让该直注的 Skill <b>安静地退回走发现</b>，
 * 而那正是这个字段被引入来修掉的那个 bug 的形状。
 */
class SkillRequirementTest {

    @Test
    @DisplayName("认识的值按 frontmatter 字面量解析，大小写无关")
    void parsesKnownTokens() {
        assertEquals(SkillRequirement.CONNECTIONS, SkillRequirement.parse("connections"));
        assertEquals(SkillRequirement.KNOWLEDGE_BASES, SkillRequirement.parse("knowledge_bases"));
        assertEquals(SkillRequirement.CONNECTIONS, SkillRequirement.parse("  CONNECTIONS  "));
    }

    @Test
    @DisplayName("没写 requires = 没有前置资源（走发现），不是错误")
    void blankMeansNoRequirement() {
        assertNull(SkillRequirement.parse(null));
        assertNull(SkillRequirement.parse(""));
        assertNull(SkillRequirement.parse("   "));
    }

    @Test
    @DisplayName("★ 认不出来的值必须当场抛，绝不能兜底成 null——拼错一个字母的后果就是静默退回走发现")
    void unknownTokenThrowsInsteadOfFallingBack() {
        // 少个 s：最可能的手误，也是最危险的一种——它长得完全正常。
        ServiceException e = assertThrows(ServiceException.class, () -> SkillRequirement.parse("connection"));
        assertTrue(e.getMessage().contains("connections"), "报错要把合法取值列出来：" + e.getMessage());
        assertTrue(e.getMessage().contains("connection"), "报错要回显收到的值：" + e.getMessage());

        assertThrows(ServiceException.class, () -> SkillRequirement.parse("knowledgebases"));
        assertThrows(ServiceException.class, () -> SkillRequirement.parse("true"));
    }

    @Test
    @DisplayName("token 与枚举名不是一回事：写进 SKILL.md 的是 token，别拿 name() 去比")
    void tokenIsTheWireFormat() {
        assertEquals("knowledge_bases", SkillRequirement.KNOWLEDGE_BASES.token());
        assertEquals("connections", SkillRequirement.CONNECTIONS.token());
    }
}
