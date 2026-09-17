package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.common.core.service.RequestService;
import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.agent.exec.service.SidecarClient;
import com.jimeng.dataserver.ai.connector.generation.precondition.AgentConfigPrecondition;
import com.jimeng.dataserver.ai.connector.generation.precondition.AgentSwitchPrecondition;
import com.jimeng.dataserver.ai.connector.generation.precondition.DescribeCapabilityPrecondition;
import com.jimeng.dataserver.ai.connector.generation.precondition.SandboxConfiguredPrecondition;
import com.jimeng.dataserver.ai.connector.generation.precondition.SandboxHealthPrecondition;
import com.jimeng.dataserver.ai.connector.generation.precondition.SemanticEnabledPrecondition;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.Order;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 语义层 agent 路径的六条廉价准入条件。 */
@DisplayName("SemanticGeneratorSelector 与六条前置条件")
class SemanticGeneratorSelectorTest {

    private static final RequestService.HttpResp HEALTHY = new RequestService.HttpResp(200, "ok");
    private static final RequestService.HttpResp SEMANTIC_CAPABILITIES = new RequestService.HttpResp(
            200, "{\"runProfiles\":[\"default\",\"semantic-layer\"],\"semanticToolsVersion\":1}");

    private ConnectorProperties properties;
    private AgentSandboxProperties sandbox;
    private SidecarClient sidecar;
    private AtomicLong nowMs;
    private SandboxHealthProbe healthProbe;
    private ConnectorView connector;

    @BeforeEach
    void setUp() {
        properties = new ConnectorProperties();
        ConnectorProperties.SemanticAgent agent = properties.getSemantic().getAgent();
        agent.setEnabled(true);
        agent.setCallbackBaseUrl("http://localhost:10011/data");
        agent.getLlm().setBaseUrl("https://api.deepseek.com/anthropic");
        agent.getLlm().setAuthToken("sk-test");
        agent.getLlm().setModel("deepseek-flash");
        agent.getLlm().setAuthScheme("api-key");

        sandbox = new AgentSandboxProperties();
        sandbox.setBaseUrl("http://localhost:8088");
        sandbox.setServiceToken("sandbox-token");

        sidecar = mock(SidecarClient.class);
        when(sidecar.healthz(any(Duration.class))).thenReturn(HEALTHY);
        when(sidecar.capabilities(any(Duration.class))).thenReturn(SEMANTIC_CAPABILITIES);
        nowMs = new AtomicLong();
        healthProbe = new SandboxHealthProbe(sidecar, properties, nowMs::get);

        connector = ConnectorView.builder().id("7").capabilities(List.of("QUERY", "DESCRIBE")).build();
    }

    private List<AgentPathPrecondition> preconditions() {
        return List.of(
                new SemanticEnabledPrecondition(properties),
                new DescribeCapabilityPrecondition(),
                new AgentSwitchPrecondition(properties),
                new SandboxConfiguredPrecondition(sandbox),
                new AgentConfigPrecondition(properties),
                new SandboxHealthPrecondition(healthProbe));
    }

    private SemanticGeneratorSelector selector() {
        // 反转后传入，确保选择器真按 @Order，而不是恰好吃到测试列表的手工顺序。
        List<AgentPathPrecondition> reversed = new ArrayList<>(preconditions());
        Collections.reverse(reversed);
        return new SemanticGeneratorSelector(reversed);
    }

    private static void assertSingleCall(SemanticGeneratorSelector.Selection selection,
                                         boolean silent,
                                         String reason,
                                         InterruptedBatchPolicy policy) {
        assertEquals(GeneratorKind.SINGLE_CALL, selection.kind());
        assertFalse(selection.verdict().pass());
        assertEquals(silent, selection.verdict().silent());
        assertEquals(reason, selection.verdict().reason());
        assertEquals(policy, selection.interruptedBatchPolicy());
        assertEquals(silent ? null : reason, selection.degradeReason());
    }

