package com.jimeng.dataserver.admin.auth.controller;

import com.jimeng.dataserver.admin.auth.dto.LoginRequest;
import com.jimeng.dataserver.admin.auth.dto.LoginResponse;
import com.jimeng.dataserver.admin.auth.service.AdminAuthService;
import com.jimeng.dataserver.admin.common.LoginAttemptGuard;
import com.jimeng.dataserver.admin.operator.auth.controller.OperatorAuthController;
import com.jimeng.dataserver.admin.operator.auth.service.OperatorAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 两个登录入口都必须经过 LoginAttemptGuard，且带上真实的客户端 IP 和各自的 realm。 */
class LoginControllersGuardTest {

    private static LoginRequest alice() {
        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("secret");
        return req;
    }

    private static MockHttpServletRequest fromCloudflare() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr("172.18.0.5");
        http.addHeader("CF-Connecting-IP", "203.0.113.9");
        return http;
    }

    /** 只有 realm、用户名、IP 都对上时才执行真正的登录；任何一项不对，mock 返回 null，断言失败。 */
    @SuppressWarnings("unchecked")
    private static LoginAttemptGuard passThrough(LoginAttemptGuard.Realm realm) {
        LoginAttemptGuard guard = mock(LoginAttemptGuard.class);
        when(guard.guard(eq(realm), eq("alice"), eq("203.0.113.9"), any(Supplier.class)))
                .thenAnswer(inv -> inv.<Supplier<Object>>getArgument(3).get());
        return guard;
    }

    @Test
    void enterpriseLoginGoesThroughGuard() {
        AdminAuthService service = mock(AdminAuthService.class);
        LoginRequest req = alice();
        LoginResponse expected = new LoginResponse();
        when(service.login(req)).thenReturn(expected);
        AdminAuthController controller =
                new AdminAuthController(service, passThrough(LoginAttemptGuard.Realm.ENTERPRISE));
        assertSame(expected, controller.login(req, fromCloudflare()));
    }

    @Test
    void operatorLoginGoesThroughGuard() {
        OperatorAuthService service = mock(OperatorAuthService.class);
        LoginRequest req = alice();
        LoginResponse expected = new LoginResponse();
        when(service.login(req)).thenReturn(expected);
        OperatorAuthController controller =
                new OperatorAuthController(service, passThrough(LoginAttemptGuard.Realm.OPERATOR));
        assertSame(expected, controller.login(req, fromCloudflare()));
    }
}
