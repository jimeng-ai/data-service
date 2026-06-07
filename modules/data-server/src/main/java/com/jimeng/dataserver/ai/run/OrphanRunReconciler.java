package com.jimeng.dataserver.ai.run;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.persistence.entity.ChatMessage;
import com.jimeng.persistence.mapper.ChatMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Set;

/**
 * 孤儿生成清理：把没有在途句柄的 GENERATING 助手消息刷成 FAILED，避免界面永久转圈。
 *
 * <ul>
 *   <li>启动时（{@link CommandLineRunner}）：单实例下注册表为空，所有 GENERATING 都是上次进程重启遗留 → 全部刷掉。</li>
 *   <li>运行中（{@link Scheduled}）：清理 run_id 不在本机注册表、且创建超过阈值（避开「刚落库未注册句柄」的瞬间窗口）的遗留。</li>
 * </ul>
 *
 * <p>跨租户更新，用 {@link TenantContext#runAsSystem} 跳过多租户过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanRunReconciler implements CommandLineRunner {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String STATUS_GENERATING = "GENERATING";
    private static final String STATUS_FAILED = "FAILED";
    /** 运行中清理的「过旧」阈值：> 单次生成上限，且足够避开落库→注册句柄的窗口。 */
    private static final long STALE_MS = 7 * 60 * 1000L;

    private final ChatMessageMapper chatMessageMapper;
    private final RunRegistry runRegistry;

    @Override
    public void run(String... args) {
        int n = reconcile(true);
        if (n > 0) {
            log.warn("启动清理：{} 条遗留 GENERATING 助手消息已刷成 FAILED（上次进程重启遗留）", n);
        }
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    public void sweep() {
        reconcile(false);
    }

    private int reconcile(boolean startup) {
        return TenantContext.runAsSystem(() -> {
            Set<String> live = runRegistry.liveRunIds();
            LambdaUpdateWrapper<ChatMessage> uw = new LambdaUpdateWrapper<ChatMessage>()
                    .eq(ChatMessage::getRole, ROLE_ASSISTANT)
                    .eq(ChatMessage::getStatus, STATUS_GENERATING);
            if (!live.isEmpty()) {
                uw.notIn(ChatMessage::getRunId, live);
            }
            if (!startup) {
                uw.lt(ChatMessage::getCreateTime, new Date(System.currentTimeMillis() - STALE_MS));
            }
            uw.set(ChatMessage::getStatus, STATUS_FAILED)
                    .set(ChatMessage::getError, "生成中断（服务重启或异常）");
            return chatMessageMapper.update(null, uw);
        });
    }
}
