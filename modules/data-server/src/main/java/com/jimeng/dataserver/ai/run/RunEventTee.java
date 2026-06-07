package com.jimeng.dataserver.ai.run;

import cn.hutool.json.JSONUtil;
import com.jimeng.dataserver.ai.support.SseEventBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 生成事件分流器：把每帧 SSE 既写进 Redis Stream（续播缓冲，供任意观众重连/补播）又折叠进 {@link RunState}
 * （服务端自持久化）。生成线程不再直接写浏览器 {@code SseEmitter}。
 *
 * <p>当 runId 没有在途句柄时（如调试台 {@code /data/claude/messages}、{@code /rag/answer} 直连），
 * 回退到旧的 {@link SseEventBridge} 直发，保持原行为不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunEventTee {

    public static final String STREAM_PREFIX = "chat:run:";
    static final long STREAM_TTL_MINUTES = 15;

    private final RedissonClient redissonClient;
    private final RunRegistry runRegistry;
    private final RunSegmentAssembler assembler;
    private final SseEventBridge sseBridge;

    /** 是否存在在途 run（决定走「续播缓冲」还是「直连回退」）。 */
    public boolean isManaged(String runId) {
        return runRegistry.isLive(runId);
    }

    public void tee(String runId, String event, String data) {
        RunHandle h = runRegistry.get(runId);
        if (h == null) {
            sseBridge.send(runId, event, data);
            return;
        }
        xadd(runId, event, data, null);
        assembler.fold(h.getState(), event, data);
    }

    public void teeJson(String runId, String event, Object payload) {
        tee(runId, event, JSONUtil.toJsonStr(payload));
    }

    /** 写终止哨兵帧并对流设 TTL；由 RunFinalizer 在落库后调用，通知所有实时观众收尾。 */
    public void terminate(String runId, String terminalStatus) {
        RStream<String, String> stream = stream(runId);
        try {
            xadd(runId, "end", "{}", terminalStatus);
            stream.expire(STREAM_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("写终止帧失败 runId={} err={}", runId, e.getMessage());
        }
    }

    private void xadd(String runId, String event, String data, String terminal) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event", event == null ? "" : event);
        fields.put("data", data == null ? "" : data);
        if (terminal != null) fields.put("terminal", terminal);
        try {
            stream(runId).addAll(fields);
        } catch (Exception e) {
            log.warn("XADD 失败 runId={} event={} err={}", runId, event, e.getMessage());
        }
    }

    public RStream<String, String> stream(String runId) {
        return redissonClient.getStream(STREAM_PREFIX + runId, StringCodec.INSTANCE);
    }
}
