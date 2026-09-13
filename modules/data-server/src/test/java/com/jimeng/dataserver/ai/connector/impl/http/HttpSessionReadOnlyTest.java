package com.jimeng.dataserver.ai.connector.impl.http;

import com.jimeng.dataserver.ai.connector.guard.HttpCallGuard;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.WritePolicy;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 连接器的<b>只读判定</b>。
 *
 * <h3>为什么这块必须单独有测试</h3>
 * 技术架构 §18 要求从差别最大的两种类型（MySQL + HTTP）同时起步，理由是防止抽象
 * 长成 MySQL 的形状。而这两种类型<b>最不一样的地方恰恰就是只读怎么验</b>：
 *
 * <ul>
 *   <li>MySQL：真发一条 UPDATE，让<b>客户的数据库</b>拒绝我们。承重的是客户侧的授权，
 *       我们改不了——这是整套设计里唯一不可绕过的一层。</li>
 *   <li>HTTP：<b>没有等价物</b>。对方接口没有「只读账号」这个概念，我们也不可能真发一个
 *       POST 去试（那可能真的建一张单）。所以承重的只能是<b>我们自己的方法白名单</b>，
 *       结论强度比数据库那边弱一档。</li>
 * </ul>
 *
 * <p>换句话说，HTTP 这边「已验证只读」这个结论<b>完全由这段代码产生</b>，没有外部力量兜底。
 * 它算错的后果是：一条能写的连接被标成只读，界面显示绿色「已验证」，
 * 而实际上 Agent 可以拿它去 POST。所以它比 MySQL 那边更需要回归保护——
 * 而在补这个文件之前，{@code impl/http} 下<b>一个测试都没有</b>（MySQL 那边有两个）。
 */
class HttpSessionReadOnlyTest {

    private HttpSession sessionWith(List<String> allowMethods) {
        ConnectorInstance inst = new ConnectorInstance(
                1L, "t1", "HTTP", "api", "API",
                Map.of("baseUrl", "https://example.com", "allowMethods", allowMethods),
                "cred", "direct", WritePolicy.FORBIDDEN);
        return new HttpSession(inst, new OkHttpClient(), new HttpCallGuard(new com.jimeng.dataserver.ai.web.WebSsrfGuard()),
                "https://example.com", "cred", "bearer", 10, 1024);
    }

    @Test
    @DisplayName("白名单只含 GET → 判定只读")
    void 只含GET时只读() {
        ReadOnlyVerdict v = sessionWith(List.of("GET")).verifyReadOnly();
        assertTrue(v.readOnly());
        assertFalse(v.undetermined());
        assertTrue(v.detail().contains("GET"), "结论要说清依据是什么：" + v.detail());
    }

    @Test
    @DisplayName("HEAD / OPTIONS 也算只读方法")
    void 幂等方法都算只读() {
        assertTrue(sessionWith(List.of("GET", "HEAD", "OPTIONS")).verifyReadOnly().readOnly());
    }

    /**
     * ★ 这条是本文件的重点。白名单里只要混进一个写方法，就<b>不能</b>再报只读——
     * 哪怕其余全是 GET。报错了的后果不是报错，是界面上一个绿色的「已验证只读」，
     * 而 Agent 可以拿它去 POST。
     */
    @Test
    @DisplayName("★ 混入任一写方法 → 必须判为可写，且点名是哪个方法")
    void 混入写方法时必须判可写() {
        for (String w : List.of("POST", "PUT", "PATCH", "DELETE")) {
            ReadOnlyVerdict v = sessionWith(List.of("GET", w)).verifyReadOnly();
            assertFalse(v.readOnly(), w + " 混在白名单里却被判成了只读");
            assertTrue(v.detail().contains(w), "要点名是哪个方法让它不只读：" + v.detail());
        }
    }

    @Test
    @DisplayName("isIdempotent 与只读判定用同一套方法集，不能各判各的")
    void 幂等判定与只读判定一致() {
        HttpSession s = sessionWith(List.of("GET", "POST"));
        assertTrue(s.isIdempotent("GET /x"));
        assertFalse(s.isIdempotent("POST /x"));
        // 两处若分叉，会出现「判定只读、但某个写方法被当成幂等重试」这种最难查的组合。
        assertFalse(s.verifyReadOnly().readOnly());
    }

    @Test
    @DisplayName("探测出的能力不含 DESCRIBE —— 接口自描述要等 OpenAPI 导入那一期")
    void 不谎称支持自描述() {
        var caps = sessionWith(List.of("GET")).probeCapabilities();
        assertEquals(java.util.Set.of(Capability.INVOKE, Capability.HEALTH), caps,
                "声明了探测不出东西的能力，界面上就会多一个「支持但用不了」的选项");
    }
}
