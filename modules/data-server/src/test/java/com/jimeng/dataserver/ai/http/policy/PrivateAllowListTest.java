package com.jimeng.dataserver.ai.http.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivateAllowListTest {

    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    @DisplayName("CIDR（v4 / v6）和域名（含一层通配）都能写")
    void permits() throws Exception {
        PrivateAllowList l = PrivateAllowList.parse(List.of("10.1.0.0/16", "fd00:1::/32", "crm.corp.local", "*.erp.local"));
        assertThat(l.permits("any", ip("10.1.2.3"))).isTrue();
        assertThat(l.permits("any", ip("10.2.0.1"))).isFalse();
        assertThat(l.permits("any", ip("fd00:1::5"))).isTrue();
        assertThat(l.permits("crm.corp.local", ip("10.9.9.9"))).isTrue();
        assertThat(l.permits("a.erp.local", ip("192.168.3.3"))).isTrue();
        assertThat(l.permits("a.b.erp.local", ip("192.168.3.3"))).isFalse();
    }

    @Test
    @DisplayName("写错的条目启动时就报错，并点名是哪一条")
    void invalidEntryFailsFast() {
        assertThatThrownBy(() -> PrivateAllowList.parse(List.of("10.0.0.0/33")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("10.0.0.0/33");
        assertThatThrownBy(() -> PrivateAllowList.parse(List.of("http://crm.local")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("http://crm.local");
    }

    @Test
    @DisplayName("空列表（SaaS 默认）什么都不放行；空白条目忽略")
    void emptyPermitsNothing() throws Exception {
        assertThat(PrivateAllowList.parse(null).isEmpty()).isTrue();
        assertThat(PrivateAllowList.parse(List.of(" ", "")).permits("x", ip("10.0.0.1"))).isFalse();
    }

    @Test
    @DisplayName("单写一个 IP（v4、带方括号的 v6）放行的是这个地址本身，不管请求里写的是哪个域名")
    void singleIpIsAnAddress() throws Exception {
        PrivateAllowList l = PrivateAllowList.parse(List.of("10.0.0.5", "[fd00::5]"));
        assertThat(l.permits("crm.corp.local", ip("10.0.0.5"))).isTrue();
        assertThat(l.permits("crm.corp.local", ip("10.0.0.6"))).isFalse();
        assertThat(l.permits("erp.corp.local", ip("fd00::5"))).isTrue();
    }
}