    @Test
    void 六条前置条件按表中顺序声明Order且全通过才选AGENT() {
        assertEquals(List.of(1, 2, 3, 4, 5, 6), preconditions().stream()
                .map(p -> p.getClass().getAnnotation(Order.class).value())
                .toList());

        SemanticGeneratorSelector.Selection selection = selector().select(connector);

        assertEquals(GeneratorKind.AGENT, selection.kind());
        assertTrue(selection.verdict().pass());
        assertNull(selection.interruptedBatchPolicy());
        assertNull(selection.degradeReason());
    }

    @Test
    void null前置链在构造时failFast() {
        assertThrows(IllegalArgumentException.class, () -> new SemanticGeneratorSelector(null));
    }

    @Test
    void 空前置链在构造时failFast() {
        assertThrows(IllegalStateException.class, () -> new SemanticGeneratorSelector(List.of()));
    }

    @Test
    void 含null前置条件在构造时failFast() {
        List<AgentPathPrecondition> withNull = new ArrayList<>(preconditions());
        withNull.set(2, null);

        assertThrows(IllegalArgumentException.class, () -> new SemanticGeneratorSelector(withNull));
    }

    @Test
    void 缺少任一预期前置条件在构造时failFast() {
        List<AgentPathPrecondition> missingHealth = new ArrayList<>(preconditions());
        missingHealth.remove(missingHealth.size() - 1);

        assertThrows(IllegalStateException.class, () -> new SemanticGeneratorSelector(missingHealth));
    }

    @Test
    void 同类型重复不能替代缺失的预期前置条件() {
        List<AgentPathPrecondition> duplicateInsteadOfDescribe = new ArrayList<>(preconditions());
        duplicateInsteadOfDescribe.set(1, duplicateInsteadOfDescribe.get(0));

        assertThrows(IllegalStateException.class,
                () -> new SemanticGeneratorSelector(duplicateInsteadOfDescribe));
    }

    @Test
    void 预期六条之外的额外条件在构造时failFast() {
        List<AgentPathPrecondition> withUnexpected = new ArrayList<>(preconditions());
        withUnexpected.add(new AgentPathPrecondition() {
            @Override
            public PreconditionVerdict check(ConnectorView view) {
                return PreconditionVerdict.allowed();
            }

            @Override
            public InterruptedBatchPolicy onInterruptedBatch() {
                return InterruptedBatchPolicy.KEEP;
            }
        });

        assertThrows(IllegalStateException.class, () -> new SemanticGeneratorSelector(withUnexpected));
    }

