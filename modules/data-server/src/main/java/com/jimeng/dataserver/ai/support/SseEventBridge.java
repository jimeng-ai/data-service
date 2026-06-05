package com.jimeng.dataserver.ai.support;

import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 把后端事件「尽力而为」桥接到前端 SSE 的横切组件（阶段 3.3）。
 *
 * <p>两条编排路径共用：{@code AiConversationLoop}（对话/RAG，宿主进程内多轮）与
 * {@code AgentExecService}（代码执行 Agent，转发边车 SSE）。两套编排骨架本质不同、不合并，
 * 只把「发事件」这件重复的事收口到这里——底层 {@link SseServiceUtil#sendEvent} 在连接已断开/已完成时
 * 会抛异常，单帧失败不应中断整条流，故统一 swallow + 记日志。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SseEventBridge {

    private final SseServiceUtil sseServiceUtil;

    /** 推送一个已序列化好的 SSE 事件；底层发送异常只记日志不抛。 */
    public void send(String connectionId, String event, String data) {
        try {
            sseServiceUtil.sendEvent(connectionId, event, data);
        } catch (Exception e) {
            log.warn("SSE 转发失败 event={} err={}", event, e.getMessage());
        }
    }

    /** 同 {@link #send}，但先把 payload 序列化成 JSON 字符串。 */
    public void sendJson(String connectionId, String event, Object payload) {
        send(connectionId, event, JSONUtil.toJsonStr(payload));
    }

    /** 结束 SSE 流（与 {@link SseServiceUtil#complete} 行为一致，仅做收口）。 */
    public void complete(String connectionId) {
        sseServiceUtil.complete(connectionId);
    }
}
