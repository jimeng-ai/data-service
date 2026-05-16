package com.jimeng.dataserver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration.class
})
@EnableDiscoveryClient
@EnableAspectJAutoProxy
@ComponentScan(basePackages = {
        "com.jimeng.common.core",
        "com.jimeng.persistence",
        "com.jimeng.dataserver"
},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {com.jimeng.common.core.utils.MinIOUtil.class}
        ))
@Slf4j
public class DataServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataServerApplication.class, args);
        log.info("数据服务启动");
    }

}
