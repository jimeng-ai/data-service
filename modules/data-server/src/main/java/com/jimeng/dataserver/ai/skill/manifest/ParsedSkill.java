package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;

import java.util.List;

/**
 * 一份 SKILL.md 的解析结果。{@code errors} 为空才算合规；name / description / body 能取到时总是填上
 * （构建器的草稿预览在不合规时也要显示）。
 *
 * @param ignoredKeys 宽松模式下被忽略的顶层键（严格模式恒为空——那里它们是错误）
 */
public record ParsedSkill(String name, String description, String body, JmMetadata jm,
                          List<String> ignoredKeys, List<String> errors) {

    public ParsedSkill {
        jm = jm == null ? JmMetadata.EMPTY : jm;
        ignoredKeys = ignoredKeys == null ? List.of() : List.copyOf(ignoredKeys);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    static ParsedSkill failed(String body, List<String> errors) {
        return new ParsedSkill(null, null, body, JmMetadata.EMPTY, List.of(), errors);
    }

    public boolean valid() {
        return errors.isEmpty();
    }

    /** 合规就原样返回；否则抛 {@link ServiceException}（INVALID_REQUEST），文案把每条错误都列出来。 */
    public ParsedSkill orThrow() {
        if (!valid()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "SKILL.md 不符合规则：" + String.join("；", errors));
        }
        return this;
    }
}
