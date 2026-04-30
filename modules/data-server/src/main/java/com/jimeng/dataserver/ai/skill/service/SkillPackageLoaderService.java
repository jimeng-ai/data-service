package com.jimeng.dataserver.ai.skill.service;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.skill.model.SkillPackage;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
@Slf4j
public class SkillPackageLoaderService {

    private static final String SKILL_FILE = "SKILL.md";
    private static final String TOOLS_FILE = "tools.json";
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    @Value("${skill.root-dir:skills}")
    private String skillRootDir;

    public Map<String, SkillPackage> loadSkillPackages() {
        Path root = resolveRootPath();
        if (root == null || !Files.isDirectory(root)) {
            return Collections.emptyMap();
        }

        Map<String, SkillPackage> byName = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> skillDirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path skillDir : skillDirs) {
                SkillPackage skillPackage = loadSingleSkill(skillDir);
                if (skillPackage == null || StrUtil.isBlank(skillPackage.getName())) {
                    continue;
                }
                byName.put(skillPackage.getName(), skillPackage);
            }
        } catch (IOException e) {
            log.warn("读取skill目录失败, root={}, error={}", root, e.getMessage());
            return Collections.emptyMap();
        }
        return byName;
    }

    public List<Map<String, Object>> listSkillSummaries() {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (SkillPackage skillPackage : loadSkillPackages().values()) {
            summaries.add(toSkillSummary(skillPackage));
        }
        return summaries;
    }

    public Map<String, Object> uploadSkillMarkdown(MultipartFile file, boolean overwrite) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "上传的SKILL.md文件不能为空");
        }

        String raw;
        try {
            raw = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException(e, ExceptionCode.INTERNAL_SERVER_ERROR.getResultCode(), "读取上传文件失败");
        }
        if (StrUtil.isBlank(raw)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "SKILL.md内容不能为空");
        }

        ParsedSkillMarkdown parsed = parseSkillMarkdown(raw, "");
        validateUploadedSkill(parsed);

        Path root = resolveRootPathForWrite();
        Path skillDir = root.resolve(parsed.name).normalize();
        if (!skillDir.startsWith(root)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "非法skill名称: " + parsed.name);
        }

        Path skillFile = skillDir.resolve(SKILL_FILE);
        boolean existed = Files.exists(skillFile);
        if (existed && !overwrite) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "skill已存在，如需覆盖请传overwrite=true: " + parsed.name);
        }

        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillFile, normalizeLineEndings(raw), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ServiceException(e, ExceptionCode.INTERNAL_SERVER_ERROR.getResultCode(), "保存SKILL.md失败");
        }

        SkillPackage skillPackage = loadSingleSkill(skillDir);
        if (skillPackage == null) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "保存后加载skill失败: " + parsed.name);
        }

        Map<String, Object> summary = toSkillSummary(skillPackage);
        summary.put("overwritten", existed);
        return summary;
    }

    public SkillPackage findByName(Map<String, SkillPackage> skillMap, String name) {
        if (skillMap == null || skillMap.isEmpty() || StrUtil.isBlank(name)) {
            return null;
        }
        SkillPackage exact = skillMap.get(name);
        if (exact != null) {
            return exact;
        }
        String normalized = normalizeName(name);
        for (Map.Entry<String, SkillPackage> entry : skillMap.entrySet()) {
            if (normalized.equals(normalizeName(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    public String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private SkillPackage loadSingleSkill(Path skillDir) {
        Path skillFile = skillDir.resolve(SKILL_FILE);
        if (!Files.isRegularFile(skillFile)) {
            return null;
        }

        String raw;
        try {
            raw = Files.readString(skillFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("读取skill文件失败, file={}, error={}", skillFile, e.getMessage());
            return null;
        }

        ParsedSkillMarkdown parsed = parseSkillMarkdown(raw, skillDir.getFileName().toString());
        if (StrUtil.isBlank(parsed.name)) {
            return null;
        }

        List<SkillToolDefinition> tools = loadToolDefinitions(skillDir.resolve(TOOLS_FILE));
        return new SkillPackage(parsed.name, parsed.description, parsed.body, skillDir, tools);
    }

    private List<SkillToolDefinition> loadToolDefinitions(Path toolsFile) {
        if (!Files.isRegularFile(toolsFile)) {
            return Collections.emptyList();
        }

        try {
            String rawJson = Files.readString(toolsFile, StandardCharsets.UTF_8);
            List<Map<String, Object>> list = CommonUtil.getObjectMapper().readValue(rawJson, List.class);
            if (list == null || list.isEmpty()) {
                return Collections.emptyList();
            }

            List<SkillToolDefinition> result = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            for (Map<String, Object> item : list) {
                if (item == null || item.isEmpty()) {
                    continue;
                }
                String name = item.get("name") == null ? null : String.valueOf(item.get("name")).trim();
                if (StrUtil.isBlank(name) || !names.add(name)) {
                    continue;
                }
                String description = item.get("description") == null ? "" : String.valueOf(item.get("description"));
                Object inputSchemaObj = item.get("input_schema");
                Map<String, Object> inputSchema = inputSchemaObj instanceof Map<?, ?>
                        ? castToStringObjectMap((Map<?, ?>) inputSchemaObj)
                        : Collections.emptyMap();
                result.add(new SkillToolDefinition(name, description, inputSchema));
            }
            return result;
        } catch (Exception e) {
            log.warn("解析tools.json失败, file={}, error={}", toolsFile, e.getMessage());
            return Collections.emptyList();
        }
    }

    private ParsedSkillMarkdown parseSkillMarkdown(String raw, String fallbackName) {
        if (raw == null) {
            raw = "";
        }
        String normalized = normalizeLineEndings(raw);

        String name = fallbackName;
        String description = "";
        String body = normalized.trim();

        if (!normalized.startsWith("---\n")) {
            return new ParsedSkillMarkdown(name, description, body);
        }

        int frontmatterEnd = normalized.indexOf("\n---\n", 4);
        if (frontmatterEnd < 0) {
            return new ParsedSkillMarkdown(name, description, body);
        }

        String frontmatter = normalized.substring(4, frontmatterEnd);
        String[] lines = frontmatter.split("\n");
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim();
            String value = line.substring(idx + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            if ("name".equalsIgnoreCase(key) && StrUtil.isNotBlank(value)) {
                name = value;
            }
            if ("description".equalsIgnoreCase(key)) {
                description = value;
            }
        }

        body = normalized.substring(frontmatterEnd + 5).trim();
        return new ParsedSkillMarkdown(name, description, body);
    }

    private void validateUploadedSkill(ParsedSkillMarkdown parsed) {
        if (parsed == null || StrUtil.isBlank(parsed.name)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "SKILL.md frontmatter必须包含name");
        }
        if (!SKILL_NAME_PATTERN.matcher(parsed.name).matches()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "skill名称只能包含字母、数字、下划线、中划线，且必须以字母开头，长度不超过64");
        }
        if (StrUtil.isBlank(parsed.description)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "SKILL.md frontmatter必须包含description");
        }
        if (StrUtil.isBlank(parsed.body)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "SKILL.md正文不能为空");
        }
    }

    private Map<String, Object> toSkillSummary(SkillPackage skillPackage) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", skillPackage.getName());
        item.put("description", skillPackage.getDescription());
        item.put("path", skillPackage.getRootPath().toString());
        item.put("toolCount", skillPackage.getTools().size());
        return item;
    }

    private String normalizeLineEndings(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("\uFEFF")) {
            return normalized.substring(1);
        }
        return normalized;
    }

    private Map<String, Object> castToStringObjectMap(Map<?, ?> source) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (source == null) {
            return map;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    private Path resolveRootPath() {
        if (StrUtil.isBlank(skillRootDir)) {
            return null;
        }
        Path path = Paths.get(skillRootDir.trim());
        if (path.isAbsolute()) {
            return path;
        }
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path preferred = cwd.resolve(skillRootDir.trim()).normalize();
        if (Files.isDirectory(preferred)) {
            return preferred;
        }
        Path moduleFallback = cwd.resolve("modules").resolve("data-server").resolve(skillRootDir.trim()).normalize();
        if (Files.isDirectory(moduleFallback)) {
            return moduleFallback;
        }
        return preferred;
    }

    private Path resolveRootPathForWrite() {
        Path root = resolveRootPath();
        if (root == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "未配置skill.root-dir");
        }
        try {
            Files.createDirectories(root);
            return root.toAbsolutePath().normalize();
        } catch (IOException e) {
            throw new ServiceException(e, ExceptionCode.INTERNAL_SERVER_ERROR.getResultCode(), "创建skill根目录失败");
        }
    }

    private static class ParsedSkillMarkdown {
        private final String name;
        private final String description;
        private final String body;

        private ParsedSkillMarkdown(String name, String description, String body) {
            this.name = name;
            this.description = description;
            this.body = body;
        }
    }
}
