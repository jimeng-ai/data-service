package com.jimeng.dataserver.ai.rag.service.ingest;

import com.jimeng.dataserver.ai.rag.config.RagRabbitConfig;
import com.jimeng.dataserver.ai.rag.model.IngestionMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionQueueProducer {

    private final RabbitTemplate rabbitTemplate;

    public void publish(IngestionMessage msg) {
        rabbitTemplate.convertAndSend(
                RagRabbitConfig.INGESTION_EXCHANGE,
                RagRabbitConfig.INGESTION_ROUTING_KEY,
                msg);
        log.info("入库消息已投递 docId={} kbId={}", msg.getDocId(), msg.getKbId());
    }
}
