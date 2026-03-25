package com.jimeng.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * @Author Moonlight
 * @Description 网关启动器
 * @Date 2024/7/24 21:25
 */

@EnableDiscoveryClient
@SpringBootApplication
@Slf4j
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
        log.info("网关服务启动成功");
    }
}
