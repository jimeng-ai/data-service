package com.jimeng.dataserver.admin.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpResolverTest {

    private static MockHttpServletRequest viaGateway() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("172.18.0.5");   // 网关容器的地址
        return r;
    }

    @Test
    void prefersCloudflareHeader() {
        MockHttpServletRequest r = viaGateway();
        r.addHeader("CF-Connecting-IP", "203.0.113.9");
        r.addHeader("X-Forwarded-For", "198.51.100.1");
        assertEquals("203.0.113.9", ClientIpResolver.resolve(r));
    }

    @Test
    void takesFirstHopOfForwardedFor() {
        MockHttpServletRequest r = viaGateway();
        r.addHeader("X-Forwarded-For", "198.51.100.1, 172.18.0.1");
        assertEquals("198.51.100.1", ClientIpResolver.resolve(r));
    }

    @Test
    void fallsBackToRealIpThenRemoteAddr() {
        MockHttpServletRequest r = viaGateway();
        r.addHeader("X-Real-IP", "192.0.2.7");
        assertEquals("192.0.2.7", ClientIpResolver.resolve(r));
        assertEquals("172.18.0.5", ClientIpResolver.resolve(viaGateway()));
    }

    @Test
    void acceptsIpv6() {
        MockHttpServletRequest r = viaGateway();
        r.addHeader("CF-Connecting-IP", "2001:db8::1");
        assertEquals("2001:db8::1", ClientIpResolver.resolve(r));
    }

    @Test
    void ignoresGarbageAndOverlongValues() {
        MockHttpServletRequest r = viaGateway();
        r.addHeader("CF-Connecting-IP", "evil:key*with spaces");
        r.addHeader("X-Forwarded-For", "1".repeat(46));
        r.addHeader("X-Real-IP", "");
        assertEquals("172.18.0.5", ClientIpResolver.resolve(r));
    }

    @Test
    void unknownWhenNothingUsable() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("not-an-ip");
        assertEquals("unknown", ClientIpResolver.resolve(r));
    }
}
