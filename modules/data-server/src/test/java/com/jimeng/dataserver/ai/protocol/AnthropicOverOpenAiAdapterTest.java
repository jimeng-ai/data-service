package com.jimeng.dataserver.ai.protocol;

import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.conversation.AiStreamAccumulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 入口 anthropic / 上游 openai 的协议转换。
 *
 * <p>这是整条链路上最容易坏、坏了又最难查的一段：形状对不上的表现不是异常，
 * 而是「模型收不到工具结果于是自己编一个答案」或者「前端一个字都渲染不出来」。
 * 所以这里逐项钉死转换后的形状，而不只是「不抛异常」。
 */
class AnthropicOverOpenAiAdapterTest {

    private final AnthropicOverOpenAiAdapter adapter =
            new AnthropicOverOpenAiAdapter(mock(ClaudeProtocolAdapter.class));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    // ================================================================ 请求

    @Nested
    @DisplayName("请求：anthropic → openai")
    class Request {

        @Test
        @DisplayName("system 从顶层字段变成第一条 system 消息")
        void system字段搬进messages() {
            Map<String, Object> body = Map.of(
                    "model", "deepseek-chat", "max_tokens", 100,
                    "system", "你是助手",
                    "messages", List.of(Map.of("role", "user", "content", "你好")));
            List<Object> msgs = list(adapter.toUpstreamBody(body).get("messages"));
            assertEquals("system", map(msgs.get(0)).get("role"));
            assertEquals("你是助手", map(msgs.get(0)).get("content"));
            assertEquals("user", map(msgs.get(1)).get("role"));
        }

        @Test
        @DisplayName("system 是内容块数组时也能摊平（带 cache_control 时就是这种形状）")
        void system块数组被摊平() {
            Map<String, Object> body = Map.of(
                    "system", List.of(Map.of("type", "text", "text", "A"), Map.of("type", "text", "text", "B")),
                    "messages", List.of());
            List<Object> msgs = list(adapter.toUpstreamBody(body).get("messages"));
            assertEquals("AB", map(msgs.get(0)).get("content"));
        }

        /**
         * ★ 最关键的一条：anthropic 把 tool_result 放在 user 消息的 content 块里，
         * 而 openai 要求它是独立的 role:"tool" 消息。漏掉这一步的表现是
         * 「工具执行了但模型看不到结果」，然后它自己编一个答案——静默且难查。
         */
        @Test
        @DisplayName("tool_result 摊成独立的 role:tool 消息")
        void tool_result摊成独立消息() {
            Map<String, Object> body = Map.of("messages", List.of(
                    Map.of("role", "assistant", "content", List.of(
                            Map.of("type", "tool_use", "id", "call_1", "name", "conn_query",
                                    "input", Map.of("connector", "crm")))),
                    Map.of("role", "user", "content", List.of(
                            Map.of("type", "tool_result", "tool_use_id", "call_1", "content", "5 行")))));
            List<Object> msgs = list(adapter.toUpstreamBody(body).get("messages"));

            Map<String, Object> assistant = map(msgs.get(0));
            assertEquals("assistant", assistant.get("role"));
            List<Object> calls = list(assistant.get("tool_calls"));
            assertEquals("call_1", map(calls.get(0)).get("id"));
            assertEquals("function", map(calls.get(0)).get("type"));
            assertEquals("conn_query", map(map(calls.get(0)).get("function")).get("name"));
            // openai 的 arguments 必须是【字符串化的 JSON】，传对象上游会 400。
            Object args = map(map(calls.get(0)).get("function")).get("arguments");
            assertTrue(args instanceof String, "arguments 必须是字符串，实际 " + args.getClass());
            assertTrue(String.valueOf(args).contains("crm"));

            Map<String, Object> tool = map(msgs.get(msgs.size() - 1));
            assertEquals("tool", tool.get("role"));
            assertEquals("call_1", tool.get("tool_call_id"));
            assertEquals("5 行", tool.get("content"));
        }

        @Test
        @DisplayName("工具定义 input_schema → function.parameters")
        void 工具定义被包成function() {
            Map<String, Object> body = Map.of("messages", List.of(), "tools", List.of(
                    Map.of("name", "conn_query", "description", "查询",
                            "input_schema", Map.of("type", "object"))));
            List<Object> tools = list(adapter.toUpstreamBody(body).get("tools"));
            Map<String, Object> fn = map(map(tools.get(0)).get("function"));
            assertEquals("function", map(tools.get(0)).get("type"));
            assertEquals("conn_query", fn.get("name"));
            assertNotNull(fn.get("parameters"));
        }

