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

    @Test
    @DisplayName("本地 NAT64 编码的本机和元数据地址不被域名或不同布局的 CIDR 白名单放开")
    void localNat64HardBlocksWinOverDomainAndCidrAllowlists() throws Exception {
        List<String> encodedTargets = List.of(
                "64:ff9b:1::7f00:1",
                "64:ff9b:1:7f00:0:100::",
                "64:ff9b:1:a9fe:a9:fe00::"
        );
        for (List<String> entries : List.of(
                List.of("crm.corp.local"),
                List.of("64:ff9b:1::/48"),
                List.of("64:ff9b:1:7f00::/64")
        )) {
            PrivateAllowList allowPrivate = PrivateAllowList.parse(entries);
            AddressPolicy policy = AddressPolicy.standard(allowPrivate);
            boolean matched = false;
            for (String encoded : encodedTargets) {
                InetAddress address = ip(encoded);
                if (allowPrivate.permits("crm.corp.local", address)) {
                    matched = true;
                    assertThat(policy.check("crm.corp.local", address, true)).as(entries + " -> " + encoded).isNotNull();
                }
            }
            assertThat(matched).as("白名单确实匹配至少一个编码地址：" + entries).isTrue();
        }
    }

    @Test
    @DisplayName("Teredo 中编码本机或元数据地址的地址不被域名和 CIDR 白名单放开")
    void teredoHardBlocksWinOverDomainAndCidrAllowlists() throws Exception {
        List<String> encodedTargets = List.of(
                "2001:0:7f00:1:0:fe44:f7f7:f7f7",
                "2001:0:808:808:0:fe44:80ff:fffe",
                "2001:0:808:808:0:fe44:5601:5601"
        );
        for (List<String> entries : List.of(List.of("crm.corp.local"), List.of("2001::/32"))) {
            PrivateAllowList allowPrivate = PrivateAllowList.parse(entries);
            AddressPolicy policy = AddressPolicy.standard(allowPrivate);
            for (String encoded : encodedTargets) {
                InetAddress address = ip(encoded);
                assertThat(allowPrivate.permits("crm.corp.local", address)).as(entries + " matches " + encoded).isTrue();
                assertThat(policy.check("crm.corp.local", address, true)).as(entries + " -> " + encoded).isNotNull();
            }
        }
    }
}
