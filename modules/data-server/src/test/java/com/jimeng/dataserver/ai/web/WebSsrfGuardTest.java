package com.jimeng.dataserver.ai.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SSRF 闸纯逻辑单测：仅用 IP 字面量 + scheme（不触发真实 DNS），确定性。 */
class WebSsrfGuardTest {

    private final WebSsrfGuard guard = new WebSsrfGuard();

    @Test
    void blocksBadSchemes() {
        assertNotNull(guard.validate("ftp://example.com/x"));
        assertNotNull(guard.validate("file:///etc/passwd"));
        assertNotNull(guard.validate("http://"));
    }

    @Test
    void blocksIpLiteralTargets() {
        assertEquals("ip literal not allowed", guard.validate("http://10.0.0.1/"));
        assertEquals("ip literal not allowed", guard.validate("http://127.0.0.1/"));
        assertEquals("ip literal not allowed", guard.validate("http://169.254.169.254/latest/meta-data"));
        assertEquals("ip literal not allowed", guard.validate("https://192.168.1.1/"));
    }

    @Test
    void isBlockedClassifiesAddresses() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "192.168.1.1", "172.16.0.1",
                "169.254.169.254", "100.64.0.1", "0.0.0.0"}) {
            assertTrue(guard.isBlocked(InetAddress.getByName(ip)), "should block " + ip);
        }
        for (String ip : new String[]{"8.8.8.8", "1.1.1.1"}) {
            assertFalse(guard.isBlocked(InetAddress.getByName(ip)), "should allow " + ip);
        }
    }
}
