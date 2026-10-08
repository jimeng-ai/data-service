package com.jimeng.dataserver.ai.http.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostPatternTest {

    @Test
    @DisplayName("规范化：小写、去掉结尾的点、中文域名转 punycode")
    void normalizes() {
        assertThat(HostPattern.parse(" API.Example.COM. ").text()).isEqualTo("api.example.com");
        assertThat(HostPattern.parse("例子.测试").text()).isEqualTo("xn--fsqu00a.xn--0zwm56d");
        assertThat(HostPattern.parse("*.Example.com").text()).isEqualTo("*.example.com");
    }

    @Test
    @DisplayName("只写域名：带协议、路径、端口、账号、空白、空标签都拒绝")
    void rejectsNonHost() {
        for (String bad : new String[]{"", "  ", "https://api.example.com", "api.example.com/v1", "api.example.com:8443",
                "user@api.example.com", "api example.com", "api..example.com", "-api.example.com", "api.example.com?x"}) {
            assertThatThrownBy(() -> HostPattern.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("通配只能写在最前面，且至少到二级域名")
    void wildcardShape() {
        assertThatThrownBy(() -> HostPattern.parse("api.*.example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostPattern.parse("*.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostPattern.parse("*")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("*.example.com 只匹配一层：a.example.com 是，a.b.example.com 和 example.com 都不是")
    void wildcardMatchesOneLabel() {
        HostPattern p = HostPattern.parse("*.example.com");
        assertThat(p.matches("a.example.com")).isTrue();
        assertThat(p.matches("A.EXAMPLE.COM.")).isTrue();
        assertThat(p.matches("a.b.example.com")).isFalse();
        assertThat(p.matches("example.com")).isFalse();
        assertThat(p.matches("evilexample.com")).isFalse();
        assertThat(p.matches("a.example.com.evil.com")).isFalse();
    }

    @Test
    @DisplayName("精确域名只匹配自己")
    void exactMatch() {
        HostPattern p = HostPattern.parse("api.example.com");
        assertThat(p.matches("api.example.com")).isTrue();
        assertThat(p.matches("x.api.example.com")).isFalse();
        assertThat(p.matches("api.example.com.cn")).isFalse();
    }

    @Test
    @DisplayName("IP 字面量按地址比：v4 与 v4-mapped 是同一个；越界的八位组直接拒绝，不能落到 DNS 去")
    void ipLiteral() {
        HostPattern v4 = HostPattern.parse("203.0.113.7");
        assertThat(v4.isIpLiteral()).isTrue();
        assertThat(v4.matches("203.0.113.7")).isTrue();
        assertThat(v4.matches("::ffff:203.0.113.7")).isTrue();
        assertThat(v4.matches("203.0.113.8")).isFalse();
        assertThat(v4.matches("api.example.com")).isFalse();
        HostPattern v6 = HostPattern.parse("[2001:4860::8888]");
        assertThat(v6.matches("2001:4860:0:0:0:0:0:8888")).isTrue();
        assertThatThrownBy(() -> HostPattern.parse("1.2.3.999")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostPattern.parse("2001:zz::1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("标签允许下划线（内网主机名、Docker Compose 服务名里常见）；开头、结尾仍不能是连字符")
    void underscoreLabels() {
        assertThat(HostPattern.parse("a_b.com").text()).isEqualTo("a_b.com");
        assertThat(HostPattern.parse("my_api.corp.local").text()).isEqualTo("my_api.corp.local");
        assertThat(HostPattern.parse("*.my_zone.corp").text()).isEqualTo("*.my_zone.corp");
        assertThat(HostPattern.parse("a_b.com").matches("A_B.com.")).isTrue();
        assertThatThrownBy(() -> HostPattern.parse("-a.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostPattern.parse("a-.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("通配那一层只能是 [a-z0-9_-]：空白、#、NUL 这类 URL 里本来就不会出现的字符不算一层")
    void wildcardHeadCharset() {
        HostPattern p = HostPattern.parse("*.example.com");
        assertThat(p.matches("a.example.com")).isTrue();
        assertThat(p.matches("a_b.example.com")).isTrue();
        assertThat(p.matches("a-b.example.com")).isTrue();
        assertThat(p.matches("evil#.example.com")).isFalse();
        assertThat(p.matches("a b.example.com")).isFalse();
        assertThat(p.matches("evil\0.example.com")).isFalse();
        assertThat(p.matches("a.b.example.com")).isFalse();
    }

    @Test
    @DisplayName("以 . 开头的冒号串不是 IP 字面量：直接拒绝")
    void dotColonIsRejected() {
        assertThatThrownBy(() -> HostPattern.parse(".:1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HostPattern.parse(".::")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 子进程里让 hosts 文件把 {@code .:1} 映射到 10.9.8.7。JDK 只在 {@code InetAddress} 类初始化时读
     * {@code jdk.net.hosts.file}，当前进程里设已经来不及，所以必须起子进程。
     * 若 {@code HostPattern} 把这串交给 {@code InetAddress.getByName}（首字符不是十六进制数字或冒号时 JDK 会去查名称服务），
     * 它就会被当成 IP 字面量收下。没有这个映射时修前修后都是「被拒绝」，证明不了任何事。
     */
    @Test
    @DisplayName("★ 解析永远不碰名称服务：hosts 文件把 \".:1\" 映射成 10.9.8.7，它仍然被拒绝")
    void neverReachesNameService() throws Exception {
        Path hosts = Files.createTempFile("host-pattern-hosts", ".txt");
        try {
            Files.writeString(hosts, "10.9.8.7 .:1\n");
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process p = new ProcessBuilder(java, "-Djdk.net.hosts.file=" + hosts, "-cp", System.getProperty("java.class.path"),
                    NoDnsProbe.class.getName()).redirectErrorStream(true).start();
            boolean done = p.waitFor(60, TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(done).as("子进程超时：" + out).isTrue();
            assertThat(p.exitValue()).as("子进程输出：" + out).isZero();
        } finally {
            Files.deleteIfExists(hosts);
        }
    }

    /** {@link #neverReachesNameService()} 的子进程入口：退出码 0 = 被拒绝且没匹配；1 = 被收下；2 = 被匹配；3 = hosts 映射没生效。 */
    public static final class NoDnsProbe {
        public static void main(String[] args) throws Exception {
            // 先证明这个子进程里映射真的生效，否则下面的断言什么也证明不了
            String mapped = InetAddress.getByName(".:1").getHostAddress();
            if (!"10.9.8.7".equals(mapped)) {
                System.out.println("hosts 映射没生效：.:1 解析成 " + mapped);
                System.exit(3);
            }
            try {
                HostPattern p = HostPattern.parse(".:1");
                System.out.println("被收下：text=" + p.text() + " isIpLiteral=" + p.isIpLiteral());
                System.exit(1);
            } catch (IllegalArgumentException expected) {
                // 期望的结果
            }
            if (HostPattern.parse("10.9.8.7").matches(".:1")) {
                System.out.println("matches() 把 .:1 当成了 10.9.8.7");
                System.exit(2);
            }
        }
    }
}