    @Test
    void 轻量Spring上下文必须扫描到六个具体组件后选择器才能启动() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ConnectorProperties.class, () -> properties);
            context.registerBean(AgentSandboxProperties.class, () -> sandbox);
            context.registerBean(SandboxHealthProbe.class, () -> healthProbe);
            context.scan("com.jimeng.dataserver.ai.connector.generation.precondition");
            context.register(SemanticGeneratorSelector.class);

            context.refresh();

            Set<Class<?>> actualTypes = context.getBeansOfType(AgentPathPrecondition.class).values().stream()
                    .map(Object::getClass)
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of(
                    SemanticEnabledPrecondition.class,
                    DescribeCapabilityPrecondition.class,
                    AgentSwitchPrecondition.class,
                    SandboxConfiguredPrecondition.class,
                    AgentConfigPrecondition.class,
                    SandboxHealthPrecondition.class), actualTypes);
            assertNotNull(context.getBean(SemanticGeneratorSelector.class));
        }
    }

    @Test
    void 总开关关闭silent走单次推导且优先于其余失败() {
        properties.getSemantic().setEnabled(false);
        connector.setCapabilities(List.of());
        properties.getSemantic().getAgent().setEnabled(false);

        assertSingleCall(selector().select(connector), true, null, InterruptedBatchPolicy.LEAVE);
        verifyNoInteractions(sidecar);
    }

    @Test
    void 无DESCRIBE能力silent走单次推导() {
        connector.setCapabilities(List.of("QUERY"));

        assertSingleCall(selector().select(connector), true, null, InterruptedBatchPolicy.SUPERSEDE);
        verifyNoInteractions(sidecar);
    }

    @Test
    void agent开关关闭写稳定原因() {
        properties.getSemantic().getAgent().setEnabled(false);

        assertSingleCall(selector().select(connector), false,
                "agent 生成未开启，走单次推导", InterruptedBatchPolicy.SUPERSEDE);
        verifyNoInteractions(sidecar);
    }

    @Test
    void sandbox地址或token未配置写稳定原因() {
        sandbox.setServiceToken("  ");

        assertSingleCall(selector().select(connector), false,
                "sandbox 未配置", InterruptedBatchPolicy.SUPERSEDE);
        verifyNoInteractions(sidecar);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-opus-4-7", "Claude-OpUs-4-7", "anthropic/CLAUDE-3", "opus", "SONNET", "HaIkU"})
    void 模型名含Claude或命令行别名都视为未配置_不区分大小写(String model) {
        properties.getSemantic().getAgent().getLlm().setModel(model);

        assertSingleCall(selector().select(connector), false,
                "agent 专用模型或回调地址未配置", InterruptedBatchPolicy.SUPERSEDE);
        verifyNoInteractions(sidecar);
    }

    @Test
    void healthz正文非ok视为不可用且不回显不可信正文() {
        when(sidecar.healthz(any(Duration.class))).thenReturn(new RequestService.HttpResp(200, "almost ok"));

        assertSingleCall(selector().select(connector), false,
                "sandbox 健康检查失败：healthz 正文不是 ok", InterruptedBatchPolicy.KEEP);
        verify(sidecar, never()).capabilities(any(Duration.class));
    }

    @Test
    void healthz显示未启用鉴权时给稳定中文原因() {
        when(sidecar.healthz(any(Duration.class)))
                .thenReturn(new RequestService.HttpResp(200, "ok (AUTH DISABLED: configure SANDBOX_SERVICE_TOKEN)"));

        assertSingleCall(selector().select(connector), false,
                "sandbox 未启用入站鉴权", InterruptedBatchPolicy.KEEP);
    }

    @Test
    void capabilities返回404写版本不支持() {
        when(sidecar.capabilities(any(Duration.class))).thenReturn(new RequestService.HttpResp(404, "not found"));

        assertSingleCall(selector().select(connector), false,
                "sandbox 版本不支持语义层", InterruptedBatchPolicy.KEEP);
    }

    @Test
    void capabilities返回401写鉴权失败() {
        when(sidecar.capabilities(any(Duration.class))).thenReturn(new RequestService.HttpResp(401, "unauthorized"));

        assertSingleCall(selector().select(connector), false,
                "sandbox 鉴权失败", InterruptedBatchPolicy.KEEP);
    }

    @Test
    void capabilities正文异常时给稳定中文摘要() {
        when(sidecar.capabilities(any(Duration.class))).thenReturn(new RequestService.HttpResp(200, "{坏掉的"));

        assertSingleCall(selector().select(connector), false,
                "sandbox 健康检查失败：capabilities 正文异常", InterruptedBatchPolicy.KEEP);
    }

    @Test
    void 探测结果缓存15秒_到期前不再发请求_到期后重新探测() {
        SemanticGeneratorSelector selector = selector();

        assertEquals(GeneratorKind.AGENT, selector.select(connector).kind());
        nowMs.set(14_999L);
        assertEquals(GeneratorKind.AGENT, selector.select(connector).kind());
        verify(sidecar, times(1)).healthz(any(Duration.class));
        verify(sidecar, times(1)).capabilities(any(Duration.class));

        nowMs.set(15_000L);
        assertEquals(GeneratorKind.AGENT, selector.select(connector).kind());
        verify(sidecar, times(2)).healthz(any(Duration.class));
        verify(sidecar, times(2)).capabilities(any(Duration.class));
    }

    @Test
    void 慢探测完成后才开始15秒缓存且锁等待者不会拿旧时间重复探测() throws Exception {
        SidecarClient slowSidecar = mock(SidecarClient.class);
        AtomicLong controlledNow = new AtomicLong();
        AtomicInteger healthCalls = new AtomicInteger();
        CountDownLatch firstProbeStarted = new CountDownLatch(1);
        CountDownLatch waiterReadOldTime = new CountDownLatch(1);
        CountDownLatch releaseFirstProbe = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (healthCalls.incrementAndGet() == 1) {
                firstProbeStarted.countDown();
                awaitQuietly(releaseFirstProbe);
            }
            return HEALTHY;
        }).when(slowSidecar).healthz(any(Duration.class));
        when(slowSidecar.capabilities(any(Duration.class))).thenReturn(SEMANTIC_CAPABILITIES);
        LongSupplier clock = () -> {
            long sampled = controlledNow.get();
            if ("health-probe-waiter".equals(Thread.currentThread().getName())) {
                waiterReadOldTime.countDown();
            }
            return sampled;
        };
        SandboxHealthProbe slowProbe = new SandboxHealthProbe(slowSidecar, properties, clock);
        AtomicReference<SandboxHealthProbe.ProbeResult> firstResult = new AtomicReference<>();
        AtomicReference<SandboxHealthProbe.ProbeResult> waiterResult = new AtomicReference<>();
        AtomicReference<Throwable> backgroundFailure = new AtomicReference<>();
        Thread first = new Thread(
                () -> runProbe(slowProbe, firstResult, backgroundFailure), "health-probe-first");
        Thread waiter = new Thread(
                () -> runProbe(slowProbe, waiterResult, backgroundFailure), "health-probe-waiter");

        first.start();
        try {
            assertTrue(firstProbeStarted.await(5, TimeUnit.SECONDS));
            controlledNow.set(5_000L);
            waiter.start();
            assertTrue(waiterReadOldTime.await(5, TimeUnit.SECONDS));
            controlledNow.set(20_000L);
        } finally {
            releaseFirstProbe.countDown();
            first.join(TimeUnit.SECONDS.toMillis(5));
            waiter.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertFalse(first.isAlive());
        assertFalse(waiter.isAlive());
        assertNull(backgroundFailure.get(), () -> "后台探测异常：" + backgroundFailure.get());
        assertTrue(firstResult.get().healthy());
        assertTrue(waiterResult.get().healthy());
        assertEquals(1, healthCalls.get(), "锁等待者应复用刚完成的探测");

        controlledNow.set(34_999L);
        assertTrue(slowProbe.probe().healthy());
        assertEquals(1, healthCalls.get(), "完整 15 秒 TTL 应从慢探测完成时开始计算");

        controlledNow.set(35_000L);
        assertTrue(slowProbe.probe().healthy());
        assertEquals(2, healthCalls.get(), "完成后满 15 秒才重新探测");
    }

    @Test
    void 每个探测请求使用当前healthTimeoutMs() {
        properties.getSemantic().getAgent().setHealthTimeoutMs(1500);

        assertEquals(GeneratorKind.AGENT, selector().select(connector).kind());

        verify(sidecar).healthz(Duration.ofMillis(1500));
        verify(sidecar).capabilities(Duration.ofMillis(1500));
    }

    @Test
    void 每条前置条件声明的中断批次策略与规格表一致() {
        List<InterruptedBatchPolicy> policies = preconditions().stream()
                .map(AgentPathPrecondition::onInterruptedBatch)
                .toList();

        assertEquals(List.of(
                InterruptedBatchPolicy.LEAVE,
                InterruptedBatchPolicy.SUPERSEDE,
                InterruptedBatchPolicy.SUPERSEDE,
                InterruptedBatchPolicy.SUPERSEDE,
                InterruptedBatchPolicy.SUPERSEDE,
                InterruptedBatchPolicy.KEEP), policies);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("等待慢探测放行超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待慢探测时被中断", e);
        }
    }

    private static void runProbe(SandboxHealthProbe probe,
                                 AtomicReference<SandboxHealthProbe.ProbeResult> result,
                                 AtomicReference<Throwable> failure) {
        try {
            result.set(probe.probe());
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }
}
