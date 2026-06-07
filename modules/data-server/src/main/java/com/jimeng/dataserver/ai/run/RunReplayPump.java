package com.jimeng.dataserver.ai.run;

import cn.hutool.core.util.StrUtil;
import com.jimeng.common.core.utils.SseServiceUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 单观众续播泵：从 {@code chat:run:{runId}} 的 fromId（不含）开始，先补播历史帧、再实时跟随，
 * 逐帧带 {@code id:}（= Redis stream id）推给 viewerKey 的 emitter，让浏览器 {@code Last-Event-ID} 续传自然生效。
 * 遇终止哨兵帧 / 观众断开 / 流过期收尾。跑在专用 {@code runPumpExecutor}，短超时阻塞读，绝不占用生成线程池。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunReplayPump {

    private static final int READ_COUNT = 256;
    private static final long BLOCK_MS = 2000;
    /** 流已过期 / run 不在本机且持续无新帧时的兜底收尾上限。 */
    private static final long MAX_IDLE_MS = 60_000;

    // Redisson 3.13.4 的 StreamMessageId 未实现 Comparable，不能直接进 TreeMap；按 (id0,id1) 自行比较。
    private static final Comparator<StreamMessageId> ID_CMP = (a, b) -> {
        long[] pa = parseId(a), pb = parseId(b);
        int c = Long.compare(pa[0], pb[0]);
        return c != 0 ? c : Long.compare(pa[1], pb[1]);
    };

    private static long[] parseId(StreamMessageId id) {
        String[] p = id.toString().split("-");
        long id0 = Long.parseLong(p[0]);
        long id1 = p.length > 1 ? Long.parseLong(p[1]) : 0L;
        return new long[]{id0, id1};
    }

    private final RedissonClient redissonClient;
    private final RunRegistry runRegistry;
    private final SseServiceUtil sseServiceUtil;

    public void pump(String runId, String viewerKey, String fromId) {
        RStream<String, String> stream =
                redissonClient.getStream(RunEventTee.STREAM_PREFIX + runId, StringCodec.INSTANCE);
        StreamMessageId cursor = parseCursor(fromId);
        long idleSince = System.currentTimeMillis();
        try {
            while (true) {
                Map<StreamMessageId, Map<String, String>> batch;
                try {
                    batch = stream.read(READ_COUNT, BLOCK_MS, TimeUnit.MILLISECONDS, cursor);
                } catch (Exception e) {
                    log.warn("续播 XREAD 失败 runId={} err={}", runId, e.getMessage());
                    break;
                }
                if (batch == null || batch.isEmpty()) {
                    if (!runRegistry.isLive(runId)
                            && (!safeExists(stream) || System.currentTimeMillis() - idleSince > MAX_IDLE_MS)) {
                        break; // 流已过期 / run 已结束且无更多帧
                    }
                    continue;
                }
                idleSince = System.currentTimeMillis();
                List<StreamMessageId> ids = new ArrayList<>(batch.keySet());
                ids.sort(ID_CMP);
                for (StreamMessageId id : ids) {
                    cursor = id;
                    Map<String, String> f = batch.get(id);
                    if (f.get("terminal") != null) {
                        return; // 终止哨兵：收尾，end 帧不转发（前端靠 onDone 结束）
                    }
                    if (!emit(viewerKey, id.toString(), f.get("event"), f.get("data"))) {
                        return; // 观众已断开
                    }
                }
            }
        } finally {
            sseServiceUtil.complete(viewerKey);
        }
    }

    private boolean emit(String viewerKey, String id, String event, String data) {
        try {
            sseServiceUtil.sendEvent(viewerKey, id, event, data);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean safeExists(RStream<String, String> stream) {
        try {
            return stream.isExists();
        } catch (Exception e) {
            return false;
        }
    }

    private StreamMessageId parseCursor(String fromId) {
        if (StrUtil.isBlank(fromId) || "0".equals(fromId)) {
            return new StreamMessageId(0, 0);
        }
        try {
            String[] parts = fromId.split("-");
            long id0 = Long.parseLong(parts[0]);
            long id1 = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            return new StreamMessageId(id0, id1);
        } catch (Exception e) {
            return new StreamMessageId(0, 0);
        }
    }
}
