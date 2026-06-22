package com.jimeng.dataserver.ai.run;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.persistence.entity.ChatMessage;
import com.jimeng.persistence.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从已落库的 {@link ChatMessage}（含 segments 工具调用）重建带 {@code tool_use}/{@code tool_result} 块的
 * 多轮历史（Claude messages 形状），替代前端只传的「纯文字 history」。
 *
 * <p><b>为什么需要</b>：前端 history 只有 {@code {role, content}}（丢了工具调用），模型多轮里看不到自己
 * 调过 generate_image 等工具，又被自己上一轮「搞定！下方就是图」的叙述带偏，<b>有时</b>就只用文字假装
 * 出图而不真调工具（实测「海边黄昏」那轮）。重建带工具块的历史后，模型能看见「要出图＝调 generate_image」，
 * 从而稳定地继续调用工具。
 *
 * <p><b>安全</b>：任何解析/重建异常都回退到调用方的纯文字 history（不回归）；输出做角色交替校验，非法即回退。
 * 仅对话/RAG 路径（{@code buildClaudeBody}，当前唯一的对话 body 构造）使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryReconstructor {

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    /** 单条 tool_result 文本上限，避免把巨大工具输出灌回上下文。 */
    private static final int MAX_TOOL_RESULT_CHARS = 1500;

    private final ChatMessageMapper messageMapper;

    /**
     * 重建会话的 Claude 历史 messages。
     *
     * @param conversationId  会话 id
     * @param beforeMessageId 截止（不含）：传当前在途轮的 user 消息 id，排除本轮 user+assistant 占位消息
     *                        （本轮 user query 由 buildClaudeBody 单独追加，不能重复）
     * @param fallback        重建失败/为空时返回的兜底（调用方原 history，可为 null）
     * @return Claude messages（含工具块）；异常/校验不过 → 返回 fallback
     */
    public List<Map<String, Object>> reconstructClaude(Long conversationId,
                                                       Long beforeMessageId,
                                                       List<Map<String, Object>> fallback) {
        try {
            List<ChatMessage> msgs = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getConversationId, conversationId)
                    .lt(beforeMessageId != null, ChatMessage::getId, beforeMessageId)
                    .orderByAsc(ChatMessage::getCreateTime)
                    .orderByAsc(ChatMessage::getId));
            if (msgs == null || msgs.isEmpty()) return fallback;

            List<Map<String, Object>> out = new ArrayList<>();
            for (ChatMessage m : msgs) {
                if (ROLE_USER.equals(m.getRole())) {
                    if (StrUtil.isNotBlank(m.getContent())) out.add(textMsg(ROLE_USER, m.getContent()));
                } else if (ROLE_ASSISTANT.equals(m.getRole())) {
                    appendAssistant(out, m);
                }
            }
            if (out.isEmpty() || !validAlternation(out)) {
                log.info("历史重建为空或角色不交替，回退纯文字 history conversationId={}", conversationId);
                return fallback;
            }
            return out;
        } catch (Exception e) {
            log.warn("历史重建失败，回退纯文字 history conversationId={} err={}", conversationId, e.getMessage());
            return fallback;
        }
    }

    /** 把一条 assistant 消息(可能含工具段)展开成「assistant(text+tool_use) → user(tool_result)」交替序列。 */
    private void appendAssistant(List<Map<String, Object>> out, ChatMessage m) {
        List<Object> segs = parseSegments(m.getSegments());
        if (segs.isEmpty()) {
            // 无工具段（纯文字回答）：原样一条 assistant 文本。
            if (StrUtil.isNotBlank(m.getContent())) out.add(textMsg(ROLE_ASSISTANT, m.getContent()));
            return;
        }
        List<Object> assistantBlocks = new ArrayList<>();
        boolean endedOnTool = false;
        for (Object segObj : segs) {
            if (!(segObj instanceof JSONObject seg)) continue;
            String type = seg.getStr("type");
            if ("tool".equals(type) && seg.getJSONObject("call") != null) {
                JSONObject call = seg.getJSONObject("call");
                String id = call.getStr("id");
                String name = call.getStr("name");
                if (StrUtil.isBlank(id) || StrUtil.isBlank(name)) continue; // 残缺工具段跳过
                Map<String, Object> toolUse = new LinkedHashMap<>();
                toolUse.put("type", "tool_use");
                toolUse.put("id", id);
                toolUse.put("name", name);
                Object input = call.get("input");
                toolUse.put("input", input instanceof Map ? input : Map.of());
                assistantBlocks.add(toolUse);
                // 收口：assistant(累积 text + 本 tool_use) → user(tool_result)
                out.add(roleMsg(ROLE_ASSISTANT, new ArrayList<>(assistantBlocks)));
                assistantBlocks.clear();
                Map<String, Object> toolResult = new LinkedHashMap<>();
                toolResult.put("type", "tool_result");
                toolResult.put("tool_use_id", id);
                toolResult.put("content", toolResultText(name, call.get("output")));
                out.add(roleMsg(ROLE_USER, List.of(toolResult)));
                endedOnTool = true;
            } else { // 文本段
                String text = seg.getStr("text");
                if (StrUtil.isNotBlank(text)) {
                    assistantBlocks.add(Map.of("type", "text", "text", text));
                    endedOnTool = false;
                }
            }
        }
        if (!assistantBlocks.isEmpty()) {
            out.add(roleMsg(ROLE_ASSISTANT, assistantBlocks));
        } else if (endedOnTool) {
            // 段以工具收尾、没有收尾文本：补一条 assistant 文本，保证序列以 assistant 结束（否则会出现
            // user(tool_result)→user(当前问题) 两条 user 相邻，Claude 报角色不交替）。
            String closer = StrUtil.isNotBlank(m.getContent()) ? m.getContent() : "（已完成上述工具操作）";
            out.add(textMsg(ROLE_ASSISTANT, closer));
        }
    }

    /** 给模型看的 tool_result 文本：精简、且对 generate_image 不回灌 URL（避免诱导 Markdown 贴图）。 */
    private String toolResultText(String toolName, Object output) {
        if ("generate_image".equals(toolName)) {
            int n = 1;
            if (output instanceof Map<?, ?> o && o.get("urls") instanceof List<?> urls && !urls.isEmpty()) {
                n = urls.size();
            }
            return "已成功生成 " + n + " 张图片并展示给用户。若用户要新图或改图，必须再次调用 generate_image 工具。";
        }
        if ("activate_skills".equals(toolName)) {
            String names = "";
            if (output instanceof Map<?, ?> o && o.get("activated") instanceof List<?> act) {
                names = StrUtil.join("、", act.toArray());
            }
            return StrUtil.isBlank(names) ? "技能已激活。" : "已激活技能：" + names + "。";
        }
        String json = output == null ? "" : JSONUtil.toJsonStr(output);
        return json.length() > MAX_TOOL_RESULT_CHARS ? json.substring(0, MAX_TOOL_RESULT_CHARS) + "…" : json;
    }

    /** 解析 segments JSON 列；非法/空返回空列表。包级可见，便于单测。 */
    static List<Object> parseSegments(String segmentsJson) {
        if (StrUtil.isBlank(segmentsJson)) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(segmentsJson);
            return new ArrayList<>(arr);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Claude messages 角色必须 user/assistant 交替；校验通过才用重建结果。包级可见，便于单测。 */
    static boolean validAlternation(List<Map<String, Object>> msgs) {
        String prev = null;
        for (Map<String, Object> m : msgs) {
            String role = String.valueOf(m.get("role"));
            if (role.equals(prev)) return false;
            prev = role;
        }
        // 末条须为 assistant（其后 buildClaudeBody 追加当前 user query）。
        return !msgs.isEmpty() && ROLE_ASSISTANT.equals(String.valueOf(msgs.get(msgs.size() - 1).get("role")));
    }

    private static Map<String, Object> textMsg(String role, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", text);
        return m;
    }

    private static Map<String, Object> roleMsg(String role, Object content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
