package com.jimeng.dataserver;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 Spring Boot 自己的健康检查机制跑一遍仓库里 application.yml 的 readiness 组。
 *
 * <p>只解析 YAML 不够：Boot 3.0 遇到写错名字的组成员（比如把 redis 写成 redsi）不会报错，只是悄悄忽略，
 * readiness 照样 UP，部署门禁就漏掉了 Redis。这里用与生产同名的健康检查项（db、redis）验证三件事：
 * 组里确实有这些成员、都正常时是 UP、任何一个挂了就是 DOWN。
 */
class ReadinessGroupTest {

    private static String[] managementProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties p = yaml.getObject();
        // 不能用 stringPropertyNames()：它只返回值为字符串的键，probes.enabled: true 这种布尔值会被漏掉
        return p.keySet().stream()
                .map(String::valueOf)
                .filter(k -> k.startsWith("management.endpoint"))
                .map(k -> k + "=" + p.getProperty(k))
                .toArray(String[]::new);
    }

    private static HealthIndicator fixed(Status status) {
        return () -> Health.status(status).build();
    }

    private static WebApplicationContextRunner runner(Status db, Status redis) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ApplicationAvailabilityAutoConfiguration.class,
                        AvailabilityHealthContributorAutoConfiguration.class,
                        AvailabilityProbesAutoConfiguration.class,
                        EndpointAutoConfiguration.class,
                        HealthContributorAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class))
                .withPropertyValues(managementProperties())
                // 与生产同名：DataSource 的检查项叫 db，Redis 的叫 redis
                .withBean("dbHealthIndicator", HealthIndicator.class, () -> fixed(db))
                .withBean("redisHealthIndicator", HealthIndicator.class, () -> fixed(redis));
    }

    private static HealthComponent readiness(org.springframework.context.ApplicationContext ctx) {
        AvailabilityChangeEvent.publish(ctx, ReadinessState.ACCEPTING_TRAFFIC);   // 启动完成后 SpringApplication 会发这个事件
        return ctx.getBean(HealthEndpoint.class).healthForPath("readiness");
    }

    @Test
    void readinessGroupReallyContainsDatabaseAndRedis() {
        runner(Status.UP, Status.UP).run(ctx -> {
            HealthComponent readiness = readiness(ctx);
            Set<String> members = ((CompositeHealth) readiness).getComponents().keySet();
            assertTrue(members.containsAll(Set.of("readinessState", "db", "redis")),
                    "readiness 组的实际成员：" + members + "（名字写错的成员会被 Boot 悄悄忽略）；容器里的健康检查项："
                            + java.util.Arrays.toString(ctx.getBeanNamesForType(HealthIndicator.class))
                            + "，probes.enabled=" + ctx.getEnvironment().getProperty("management.endpoint.health.probes.enabled"));
            assertEquals(Status.UP, readiness.getStatus());
        });
    }

    @Test
    void redisDownMakesReadinessDown() {
        runner(Status.UP, Status.DOWN).run(ctx -> assertEquals(Status.DOWN, readiness(ctx).getStatus()));
    }

    @Test
    void databaseDownMakesReadinessDown() {
        runner(Status.DOWN, Status.UP).run(ctx -> assertEquals(Status.DOWN, readiness(ctx).getStatus()));
    }
}
