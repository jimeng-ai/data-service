package com.jimeng.common.core.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author Moonlight
 * @Description Knife4j (Swagger3) 配置类
 * @Date 2024/10/19 17:15
 */

@Configuration
public class Knife4jConfig {

    /**
     * 配置 OpenAPI 基本信息
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("基础模板系统 API 文档")
                        .version("1.0.0")
                        .description("基于 Spring Boot 3.x + Spring Cloud 2022 的微服务基础模板")
                        .contact(new Contact()
                                .name("Moonlight")
                                .email("your-email@example.com")
                                .url("https://github.com/your-repo"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0.html")));
    }

    /**
     * 配置 API 分组 - 系统管理模块
     */
    @Bean
    public GroupedOpenApi systemApi() {
        return GroupedOpenApi.builder()
                .group("系统管理")
                .pathsToMatch("/admin/sys/**")
                .build();
    }

    /**
     * 配置 API 分组 - 业务模块
     */
    @Bean
    public GroupedOpenApi businessApi() {
        return GroupedOpenApi.builder()
                .group("业务模块")
                .pathsToMatch("/api/**")
                .build();
    }

    /**
     * 配置 API 分组 - 全部接口
     */
    @Bean
    public GroupedOpenApi allApi() {
        return GroupedOpenApi.builder()
                .group("全部接口")
                .pathsToMatch("/**")
                .build();
    }

}
