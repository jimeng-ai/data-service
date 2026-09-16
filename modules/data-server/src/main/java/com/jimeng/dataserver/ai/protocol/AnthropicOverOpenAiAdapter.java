package com.jimeng.dataserver.ai.protocol;

import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.conversation.AiStreamAccumulator;
import com.jimeng.dataserver.ai.skill.model.ActivationResult;
import com.jimeng.dataserver.ai.skill.model.SkillToolDefinition;
import com.jimeng.dataserver.ai.skill.model.ToolExecutionResult;
import com.jimeng.dataserver.ai.skill.model.ToolUseCall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>入口是 anthropic、上游是 OpenAI 协议</b>的适配器。
 *
 * <h3>为什么需要它</h3>
 * 平台整条对话链路（{@code /rag/answer} → {@code ClaudeService}）的入口协议写死是 anthropic，
 * 而 DeepSeek / 通义 / Kimi / 豆包 / vLLM / Xinference / Ollama 这些只提供 OpenAI 协议
 * （DeepSeek 实测 {@code POST /v1/messages} 返回 404）。没有这一层，它们在控制台里根本选不了。
 *
 * <p>它的价值远不止某一个模型：技术架构 §17.2 把「大模型从哪来」列为私有化部署的五个难点之一
 * ——「内网调不到公网 → 接客户自己的模型服务」，而<b>客户自建的模型服务几乎全是 OpenAI 协议</b>。
 * 这一层建好，那一整类需求都白拿。
 *
 * <h3>为什么是 adapter 而不是在别处转</h3>
 * {@code AiConversationLoop} 全程把 body 和 responseMap 当成<b>入口协议</b>的形状在操作
 * （注工具、拼多轮、抽 tool_use），这是它能保持协议无关的前提。跨协议的差异只在
 * 「发出去之前 / 收回来之后 / 转发给前端之前」三处出现，把它们收敛进一个 adapter，
 * 循环与上层一行都不用改——这正是适配器该干的事。
 *
 * <h3>body 在各阶段的形状（读这个类之前先看懂这张表）</h3>
 * <pre>
 *   ClaudeService 传入          anthropic
 *   GenericChatClient.prepareBody  anthropic（按 entry-protocol 补默认值）
 *   AiConversationLoop 全程        anthropic（工具注入、多轮拼接都按 anthropic）
 *   toUpstreamBody              anthropic → openai   ← 唯一的请求转换点
 *   DeepSeek                    openai
 *   fromUpstreamResponse        openai → anthropic   ← 唯一的响应转换点
 *   之后全程                     anthropic
 * </pre>
 *
 * <h3>已知不支持</h3>
 * <b>图片</b>：转换时会替换成一句占位文字而不是静默丢弃。静默丢弃的后果是模型看不见图却照常回答
 * ——这正是本项目反复吃亏的那类静默降级。<b>提示缓存</b>（{@code cache_control}）OpenAI 协议没有对应物，
 * 会被丢掉，代价只是成本，不影响正确性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnthropicOverOpenAiAdapter implements AiProtocolAdapter {

    /**
     * body 与 responseMap 全程是 anthropic 形状，所以所有「操作 body」的方法原样委托。
     * 这个委托是本类能只有几百行的原因：真正要写的只有三个转换点 + 一个流式累加器。
     */
    private final ClaudeProtocolAdapter delegate;

    // ================================================================ 委托（body 是 anthropic 形状）

    @Override public void appendSystemContent(Map<String, Object> body, String text) { delegate.appendSystemContent(body, text); }
    @Override public List<Object> getToolsList(Map<String, Object> body) { return delegate.getToolsList(body); }
    @Override public void setToolsList(Map<String, Object> body, List<Object> tools) { delegate.setToolsList(body, tools); }
    @Override public Map<String, Object> convertToolDef(SkillToolDefinition def) { return delegate.convertToolDef(def); }
    @Override public String getToolName(Object toolDef) { return delegate.getToolName(toolDef); }
    @Override public Object buildActivateSkillsToolDef() { return delegate.buildActivateSkillsToolDef(); }
    @Override public void ensureToolChoiceAuto(Map<String, Object> body) { delegate.ensureToolChoiceAuto(body); }
    @Override public void removeToolByName(Map<String, Object> body, String name) { delegate.removeToolByName(body, name); }
    @Override public List<ToolUseCall> extractToolUseCalls(Map<String, Object> responseMap) { return delegate.extractToolUseCalls(responseMap); }
    @Override public int[] extractUsage(Map<String, Object> responseMap) { return delegate.extractUsage(responseMap); }
    @Override public String extractAssistantText(Map<String, Object> responseMap) { return delegate.extractAssistantText(responseMap); }
    @Override public void appendToolResultTurn(Map<String, Object> body, Map<String, Object> responseMap, List<ToolExecutionResult> results) { delegate.appendToolResultTurn(body, responseMap, results); }
    @Override public void appendActivationTurn(Map<String, Object> body, Map<String, Object> responseMap, ActivationResult activation) { delegate.appendActivationTurn(body, responseMap, activation); }
    @Override public Map<String, Object> buildActivationToolResultBlock(String toolUseId, String toolName, Object payload, boolean isError) { return delegate.buildActivationToolResultBlock(toolUseId, toolName, payload, isError); }
    @Override public Object buildAggregatedResponse(Map<String, Object> responseMap, int totalInput, int totalOutput, int toolRounds, String traceId) { return delegate.buildAggregatedResponse(responseMap, totalInput, totalOutput, toolRounds, traceId); }

    // ================================================================ 流式

    /**
     * 前端的 SSE 处理同时支持 {@code claude-delta}（原生 Claude 帧）和 {@code message}
     * （OpenAI-like 的 {@code {delta}} / {@code {text}} 回退分支，见 chat-admin/api.ts）。
     * 走 {@code message} 这条既有回退路，就不必把 OpenAI 的增量帧重写成 Claude 的
     * {@code content_block_delta} 序列——少一层转换，少一处会错的地方。
     */
    @Override
    public String getDeltaEventType() {
        return "message";
    }

    @Override
    public boolean isDoneSignal(String data) {
        return data != null && "[DONE]".equals(data.trim());
    }

    @Override
    public AiStreamAccumulator createStreamAccumulator() {
        return new OpenAiToAnthropicAccumulator();
    }

    /**
     * 把 OpenAI 的增量帧摊成前端 {@code message} 分支认识的 {@code {"text": "..."}}。
     *
     * <p>返回 null 的帧不会被转发：OpenAI 的流里有不少只带 {@code role} 或只带 {@code finish_reason}
     * 的空帧，转过去前端会解析出空串、白白触发一次渲染。{@code [DONE]} 同理——
     * 它是协议信号不是内容。
     */
    @Override
    public String transformDeltaFrame(String data) {
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
            return null;
        }
        try {
            Map<String, Object> frame = JSONUtil.toBean(data, Map.class);
            String text = firstDeltaText(frame);
            if (text == null || text.isEmpty()) {
                return null;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", text);
            return JSONUtil.toJsonStr(out);
        } catch (Exception e) {
            // 上游偶发的非 JSON 帧不该让整个流断掉。丢掉并记 debug——
            // 真出问题时 accumulator 那边也拿不到内容，会以空回复暴露出来，不会静默成"半截答案"。
            log.debug("无法解析上游增量帧，已跳过: {}", abbreviate(data));
            return null;
        }
    }

    // ================================================================ 请求：anthropic → openai

    @Override
    public Map<String, Object> toUpstreamBody(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(body, out, "model");
        copyIfPresent(body, out, "temperature");
        copyIfPresent(body, out, "top_p");
        copyIfPresent(body, out, "stream");
        // anthropic 的 max_tokens 对应 openai 的 max_tokens（新版叫 max_completion_tokens，
        // 但 DeepSeek 等兼容端点普遍仍认 max_tokens，且两者语义一致）。
        copyIfPresent(body, out, "max_tokens");

        List<Map<String, Object>> messages = new ArrayList<>();
        // anthropic 的 system 是【顶层字段】，openai 是 messages 里的第一条。
        String system = flattenSystem(body.get("system"));
        if (system != null && !system.isBlank()) {
            messages.add(Map.of("role", "system", "content", system));
        }
        for (Object m : asList(body.get("messages"))) {
            convertMessage(asMap(m), messages);
        }
        out.put("messages", messages);

        List<Object> tools = convertTools(asList(body.get("tools")));
        if (!tools.isEmpty()) {
            out.put("tools", tools);
            // anthropic 的 tool_choice 形状是 {"type":"auto"}，openai 是字符串 "auto"。
            Object tc = body.get("tool_choice");
            if (tc instanceof Map<?, ?> tcm) {
                Object type = tcm.get("type");
                // JSON null 也要落回 "auto"：把字符串 "null" 发上去是个非法的 tool_choice。
                out.put("tool_choice", JsonNulls.isNull(type) ? "auto" : String.valueOf(type));
            }
        }
        return out;
    }

    /**
     * 一条 anthropic 消息可能摊成<b>多条</b> openai 消息：
     * anthropic 把 tool_result 放在 user 消息的 content 块里，而 openai 要求它是独立的
     * {@code role:"tool"} 消息。漏掉这一步的表现是「工具执行了但模型看不到结果」，
     * 然后它会自己编一个答案——静默且难查。
     */
    private void convertMessage(Map<String, Object> msg, List<Map<String, Object>> out) {
        if (msg == null) return;
        String role = str(msg.get("role"));
        Object content = msg.get("content");

        if (content instanceof String s) {
            out.add(Map.of("role", role, "content", s));
            return;
        }

        StringBuilder text = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        List<Map<String, Object>> toolResults = new ArrayList<>();

        for (Object b : asList(content)) {
            Map<String, Object> block = asMap(b);
            if (block == null) continue;
            String type = str(block.get("type"));
            switch (type == null ? "" : type) {
                case "text" -> text.append(str(block.get("text")));
                case "tool_use" -> {
                    Map<String, Object> fn = new LinkedHashMap<>();
                    fn.put("name", str(block.get("name")));
                    // openai 的 arguments 是【字符串化的 JSON】，不是对象。传对象上游会报 400。
                    fn.put("arguments", JSONUtil.toJsonStr(block.get("input") == null ? Map.of() : block.get("input")));
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("id", str(block.get("id")));
                    call.put("type", "function");
                    call.put("function", fn);
                    toolCalls.add(call);
                }
                case "tool_result" -> {
                    Map<String, Object> tr = new LinkedHashMap<>();
                    tr.put("role", "tool");
                    tr.put("tool_call_id", str(block.get("tool_use_id")));
                    tr.put("content", flattenToolResultContent(block.get("content")));
                    toolResults.add(tr);
                }
                case "image" ->
                    // 不静默丢：模型看不见图却照常回答，是最难查的一类错。
                    text.append("\n[平台提示：这条消息里有一张图片，但当前模型不支持图片输入，图片未被发送]\n");
                default -> log.debug("忽略无法转换的内容块类型: {}", type);
            }
        }

        if (!toolCalls.isEmpty()) {
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            // openai 要求带 tool_calls 的 assistant 消息 content 可为空串，但不能缺字段。
            assistant.put("content", text.toString());
            assistant.put("tool_calls", toolCalls);
            out.add(assistant);
        } else if (text.length() > 0 || toolResults.isEmpty()) {
            out.add(Map.of("role", role, "content", text.toString()));
        }
        // tool_result 必须排在触发它的 assistant 消息之后，所以最后追加。
        out.addAll(toolResults);
    }

    private List<Object> convertTools(List<Object> anthropicTools) {
        List<Object> out = new ArrayList<>();
        for (Object t : anthropicTools) {
            Map<String, Object> tool = asMap(t);
            if (tool == null) continue;
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", str(tool.get("name")));
            fn.put("description", str(tool.get("description")));
            // anthropic 叫 input_schema，openai 叫 parameters，内容都是 JSON Schema。
            Object schema = tool.get("input_schema");
            fn.put("parameters", schema == null ? Map.of("type", "object", "properties", Map.of()) : schema);
            Map<String, Object> wrapped = new LinkedHashMap<>();
            wrapped.put("type", "function");
            wrapped.put("function", fn);
            out.add(wrapped);
        }
        return out;
    }

    // ================================================================ 响应：openai → anthropic

    @Override
    public Map<String, Object> fromUpstreamResponse(Map<String, Object> resp) {
        if (resp == null) return null;
        // 上游报错时原样透出：错误体本来就不该被伪装成一条正常回复。
        if (resp.containsKey("error") && !resp.containsKey("choices")) {
            return resp;
        }
        List<Object> choices = asList(resp.get("choices"));
        if (choices.isEmpty()) {
            return resp;
        }
        Map<String, Object> choice = asMap(choices.get(0));
        Map<String, Object> message = asMap(choice == null ? null : choice.get("message"));
        String finish = str(choice == null ? null : choice.get("finish_reason"));

        List<Map<String, Object>> blocks = new ArrayList<>();
        // ★ 只取 content，刻意不碰 reasoning_content：推理模型（deepseek-flash / reasoner）把思考过程放在
        //   message.reasoning_content、与正文分开下发。拼进 text 的话，按 content[].text 抽文本再解析 JSON 的调用方
        //  （语义层推导）会拿到「一段思考 + JSON」而解析失败；对话侧则会把思考过程当成回答落库、喂回下一轮。
        String text = str(message == null ? null : message.get("content"));
        if (text != null && !text.isEmpty()) {
            blocks.add(Map.of("type", "text", "text", text));
        }
        for (Object c : asList(message == null ? null : message.get("tool_calls"))) {
            Map<String, Object> call = asMap(c);
            if (call == null) continue;
            Map<String, Object> fn = asMap(call.get("function"));
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_use");
            block.put("id", str(call.get("id")));
            block.put("name", fn == null ? null : str(fn.get("name")));
            block.put("input", parseArgs(fn == null ? null : str(fn.get("arguments"))));
            blocks.add(block);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", str(resp.get("id")));
        out.put("type", "message");
        out.put("role", "assistant");
        out.put("model", str(resp.get("model")));
        out.put("content", blocks);
        out.put("stop_reason", stopReasonOf(finish));
        Map<String, Object> usage = asMap(resp.get("usage"));
        out.put("usage", Map.of(
                "input_tokens", usage == null ? 0 : intOf(usage.get("prompt_tokens")),
                "output_tokens", usage == null ? 0 : intOf(usage.get("completion_tokens"))));
        return out;
    }

    /**
     * 把 OpenAI 的增量帧累积起来，{@link #buildResponseMap()} 吐出<b>anthropic 形状</b>。
     *
     * <p>吐 anthropic 形状是关键：这样上面那一整排委托给 ClaudeProtocolAdapter 的
     * extract/append 方法可以直接用在它身上，不需要为流式再写一套。
     */
    private final class OpenAiToAnthropicAccumulator implements AiStreamAccumulator {

        private final StringBuilder text = new StringBuilder();
        /** index → 累积中的 tool_call。OpenAI 的工具参数是按 index 分片流式下发的。 */
        private final Map<Integer, Map<String, Object>> toolCalls = new LinkedHashMap<>();
        private String id;
        private String model;
        private String finishReason;
        private int inputTokens;
        private int outputTokens;

        @Override
        public void accumulateEvent(String eventType, String data) {
            if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) return;
            Map<String, Object> frame;
            try {
                frame = JSONUtil.toBean(data, Map.class);
            } catch (Exception e) {
                log.debug("累加器跳过非 JSON 帧: {}", abbreviate(data));
                return;
            }
            if (id == null) id = str(frame.get("id"));
            if (model == null) model = str(frame.get("model"));
            Map<String, Object> usage = asMap(frame.get("usage"));
            if (usage != null) {
                // DeepSeek 等实现把 usage 放在最后一帧；有的放在每帧。取最大值而不是累加，
                // 累加会把 token 数算成好几倍，直接反映在计费上。
                inputTokens = Math.max(inputTokens, intOf(usage.get("prompt_tokens")));
                outputTokens = Math.max(outputTokens, intOf(usage.get("completion_tokens")));
            }
            for (Object c : asList(frame.get("choices"))) {
                Map<String, Object> choice = asMap(c);
                if (choice == null) continue;
                String fr = str(choice.get("finish_reason"));
                if (fr != null) finishReason = fr;
                Map<String, Object> delta = asMap(choice.get("delta"));
                if (delta == null) continue;
                String piece = str(delta.get("content"));
                if (piece != null) text.append(piece);
                accumulateToolCalls(asList(delta.get("tool_calls")));
            }
        }

        /** OpenAI 的工具调用按 index 分片：第一片带 id 和 name，后续片只带 arguments 的一小段。 */
        private void accumulateToolCalls(List<Object> deltas) {
            for (Object d : deltas) {
                Map<String, Object> call = asMap(d);
                if (call == null) continue;
                int idx = intOf(call.get("index"));
                Map<String, Object> acc = toolCalls.computeIfAbsent(idx, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("args", new StringBuilder());
                    return m;
                });
                if (call.get("id") != null) acc.put("id", str(call.get("id")));
                Map<String, Object> fn = asMap(call.get("function"));
                if (fn != null) {
                    if (fn.get("name") != null) acc.put("name", str(fn.get("name")));
                    String argPiece = str(fn.get("arguments"));
                    if (argPiece != null) ((StringBuilder) acc.get("args")).append(argPiece);
                }
            }
        }

        @Override
        public Map<String, Object> buildResponseMap() {
            List<Map<String, Object>> blocks = new ArrayList<>();
            if (text.length() > 0) {
                blocks.add(Map.of("type", "text", "text", text.toString()));
            }
            for (Map<String, Object> acc : toolCalls.values()) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_use");
                block.put("id", acc.get("id"));
                block.put("name", acc.get("name"));
                block.put("input", parseArgs(acc.get("args").toString()));
                blocks.add(block);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("type", "message");
            out.put("role", "assistant");
            out.put("model", model);
            out.put("content", blocks);
            out.put("stop_reason", hasToolUse() ? "tool_use" : "end_turn");
            out.put("usage", Map.of("input_tokens", inputTokens, "output_tokens", outputTokens));
            return out;
        }

        @Override public boolean hasToolUse() { return !toolCalls.isEmpty() || "tool_calls".equals(finishReason); }
        @Override public int getInputTokens() { return inputTokens; }
        @Override public int getOutputTokens() { return outputTokens; }
        @Override public String getRequestId() { return id; }
        @Override public String toJson() { return JSONUtil.toJsonStr(buildResponseMap()); }
    }

    // ================================================================ 小工具

    /**
     * 非流式响应的 finish_reason → anthropic 的 stop_reason。
     *
     * <p>{@code length} 必须映射成 {@code max_tokens}，不能和 {@code stop} 一起落成 {@code end_turn}：
     * 推理模型的思考 token 也计入 completion_tokens、吃 max_tokens 的额度，被截断时正文可能只有半截甚至为空。
     * 报成 end_turn 等于告诉调用方「模型说完了」，被截断这件事就在协议转换这一层静默消失了。
     * 其余取值（stop / content_filter / 缺失）维持原来的 end_turn。流式累加器不走这里，行为不变。
     */
    private static String stopReasonOf(String finish) {
        if ("tool_calls".equals(finish)) {
            return "tool_use";
        }
        if ("length".equals(finish)) {
            return "max_tokens";
        }
        return "end_turn";
    }

    private static String flattenSystem(Object system) {
        if (system == null) return null;
        if (system instanceof String s) return s;
        // anthropic 允许 system 是一个内容块数组（带 cache_control 时就是这种形状）。
        StringBuilder sb = new StringBuilder();
        for (Object b : asList(system)) {
            Map<String, Object> block = asMap(b);
            if (block != null && block.get("text") != null) sb.append(str(block.get("text")));
        }
        return sb.toString();
    }

    /** tool_result 的 content 可能是字符串，也可能是内容块数组。openai 只认字符串。 */
    private static String flattenToolResultContent(Object content) {
        if (JsonNulls.isNull(content)) return "";
        if (content instanceof String s) return s;
        StringBuilder sb = new StringBuilder();
        for (Object b : asList(content)) {
            Map<String, Object> block = asMap(b);
            if (block == null) continue;
            Object t = block.get("text");
            sb.append(JsonNulls.isNull(t) ? JSONUtil.toJsonStr(block) : String.valueOf(t));
        }
        return sb.length() == 0 ? JSONUtil.toJsonStr(content) : sb.toString();
    }

    private static String firstDeltaText(Map<String, Object> frame) {
        for (Object c : asList(frame.get("choices"))) {
            Map<String, Object> choice = asMap(c);
            Map<String, Object> delta = asMap(choice == null ? null : choice.get("delta"));
            String piece = str(delta == null ? null : delta.get("content"));
            if (piece != null && !piece.isEmpty()) return piece;
        }
        return null;
    }

    /** 工具参数解析失败返回空 Map 而不是抛：一次参数损坏不该让整轮对话崩掉，执行器会报"缺参数"。 */
    private static Map<String, Object> parseArgs(String args) {
        if (args == null || args.isBlank()) return Map.of();
        try {
            return JSONUtil.toBean(args, Map.class);
        } catch (Exception e) {
            log.warn("工具调用参数不是合法 JSON，按空参数处理");
            return Map.of();
        }
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        Object v = from.get(key);
        if (v != null) to.put(key, v);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** 取不到值返回 null。JSON 里的 null 也算取不到 —— 见 {@link JsonNulls}。 */
    private static String str(Object o) {
        return JsonNulls.isNull(o) ? null : String.valueOf(o);
    }

    private static int intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return JsonNulls.isNull(o) ? 0 : Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String abbreviate(String s) {
        return s == null ? null : (s.length() > 120 ? s.substring(0, 120) + "…" : s);
    }
}
