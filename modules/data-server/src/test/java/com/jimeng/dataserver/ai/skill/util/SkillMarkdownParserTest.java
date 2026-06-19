package com.jimeng.dataserver.ai.skill.util;

import com.jimeng.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkillMarkdownParserTest {
    @Test
    void parsesFrontmatterAndBody() {
        String raw = "---\nname: my-skill\ndescription: 测试技能\n---\n# 正文\n内容";
        SkillMarkdownParser.ParsedSkill p = SkillMarkdownParser.parse(raw);
        assertEquals("my-skill", p.name());
        assertEquals("测试技能", p.description());
        assertTrue(p.body().contains("正文"));
    }
    @Test
    void rejectsMissingName() {
        String raw = "---\ndescription: x\n---\n正文";
        ServiceException ex = assertThrows(ServiceException.class,
                () -> SkillMarkdownParser.validate(SkillMarkdownParser.parse(raw)));
        assertTrue(ex.getMessage().contains("name"));
    }
    @Test
    void rejectsIllegalName() {
        String raw = "---\nname: 1bad name\ndescription: x\n---\n正文";
        assertThrows(ServiceException.class,
                () -> SkillMarkdownParser.validate(SkillMarkdownParser.parse(raw)));
    }
}
