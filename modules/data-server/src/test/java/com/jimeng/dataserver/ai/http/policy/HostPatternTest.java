package com.jimeng.dataserver.ai.http.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
