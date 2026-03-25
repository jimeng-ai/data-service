package com.jimeng.sys.test.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.common.core.utils.SseServiceUtil;
import com.jimeng.sys.test.entity.ChatDTO;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @Author Moonlight
 * @Description 描述
 * @Date 2025/8/30 12:52
 */

@Slf4j
@RestController
@RequestMapping("/admin/sys/test")
@RequiredArgsConstructor
public class TestController {

    private final RequestService requestService;
    private final SseServiceUtil sseServiceUtil;

    @PostMapping("/chat")
    public SseEmitter chat(@RequestBody ChatDTO chatDTO) {
        // 生成唯一的连接ID
        String connectionId = UUID.randomUUID().toString();
        
        // 创建SSE连接
        SseEmitter sseEmitter = sseServiceUtil.getConnection(connectionId, 30000L);
        
        // 确保stream参数为true
        chatDTO.setStream(true);
        
        Map<String, String> header = new HashMap<>();
        header.put("Content-Type", "application/json");
        header.put("Accept", "text/event-stream");
        header.put("Authorization", "Bearer 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");

        Map<String, Object> map = BeanUtil.beanToMap(chatDTO, false, true);
        EventSourceListener eventSourceListener = new EventSourceListener() {
            @Override
            public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
                log.info("SSE连接已建立，连接ID: {}", connectionId);
            }

            @Override
            public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
                log.info("接收到数据: {}", data);
                try {
                    // 将接收到的数据通过SSE发送给客户端
                    sseServiceUtil.send(connectionId, data);
                } catch (Exception e) {
                    log.error("发送SSE数据失败: {}", e.getMessage(), e);
                }
            }

            @Override
            public void onClosed(@NotNull EventSource eventSource) {
                log.info("SSE连接已关闭，连接ID: {}", connectionId);
                try {
                    // 发送结束标记
                    sseServiceUtil.send(connectionId, "data: [DONE]\n\n");
                } catch (Exception e) {
                    log.error("发送结束标记失败: {}", e.getMessage(), e);
                }
            }
            
            @Override
            public void onFailure(@NotNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
                log.error("SSE连接失败，连接ID: {}, 错误: {}", connectionId, t != null ? t.getMessage() : "未知错误");
                try {
                    // 发送错误信息
                    sseServiceUtil.send(connectionId, "data: {\"error\": \"连接失败\"}\n\n");
                } catch (Exception e) {
                    log.error("发送错误信息失败: {}", e.getMessage(), e);
                }
            }
        };
        requestService.postStream("http://localhost:8090/v1/chat/completions", header, JSONUtil.toJsonStr(map).toString(), eventSourceListener);
        
        return sseEmitter;
    }

}
