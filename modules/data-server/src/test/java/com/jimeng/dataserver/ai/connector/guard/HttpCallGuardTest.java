package com.jimeng.dataserver.ai.connector.guard;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.web.WebSsrfGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP 调用白名单。
 *
 * <p>这一层在平台侧是<b>全新的</b>——{@code allow_methods} / {@code allow_paths} 原先只有
 * 沙箱的 egress 代理在执行，data-server 进程内零校验。把执行收回平台侧之后，
 * 不补这一层它就凭空消失了。所以这里测的每一条，都是「原来有、差点丢掉」的保护。
 *
 * <p>SSRF 闸单独测过（{@code WebSsrfGuardTest}），这里 mock 掉它，只验编排。
 */
class HttpCallGuardTest {

    private WebSsrfGuard ssrf;
    private HttpCallGuard guard;

    @BeforeEach
    void setUp() {
        ssrf = mock(WebSsrfGuard.class);
        when(ssrf.validate(anyString())).thenReturn(null);   // 默认放行
        guard = new HttpCallGuard(ssrf);
    }

    private ConnectorException reject(String method, String path, String methods, String paths) {
        return assertThrows(ConnectorException.class,
                () -> guard.check("https://api.example.com", method, path, methods, paths));
    }

    @Nested
    @DisplayName("方法白名单")
    class Methods {

        @Test
        void 默认只读_POST被拒() {
            assertEquals(ConnectorErrorCode.FORBIDDEN, reject("POST", "/v1/x", null, null).getCode());
        }

        @Test
        void 默认只读_GET放行() {
            assertTrue(guard.check("https://api.example.com", "GET", "/v1/x", null, null)
                    .startsWith("https://api.example.com/v1/x"));
        }

        @Test
        void 显式声明后写方法放行() {
            assertTrue(guard.check("https://api.example.com", "POST", "/v1/x", "GET,POST", null)
                    .contains("/v1/x"));
        }

        @Test
        void 大小写不敏感() {
            assertTrue(guard.check("https://api.example.com", "get", "/v1/x", "GET", null).contains("/v1/x"));
        }

        @Test
        void 方法为空被拒() {
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, reject("", "/v1/x", "GET", null).getCode());
        }
    }

    @Nested
    @DisplayName("路径 glob 白名单")
    class Paths {

        @Test
        void 命中放行() {
            assertTrue(guard.check("https://api.example.com", "GET", "/v1/orders/9",
                    "GET", "[\"/v1/orders/**\"]").contains("/v1/orders/9"));
        }

        @Test
        void 不命中被拒() {
            assertEquals(ConnectorErrorCode.FORBIDDEN,
                    reject("GET", "/v2/secrets", "GET", "[\"/v1/orders/**\"]").getCode());
        }

        @Test
        void 单星不跨段() {
            // /v1/* 只能匹配一段——否则「只许调订单列表」会悄悄变成「订单下面什么都能调」。
            assertEquals(ConnectorErrorCode.FORBIDDEN,
                    reject("GET", "/v1/a/b", "GET", "[\"/v1/*\"]").getCode());
        }

        @Test
        void 留空等价于全放行() {
            assertTrue(guard.check("https://api.example.com", "GET", "/anything", "GET", null)
                    .contains("/anything"));
        }

        /**
         * ★ 本类最重要的一条：解析失败必须<b>拒绝</b>，不能像 ConnectionResolver 那样
         * 往「放宽」方向回落。一个手抖写坏的 JSON 把「只许调 /v1/orders」静默变成
         * 「什么都能调」，护栏就等于不存在。
         */
        @Test
        void 非法JSON必须拒绝而不是放宽() {
            ConnectorException e = reject("GET", "/anything", "GET", "{坏掉的 json");
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
        }

        @Test
        void 空数组也拒绝_语义不明确() {
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, reject("GET", "/x", "GET", "[]").getCode());
        }
    }

    @Nested
    @DisplayName("路径穿越与主机劫持")
    class Traversal {

        @ParameterizedTest
        @ValueSource(strings = {
                "/v1/../../etc/passwd",
                "/v1/%2e%2e/admin",
                "/v1/%2E%2E/admin",
                "/v1//../admin"
        })
        void 穿越被拒(String path) {
            assertEquals(ConnectorErrorCode.FORBIDDEN, reject("GET", path, "GET", null).getCode());
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "http://evil.com/x",
                "https://evil.com/x",
                "//evil.com/x"
        })
        void 绝对URL被拒_目标主机只能由连接配置决定(String path) {
            assertEquals(ConnectorErrorCode.FORBIDDEN, reject("GET", path, "GET", null).getCode());
        }

        @Test
        void 不以斜杠开头被拒() {
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, reject("GET", "v1/x", "GET", null).getCode());
        }
    }

    @Nested
    @DisplayName("SSRF 与 URL 拼装")
    class UrlAssembly {

        @Test
        void SSRF被拦时报错文案不回显解析出的地址() {
            when(ssrf.validate(anyString())).thenReturn("blocked address 169.254.169.254");
            ConnectorException e = reject("GET", "/x", "GET", null);
            assertEquals(ConnectorErrorCode.CONFIG_ERROR, e.getCode());
            // 解析出来的内网 IP 只准进日志——safeDetail 会进模型上下文与审计表。
            assertTrue(e.getSafeDetail() == null || !e.getSafeDetail().contains("169.254"));
        }

        @Test
        void baseUrl的路径前缀必须保留() {
            // 剥掉前缀会把路径悄悄改错而且不报错，沙箱 egress 那边踩过同一个坑。
            String url = guard.check("https://api.example.com/v1", "GET", "/orders", "GET", null);
            assertEquals("https://api.example.com/v1/orders", url);
        }

        @Test
        void query参数原样保留() {
            String url = guard.check("https://api.example.com", "GET", "/orders?status=paid", "GET", null);
            assertTrue(url.endsWith("/orders?status=paid"));
        }

        @Test
        void query里的编码斜杠不算穿越() {
            // ?url=https%3A%2F%2Fx 是合法调用，把 query 一起判会误杀。
            String url = guard.check("https://api.example.com", "GET", "/cb?u=https%3A%2F%2Fx", "GET", null);
            assertTrue(url.contains("%2F"));
        }
    }
}
