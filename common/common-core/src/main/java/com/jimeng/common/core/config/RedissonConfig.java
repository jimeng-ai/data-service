package com.jimeng.common.core.config;

import com.jimeng.common.core.redisson.DistributedLocker;
import com.jimeng.common.core.redisson.RedissonDistributedLocker;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * @Author Moonlight
 * @Description 配置类
 * @Date 2024/7/13 19:55
 */

@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean
    public DistributedLocker distributedLocker() {
        return new RedissonDistributedLocker();
    }

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        var serverConfig = config.useSingleServer().setAddress("redis://" + host + ":" + port);
        if (StringUtils.hasText(password)) {
            serverConfig.setPassword(password);
        }
        // 集群模式
//        config.useClusterServers().setScanInterval(2000).addNodeAddress(configArray);
        return Redisson.create(config);
    }

}