        @Test
        @DisplayName("tool_choice 从 {\"type\":\"auto\"} 变成字符串 \"auto\"")
        void tool_choice形状转换() {
            Map<String, Object> body = Map.of("messages", List.of(),
                    "tools", List.of(Map.of("name", "t", "input_schema", Map.of())),
                    "tool_choice", Map.of("type", "auto"));
            assertEquals("auto", adapter.toUpstreamBody(body).get("tool_choice"));
        }

        /** 静默丢图片 = 模型看不见图却照常回答，是最难查的一类错。 */
        @Test
        @DisplayName("图片不静默丢弃，替换成可见的占位说明")
        void 图片有占位提示() {
            Map<String, Object> body = Map.of("messages", List.of(
                    Map.of("role", "user", "content", List.of(
                            Map.of("type", "image", "source", Map.of("data", "xxx"))))));
            List<Object> msgs = list(adapter.toUpstreamBody(body).get("messages"));
            assertTrue(String.valueOf(map(msgs.get(0)).get("content")).contains("不支持图片输入"));
        }
    }

    // ================================================================ 响应

    @Nested
    @DisplayName("响应：openai → anthropic")
    class Response {

        @Test
        @DisplayName("纯文本回复转成 anthropic 的 content 块")
        void 文本回复() {
            Map<String, Object> resp = Map.of(
                    "id", "x1", "model", "deepseek-chat",
                    "choices", List.of(Map.of("message", Map.of("content", "你好"), "finish_reason", "stop")),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 3));
            Map<String, Object> out = adapter.fromUpstreamResponse(resp);
            assertEquals("message", out.get("type"));
            assertEquals("assistant", out.get("role"));
            assertEquals("end_turn", out.get("stop_reason"));
            assertEquals("text", map(list(out.get("content")).get(0)).get("type"));
            assertEquals("你好", map(list(out.get("content")).get(0)).get("text"));
            // usage 的字段名必须是 anthropic 的，否则计费统计全是 0。
            assertEquals(10, map(out.get("usage")).get("input_tokens"));
            assertEquals(3, map(out.get("usage")).get("output_tokens"));
        }

        @Test
        @DisplayName("tool_calls 转成 tool_use，arguments 从字符串解析回对象")
        void 工具调用回转() {
            Map<String, Object> resp = Map.of("choices", List.of(Map.of(
                    "message", Map.of("content", "", "tool_calls", List.of(Map.of(
                            "id", "call_9", "type", "function",
                            "function", Map.of("name", "conn_query", "arguments", "{\"connector\":\"crm\"}")))),
                    "finish_reason", "tool_calls")));
            Map<String, Object> out = adapter.fromUpstreamResponse(resp);
            assertEquals("tool_use", out.get("stop_reason"));
            Map<String, Object> block = map(list(out.get("content")).get(0));
            assertEquals("tool_use", block.get("type"));
            assertEquals("call_9", block.get("id"));
            assertEquals("conn_query", block.get("name"));
            assertEquals("crm", map(block.get("input")).get("connector"));
        }

        /** 上游报错不该被伪装成一条正常回复——那会让错误变成一句模型说的话。 */
        @Test
        @DisplayName("上游错误体原样透出")
        void 错误体不被包装() {
            Map<String, Object> err = Map.of("error", Map.of("message", "余额不足"));
            assertEquals(err, adapter.fromUpstreamResponse(err));
        }

