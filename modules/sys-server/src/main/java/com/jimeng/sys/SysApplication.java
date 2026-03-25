package com.jimeng.sys;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * @Author Moonlight
 * @Description 系统服务
 * @Date 2024/7/24 23:53
 */

@SpringBootApplication
@EnableDiscoveryClient
@EnableAspectJAutoProxy
@ComponentScan(basePackages = {
        "com.jimeng.common.core.config",
        "com.jimeng.common.core.service",
        "com.jimeng.common.core.utils",
        "com.jimeng.persistence",
        "com.jimeng.sys"
})
@Slf4j
public class SysApplication {
    public static void main(String[] args) {
        SpringApplication.run(SysApplication.class, args);
        log.info("系统服务启动成功");
    }
}
