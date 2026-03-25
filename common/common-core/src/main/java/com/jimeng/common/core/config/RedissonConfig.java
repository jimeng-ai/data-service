package com.jimeng.common.core.config;

import com.jimeng.common.core.redisson.DistributedLocker;
import com.jimeng.common.core.redisson.RedissonDistributedLocker;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @Author Moonlight
 * @Description 配置类
 * @Date 2024/7/13 19:55
 */

@Configuration
public class RedissonConfig {

    private final List<String> redisConfigList = new ArrayList<>(Arrays.asList("redis://localhost:6379"));

    @Bean
    public DistributedLocker distributedLocker() {
        return new RedissonDistributedLocker();
    }

    @Bean
    public RedissonClient redissonClient() {
        String[] configArray = redisConfigList.toArray(new String[0]);
        Config config = new Config();
        config.useSingleServer().setAddress(configArray[0]);
        // 集群模式
//        config.useClusterServers().setScanInterval(2000).addNodeAddress(configArray);
        return Redisson.create(config);
    }

}
