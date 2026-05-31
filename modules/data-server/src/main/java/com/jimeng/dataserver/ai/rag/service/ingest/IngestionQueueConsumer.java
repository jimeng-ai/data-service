package com.jimeng.dataserver.ai.rag.service.ingest;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.rag.model.IngestionMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionQueueConsumer {

    private final DocumentIngestionService ingestionService;

    @RabbitListener(queues = "${rag.ingestion.queue:rag.ingestion}",
            concurrency = "#{@ragProperties.ingestion.concurrency}")
    public void onMessage(IngestionMessage msg) {
        log.info("收到入库消息 docId={} kbId={} tenantId={}", msg.getDocId(), msg.getKbId(), msg.getTenantId());
        // 异步线程没有 HTTP 请求上下文，按入队时携带的租户设置 TenantContext，
        // 让入库期间 embedding / contextualization 的计费能归到对应租户；finally 清理防线程池泄漏。
        boolean tenantSet = StringUtils.hasText(msg.getTenantId());
        if (tenantSet) {
            TenantContext.set(msg.getTenantId());
        }
        try {
            ingestionService.ingest(msg.getDocId());
        } catch (Exception e) {
            // 抛出让 RabbitMQ 重试 / 进 DLQ
            throw new RuntimeException("入库失败 docId=" + msg.getDocId(), e);
        } finally {
            if (tenantSet) {
                TenantContext.clear();
            }
        }
    }
}
