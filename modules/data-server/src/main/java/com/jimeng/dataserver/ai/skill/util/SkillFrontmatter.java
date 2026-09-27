package com.jimeng.dataserver.ai.skill.util;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SKILL.md 的 frontmatter 解析与校验，逐条移植自 Anthropic skill-creator 的 {@code scripts/quick_validate.py}
 * （Skill 构建器原样跑 skill-creator，发布关口就用它自己的规则，不另立一套）。
 *
 * <p>规则：以 {@code ---} 开头的 YAML 字典；只允许 name / description / license / allowed-tools / metadata /
 * compatibility 六个键；name 必填、kebab-case、不以连字符开头结尾、无连续连字符、≤64；description 必填、
 * 不含尖括号、≤1024；compatibility 可选、≤500。「只能有一个 SKILL.md」这条在调用方按目录判（见 SkillWorkspaceReader）。
 */
public final class SkillFrontmatter {

    public static final Set<String> ALLOWED_KEYS =
            Set.of("name", "description", "license", "allowed-tools", "metadata", "compatibility");
    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\r?\\n(.*?)\\r?\\n---[ \\t]*(?:\\r?\\n|\\z)", Pattern.DOTALL);
    private static final Pattern KEBAB = Pattern.compile("^[a-z0-9-]+$");

    /** 解析结果。{@code errors} 为空才算通过；name / description / body 在能取到时总是填上（便于草稿预览）。 */
    public record Parsed(String name, String description, String body, Map<String, Object> fields, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private SkillFrontmatter() {}

    public static Parsed parse(String content) {
        List<String> errors = new ArrayList<>();
        if (content == null || !content.startsWith("---")) {
            errors.add("SKILL.md 缺少 YAML frontmatter（第一行必须是 ---）");
            return new Parsed(null, null, content == null ? "" : content, Map.of(), errors);
        }
        Matcher m = FRONTMATTER.matcher(content);
        if (!m.find()) {
            errors.add("frontmatter 格式不对：找不到结尾的 ---");
            return new Parsed(null, null, content, Map.of(), errors);
        }
        String body = content.substring(m.end()).replaceFirst("\\A(\\r?\\n)+", "");
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(m.group(1));
        } catch (Exception e) {
            errors.add("frontmatter 不是合法的 YAML：" + e.getMessage());
            return new Parsed(null, null, body, Map.of(), errors);
        }
        if (!(loaded instanceof Map<?, ?> raw)) {
            errors.add("frontmatter 必须是 YAML 字典");
            return new Parsed(null, null, body, Map.of(), errors);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        raw.forEach((k, v) -> fields.put(String.valueOf(k), v));

        List<String> unexpected = fields.keySet().stream().filter(k -> !ALLOWED_KEYS.contains(k)).sorted().toList();
        if (!unexpected.isEmpty()) {
            errors.add("frontmatter 里有不允许的键：" + String.join(", ", unexpected)
                    + "（只允许 " + String.join(", ", ALLOWED_KEYS.stream().sorted().toList()) + "）");
        }

        String name = null;
        Object n = fields.get("name");
        if (n == null) {
            errors.add("frontmatter 缺少 name");
        } else if (!(n instanceof String s)) {
            errors.add("name 必须是字符串");
        } else {
            name = s.strip();
            if (!name.isEmpty()) {
                if (!KEBAB.matcher(name).matches()) {
                    errors.add("name「" + name + "」必须是 kebab-case（小写字母、数字、连字符）");
                } else if (name.startsWith("-") || name.endsWith("-") || name.contains("--")) {
                    errors.add("name「" + name + "」不能以连字符开头或结尾，也不能有连续连字符");
                }
                if (name.length() > 64) errors.add("name 太长（" + name.length() + " 字符），最多 64");
            } else {
                errors.add("name 不能为空");
            }
        }

        String description = null;
        Object d = fields.get("description");
        if (d == null) {
            errors.add("frontmatter 缺少 description");
        } else if (!(d instanceof String s)) {
            errors.add("description 必须是字符串");
        } else {
            description = s.strip();
            if (description.isEmpty()) {
                errors.add("description 不能为空");
            } else {
                if (description.contains("<") || description.contains(">")) {
                    errors.add("description 不能包含尖括号（< 或 >）");
                }
                // 按码点计：与 quick_validate.py 的 len()、MySQL VARCHAR(1024) 的字符数同一口径（emoji 等不按 2 算）
                int len = description.codePointCount(0, description.length());
                if (len > 1024) {
                    errors.add("description 太长（" + len + " 字符），最多 1024");
                }
            }
        }

        Object c = fields.get("compatibility");
        if (c != null && !"".equals(c)) {
            if (!(c instanceof String s)) {
                errors.add("compatibility 必须是字符串");
            } else if (s.codePointCount(0, s.length()) > 500) {
                errors.add("compatibility 太长（" + s.codePointCount(0, s.length()) + " 字符），最多 500");
            }
        }
        return new Parsed(name, description, body, fields, errors);
    }
}
