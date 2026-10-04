package com.jimeng.dataserver.ai.rag.service.ingest;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.rag.model.IngestionMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IngestionQueueConsumerTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void failedIngestionIsDeadLetteredNotRequeued() {
        DocumentIngestionService ingestion = mock(DocumentIngestionService.class);
        doThrow(new IllegalStateException("embedding 429")).when(ingestion).ingest(42L);
        IngestionQueueConsumer consumer = new IngestionQueueConsumer(ingestion);

        AmqpRejectAndDontRequeueException e = assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.onMessage(new IngestionMessage(42L, 7L, null, "t_a")));
        assertTrue(e.getMessage().contains("docId=42"), e.getMessage());
        assertNull(TenantContext.get(), "失败后也要清掉租户上下文，消费线程会被复用");
    }

    @Test
    void successfulIngestionRunsUnderTheMessageTenant() {
        DocumentIngestionService ingestion = mock(DocumentIngestionService.class);
        doAnswer(inv -> {
            assertEquals("t_a", TenantContext.get());
            return null;
        }).when(ingestion).ingest(42L);

        new IngestionQueueConsumer(ingestion).onMessage(new IngestionMessage(42L, 7L, null, "t_a"));

        verify(ingestion).ingest(42L);
        assertNull(TenantContext.get());
    }
}
