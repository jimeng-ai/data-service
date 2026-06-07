package com.jimeng.common.core.utils;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author Moonlight
 * @Description Sse工具类
 * @Date 2024/10/5 14:17
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class SseServiceUtil {

    private final Map<String, SseEmitter> sseManagerMap = new ConcurrentHashMap<>();

    private SseEmitter initSseEmitter(String key, SseEmitter sseEmitter) {
        sseEmitter.onCompletion(() -> {
            log.info("key【{}】sse发送完成", key);
            sseManagerMap.remove(key);
        });
        sseEmitter.onError((error) -> {
            log.error("key【{}】sse连接异常: {}", key, error.toString());
            sseManagerMap.remove(key);
        });
        sseEmitter.onTimeout(() -> {
            log.info("key【{}】sse连接超时", key);
            sseManagerMap.remove(key);
        });
        return sseEmitter;
    }

    public SseEmitter getConnection(String key, Long timeout) {
        SseEmitter sseEmitter = sseManagerMap.computeIfAbsent(key,
                k -> timeout == null ? new SseEmitter(-1L) : new SseEmitter(timeout));
        return initSseEmitter(key, sseEmitter);
    }

    public void send(String key, String msg) {
        SseEmitter sseEmitter = sseManagerMap.get(key);
        if (sseEmitter == null) {
            throw new ServiceException(ExceptionCode.SSE_NOT_FOUND, "sse连接不存在");
        }
        try {
            log.info("sse发送消息: {}", msg);
            sseEmitter.send(msg);
        } catch (IOException e) {
            log.info("sse发送消息异常", e.getMessage(), e);
            throw new ServiceException(ExceptionCode.SSE_SEND_ERROR, e.getMessage());
        }
    }

    /**
     * 发送带 event type 的 SSE 事件
     *
     * @param key       SSE 连接标识
     * @param eventName SSE 事件名称（客户端通过 addEventListener 监听）
     * @param data      事件数据
     */
    public void sendEvent(String key, String eventName, String data) {
        SseEmitter sseEmitter = sseManagerMap.get(key);
        if (sseEmitter == null) {
            throw new ServiceException(ExceptionCode.SSE_NOT_FOUND, "sse连接不存在");
        }
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .name(eventName)
                    .data(data);
            sseEmitter.send(event);
        } catch (IOException e) {
            throw new ServiceException(ExceptionCode.SSE_SEND_ERROR, e.getMessage());
        }
    }

    /**
     * 发送带 {@code id} 的 SSE 事件。id 用于客户端断线重连时回传 {@code Last-Event-ID}，
     * 实现可重连续播（见对话生成的 Redis Stream 续播泵）。
     */
    public void sendEvent(String key, String id, String eventName, String data) {
        SseEmitter sseEmitter = sseManagerMap.get(key);
        if (sseEmitter == null) {
            throw new ServiceException(ExceptionCode.SSE_NOT_FOUND, "sse连接不存在");
        }
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .id(id)
                    .name(eventName)
                    .data(data);
            sseEmitter.send(event);
        } catch (IOException e) {
            throw new ServiceException(ExceptionCode.SSE_SEND_ERROR, e.getMessage());
        }
    }

    /**
     * 完成 SSE 连接并从管理 Map 中移除
     *
     * @param key SSE 连接标识
     */
    public void complete(String key) {
        SseEmitter sseEmitter = sseManagerMap.get(key);
        if (sseEmitter != null) {
            sseEmitter.complete();
            sseManagerMap.remove(key);
        }
    }

}
