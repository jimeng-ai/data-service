package com.jimeng.dataserver.ai.rag.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RagRabbitConfig {

    public static final String INGESTION_EXCHANGE = "rag.ingestion.exchange";
    public static final String INGESTION_ROUTING_KEY = "rag.ingestion";
    public static final String INGESTION_DLQ_EXCHANGE = "rag.ingestion.dlq.exchange";
    public static final String INGESTION_DLQ_ROUTING_KEY = "rag.ingestion.dlq";

    private final RagProperties ragProperties;

    @Bean
    public DirectExchange ragIngestionExchange() {
        return new DirectExchange(INGESTION_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange ragIngestionDlqExchange() {
        return new DirectExchange(INGESTION_DLQ_EXCHANGE, true, false);
    }

    @Bean
    public Queue ragIngestionQueue() {
        return QueueBuilder.durable(ragProperties.getIngestion().getQueue())
                .withArgument("x-dead-letter-exchange", INGESTION_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", INGESTION_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue ragIngestionDlq() {
        return QueueBuilder.durable(ragProperties.getIngestion().getQueue() + ".dlq").build();
    }

    @Bean
    public Binding ragIngestionBinding(Queue ragIngestionQueue, DirectExchange ragIngestionExchange) {
        return BindingBuilder.bind(ragIngestionQueue).to(ragIngestionExchange).with(INGESTION_ROUTING_KEY);
    }

    @Bean
    public Binding ragIngestionDlqBinding(Queue ragIngestionDlq, DirectExchange ragIngestionDlqExchange) {
        return BindingBuilder.bind(ragIngestionDlq).to(ragIngestionDlqExchange).with(INGESTION_DLQ_ROUTING_KEY);
    }

    @Bean
    public MessageConverter ragMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
