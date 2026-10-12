package com.jimeng.dataserver.ai.http.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AddressPolicyTest {

    private static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    @DisplayName("公网直接放行；内网默认拒绝")
    void defaults() throws Exception {
        AddressPolicy p = AddressPolicy.standard(PrivateAllowList.EMPTY);
        assertThat(p.check("api.example.com", ip("8.8.8.8"), true)).isNull();
        assertThat(p.check("api.example.com", ip("10.0.0.1"), true)).contains("内网");
    }

    @Test
    @DisplayName("白名单只对「skill 或 key 声明过的域名」生效：靠 web:true 放进来的地址不能借它进内网（§4.4）")
    void allowPrivateNeedsDeclaredHost() throws Exception {
        AddressPolicy p = AddressPolicy.standard(PrivateAllowList.parse(List.of("10.0.0.0/8")));
        assertThat(p.check("crm.corp.local", ip("10.1.1.1"), true)).isNull();
        assertThat(p.check("crm.corp.local", ip("10.1.1.1"), false)).isNotNull();
    }

    @Test
    @DisplayName("白名单写了 127.0.0.0/8 也放不开本机：本机上有 Nacos / Redis / MySQL")
    void hardBlockWins() throws Exception {
        AddressPolicy p = AddressPolicy.standard(PrivateAllowList.parse(List.of("127.0.0.0/8", "169.254.0.0/16")));
        assertThat(p.check("crm.corp.local", ip("127.0.0.1"), true)).isNotNull();
        assertThat(p.check("crm.corp.local", ip("169.254.169.254"), true)).isNotNull();
    }
}
