package com.jimeng.sys.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * @Author Moonlight
 * @Description RabbitMQ配置类
 * @Date 2024/10/13 15:17
 */

@Configuration
@Slf4j
public class RabbitConfig {

    private final String host = "121.36.200.69";

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(host);
        // 开启消息发布确认
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        return connectionFactory;
    }

    @Bean
    public SimpleMessageListenerContainer container(ConnectionFactory connectionFactory) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 设置消息确认模式为手动确认
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return container;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        // 设置 ConfirmCallback
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.info("消息发送成功，消息ID：{}，发送时间：{}", correlationData.getId(), LocalDateTime.now());
            } else {
                log.info("消息发送失败，消息ID：{}，原因：{}", correlationData.getId(), cause);
            }
        });
        // 设置 ReturnsCallback
        template.setReturnsCallback(returned -> {
            int code = returned.getReplyCode();
            log.info("消息返回，code={}，returned={}", code, returned);
        });
        // 开启强制消息投递（mandatory为设置为true），但消息未被路由至任何一个queue，则回退一条消息，避免消息丢失
        template.setMandatory(true);
        log.info("RabbitMQ初始化完成~~~");
        return template;
    }

}
