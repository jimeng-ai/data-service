package com.jimeng.dataserver.ai.agent.exec.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.dto.SidecarRunPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** 调用沙箱边车 /sandbox/run（SSE），复用 AiConversationLoop 同款的 OkHttp 流式客户端。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SidecarClient {

    private final RequestService requestService;
    private final AgentSandboxProperties props;

    /**
     * 返回底层 {@link EventSource}，供取消时关闭到边车的上游请求（边车据此 docker-kill）。
     *
     * <p>派发日志在这里记 runId 与 runProfile，<b>不记 payload</b>：{@code RequestService.postStream}
     * 已经不再打印请求体（payload 里有模型 key 与回调 JWT），排查「派的是哪一次、哪种运行」靠这一行。
     * runProfile 为空记成 default——与边车的解释一致（缺省 = 现有文件处理 agent）。
     */
    public EventSource run(SidecarRunPayload payload, EventSourceListener listener) {
        String url = baseUrl() + "/sandbox/run";
        log.info("[sidecar] 派发 runId={} runProfile={} tenantId={}", payload.getRunId(),
                StrUtil.blankToDefault(payload.getRunProfile(), "default"), payload.getTenantId());
        Map<String, String> headers = serviceTokenHeader();
        headers.put("Content-Type", "application/json");
        return requestService.postStream(url, headers, JSONUtil.toJsonStr(payload), listener);
    }

    /**
     * {@code GET /healthz}，本次调用的读超时为 {@code timeout}（不走全局 okhttp 读超时，见
     * {@link RequestService#get(String, Map, Map, Duration)}）。
     *
     * <p>边车的 healthz 不校验 service token，所以这里不带头。返回原始状态码与正文，由调用方判定：
     * 边车未配 token 时仍回 200，但正文是 {@code ok (AUTH DISABLED: ...)}，而 run 一律 503——
     * 只有正文<b>恰好</b>是 {@code ok} 才算可用，这个判断属于调用方（语义层的健康前置条件），不在这里吞掉。
     *
     * @throws RuntimeException 连不上、读超时等传输失败（{@link RequestService} 把 IOException 包成 RuntimeException）
     */
    public RequestService.HttpResp healthz(Duration timeout) {
        return requestService.get(baseUrl() + "/healthz", null, null, timeout);
    }

    /**
     * {@code GET /sandbox/capabilities}，带 {@code x-service-token}（与 /sandbox/run 同一道鉴权），
     * 本次调用的读超时为 {@code timeout}。
     *
     * <p>返回原始状态码与正文（期望 200 {@code {"runProfiles":[...],"semanticToolsVersion":1}}）。
     * 404 说明边车版本早于 profile 注册表——老边车会<b>静默忽略</b> {@code runProfile}，照常按文件处理 agent 跑完并报
     * success，所以派发语义层之前必须先问一次；401 是 token 不符。怎么解释交给调用方。
     *
     * @throws RuntimeException 连不上、读超时等传输失败
     */
    public RequestService.HttpResp capabilities(Duration timeout) {
        return requestService.get(baseUrl() + "/sandbox/capabilities", serviceTokenHeader(), null, timeout);
    }

    private String baseUrl() {
        return StrUtil.removeSuffix(props.getBaseUrl(), "/");
    }

    /** 可变 map：run 还要往里加 Content-Type。token 为空则不带头（与边车未启用鉴权的历史行为一致）。 */
    private Map<String, String> serviceTokenHeader() {
        Map<String, String> headers = new HashMap<>();
        if (StrUtil.isNotBlank(props.getServiceToken())) {
            headers.put("x-service-token", props.getServiceToken());
        }
        return headers;
    }
}
