package com.jimeng.dataserver.ai.rag.service.ingest;

import com.jimeng.dataserver.ai.rag.model.IngestionMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionQueueConsumer {

    private final DocumentIngestionService ingestionService;

    @RabbitListener(queues = "${rag.ingestion.queue:rag.ingestion}",
            concurrency = "#{@ragProperties.ingestion.concurrency}")
    public void onMessage(IngestionMessage msg) {
        log.info("收到入库消息 docId={} kbId={}", msg.getDocId(), msg.getKbId());
        try {
            ingestionService.ingest(msg.getDocId());
        } catch (Exception e) {
            // 抛出让 RabbitMQ 重试 / 进 DLQ
            throw new RuntimeException("入库失败 docId=" + msg.getDocId(), e);
        }
    }
}
