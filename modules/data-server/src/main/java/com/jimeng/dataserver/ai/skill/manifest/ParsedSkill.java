package com.jimeng.dataserver.ai.skill.manifest;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;

import java.util.List;

/**
 * 一份 SKILL.md 的解析结果。{@code errors} 为空才算合规；name / description / body 能取到时总是填上
 * （构建器的草稿预览在不合规时也要显示）。
 *
 * @param ignoredKeys 宽松模式下被忽略的顶层键（严格模式恒为空——那里它们是错误）
 * @param decoded     frontmatter 里由 YAML 解码出来的每一个字符串（键名和标量值都算），连同它在哪儿。发布处理器解析之后拿它核对（R34）：
 *                    转义、续行、!!binary、十六进制数字……拼出来的 key 值原文里没有、替换不掉，只有解码之后才看得见；
 *                    不入库的字段（license、被忽略的键）也要核对，因为转义原样留在 bundle 的 SKILL.md 里，运行时模型读得到、也解得开
 */
public record ParsedSkill(String name, String description, String body, JmMetadata jm,
                          List<String> ignoredKeys, List<String> errors, List<Decoded> decoded) {

    /** frontmatter 里解码出来的一个字符串，和它在哪儿（顶层的键名、某个顶层键下面的值、某个顶层键里面的键名）。toString 不带原文。 */
    public record Decoded(String where, String text) {
        @Override
        public String toString() {
            return "Decoded[" + where + "]";
        }
    }

    public ParsedSkill {
        jm = jm == null ? JmMetadata.EMPTY : jm;
        ignoredKeys = ignoredKeys == null ? List.of() : List.copyOf(ignoredKeys);
        errors = errors == null ? List.of() : List.copyOf(errors);
        decoded = decoded == null ? List.of() : List.copyOf(decoded);
    }

    static ParsedSkill failed(String body, List<String> errors) {
        return new ParsedSkill(null, null, body, JmMetadata.EMPTY, List.of(), errors, List.of());
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
