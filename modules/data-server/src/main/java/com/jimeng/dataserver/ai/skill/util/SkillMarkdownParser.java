package com.jimeng.dataserver.ai.skill.util;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import java.util.regex.Pattern;

public final class SkillMarkdownParser {
    private SkillMarkdownParser() {}
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    public record ParsedSkill(String name, String description, String body) {}

    public static ParsedSkill parse(String raw) {
        String normalized = normalize(raw);
        String name = "";
        String description = "";
        String body = normalized.trim();
        if (normalized.startsWith("---\n")) {
            int end = normalized.indexOf("\n---\n", 4);
            if (end >= 0) {
                String fm = normalized.substring(4, end);
                for (String line : fm.split("\n")) {
                    int idx = line == null ? -1 : line.indexOf(':');
                    if (idx <= 0) continue;
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if ("name".equalsIgnoreCase(key) && StrUtil.isNotBlank(value)) name = value;
                    if ("description".equalsIgnoreCase(key)) description = value;
                }
                body = normalized.substring(end + 5).trim();
            }
        }
        return new ParsedSkill(name, description, body);
    }

    /**
     * parse 的逆操作：由 name/description/body 重建带 frontmatter 的规范 SKILL.md。
     * description 用双引号包裹（含冒号也不会被 YAML 解析器截断），换行折叠为空格。
     * 供 finalize 写入 bundle、详情页展示统一格式，与 parse 往返一致。
     */
    public static String render(String name, String description, String body) {
        String n = name == null ? "" : name.trim();
        String desc = description == null ? "" : description
                .replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", " ").replace("\n", " ").trim();
        String b = body == null ? "" : body.trim();
        return "---\n"
                + "name: " + n + "\n"
                + "description: \"" + desc + "\"\n"
                + "---\n\n"
                + b + "\n";
    }

    public static void validate(ParsedSkill p) {
        if (p == null || StrUtil.isBlank(p.name()))
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "SKILL.md frontmatter 必须包含 name");
        if (!NAME_PATTERN.matcher(p.name()).matches())
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "skill 名称只能含字母/数字/下划线/中划线，且以字母开头，长度≤64");
        if (StrUtil.isBlank(p.description()))
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "SKILL.md frontmatter 必须包含 description");
        if (StrUtil.isBlank(p.body()))
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "SKILL.md 正文不能为空");
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String n = raw.replace("\r\n", "\n").replace('\r', '\n');
        return n.startsWith("﻿") ? n.substring(1) : n;
    }
}
