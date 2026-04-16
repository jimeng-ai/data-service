package com.jimeng.dataserver.ai.claude.stream;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流式事件累积器：在 Claude API 流式输出过程中累积所有事件，
 * 流结束后重建完整响应 Map，供 SkillRuntimeService 提取 tool_use 调用。
 */
@Slf4j
public class ClaudeStreamEventAccumulator {

    private String messageId;
    private String model;
    private String role;
    private String stopReason;
    private final List<ContentBlock> contentBlocks = new ArrayList<>();
    private int currentBlockIndex = -1;
    private int inputTokens = 0;
    private int outputTokens = 0;

    /**
     * 处理单个流式事件，累积到内部状态中。
     *
     * @param eventType 事件类型，如 message_start、content_block_start 等
     * @param jsonData  事件的 JSON 数据
     */
    public void accumulate(String eventType, String jsonData) {
        if (eventType == null || jsonData == null) {
            return;
        }
        try {
            switch (eventType) {
                case "message_start" -> handleMessageStart(jsonData);
                case "content_block_start" -> handleContentBlockStart(jsonData);
                case "content_block_delta" -> handleContentBlockDelta(jsonData);
                case "content_block_stop" -> handleContentBlockStop(jsonData);
                case "message_delta" -> handleMessageDelta(jsonData);
                case "message_stop" -> { /* 流结束标记，无需额外处理 */ }
                default -> log.debug("忽略未知事件类型: {}", eventType);
            }
        } catch (Exception e) {
            log.warn("累积事件失败, eventType={}, error={}", eventType, e.getMessage());
        }
    }

    /**
     * 重建与同步 Claude API 响应格式一致的完整响应 Map。
     * 输出格式兼容 SkillRuntimeService.extractToolUseCalls()。
     */
    public Map<String, Object> buildResponseMap() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", messageId);
        response.put("type", "message");
        response.put("role", role);
        response.put("model", model);

        List<Map<String, Object>> contentList = new ArrayList<>();
        for (ContentBlock block : contentBlocks) {
            contentList.add(block.toMap());
        }
        response.put("content", contentList);
        response.put("stop_reason", stopReason);

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", outputTokens);
        response.put("usage", usage);

        return response;
    }

    /**
     * 判断累积的响应中是否包含 tool_use 类型的 content block。
     */
    public boolean hasToolUse() {
        for (ContentBlock block : contentBlocks) {
            if ("tool_use".equals(block.type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取累积的 input tokens 数量。
     */
    public int getInputTokens() {
        return inputTokens;
    }

    /**
     * 获取累积的 output tokens 数量。
     */
    public int getOutputTokens() {
        return outputTokens;
    }

    // ==================== 事件处理方法 ====================

    private void handleMessageStart(String jsonData) {
        JSONObject root = JSONUtil.parseObj(jsonData);
        JSONObject message = root.getJSONObject("message");
        if (message == null) {
            log.warn("message_start 事件缺少 message 字段");
            return;
        }
        this.messageId = message.getStr("id");
        this.model = message.getStr("model");
        this.role = message.getStr("role");

        JSONObject usage = message.getJSONObject("usage");
        if (usage != null) {
            this.inputTokens = usage.getInt("input_tokens", 0);
        }
    }

    private void handleContentBlockStart(String jsonData) {
        JSONObject root = JSONUtil.parseObj(jsonData);
        int index = root.getInt("index", 0);
        JSONObject contentBlock = root.getJSONObject("content_block");
        if (contentBlock == null) {
            log.warn("content_block_start 事件缺少 content_block 字段");
            return;
        }

        ContentBlock block = new ContentBlock();
        block.type = contentBlock.getStr("type");

        if ("tool_use".equals(block.type)) {
            block.toolUseId = contentBlock.getStr("id");
            block.toolName = contentBlock.getStr("name");
            block.inputJsonBuilder = new StringBuilder();
        } else if ("text".equals(block.type)) {
            String initialText = contentBlock.getStr("text");
            block.text = initialText != null ? initialText : "";
        }

        // 确保 contentBlocks 列表大小与 index 对齐
        while (contentBlocks.size() <= index) {
            contentBlocks.add(null);
        }
        contentBlocks.set(index, block);
        this.currentBlockIndex = index;
    }

    private void handleContentBlockDelta(String jsonData) {
        JSONObject root = JSONUtil.parseObj(jsonData);
        int index = root.getInt("index", currentBlockIndex);
        JSONObject delta = root.getJSONObject("delta");
        if (delta == null) {
            return;
        }

        ContentBlock block = getBlock(index);
        if (block == null) {
            log.warn("content_block_delta 引用了不存在的 block, index={}", index);
            return;
        }

        String deltaType = delta.getStr("type");
        if ("text_delta".equals(deltaType)) {
            String text = delta.getStr("text");
            if (text != null) {
                block.text = (block.text == null ? "" : block.text) + text;
            }
        } else if ("input_json_delta".equals(deltaType)) {
            String partialJson = delta.getStr("partial_json");
            if (partialJson != null && block.inputJsonBuilder != null) {
                block.inputJsonBuilder.append(partialJson);
            }
        }
    }

    private void handleContentBlockStop(String jsonData) {
        JSONObject root = JSONUtil.parseObj(jsonData);
        int index = root.getInt("index", currentBlockIndex);

        ContentBlock block = getBlock(index);
        if (block == null) {
            return;
        }

        if ("tool_use".equals(block.type) && block.inputJsonBuilder != null) {
            String accumulated = block.inputJsonBuilder.toString();
            if (!accumulated.isEmpty()) {
                try {
                    JSONObject parsed = JSONUtil.parseObj(accumulated);
                    block.parsedInput = new LinkedHashMap<>();
                    for (String key : parsed.keySet()) {
                        block.parsedInput.put(key, parsed.get(key));
                    }
                } catch (Exception e) {
                    log.error("tool_use input JSON 解析失败, accumulated={}", accumulated, e);
                    block.parsedInput = Collections.emptyMap();
                }
            } else {
                block.parsedInput = Collections.emptyMap();
            }
        }
    }

    private void handleMessageDelta(String jsonData) {
        JSONObject root = JSONUtil.parseObj(jsonData);
        JSONObject delta = root.getJSONObject("delta");
        if (delta != null) {
            String stopReason = delta.getStr("stop_reason");
            if (stopReason != null) {
                this.stopReason = stopReason;
            }
        }

        JSONObject usage = root.getJSONObject("usage");
        if (usage != null) {
            this.outputTokens = usage.getInt("output_tokens", 0);
        }
    }

    // ==================== 辅助方法 ====================

    private ContentBlock getBlock(int index) {
        if (index < 0 || index >= contentBlocks.size()) {
            return null;
        }
        return contentBlocks.get(index);
    }

    // ==================== 内部类 ====================

    private static class ContentBlock {
        String type;                        // "text" 或 "tool_use"
        String text;                        // text 类型的文本内容
        String toolUseId;                   // tool_use 类型的 id
        String toolName;                    // tool_use 类型的 name
        StringBuilder inputJsonBuilder;     // tool_use 类型的 JSON 片段累积
        Map<String, Object> parsedInput;    // 解析后的 input

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            if ("text".equals(type)) {
                map.put("text", text != null ? text : "");
            } else if ("tool_use".equals(type)) {
                map.put("id", toolUseId);
                map.put("name", toolName);
                map.put("input", parsedInput != null ? parsedInput : Collections.emptyMap());
            }
            return map;
        }
    }
}
