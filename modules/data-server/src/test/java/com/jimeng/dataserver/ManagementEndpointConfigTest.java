package com.jimeng.dataserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 部署脚本靠 http://ds-data-server:8021/actuator/health/readiness 判断新版本是否就绪（.github/workflows/deploy.yml）。
 * 这几个值放在仓库的 application.yml 而不是 Nacos：它们和部署脚本必须一起评审、一起回滚。
 */
class ManagementEndpointConfigTest {

    private static Properties appYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    @Test
    void actuatorIsOnTheClasspath() {
        assertDoesNotThrow(() -> Class.forName("org.springframework.boot.actuate.health.HealthEndpoint"));
    }

    @Test
    void healthIsServedOnASeparateInternalPort() {
        assertEquals("8021", appYml().getProperty("management.server.port"));
    }

    @Test
    void onlyHealthIsExposed() {
        assertEquals("health", appYml().getProperty("management.endpoints.web.exposure.include"));
    }

    @Test
    void readinessRequiresDatabaseAndRedis() {
        Properties p = appYml();
        assertEquals("true", p.getProperty("management.endpoint.health.probes.enabled"));
        List<String> include = Arrays.asList(
                p.getProperty("management.endpoint.health.group.readiness.include").split(","));
        assertTrue(include.containsAll(List.of("readinessState", "db", "redis")), include.toString());
    }
}