        @Test
        @DisplayName("参数不是合法 JSON 时按空参数处理，不抛")
        void 损坏的参数不炸() {
            Map<String, Object> resp = Map.of("choices", List.of(Map.of(
                    "message", Map.of("tool_calls", List.of(Map.of(
                            "id", "c", "function", Map.of("name", "t", "arguments", "{坏掉")))),
                    "finish_reason", "tool_calls")));
            Map<String, Object> block = map(list(adapter.fromUpstreamResponse(resp).get("content")).get(0));
            assertTrue(map(block.get("input")).isEmpty());
        }
    }

    // ================================================================ 流式

    @Nested
    @DisplayName("流式")
    class Streaming {

        private String frame(String content) {
            return JSONUtil.toJsonStr(Map.of("choices", List.of(Map.of("delta", Map.of("content", content)))));
        }

        @Test
        @DisplayName("增量帧摊成前端 message 分支认识的 {text}")
        void 增量帧转换() {
            String out = adapter.transformDeltaFrame(frame("杭州"));
            assertEquals("杭州", map(JSONUtil.toBean(out, Map.class)).get("text"));
        }

        /** 空帧转过去只会让前端解析出空串、白白触发一次渲染。 */
        @Test
        @DisplayName("空帧 / [DONE] / 非 JSON 不转发")
        void 无内容的帧不转发() {
            assertNull(adapter.transformDeltaFrame("[DONE]"));
            assertNull(adapter.transformDeltaFrame(""));
            assertNull(adapter.transformDeltaFrame("not json"));
            assertNull(adapter.transformDeltaFrame(
                    JSONUtil.toJsonStr(Map.of("choices", List.of(Map.of("delta", Map.of("role", "assistant")))))));
        }

        @Test
        @DisplayName("累加器拼出 anthropic 形状的 responseMap")
        void 累加器吐anthropic形状() {
            AiStreamAccumulator acc = adapter.createStreamAccumulator();
            acc.accumulateEvent("message", frame("杭"));
            acc.accumulateEvent("message", frame("州"));
            acc.accumulateEvent("message", "[DONE]");
            Map<String, Object> out = acc.buildResponseMap();
            assertEquals("message", out.get("type"));
            assertEquals("杭州", map(list(out.get("content")).get(0)).get("text"));
            assertFalse(acc.hasToolUse());
        }

        /**
         * ★ OpenAI 的工具参数是按 index 分片下发的：第一片带 id 和 name，
         * 后续片只带 arguments 的一小段。不拼起来的话工具就是「参数不全」。
         */
        @Test
        @DisplayName("分片下发的 tool_calls 被按 index 拼完整")
        void 工具分片累积() {
            AiStreamAccumulator acc = adapter.createStreamAccumulator();
            String p1 = JSONUtil.toJsonStr(Map.of("choices", List.of(Map.of(
                    "delta", Map.of("tool_calls", List.of(Map.of("index", 0, "id", "c1",
                            "function", Map.of("name", "conn_query", "arguments", "{\"a\":"))))))));
            String p2 = JSONUtil.toJsonStr(Map.of("choices", List.of(Map.of(
                    "delta", Map.of("tool_calls", List.of(Map.of("index", 0,
                            "function", Map.of("arguments", "1}"))))))));
            acc.accumulateEvent("message", p1);
            acc.accumulateEvent("message", p2);
            assertTrue(acc.hasToolUse());
            Map<String, Object> block = map(list(acc.buildResponseMap().get("content")).get(0));
            assertEquals("tool_use", block.get("type"));
            assertEquals("c1", block.get("id"));
            assertEquals("conn_query", block.get("name"));
            assertEquals(1, map(block.get("input")).get("a"));
        }

        /**
         * ★ 有的实现每帧都带 usage，有的只在最后一帧带。累加会把 token 数算成好几倍，
         * 而这直接反映在计费上。取最大值。
         */
        @Test
        @DisplayName("usage 取最大值而不是累加")
        void usage不累加() {
            AiStreamAccumulator acc = adapter.createStreamAccumulator();
            String u = JSONUtil.toJsonStr(Map.of("usage", Map.of("prompt_tokens", 100, "completion_tokens", 20),
                    "choices", List.of()));
            acc.accumulateEvent("message", u);
            acc.accumulateEvent("message", u);
            acc.accumulateEvent("message", u);
            assertEquals(100, acc.getInputTokens());
            assertEquals(20, acc.getOutputTokens());
        }
    }

    @Test
    @DisplayName("默认的三个转换点在同协议 adapter 上是恒等的")
    void 同协议adapter不受影响() {
        AiProtocolAdapter plain = new ClaudeProtocolAdapter();
        Map<String, Object> body = Map.of("a", 1);
        assertEquals(body, plain.toUpstreamBody(body));
        assertEquals(body, plain.fromUpstreamResponse(body));
        assertEquals("raw", plain.transformDeltaFrame("raw"));
    }
}
