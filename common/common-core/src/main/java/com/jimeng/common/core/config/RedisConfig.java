package com.jimeng.common.core.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jimeng.common.core.service.RoomReceiverService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * @Author Moonlight
 * @Description redis配置类
 * @Date 2024/7/14 11:49
 */

@Configuration
@Slf4j
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(final RedisConnectionFactory redisConnectionFactory) {
        final RedisSerializer<String> stringRedisSerializer = new StringRedisSerializer();
        // 使用定制的 Jackson2JsonRedisSerializer
        Jackson2JsonRedisSerializer<Object> resSerializer = new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false); // 设置忽略未知属性
        resSerializer.setObjectMapper(objectMapper);

        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(stringRedisSerializer);
        redisTemplate.setValueSerializer(resSerializer);
        redisTemplate.setHashKeySerializer(stringRedisSerializer);
        redisTemplate.afterPropertiesSet();

        return redisTemplate;
    }

    /**
     * redis消息监听器容器
     * 可以添加多个监听不同话题的redis监听器，只需要把消息监听器和相应的消息订阅类添加到该监听器
     * @param connectionFactory
     * @param listenerAdapter
     * @return
     */
    @Bean
    RedisMessageListenerContainer roomContainer(RedisConnectionFactory connectionFactory,
                                                MessageListenerAdapter listenerAdapter
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        //可以添加多个 messageListener
        container.addMessageListener(listenerAdapter, new PatternTopic("index"));

        return container;
    }

    /**
     * 消息监听器适配器，绑定消息处理器，利用反射技术调用消息处理器的业务方法
     * @param roomReceiverService
     * @return
     */
    @Bean
    MessageListenerAdapter roomListenerAdapter(RoomReceiverService roomReceiverService) {
        log.info("已绑定消息处理器");
        return new MessageListenerAdapter(roomReceiverService, "receiveMessage");
    }

}
