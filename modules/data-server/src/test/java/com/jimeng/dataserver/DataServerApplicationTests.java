package com.jimeng.dataserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 整个 Spring 上下文能不能装起来。
 *
 * <h3>★ 为什么加了这道 {@link EnabledIf}</h3>
 * 这是一个<b>需要全套基础设施</b>的集成测试：配置来自 Nacos，上下文里还要建 Redisson、
 * 数据源、ES 客户端。基础设施没起时它<b>必然</b>失败，失败信息还各不相同——Redis 没起时报
 * {@code Unable to connect to Redis server}，Redis 起了而 Nacos 没起时报
 * {@code Could not resolve placeholder 'okhttp.read-timeout'}，都不是代码的问题。
 *
 * <p>而 {@code mvn -pl modules/data-server test} 是本仓库的提交门禁。一个在开发机上必然失败的
 * 测试会让门禁<b>永远是红的</b>，于是没有人再看它——<b>永远红的门禁等于没有门禁</b>，
 * 真正的回归反而藏在这一片红里。
 *
 * <p>所以：基础设施可达就照常跑（CI 的自托管 runner 上 {@code ds-*} 一直在跑，这里是真的会执行的）；
 * 不可达就<b>跳过并写明原因</b>。跳过和失败的区别是：跳过说的是「这次没验」，失败说的是「这里坏了」。
 * 把前者说成后者，代价是所有人都不再相信后者。
 *
 * <p>要在本机真的跑它：{@code ./deploy.sh infra} 起齐依赖后再 {@code mvn test}。
 */
@SpringBootTest
@EnabledIf(value = "infraReachable",
        disabledReason = "基础设施未就绪（Nacos / Redis 连不上）——这是集成测试，"
                + "跑它需要先 ./deploy.sh infra。跳过≠通过。")
class DataServerApplicationTests {

    @Test
    void contextLoads() {
    }

    /**
     * 探测上下文启动所必需的两个外部依赖：Nacos（配置从它来，拉不到就全是 placeholder 解析失败）
     * 与 Redis（Redisson bean 在上下文装配期就要连上）。
     * 地址跟随环境变量，默认与 docker/docker-compose.yml 的 {@code ds-*} 一致。
     */
    @SuppressWarnings("unused")
    static boolean infraReachable() {
        return reachable(hostPort(System.getenv("NACOS_SERVER_ADDR"), "localhost", 8848))
                && reachable(new InetSocketAddress(
                        orDefault(System.getenv("SPRING_DATA_REDIS_HOST"), "localhost"),
                        parsePort(System.getenv("SPRING_DATA_REDIS_PORT"), 6379)));
    }

    private static InetSocketAddress hostPort(String addr, String defHost, int defPort) {
        if (addr == null || addr.isBlank()) {
            return new InetSocketAddress(defHost, defPort);
        }
        int idx = addr.lastIndexOf(':');
        if (idx < 0) {
            return new InetSocketAddress(addr.trim(), defPort);
        }
        return new InetSocketAddress(addr.substring(0, idx).trim(),
                parsePort(addr.substring(idx + 1), defPort));
    }

    private static boolean reachable(InetSocketAddress addr) {
        try (Socket s = new Socket()) {
            s.connect(addr, 700);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String orDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private static int parsePort(String v, int def) {
        try {
            return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
