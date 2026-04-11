package com.jimeng.dataserver.ai.skill.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.dataserver.ai.skill.model.SkillApplyResult;
import com.jimeng.dataserver.ai.skill.model.SkillPackage;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SkillRuntimeService {

    @Value("${skill.enabled}")
    private boolean skillEnabled;

    @Value("${skill.explicit-prefix}")
    private String explicitPrefix;

    @Value("${skill.max-selected}")
    private int maxSelected;

    @Value("${skill.skill-system-prompt}")
    private String skillSystemPrompt;

    private final SkillPackageLoaderService skillPackageLoaderService;
    private final SkillToolExecutorRegistryService skillToolExecutorRegistryService;

    public SkillApplyResult applySkillContext(Map<String, Object> body) {
        if (!skillEnabled || body == null) {
            return SkillApplyResult.disabled();
        }

        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> messages) || messages.isEmpty()) {
            return SkillApplyResult.disabled();
        }

        Map<String, SkillPackage> skillMap = skillPackageLoaderService.loadSkillPackages();
        if (skillMap.isEmpty()) {
            return SkillApplyResult.disabled();
        }

        List<String> explicitNames = extractExplicitSkillNamesAndStrip(messages);
        List<SkillPackage> selectedSkills;
        if (explicitNames.isEmpty()) {
            // 不做关键词匹配：直接把可用 skill 暴露给模型，由模型自行判断是否调用。
            selectedSkills = selectSkillsForModelDecide(skillMap);
            if (!selectedSkills.isEmpty()) {
                log.info("模型自判断skills: {}", selectedSkills.stream().map(SkillPackage::getName).toList());
            }
        } else {
            selectedSkills = resolveSelectedSkills(skillMap, explicitNames);
        }

        if (selectedSkills.isEmpty()) {
            return SkillApplyResult.disabled();
        }

        injectSkillSystemPrompt(body, selectedSkills);
        mergeSkillTools(body, selectedSkills);
        ensureToolChoiceAuto(body);

        List<String> finalSelectedNames = selectedSkills.stream().map(SkillPackage::getName).toList();
        return new SkillApplyResult(true, finalSelectedNames);
    }

    public List<ToolUseCall> extractToolUseCalls(Map<String, Object> responseMap) {
        if (responseMap == null || responseMap.isEmpty()) {
            return Collections.emptyList();
        }
        Object contentObj = responseMap.get("content");
        if (!(contentObj instanceof List<?> contentList) || contentList.isEmpty()) {
            return Collections.emptyList();
        }

        List<ToolUseCall> calls = new ArrayList<>();
        for (Object blockObj : contentList) {
            if (!(blockObj instanceof Map<?, ?> blockMap)) {
                continue;
            }
            String type = getString(blockMap.get("type"));
            if (!"tool_use".equals(type)) {
                continue;
            }
            String toolUseId = getString(blockMap.get("id"));
            String toolName = getString(blockMap.get("name"));
            Object inputObj = blockMap.get("input");
            Map<String, Object> input = inputObj instanceof Map<?, ?>
                    ? castToStringObjectMap((Map<?, ?>) inputObj)
                    : Collections.emptyMap();
            calls.add(new ToolUseCall(toolUseId, toolName, input));
        }
        return calls;
    }

    public List<Map<String, Object>> buildToolResultBlocks(List<ToolUseCall> toolCalls) {
        List<ToolExecutionResult> executionResults = skillToolExecutorRegistryService.executeAll(toolCalls);
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (ToolExecutionResult result : executionResults) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_result");
            block.put("tool_use_id", result.getToolUseId());
            block.put("content", JSONUtil.toJsonStr(result.getPayload()));
            if (!result.isSuccess()) {
                block.put("is_error", true);
            }
            blocks.add(block);
        }
        return blocks;
    }

    public void appendAssistantAndToolResultMessages(Map<String, Object> body,
                                                     Map<String, Object> responseMap,
                                                     List<Map<String, Object>> toolResultBlocks) {
        if (body == null || responseMap == null || toolResultBlocks == null || toolResultBlocks.isEmpty()) {
            return;
        }
        Object messagesObj = body.get("messages");
        if (!(messagesObj instanceof List<?> rawMessages)) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object> messages = (List<Object>) rawMessages;

        Object contentObj = responseMap.get("content");
        List<Object> assistantContent = contentObj instanceof List<?> ? new ArrayList<>((List<?>) contentObj) : Collections.emptyList();

        Map<String, Object> assistantMessage = new LinkedHashMap<>();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", assistantContent);
        messages.add(assistantMessage);

        Map<String, Object> userToolResultMessage = new LinkedHashMap<>();
        userToolResultMessage.put("role", "user");
        userToolResultMessage.put("content", new ArrayList<>(toolResultBlocks));
        messages.add(userToolResultMessage);
    }

    private List<SkillPackage> selectSkillsForModelDecide(Map<String, SkillPackage> skillMap) {
        List<SkillPackage> all = new ArrayList<>(skillMap.values());
        int limit = Math.max(maxSelected, 1);
        if (all.size() <= limit) {
            return all;
        }
        return new ArrayList<>(all.subList(0, limit));
    }

    private List<SkillPackage> resolveSelectedSkills(Map<String, SkillPackage> skillMap, List<String> explicitNames) {
        Set<String> dedup = new LinkedHashSet<>(explicitNames);
        List<SkillPackage> selected = new ArrayList<>();
        for (String name : dedup) {
            SkillPackage matched = skillPackageLoaderService.findByName(skillMap, name);
            if (matched == null) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST, "未找到skill: " + name);
            }
            selected.add(matched);
            if (selected.size() >= Math.max(maxSelected, 1)) {
                break;
            }
        }
        return selected;
    }

    private List<String> extractExplicitSkillNamesAndStrip(List<?> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }

        Pattern detectPattern = buildSkillDetectPattern();
        Pattern stripPattern = buildSkillStripPattern();

        List<String> names = new ArrayList<>();
        for (Object messageObj : messages) {
            if (!(messageObj instanceof Map<?, ?> rawMsg)) {
                continue;
            }
            String role = getString(rawMsg.get("role"));
            if (!"user".equalsIgnoreCase(role)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) rawMsg;
            Object contentObj = message.get("content");
            if (contentObj instanceof String textContent) {
                names.addAll(extractSkillNames(textContent, detectPattern));
                message.put("content", stripSkillMentions(textContent, stripPattern));
                continue;
            }

            if (contentObj instanceof List<?> contentList) {
                for (Object blockObj : contentList) {
                    if (!(blockObj instanceof Map<?, ?> rawBlock)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> block = (Map<String, Object>) rawBlock;
                    String type = getString(block.get("type"));
                    if (!"text".equals(type)) {
                        continue;
                    }
                    String text = getString(block.get("text"));
                    names.addAll(extractSkillNames(text, detectPattern));
                    block.put("text", stripSkillMentions(text, stripPattern));
                }
            }
        }
        return names;
    }

    private List<String> extractSkillNames(String text, Pattern detectPattern) {
        if (StrUtil.isBlank(text)) {
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<>();
        Matcher matcher = detectPattern.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (StrUtil.isNotBlank(name)) {
                names.add(name.trim());
            }
        }
        return names;
    }

    private String stripSkillMentions(String text, Pattern stripPattern) {
        if (text == null) {
            return "";
        }
        String stripped = stripPattern.matcher(text).replaceAll("").trim();
        return stripped.replaceAll("[\\t ]{2,}", " ");
    }

    private void injectSkillSystemPrompt(Map<String, Object> body, List<SkillPackage> selectedSkills) {
        String skillPrompt = buildSkillSystemPrompt(selectedSkills);
        if (StrUtil.isBlank(skillPrompt)) {
            return;
        }

        Object existingSystem = body.get("system");
        if (existingSystem == null) {
            body.put("system", skillPrompt);
            return;
        }
        if (existingSystem instanceof String systemText) {
            if (StrUtil.isBlank(systemText)) {
                body.put("system", skillPrompt);
            } else {
                body.put("system", systemText + "\n\n" + skillPrompt);
            }
            return;
        }
        if (existingSystem instanceof List<?> systemBlocks) {
            @SuppressWarnings("unchecked")
            List<Object> blocks = (List<Object>) systemBlocks;
            Map<String, Object> newBlock = new LinkedHashMap<>();
            newBlock.put("type", "text");
            newBlock.put("text", skillPrompt);
            blocks.add(newBlock);
            return;
        }

        body.put("system", String.valueOf(existingSystem) + "\n\n" + skillPrompt);
    }

    private String buildSkillSystemPrompt(List<SkillPackage> selectedSkills) {
        if (selectedSkills == null || selectedSkills.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(skillSystemPrompt);
        for (SkillPackage skill : selectedSkills) {
            sb.append("\n[SKILL: ").append(skill.getName()).append("]\n");
            if (StrUtil.isNotBlank(skill.getDescription())) {
                sb.append("描述: ").append(skill.getDescription()).append("\n");
            }
            if (StrUtil.isNotBlank(skill.getBody())) {
                sb.append(skill.getBody()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private void mergeSkillTools(Map<String, Object> body, List<SkillPackage> selectedSkills) {
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();

        Object existingToolsObj = body.get("tools");
        if (existingToolsObj instanceof List<?> existingTools) {
            for (Object toolObj : existingTools) {
                if (!(toolObj instanceof Map<?, ?> rawTool)) {
                    continue;
                }
                Map<String, Object> tool = castToStringObjectMap(rawTool);
                String name = getString(tool.get("name"));
                String normalizedName = normalizeModelToolName(name);
                if (StrUtil.isBlank(normalizedName) || !names.add(normalizedName)) {
                    continue;
                }
                tool.put("name", normalizedName);
                merged.add(tool);
            }
        }

        for (SkillPackage skill : selectedSkills) {
            for (SkillToolDefinition tool : skill.getTools()) {
                if (tool == null || StrUtil.isBlank(tool.getModelName()) || !names.add(tool.getModelName())) {
                    continue;
                }
                merged.add(tool.toClaudeTool());
            }
        }

        if (!merged.isEmpty()) {
            body.put("tools", merged);
        }
    }

    private void ensureToolChoiceAuto(Map<String, Object> body) {
        if (body == null || body.containsKey("tool_choice")) {
            return;
        }
        Map<String, Object> auto = new LinkedHashMap<>();
        auto.put("type", "auto");
        body.put("tool_choice", auto);
    }

    private String getString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Pattern buildSkillDetectPattern() {
        String prefix = StrUtil.isBlank(explicitPrefix) ? "$" : explicitPrefix;
        String regex = "(?<!\\S)" + Pattern.quote(prefix) + "([A-Za-z][A-Za-z0-9_-]{0,63})";
        return Pattern.compile(regex);
    }

    private Pattern buildSkillStripPattern() {
        String prefix = StrUtil.isBlank(explicitPrefix) ? "$" : explicitPrefix;
        String regex = "(?<!\\S)" + Pattern.quote(prefix) + "[A-Za-z][A-Za-z0-9_-]{0,63}";
        return Pattern.compile(regex);
    }

    private Map<String, Object> castToStringObjectMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (raw == null) {
            return map;
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    private String normalizeModelToolName(String source) {
        if (source == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(source.length());
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_'
                    || c == '-';
            sb.append(valid ? c : '_');
        }
        String normalized = sb.toString().trim();
        if (normalized.length() > 128) {
            normalized = normalized.substring(0, 128);
        }
        return normalized;
    }
}
