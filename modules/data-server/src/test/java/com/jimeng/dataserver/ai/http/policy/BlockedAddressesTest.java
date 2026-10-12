package com.jimeng.dataserver.ai.http.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class BlockedAddressesTest {

    private static boolean hard(String ip) throws Exception {
        return BlockedAddresses.isHardBlocked(InetAddress.getByName(ip));
    }

    @Test
    @DisplayName("本机、链路本地（含云元数据）、未指定、组播：任何白名单都放不开；内嵌这些地址的 v6 也一样")
    void hardBlocked() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "127.9.9.9", "::1", "::", "0.0.0.0", "169.254.169.254",
                "fe80::1", "224.0.0.1", "255.255.255.255", "ff02::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2002:7f00:1::1"}) {
            assertThat(hard(ip)).as(ip).isTrue();
        }
    }

    @Test
    @DisplayName("普通私网段不是硬拒绝：私有化部署可以用白名单放开")
    void privateIsNotHard() throws Exception {
        for (String ip : new String[]{"10.0.0.1", "172.16.0.1", "192.168.1.1", "100.64.0.1", "fc00::1", "::ffff:10.0.0.1"}) {
            assertThat(hard(ip)).as(ip).isFalse();
        }
    }

    @Test
    @DisplayName("16 字节的 v4-mapped（Inet6Address.getByAddress 造得出来）按内嵌的 v4 判，不能当成公网")
    void mappedV6FormIsJudgedByEmbeddedV4() throws Exception {
        assertThat(BlockedAddresses.isBlocked(mapped(10, 0, 0, 1))).isTrue();
        assertThat(BlockedAddresses.isHardBlocked(mapped(127, 0, 0, 1))).isTrue();
        assertThat(BlockedAddresses.isBlocked(mapped(8, 8, 8, 8))).isFalse();
    }

    private static InetAddress mapped(int a, int b, int c, int d) throws Exception {
        byte[] bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        bytes[12] = (byte) a;
        bytes[13] = (byte) b;
        bytes[14] = (byte) c;
        bytes[15] = (byte) d;
        InetAddress addr = Inet6Address.getByAddress(null, bytes, -1);
        // 前提：JDK 没把它归一成 4 字节；否则这条测试碰不到 16 字节的分支，会白白通过
        assertThat(addr).isInstanceOf(Inet6Address.class);
        return addr;
    }
}
