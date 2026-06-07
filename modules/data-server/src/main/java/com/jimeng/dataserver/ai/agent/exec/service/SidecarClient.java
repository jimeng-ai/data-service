package com.jimeng.dataserver.ai.agent.exec.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import lombok.RequiredArgsConstructor;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/** 调用沙箱边车 /sandbox/run（SSE），复用 AiConversationLoop 同款的 OkHttp 流式客户端。 */
@Service
@RequiredArgsConstructor
public class SidecarClient {

    private final RequestService requestService;
    private final AgentSandboxProperties props;

    /** 返回底层 {@link EventSource}，供取消时关闭到边车的上游请求（边车据此 docker-kill）。 */
    public EventSource run(SidecarRunPayload payload, EventSourceListener listener) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (StrUtil.isNotBlank(props.getServiceToken())) {
            headers.put("x-service-token", props.getServiceToken());
        }
        String url = StrUtil.removeSuffix(props.getBaseUrl(), "/") + "/sandbox/run";
        return requestService.postStream(url, headers, JSONUtil.toJsonStr(payload), listener);
    }
}
