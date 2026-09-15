package com.jimeng.dataserver.ai.connector.runtime;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorHealthJob.MarkState;
import com.jimeng.dataserver.ai.connector.service.ConnectorSchemaService;
import com.jimeng.dataserver.ai.connector.service.ConnectorSemanticDeriveService;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.stubbing.Answer;
import org.redisson.api.RBucket;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 结构快照的低频刷新——{@link ConnectorHealthJob} 的第二件活。
 *
 * <p>这里每一条规则坏掉时都<b>不报错</b>，所以一条一条钉：
 * <ul>
 *   <li>刷成了重推导 → 平台花客户额度换来的验证结论和人确认过的口径被整批冲掉，日志里只有一行「完成」。</li>
 *   <li>租户没设或没清 → 网关以「缺少租户上下文」拒掉每一条，或者下一条连接带着上一个租户的身份去刷。</li>
 *   <li>不按最旧优先 / 失败、拒绝落库不让位 → 几条坏的连接永远排第一，健康的连接一条都轮不到，
 *       而每轮日志看起来都在「干活」。</li>
 *   <li>繁忙占名额 → 9 条以上持续繁忙的连接在每一次检查里轮流把名额占光，同上，只是日志是 INFO。</li>
 *   <li>繁忙被当成失败 / HTTP 被拿去刷 → 每轮一条 ERROR，很快没人再看日志。</li>
 *   <li>拿本轮开始时的旧行判跳过 → 前一条刷新期间才开始的推导被一次定时刷新作废，推导写 FAILED。</li>
 *   <li>被中断的线程上直接放锁，或卡在客户库上的线程根本走不到放锁 → 新进程半小时刷不了，日志里只有 debug。</li>
 *   <li>检查间隔写成 {@code PT10M} → {@code @Scheduled} 接受，本 bean 却建不出来，data-server 起不来。</li>
 * </ul>
 *
 * <p>不连库。最后两组分别起一个最小的 Spring 容器、用<b>真实的网关 + 真实的 refresh()</b>，钉住跨类的隐性契约。
 */
class ConnectorHealthJobSchemaRefreshTest {

    private static final long HOUR = 3_600_000L;

    private ConnectionMapper connectionMapper;
    private ConnectorSchemaMapper schemaMapper;
    private ConnectorRegistry registry;
    private ConnectorInstanceLoader loader;
    private ConnectorProperties properties;
    private RedissonClient redissonClient;
    private RLock lock;
    private Map<String, RBucket<Object>> buckets;
    /** set 过、还「活着」的标记：key → 值。让 mock 的 Redis 有状态，见 {@link #newBucket}。 */
    private Map<String, Object> liveMarks;
    private Connector mysql;
    private ConnectorSchemaService schemaService;
    private ConnectorHealthJob job;

    /** {@code connector_schema} 分组查询的返回，按数据库会给的顺序（最旧在前）排好。 */
    private final List<ConnectorSchema> ages = new ArrayList<>();
    /** {@code connection} 查询的返回。刻意不按快照年龄排，用来钉「顺序以快照为准」。 */
    private final List<Connection> connections = new ArrayList<>();

    /** 纯单测里没有 Spring 扫 mapper，LambdaQueryWrapper 的列缓存得手工建（同 ConnectorSemanticServiceTest）。 */
    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Connection.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        connectionMapper = mock(ConnectionMapper.class);
        schemaMapper = mock(ConnectorSchemaMapper.class);
        registry = mock(ConnectorRegistry.class);
        loader = mock(ConnectorInstanceLoader.class);
        properties = new ConnectorProperties();
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        schemaService = mock(ConnectorSchemaService.class);

        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        buckets = new ConcurrentHashMap<>();
        liveMarks = new ConcurrentHashMap<>();
        when(redissonClient.getBucket(anyString())).thenAnswer(inv ->
                buckets.computeIfAbsent(inv.getArgument(0), this::newBucket));

        mysql = mock(Connector.class);
        when(mysql.declaredCapabilities()).thenReturn(Set.of(Capability.QUERY, Capability.DESCRIBE, Capability.HEALTH));
        when(registry.require("MYSQL")).thenReturn(mysql);
        Connector http = mock(Connector.class);
        // 与 HttpConnector 的真实声明一致：没有 DESCRIBE。
        when(http.declaredCapabilities()).thenReturn(Set.of(Capability.INVOKE, Capability.HEALTH));
        when(registry.require("HTTP")).thenReturn(http);

        when(schemaMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(ages));
        when(connectionMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(connections));

        job = newJob(schemaService);
    }

    @AfterEach
    void tearDown() {
        job.releaseOnShutdown();
        TenantContext.clear();
    }

    private ConnectorHealthJob newJob(ConnectorSchemaService service) {
        return new ConnectorHealthJob(connectionMapper, registry, loader, properties, redissonClient,
                schemaMapper, service);
    }

    private void givenSnapshot(long connectorId, long ageHours) {
        ConnectorSchema s = new ConnectorSchema();
        s.setConnectorId(connectorId);
        s.setSyncedAt(new Date(System.currentTimeMillis() - ageHours * HOUR));
        ages.add(s);
    }

    private Connection givenMysql(long id, String tenant) {
        Connection c = new Connection();
        c.setId(id);
        c.setTenantId(tenant);
        c.setName("conn" + id);
        c.setKind("MYSQL");
        c.setStatus("ACTIVE");
        c.setTransport("direct");
        c.setCapabilityFlags("QUERY,DESCRIBE,HEALTH");
        c.setHealthState("HEALTHY");
        connections.add(c);
        return c;
    }

    private static String key(long connectorId) {
        return ConnectorHealthJob.REFRESH_COOLDOWN_KEY_PREFIX + connectorId;
    }

    private RBucket<Object> bucketFor(long connectorId) {
        return buckets.computeIfAbsent(key(connectorId), this::newBucket);
    }

    /**
     * 有状态的 bucket：{@code set} 过的值之后 {@code get()} 读得到、{@code delete()} 删得掉。
     * 连着跑几次检查的用例靠它看见上一次记下的标记。
     *
     * <p>用默认 Answer 按方法名分派，而不是 {@code when(...)}：它是在被测代码调 {@code getBucket} 的<b>过程中</b>
     * 被创建的，在另一次 mock 调用进行中再做 stubbing 容易把 Mockito 的进行中状态搅乱。
     * 用例里仍可以对它 {@code when(...)} 覆盖（冷却中 / Redis 读失败）。
     */
    @SuppressWarnings("unchecked")
    private RBucket<Object> newBucket(String key) {
        return mock(RBucket.class, (Answer<Object>) inv -> switch (inv.getMethod().getName()) {
            case "set" -> {
                liveMarks.put(key, inv.getArgument(0));
                yield null;
            }
            case "get" -> liveMarks.get(key);
            case "isExists" -> liveMarks.containsKey(key);
            case "delete" -> liveMarks.remove(key) != null;
            default -> Answers.RETURNS_DEFAULTS.answer(inv);
        });
    }

    /** 把所有 BUSY 标记的时间戳拨回 {@code minutes} 分钟之前：模拟时间过去了、TTL 还没到。 */
    private void ageBusyMarks(long minutes) {
        String past = ConnectorHealthJob.OUTCOME_BUSY + "@" + (System.currentTimeMillis() - minutes * 60_000L);
        liveMarks.replaceAll((k, v) -> String.valueOf(v).startsWith(ConnectorHealthJob.OUTCOME_BUSY + "@") ? past : v);
    }

    /** 模拟 Redisson 3.13.4：线程带着中断标记时，等响应那一步立刻被打断，抛 RedisException。 */
    private static void failIfInterrupted(String op) {
        if (Thread.currentThread().isInterrupted()) {
            throw new RedisException(op + " interrupted");
        }
    }

    @SuppressWarnings("unchecked")
    private static RFuture<Void> finishedFuture(boolean success, Throwable cause) {
        RFuture<Void> f = mock(RFuture.class);
        when(f.awaitUninterruptibly(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(f.isSuccess()).thenReturn(success);
        when(f.cause()).thenReturn(cause);
        return f;
    }

    /** 数据库里「此刻」的连接行，每次查询都给<b>副本</b>——被测代码拿着的旧对象不会跟着变。 */
    private void serveCopiesFrom(Map<Long, Connection> db, List<Boolean> systemModeSeen) {
        when(connectionMapper.selectList(any())).thenAnswer(inv -> {
            systemModeSeen.add(TenantContext.isSystemMode());
            List<Connection> out = new ArrayList<>();
            for (Connection c : db.values()) {
                Connection copy = new Connection();
                BeanUtils.copyProperties(c, copy);
                out.add(copy);
            }
            return out;
        });
    }

    /** 反例用：与 ConnectorHealthJob 同一个占位符，但按 long 绑定——本轮修掉的正是这种写法。 */
    static class LongBoundCheckInterval {
        @Value("${connector.schema-refresh.check-interval-ms:600000}")
        long checkIntervalMs;

        @Scheduled(fixedDelayString = "${connector.schema-refresh.check-interval-ms:600000}", initialDelay = 600_000L)
        public void tick() {
        }
    }

    // ================================================================ 只刷新，最旧优先，有上限

    @Nested
    @DisplayName("只刷新、最旧优先、每轮有上限")
    class OrderAndCap {

        /**
         * ★ 本类最重要的一条：定时任务对语义层能做的<b>唯一</b>一件事是调 refresh()。
         * 顺序以快照年龄为准（连接表是按 id 返回的，刻意与之相反），尝试数到上限就停。
         */
        @Test
        @DisplayName("★ 只调 refresh()，按快照从旧到新，尝试数到上限就停")
        void 只调refresh_最旧优先_到上限就停() {
            properties.getSchemaRefresh().setMaxPerSweep(2);
            givenMysql(1L, "t1");
            givenMysql(2L, "t1");
            givenMysql(3L, "t1");
            givenMysql(4L, "t1");
            givenSnapshot(3L, 30);
            givenSnapshot(1L, 20);
            givenSnapshot(2L, 10);
            givenSnapshot(4L, 7);

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            InOrder order = inOrder(schemaService);
            order.verify(schemaService).refresh(3L);
            order.verify(schemaService).refresh(1L);
            verifyNoMoreInteractions(schemaService);
            assertEquals(4, t.due);
            assertEquals(2, t.attempted);
            assertEquals(2, t.refreshed);
        }

        /**
         * 「最旧优先」和「只挑到期的」是写在 SQL 里的——mock 的 mapper 不会替我们排序，
         * 所以直接看生成的语句片段，免得有人把 ORDER BY 或 HAVING 顺手删了而单测照绿。
         */
        @Test
        @DisplayName("选人的 SQL：按 connector_id 分组、只要过期的、最旧在前")
        @SuppressWarnings("unchecked")
        void 选人SQL的形状() {
            long before = System.currentTimeMillis();
            job.refreshDueSchemas();

            ArgumentCaptor<Wrapper<ConnectorSchema>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(schemaMapper).selectList(captor.capture());
            QueryWrapper<ConnectorSchema> w = (QueryWrapper<ConnectorSchema>) captor.getValue();
            String select = w.getSqlSelect();
            String segment = w.getSqlSegment();

            assertTrue(select.contains("MAX(synced_at)"), select);
            assertTrue(segment.contains("GROUP BY connector_id"), segment);
            assertTrue(segment.contains("HAVING"), segment);
            assertTrue(segment.contains("MAX(synced_at) IS NULL"), "synced_at 为空的存量行必须算到期: " + segment);
            assertTrue(segment.contains("ORDER BY MAX(synced_at) ASC"), segment);

            Date dueBefore = (Date) w.getParamNameValuePairs().values().stream()
                    .filter(v -> v instanceof Date).findFirst().orElseThrow();
            long expected = before - 6 * HOUR;
            assertTrue(Math.abs(dueBefore.getTime() - expected) < 60_000L,
                    "到期线应是「现在 - intervalHours」，实际 " + dueBefore);
        }

        /** 从没拉过结构的连接归推导的补拉管。选人从快照表出发，它按构造进不来。 */
        @Test
        @DisplayName("没有快照的连接不刷")
        void 没有快照的连接不刷() {
            givenMysql(1L, "t1");
            givenMysql(2L, "t1");
            givenSnapshot(1L, 8);

            job.refreshDueSchemas();

            verify(schemaService).refresh(1L);
            verify(schemaService, never()).refresh(2L);
        }

        /**
         * 结构刷新本身就会调别的服务（漂移处置、增量推导），那是 refresh() 自己的事。
         * 本类<b>自己</b>绝不能拿到任何能重推或改写语义行的 bean——拿到了，迟早有人在这里顺手调一次。
         */
        @Test
        @DisplayName("★ 本类不持有任何推导或语义写入的 bean")
        void 不持有推导或语义写入的bean() {
            for (Field f : ConnectorHealthJob.class.getDeclaredFields()) {
                String type = f.getType().getName();
                assertFalse(type.contains("Semantic") || type.contains("Derive"),
                        "字段 " + f.getName() + " 的类型是 " + type);
            }
            for (Constructor<?> c : ConnectorHealthJob.class.getDeclaredConstructors()) {
                for (Class<?> p : c.getParameterTypes()) {
                    assertFalse(p.getName().contains("Semantic") || p.getName().contains("Derive"),
                            "构造参数里出现了 " + p.getName());
                }
            }
        }
    }

    // ================================================================ 租户上下文

    @Nested
    @DisplayName("租户上下文")
    class Tenant {

        /**
         * ★ executeAsPlatform 要真实租户，runAsSystem 不算。而且刷新时要<b>退出</b>系统模式——
         * 否则租户拦截器不加条件，第二道篱笆就没了。
         */
        @Test
        @DisplayName("★ 每条刷新都带那一行的真实租户、不在系统模式里；结束后清掉")
        void 刷新带真实租户且不在系统模式() {
            givenMysql(1L, "t1");
            givenMysql(2L, "t2");
            givenSnapshot(1L, 9);
            givenSnapshot(2L, 8);
            List<String> seenTenants = new ArrayList<>();
            List<Boolean> seenSystemMode = new ArrayList<>();
            when(schemaService.refresh(anyLong())).thenAnswer(inv -> {
                seenTenants.add(TenantContext.get());
                seenSystemMode.add(TenantContext.isSystemMode());
                return null;
            });

            job.refreshDueSchemas();

            assertEquals(List.of("t1", "t2"), seenTenants);
            assertEquals(List.of(false, false), seenSystemMode);
            assertNull(TenantContext.get(), "定时线程是复用的，漏清一次下一条就带着别人的租户");
            assertFalse(TenantContext.isSystemMode());
        }

        @Test
        @DisplayName("选人是跨租户的：在系统模式里查")
        void 选人在系统模式里() {
            List<Boolean> seen = new ArrayList<>();
            when(schemaMapper.selectList(any())).thenAnswer(inv -> {
                seen.add(TenantContext.isSystemMode());
                return List.of();
            });

            job.refreshDueSchemas();

            assertEquals(List.of(true), seen, "不在系统模式里，租户拦截器回落到哨兵，一行都选不出来且不报错");
        }

        @Test
        @DisplayName("refresh 抛异常时租户照样清掉，后面的连接照刷且带自己的租户")
        void 抛异常后租户仍被清理() {
            givenMysql(1L, "t1");
            givenMysql(2L, "t2");
            givenSnapshot(1L, 9);
            givenSnapshot(2L, 8);
            List<String> seen = new ArrayList<>();
            when(schemaService.refresh(1L)).thenThrow(new IllegalStateException("boom"));
            when(schemaService.refresh(2L)).thenAnswer(inv -> {
                seen.add(TenantContext.get());
                return null;
            });

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            assertEquals(List.of("t2"), seen);
            assertEquals(1, t.failed);
            assertEquals(1, t.refreshed);
            assertNull(TenantContext.get());
        }

        @Test
        @DisplayName("线程上原本有租户时，刷完恢复成原来的")
        void 原有租户会被恢复() {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            TenantContext.set("outer");

            job.refreshDueSchemas();

            assertEquals("outer", TenantContext.get());
        }
    }

    // ================================================================ 刷之前就判掉的

    @Nested
    @DisplayName("刷之前就判掉的：不调 refresh、不记冷却、不占上限")
    class Skips {

        /**
         * ★ HTTP 没有 DESCRIBE。必须在调 refresh() 之前判掉：refresh() 抛的 ServiceException
         * 在<b>构造器里</b>就 log.error 了，catch 住也拦不下那一行。
         */
        @Test
        @DisplayName("★ HTTP 连接器静默跳过，连 refresh 都不调")
        void HTTP跳过() {
            Connection c = givenMysql(1L, "t1");
            c.setKind("HTTP");
            c.setCapabilityFlags("INVOKE,HEALTH");
            givenSnapshot(1L, 9);

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            verifyNoInteractions(schemaService);
            assertEquals(1, t.skipped);
            assertEquals(0, t.attempted);
            assertTrue(buckets.isEmpty(), "不适用不是失败，不该记冷却");
        }

        @Test
        @DisplayName("停用 / 隧道 / 探测没确认 DESCRIBE / UNHEALTHY / 没租户都跳过，且不占本轮上限")
        void 各种不该刷的都跳过且不占上限() {
            properties.getSchemaRefresh().setMaxPerSweep(1);
            givenMysql(1L, "t1").setStatus("DISABLED");
            givenMysql(2L, "t1").setTransport("tunnel");
            givenMysql(3L, "t1").setCapabilityFlags("QUERY,HEALTH");
            givenMysql(4L, "t1").setHealthState("UNHEALTHY");
            givenMysql(5L, " ");
            givenMysql(6L, "t1");
            for (long id = 1; id <= 6; id++) {
                givenSnapshot(id, 20 - id);
            }

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            verify(schemaService).refresh(6L);
            verifyNoMoreInteractions(schemaService);
            assertEquals(5, t.skipped);
            assertEquals(1, t.attempted);
        }

        @Test
        @DisplayName("ping 关着时不信 UNHEALTHY——那可能是很久以前的状态")
        void ping关着时不信UNHEALTHY() {
            properties.getHealth().setEnabled(false);
            givenMysql(1L, "t1").setHealthState("UNHEALTHY");
            givenSnapshot(1L, 9);

            job.refreshDueSchemas();

            verify(schemaService).refresh(1L);
        }

        /**
         * ★ 推导落库前比对快照指纹（行数 + 最新 synced_at）。refresh() 删了重插、盖新的 synced_at，
         * 结构没变指纹也变——推导会把整批模型产出作废并写 FAILED。认领超时的（进程死在半路）不再算数。
         */
        @Test
        @DisplayName("★ 推导进行中跳过；认领已超时的不算")
        void 推导进行中跳过() {
            long now = System.currentTimeMillis();
            Connection live = givenMysql(1L, "t1");
            live.setSemanticStatus(ConnectorSemanticDeriveService.SEM_RUNNING);
            live.setSemanticClaimAt(new Date(now - 5 * 60_000L));
            Connection dead = givenMysql(2L, "t1");
            dead.setSemanticStatus(ConnectorSemanticDeriveService.SEM_RUNNING);
            dead.setSemanticClaimAt(new Date(now - 2 * HOUR));
            givenSnapshot(1L, 9);
            givenSnapshot(2L, 8);

            job.refreshDueSchemas();

            verify(schemaService, never()).refresh(1L);
            verify(schemaService).refresh(2L);
        }

        @Test
        @DisplayName("推导刚 READY 的窗口内跳过（给采样验证让出并发许可）")
        void 推导刚完成跳过() {
            long now = System.currentTimeMillis();
            givenMysql(1L, "t1").setSemanticSyncedAt(new Date(now - 10 * 60_000L));
            givenMysql(2L, "t1").setSemanticSyncedAt(new Date(now - 3 * HOUR));
            givenSnapshot(1L, 9);
            givenSnapshot(2L, 8);

            job.refreshDueSchemas();

            verify(schemaService, never()).refresh(1L);
            verify(schemaService).refresh(2L);
        }
    }

    // ================================================================ 失败、繁忙、冷却

    @Nested
    @DisplayName("失败、繁忙与冷却")
    class Failures {

        /** ★ 繁忙是「让位半小时、不占名额」，失败是「一个间隔之后再试」。混成一种，要么惩罚健康的连接，要么饿死别人。 */
        @Test
        @DisplayName("★ 繁忙不占名额、标记 TTL 是「冷却 + 一个间隔」；失败记 intervalHours 小时的冷却；一条失败不中断这一轮")
        void 繁忙短冷却_失败长冷却() {
            givenMysql(1L, "t1");
            givenMysql(2L, "t1");
            givenMysql(3L, "t1");
            givenSnapshot(1L, 9);
            givenSnapshot(2L, 8);
            givenSnapshot(3L, 7);
            when(schemaService.refresh(1L)).thenThrow(new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "连接「conn1」当前并发查询已达上限，请稍后重试"));
            when(schemaService.refresh(2L)).thenThrow(new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "连接器离线"));

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            assertEquals(1, t.busy);
            assertEquals(1, t.failed);
            assertEquals(1, t.refreshed);
            assertEquals(2, t.attempted, "繁忙不占名额");
            verify(schemaService).refresh(3L);
            verify(bucketFor(2L)).set(any(), eq(6L), eq(TimeUnit.HOURS));
            verify(bucketFor(1L)).set(any(), eq(ConnectorHealthJob.busyMemoryMinutes(600_000L, 6)), eq(TimeUnit.MINUTES));
            verify(bucketFor(1L), never()).set(any(), anyLong(), eq(TimeUnit.HOURS));
            verify(bucketFor(3L), never()).set(any(), anyLong(), any(TimeUnit.class));
            assertTrue(String.valueOf(liveMarks.get(key(1L))).startsWith("BUSY@"), "BUSY 的时间戳是承重的");
        }

        @Test
        @DisplayName("繁忙冷却跟着检查间隔走：不短于 3 个检查间隔，默认 30 分钟")
        void 繁忙冷却跟着检查间隔走() {
            assertEquals(30L, ConnectorHealthJob.busyCooldownMinutes(600_000L));
            assertEquals(30L, ConnectorHealthJob.busyCooldownMinutes(60_000L));
            // 有人把检查间隔调到 20 分钟：30 分钟的冷却只让得出一次检查，要跟着变长。
            assertEquals(60L, ConnectorHealthJob.busyCooldownMinutes(20 * 60_000L));
            assertEquals(30L, ConnectorHealthJob.busyCooldownMinutes(0L));
        }

        /** 冷却中的连接不占名额：否则一串失败的连接照样能把健康的连接挤出这一轮。 */
        @Test
        @DisplayName("冷却中的连接跳过，且不占本轮上限")
        void 冷却中跳过且不占上限() {
            properties.getSchemaRefresh().setMaxPerSweep(1);
            givenMysql(1L, "t1");
            givenMysql(2L, "t1");
            givenSnapshot(1L, 30);
            givenSnapshot(2L, 8);
            when(bucketFor(1L).get()).thenReturn(ConnectorHealthJob.OUTCOME_FAILED + "@1");

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            verify(schemaService, never()).refresh(1L);
            verify(schemaService).refresh(2L);
            assertEquals(1, t.cooling);
        }

        @Test
        @DisplayName("Redis 读标记失败时放行，不把整轮卡死")
        void 冷却标记读失败时放行() {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            when(bucketFor(1L).get()).thenThrow(new IllegalStateException("redis down"));

            job.refreshDueSchemas();

            verify(schemaService).refresh(1L);
        }

        /**
         * 本仓库 push main 即部署。部署时 shutdownNow 打断的那条刷新若被当成失败，
         * 那条连接就会被平白冷却一个间隔——每次部署都挑一条连接推迟 6 小时，而且看起来像是它自己坏了。
         */
        @Test
        @DisplayName("进程关闭时被打断的刷新：不算失败、不记冷却、不再开始下一条")
        void 被中断不算失败() {
            givenMysql(1L, "t1");
            givenMysql(2L, "t1");
            givenSnapshot(1L, 9);
            givenSnapshot(2L, 8);
            when(schemaService.refresh(1L)).thenAnswer(inv -> {
                throw new InterruptedException();
            });
            try {
                ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

                assertEquals(1, t.aborted);
                assertEquals(0, t.failed);
                verify(schemaService, never()).refresh(2L);
                verify(bucketFor(1L), never()).set(any(), anyLong(), any(TimeUnit.class));
                assertTrue(Thread.currentThread().isInterrupted(), "中断标记要还回去，外层才知道进程在关闭");
            } finally {
                // 清掉，别污染同一线程上的后续用例。
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("繁忙的判定：只认 RATE_LIMITED 的 cause 或那句文案，其它一律算失败")
        void 繁忙判定() {
            assertTrue(ConnectorHealthJob.isBusy(new RuntimeException("wrap",
                    ConnectorException.of(ConnectorErrorCode.RATE_LIMITED, null))));
            assertTrue(ConnectorHealthJob.isBusy(new ServiceException(ExceptionCode.SERVER_BUSY, "忙")));
            assertFalse(ConnectorHealthJob.isBusy(new ServiceException(ExceptionCode.INVALID_REQUEST, "连接器离线")));
            // 码不对就不认文案：宁可把繁忙错认成失败，不要把失败错认成繁忙。
            assertFalse(ConnectorHealthJob.isBusy(new ServiceException(ExceptionCode.NOT_FOUND, "请稍后重试")));
            assertFalse(ConnectorHealthJob.isBusy(ConnectorException.of(ConnectorErrorCode.TIMEOUT, null)));
            assertFalse(ConnectorHealthJob.isBusy(new IllegalStateException("请稍后重试")));
        }

        /**
         * ★ 有原件就不读复印件：refresh() 转换时带上 cause，就只信 cause 上的错误码。
         * 文案会撒谎——写着「请稍后重试」的超时不是繁忙；文案被人改过的限流仍然是繁忙。
         */
        @Test
        @DisplayName("★ cause 链上有 ConnectorException 时只信错误码，不再看文案；没有 cause 才退回认文案")
        void 有cause时只信错误码() {
            String invalid = ExceptionCode.INVALID_REQUEST.getResultCode();
            assertTrue(ConnectorHealthJob.isBusy(new ServiceException(
                    ConnectorException.of(ConnectorErrorCode.RATE_LIMITED, "限流了"), invalid, "文案里没有那句话")));
            assertFalse(ConnectorHealthJob.isBusy(new ServiceException(
                    ConnectorException.of(ConnectorErrorCode.TIMEOUT, "查询超时，请稍后重试"), invalid, "查询超时，请稍后重试")));
            assertTrue(ConnectorHealthJob.isBusy(new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "数据库当前连接数已满，请稍后重试")));
        }
    }

    // ================================================================ 繁忙不占名额

    @Nested
    @DisplayName("繁忙不占名额；有繁忙前科的重试另有预算")
    class BusyLane {

        private final ServiceException busy = new ServiceException(
                ConnectorException.of(ConnectorErrorCode.RATE_LIMITED, "限流了"),
                ExceptionCode.INVALID_REQUEST.getResultCode(),
                "连接当前并发查询已达上限，请稍后重试");

        private String busyMarkMinutesAgo(long minutes) {
            return ConnectorHealthJob.OUTCOME_BUSY + "@" + (System.currentTimeMillis() - minutes * 60_000L);
        }

        /**
         * ★ 这条修的是真实的饿死。从前繁忙占名额、靠 30 分钟冷却让位：冷却 3 个检查间隔 × 每次 3 个名额，
         * 9 条以上持续繁忙的连接就能在每一次检查里轮流把名额占光，排在后面的健康连接永远是 0。
         * 这里放 10 条，连跑三次检查：健康的每次都刷到，有前科的每次至多重试 3 条、轮流来。
         */
        @Test
        @DisplayName("★ 前面十条持续繁忙：健康的到期连接每一次检查都刷得到；有前科的重试每轮至多 maxPerSweep 次、轮流来")
        void 持续繁忙挡不住健康的连接() {
            properties.getSchemaRefresh().setMaxPerSweep(3);
            for (long id = 1; id <= 10; id++) {
                givenMysql(id, "t1");
                givenSnapshot(id, 100 - id);
                when(schemaService.refresh(id)).thenThrow(busy);
            }
            for (long id = 11; id <= 13; id++) {
                givenMysql(id, "t1");
                givenSnapshot(id, 50 - id);
            }

            ConnectorHealthJob.RefreshTally first = job.refreshDueSchemas();

            assertEquals(10, first.busy, "首次撞上的繁忙事先认不出来：都试，但都不占名额");
            assertEquals(3, first.attempted);
            assertEquals(3, first.refreshed, "三个名额全给了健康的连接——从前这里是 0");

            ageBusyMarks(ConnectorHealthJob.BUSY_COOLDOWN_MINUTES + 1);
            ConnectorHealthJob.RefreshTally second = job.refreshDueSchemas();

            assertEquals(3, second.busy, "有前科的重试每轮至多 maxPerSweep 次");
            assertEquals(7, second.deferred);
            assertEquals(3, second.refreshed, "健康的连接照样轮得到");

            ConnectorHealthJob.RefreshTally third = job.refreshDueSchemas();

            assertEquals(3, third.cooling, "上一轮刚重试过的三条在冷却");
            assertEquals(3, third.busy, "轮到下三条有前科的");
            assertEquals(4, third.deferred);
            assertEquals(3, third.refreshed);
            verify(schemaService, times(2)).refresh(1L);
            verify(schemaService, times(2)).refresh(4L);
            verify(schemaService, times(1)).refresh(7L);
            verify(schemaService, times(3)).refresh(11L);
        }

        @Test
        @DisplayName("首次撞上的繁忙也算进预算：预算用完后有前科的不去撞，没有前科的照常去刷")
        void 首次繁忙也算进预算() {
            properties.getSchemaRefresh().setMaxPerSweep(1);
            givenMysql(1L, "t1");
            givenMysql(2L, "t1");
            givenMysql(3L, "t1");
            givenSnapshot(1L, 30);
            givenSnapshot(2L, 20);
            givenSnapshot(3L, 10);
            when(schemaService.refresh(1L)).thenThrow(busy);
            liveMarks.put(key(2L), busyMarkMinutesAgo(ConnectorHealthJob.BUSY_COOLDOWN_MINUTES + 1));

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            verify(schemaService).refresh(1L);
            verify(schemaService, never()).refresh(2L);
            verify(schemaService).refresh(3L);
            assertEquals(1, t.busy);
            assertEquals(1, t.deferred);
            assertEquals(1, t.refreshed);
        }

        @Test
        @DisplayName("有前科的连接不忙了：照常刷、占名额，前科当场清掉")
        void 有前科的连接恢复了() {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            liveMarks.put(key(1L), busyMarkMinutesAgo(ConnectorHealthJob.BUSY_COOLDOWN_MINUTES + 1));

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            verify(schemaService).refresh(1L);
            assertEquals(1, t.refreshed);
            assertEquals(1, t.attempted);
            assertFalse(liveMarks.containsKey(key(1L)), "不清的话，前科窗口里它仍按「已知繁忙」受繁忙预算约束");
        }

        @Test
        @DisplayName("标记的读法：FAILED / GUARDED 键在即冷却；BUSY 冷却期内算冷却、过了是前科；认不出来的按冷却")
        void 标记的读法() {
            long now = System.currentTimeMillis();
            long cooldown = 30 * 60_000L;
            assertEquals(MarkState.NONE, ConnectorHealthJob.classifyMark(null, now, cooldown));
            assertEquals(MarkState.COOLING, ConnectorHealthJob.classifyMark("FAILED@" + now, now, cooldown));
            assertEquals(MarkState.COOLING, ConnectorHealthJob.classifyMark("GUARDED@1", now, cooldown));
            assertEquals(MarkState.COOLING,
                    ConnectorHealthJob.classifyMark("BUSY@" + (now - 5 * 60_000L), now, cooldown));
            assertEquals(MarkState.BUSY_BEFORE,
                    ConnectorHealthJob.classifyMark("BUSY@" + (now - 31 * 60_000L), now, cooldown));
            assertEquals(MarkState.COOLING, ConnectorHealthJob.classifyMark("BUSY@不是数字", now, cooldown));
            assertEquals(MarkState.COOLING, ConnectorHealthJob.classifyMark("谁手工写的值", now, cooldown),
                    "认不出来的按冷却：按「没有标记」处理，一个坏值就能让冷却整个失效");
        }

        @Test
        @DisplayName("前科窗口 = 繁忙冷却 + 一个间隔；重试预算跟着 maxPerSweep 走")
        void 前科窗口与预算() {
            assertEquals(30L + 6 * 60L, ConnectorHealthJob.busyMemoryMinutes(600_000L, 6));
            assertEquals(60L + 60L, ConnectorHealthJob.busyMemoryMinutes(20 * 60_000L, 1));
            ConnectorProperties.SchemaRefresh cfg = new ConnectorProperties.SchemaRefresh();
            cfg.setMaxPerSweep(5);
            assertEquals(5, ConnectorHealthJob.busyRetryBudget(cfg));
            cfg.setMaxPerSweep(0);
            assertEquals(1, ConnectorHealthJob.busyRetryBudget(cfg));
        }
    }

    // ================================================================ 中断与放锁

    @Nested
    @DisplayName("进程关闭时的中断与放锁")
    class InterruptAndLock {

        /**
         * ★ 部署时 shutdownNow 打断正在跑的一轮。Redisson 在带着中断标记的线程上 isHeldByCurrentThread / unlock 会抛：
         * 不先清标记，unlock 根本发不出去（新进程要等租期到了才能刷），异常从 finally 里抛出去变成一段「整轮失败」的 ERROR。
         */
        @Test
        @DisplayName("★ 被中断的一轮：放锁前清掉中断标记，锁照常放掉、不抛，标记事后还回去")
        void 被中断时照常放锁() {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            when(schemaService.refresh(1L)).thenAnswer(inv -> {
                throw new InterruptedException();
            });
            when(lock.isHeldByCurrentThread()).thenAnswer(inv -> {
                failIfInterrupted("isHeldByCurrentThread");
                return true;
            });
            List<Boolean> interruptedAtUnlock = new ArrayList<>();
            doAnswer(inv -> {
                interruptedAtUnlock.add(Thread.currentThread().isInterrupted());
                failIfInterrupted("unlock");
                return null;
            }).when(lock).unlock();
            try {
                ConnectorHealthJob.RefreshTally t = assertDoesNotThrow(() -> job.refreshDueSchemas(),
                        "放锁抛出去的话，派发线程会把它记成「结构定时刷新整轮失败」的 ERROR");

                assertEquals(1, t.aborted);
                assertEquals(0, t.failed);
                assertEquals(List.of(false), interruptedAtUnlock, "unlock 必须真的发出去，而且是在清掉中断标记之后");
                assertTrue(Thread.currentThread().isInterrupted(), "中断标记要还回去，线程才知道自己该收工");
            } finally {
                // 清掉，别污染同一线程上的后续用例。
                Thread.interrupted();
            }
        }

        @Test
        @DisplayName("放锁本身失败（Redis 抖动）：不抛出，本轮计数照常返回，也不凭空多出中断标记")
        void 放锁失败不抛() {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            doThrow(new RedisException("redis down")).when(lock).unlock();

            ConnectorHealthJob.RefreshTally t = assertDoesNotThrow(() -> job.refreshDueSchemas());

            assertEquals(1, t.refreshed);
            assertFalse(Thread.currentThread().isInterrupted());
        }

        @Test
        @DisplayName("健康探测那把锁同理：扫描途中被中断，照样清标记放锁、不抛")
        void 健康探测被中断时照常放锁() {
            when(connectionMapper.selectList(any())).thenAnswer(inv -> {
                // 扫到一半，调度器在关闭。
                Thread.currentThread().interrupt();
                return new ArrayList<Connection>();
            });
            when(lock.isHeldByCurrentThread()).thenAnswer(inv -> {
                failIfInterrupted("isHeldByCurrentThread");
                return true;
            });
            doAnswer(inv -> {
                failIfInterrupted("unlock");
                return null;
            }).when(lock).unlock();
            try {
                assertDoesNotThrow(() -> job.sweep());

                verify(lock).unlock();
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    // ================================================================ 进程关闭：替卡住的线程放锁

    @Nested
    @DisplayName("进程关闭：替卡在客户库上的线程放锁")
    class ShutdownReleasesHeldLocks {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch wake = new CountDownLatch(1);
        private final AtomicReference<Thread> worker = new AtomicReference<>();

        /** 模拟一次 JDBC socket 读：中断叫不醒，等到 {@link #wake} 才返回；返回时中断标记仍在（socket 读不会清它）。 */
        private Answer<Object> blockedInSocketRead(Object result) {
            return inv -> {
                worker.set(Thread.currentThread());
                entered.countDown();
                boolean interrupted = false;
                long deadline = System.currentTimeMillis() + 10_000L;
                while (wake.getCount() > 0 && System.currentTimeMillis() < deadline) {
                    try {
                        wake.await(100, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return result;
            };
        }

        @AfterEach
        void wakeWorker() throws InterruptedException {
            wake.countDown();
            Thread t = worker.get();
            if (t != null) {
                t.join(5_000L);
            }
        }

        /**
         * ★ 这条修的是：部署落在一轮刷新中间，刷新线程卡在 socket 读上，shutdownNow 叫不醒它，
         * Redisson 被销毁、守护线程死掉，锁留在 Redis 里等 30 分钟租期——新进程这半小时每次检查都静默跳过。
         */
        @Test
        @DisplayName("★ 刷新卡在客户库上时进程关闭：按拿锁那条线程的 id 当场放锁；那条线程醒来后不再放第二次")
        void 卡住的刷新由关闭回调替它放锁() throws Exception {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            when(schemaService.refresh(1L)).thenAnswer(blockedInSocketRead(null));
            RFuture<Void> released = finishedFuture(true, null);
            when(lock.unlockAsync(anyLong())).thenReturn(released);

            assertTrue(job.dispatchSchemaRefresh());
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            job.releaseOnShutdown();

            verify(lock).unlockAsync(worker.get().getId());
            verify(lock, never()).unlock();
            verify(lock, never()).forceUnlock();

            wake.countDown();
            worker.get().join(5_000L);
            assertFalse(worker.get().isAlive(), "shutdownNow 之后，那条线程跑完手上这一条就该退出");
            verify(lock, times(1)).unlockAsync(anyLong());
            verify(lock, never()).unlock();
            verify(schemaService, times(1)).refresh(anyLong());
        }

        /**
         * ★ 替别人放锁要赶在 Redisson 关掉之前。这一点不靠本类自己的代码，靠 Spring 的销毁顺序：本类构造器依赖
         * {@code RedissonClient}，依赖方先销毁。谁把这个依赖改成延迟拿（{@code ObjectProvider} / {@code @Lazy}），
         * 依赖边就没了，Redisson 可能先关——放锁静默失败，锁照样等租期。所以真起一个容器、真关一次，看先后。
         */
        @Test
        @DisplayName("★ 容器关闭时：先替卡住的线程放锁，再关 Redisson")
        void 放锁赶在Redisson关闭之前() throws Exception {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            when(schemaService.refresh(1L)).thenAnswer(blockedInSocketRead(null));
            List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
            RFuture<Void> released = finishedFuture(true, null);
            when(lock.unlockAsync(anyLong())).thenAnswer(inv -> {
                order.add("unlockAsync");
                return released;
            });
            doAnswer(inv -> {
                order.add("redisson.shutdown");
                return null;
            }).when(redissonClient).shutdown();

            AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
            try {
                DefaultListableBeanFactory bf = ctx.getDefaultListableBeanFactory();
                bf.registerResolvableDependency(ConnectionMapper.class, connectionMapper);
                bf.registerResolvableDependency(ConnectorRegistry.class, registry);
                bf.registerResolvableDependency(ConnectorInstanceLoader.class, loader);
                bf.registerResolvableDependency(ConnectorProperties.class, properties);
                bf.registerResolvableDependency(ConnectorSchemaMapper.class, schemaMapper);
                bf.registerResolvableDependency(ConnectorSchemaService.class, schemaService);
                // Redisson 注册成真 bean，并且和 RedissonConfig 里 @Bean 推断出来的一样，销毁时调 shutdown()。
                ctx.registerBean("redissonClient", RedissonClient.class, () -> redissonClient,
                        bd -> bd.setDestroyMethodName("shutdown"));
                ctx.registerBean(ConnectorHealthJob.class);
                ctx.refresh();

                assertTrue(ctx.getBean(ConnectorHealthJob.class).dispatchSchemaRefresh());
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            } finally {
                ctx.close();
            }

            assertEquals(List.of("unlockAsync", "redisson.shutdown"), order,
                    "关闭回调必须在 Redisson 关掉之前放锁；顺序反了，放锁静默失败，新进程要等半小时租期");
        }

        @Test
        @DisplayName("健康探测那把锁同理：调度线程卡住时关闭，照样按它的线程 id 放掉")
        void 卡住的健康探测由关闭回调替它放锁() throws Exception {
            when(connectionMapper.selectList(any())).thenAnswer(blockedInSocketRead(new ArrayList<Connection>()));
            RFuture<Void> released = finishedFuture(true, null);
            when(lock.unlockAsync(anyLong())).thenReturn(released);
            Thread scheduler = new Thread(job::sweep, "scheduling-1");
            scheduler.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            job.releaseOnShutdown();

            verify(lock).unlockAsync(scheduler.getId());
            wake.countDown();
            scheduler.join(5_000L);
            verify(lock, never()).unlock();
        }

        @Test
        @DisplayName("关闭时锁已经不是本实例的（租期过了、被别的副本拿走）：不删别人的锁、不抛")
        void 锁已被别人拿走时不动它() throws Exception {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            when(schemaService.refresh(1L)).thenAnswer(blockedInSocketRead(null));
            RFuture<Void> notOurs = finishedFuture(false,
                    new IllegalMonitorStateException("attempt to unlock lock, not locked by current thread"));
            when(lock.unlockAsync(anyLong())).thenReturn(notOurs);
            assertTrue(job.dispatchSchemaRefresh());
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            assertDoesNotThrow(() -> job.releaseOnShutdown());

            verify(lock).unlockAsync(anyLong());
            verify(lock, never()).forceUnlock();
            verify(lock, never()).forceUnlockAsync();
        }

        @Test
        @DisplayName("关闭之后：不再抢任何锁、不再派发")
        void 关闭之后不再抢锁() {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            job.releaseOnShutdown();

            job.refreshDueSchemas();
            job.sweep();

            assertFalse(job.dispatchSchemaRefresh());
            verify(redissonClient, never()).getLock(anyString());
            verifyNoInteractions(schemaService);
        }

        /** 关闭回调遍历时还看不到这一项、抢锁的一方随后才记下：它必须自己看见 closing，当场放掉。 */
        @Test
        @DisplayName("关闭恰好插在「抢到锁」与「记下是谁拿着」之间：抢锁的一方自己当场放掉，不刷")
        void 关闭插在抢锁与记账之间() throws Exception {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
                job.releaseOnShutdown();
                return true;
            });

            job.refreshDueSchemas();

            verify(lock).unlock();
            verify(lock, never()).unlockAsync(anyLong());
            verifyNoInteractions(schemaService);
        }

        @Test
        @DisplayName("一轮正常跑完再关闭：锁已经由那一轮自己放了，关闭时什么都不放")
        void 正常收尾之后关闭不重复放() {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);

            job.refreshDueSchemas();
            job.releaseOnShutdown();

            verify(lock).unlock();
            verify(lock, never()).unlockAsync(anyLong());
        }

        /** 部署落在一条刷新中间，那条刷新随后因为依赖被拆掉而失败：那不是这条连接的问题。 */
        @Test
        @DisplayName("关闭已经开始时 refresh() 抛了别的异常：按被中断处理，不算失败、不写冷却、不再开始下一条")
        void 关闭中的失败不记冷却() {
            givenMysql(1L, "t1");
            givenMysql(2L, "t1");
            givenSnapshot(1L, 9);
            givenSnapshot(2L, 8);
            RFuture<Void> released = finishedFuture(true, null);
            when(lock.unlockAsync(anyLong())).thenReturn(released);
            when(schemaService.refresh(1L)).thenAnswer(inv -> {
                job.releaseOnShutdown();
                throw new IllegalStateException("数据源已关闭");
            });

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            assertEquals(1, t.aborted);
            assertEquals(0, t.failed);
            assertTrue(liveMarks.isEmpty(), "部署不是这条连接的问题，不该记冷却");
            verify(schemaService, never()).refresh(2L);
            verify(lock).unlockAsync(Thread.currentThread().getId());
            verify(lock, never()).unlock();
        }
    }

    // ================================================================ 判定用此刻的行

    @Nested
    @DisplayName("跳过规则对着轮到它那一刻重读的行判")
    class RereadBeforeRefresh {

        /** 「数据库」里此刻的连接行。被测代码每次查询拿到的都是副本。 */
        private final Map<Long, Connection> db = new LinkedHashMap<>();
        private final List<Boolean> systemModeSeen = new ArrayList<>();

        @BeforeEach
        void serveFreshRows() {
            serveCopiesFrom(db, systemModeSeen);
        }

        private Connection row(long id) {
            Connection c = givenMysql(id, "t1");
            db.put(id, c);
            return c;
        }

        /**
         * ★ 前一条刷新的几十秒里，后面几条的状态变了。拿本轮开始时的旧行判会照刷：
         * 推导落库前比对快照指纹，一次刷新就能让刚点的「重新生成」整批作废、写 FAILED。
         */
        @Test
        @DisplayName("★ 前一条刷新期间才开始的推导 / 才判异常 / 才写 READY / 才删掉的连接：都按此刻判，跳过")
        void 前一条刷新期间变了的都按此刻判() {
            properties.getSchemaRefresh().setMaxPerSweep(6);
            for (long id = 1; id <= 6; id++) {
                row(id);
                givenSnapshot(id, 20 - id);
            }
            when(schemaService.refresh(1L)).thenAnswer(inv -> {
                Date now = new Date();
                db.get(2L).setSemanticStatus(ConnectorSemanticDeriveService.SEM_RUNNING);
                db.get(2L).setSemanticClaimAt(now);
                db.get(3L).setHealthState("UNHEALTHY");
                db.get(4L).setSemanticSyncedAt(now);
                db.remove(5L);
                return null;
            });

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            verify(schemaService).refresh(1L);
            verify(schemaService).refresh(6L);
            verifyNoMoreInteractions(schemaService);
            assertEquals(6, t.due);
            assertEquals(4, t.skipped);
            assertEquals(2, t.refreshed);
        }

        /** 反方向：开始时是异常、轮到它时 ping 已经判回健康，就照刷——证明判的真是此刻的行，而不只是多查了一次。 */
        @Test
        @DisplayName("开始时 UNHEALTHY、轮到它时已恢复：照刷")
        void 开始时异常轮到时已恢复() {
            row(1L);
            row(2L).setHealthState("UNHEALTHY");
            givenSnapshot(1L, 9);
            givenSnapshot(2L, 8);
            when(schemaService.refresh(1L)).thenAnswer(inv -> {
                db.get(2L).setHealthState("HEALTHY");
                return null;
            });

            job.refreshDueSchemas();

            verify(schemaService).refresh(2L);
        }

        /** 不在系统模式里重读，租户拦截器回落到哨兵，每条都读不到、被当成「已删除」静默跳过。 */
        @Test
        @DisplayName("重读在系统模式里（跨租户）；刷新本身不在")
        void 重读在系统模式里() {
            row(1L);
            row(2L);
            givenSnapshot(1L, 9);
            givenSnapshot(2L, 8);
            List<Boolean> refreshSystemMode = new ArrayList<>();
            when(schemaService.refresh(anyLong())).thenAnswer(inv -> {
                refreshSystemMode.add(TenantContext.isSystemMode());
                return null;
            });

            job.refreshDueSchemas();

            assertEquals(List.of(true, true, true), systemModeSeen, "选人一次 + 每条重读一次，都在系统模式里");
            assertEquals(List.of(false, false), refreshSystemMode);
        }

        @Test
        @DisplayName("重读失败：这条跳过、不刷、不记冷却，后面的照常")
        void 重读失败就跳过() {
            row(1L);
            row(2L);
            row(3L);
            givenSnapshot(1L, 9);
            givenSnapshot(2L, 8);
            givenSnapshot(3L, 7);
            AtomicInteger calls = new AtomicInteger();
            when(connectionMapper.selectList(any())).thenAnswer(inv -> {
                // 第 1 次是选人，第 2 次重读 1，第 3 次重读 2——让它失败。
                if (calls.incrementAndGet() == 3) {
                    throw new IllegalStateException("db blip");
                }
                return new ArrayList<>(db.values());
            });

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            verify(schemaService).refresh(1L);
            verify(schemaService, never()).refresh(2L);
            verify(schemaService).refresh(3L);
            assertEquals(1, t.skipped);
            assertTrue(liveMarks.isEmpty(), "我们自己库的一次抖动不是这条连接的问题，不该记冷却");
        }
    }

    // ================================================================ 拒绝落库

    @Nested
    @DisplayName("refresh() 拒绝落库可疑快照（guardNote）")
    class Guarded {

        /** mock 而不是 new：SnapshotResult 的构造器形状归结构刷新那边管，按位置 new 会被它的改动静默带偏。 */
        private ConnectorSchemaService.SnapshotResult guardedResult() {
            ConnectorSchemaService.SnapshotResult r = mock(ConnectorSchemaService.SnapshotResult.class);
            when(r.getGuardNote()).thenReturn("本次拉到 0 个对象，而上一份快照有 12 个，快照未更新");
            return r;
        }

        /**
         * ★ 拒绝落库不是失败：不算失败、不记 WARN。但 synced_at 没前进——不记标记的话，
         * 一条权限被收走、目录永远拉回来是空的连接每次检查都排第一、占名额、打客户库，结论次次一样。
         */
        @Test
        @DisplayName("★ 当成成功的无变化：不算失败；记一个 intervalHours 的标记，与正常刷新完的连接同一个节奏")
        void 拒绝落库按无变化处理() {
            givenMysql(1L, "t1");
            givenMysql(2L, "t1");
            givenSnapshot(1L, 9);
            givenSnapshot(2L, 8);
            ConnectorSchemaService.SnapshotResult r = guardedResult();
            when(schemaService.refresh(1L)).thenReturn(r);

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            assertEquals(1, t.guarded);
            assertEquals(0, t.failed);
            assertEquals(0, t.busy);
            assertEquals(1, t.refreshed, "后面那条照常刷");
            verify(bucketFor(1L)).set(any(), eq(6L), eq(TimeUnit.HOURS));
            verify(bucketFor(2L), never()).set(any(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("★ 下一次检查不再重撞它，排在后面的到期连接轮得到")
        void 下一次检查不重撞() {
            properties.getSchemaRefresh().setMaxPerSweep(1);
            givenMysql(1L, "t1");
            givenMysql(2L, "t1");
            givenSnapshot(1L, 30);
            givenSnapshot(2L, 8);
            ConnectorSchemaService.SnapshotResult r = guardedResult();
            when(schemaService.refresh(1L)).thenReturn(r);

            job.refreshDueSchemas();
            ConnectorHealthJob.RefreshTally second = job.refreshDueSchemas();

            verify(schemaService, times(1)).refresh(1L);
            verify(schemaService).refresh(2L);
            assertEquals(1, second.cooling);
        }

        @Test
        @DisplayName("guardNote 为空的正常刷新：算成功，不写任何标记")
        void 正常刷新不写标记() {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            ConnectorSchemaService.SnapshotResult r = mock(ConnectorSchemaService.SnapshotResult.class);
            when(schemaService.refresh(1L)).thenReturn(r);

            ConnectorHealthJob.RefreshTally t = job.refreshDueSchemas();

            assertEquals(1, t.refreshed);
            assertEquals(0, t.guarded);
            assertTrue(liveMarks.isEmpty());
        }
    }

    // ================================================================ 开关、锁、线程

    @Nested
    @DisplayName("开关、跨副本锁与执行线程")
    class SwitchesAndThreads {

        @Test
        @DisplayName("关掉 / 间隔 <= 0 / 上限 <= 0：什么都不做，连锁都不抢")
        void 关掉时什么都不做() {
            properties.getSchemaRefresh().setEnabled(false);
            job.refreshDueSchemas();
            properties.getSchemaRefresh().setEnabled(true);
            properties.getSchemaRefresh().setIntervalHours(0);
            job.refreshDueSchemas();
            properties.getSchemaRefresh().setIntervalHours(6);
            properties.getSchemaRefresh().setMaxPerSweep(0);
            job.refreshDueSchemas();

            verifyNoInteractions(schemaMapper, schemaService);
            verify(redissonClient, never()).getLock(anyString());
            assertFalse(new ConnectorHealthJob(connectionMapper, registry, loader, disabled(), redissonClient,
                    schemaMapper, schemaService).dispatchSchemaRefresh());
        }

        private ConnectorProperties disabled() {
            ConnectorProperties p = new ConnectorProperties();
            p.getSchemaRefresh().setEnabled(false);
            return p;
        }

        @Test
        @DisplayName("拿不到跨副本锁：不选人、不刷新")
        void 拿不到锁() throws Exception {
            when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);

            job.refreshDueSchemas();

            verifyNoInteractions(schemaMapper, schemaService);
        }

        @Test
        @DisplayName("锁用显式租期（sweepTimeoutMinutes），不用看门狗续租")
        void 锁用显式租期() throws Exception {
            properties.getSchemaRefresh().setSweepTimeoutMinutes(17);

            job.refreshDueSchemas();

            verify(lock).tryLock(0L, 17L, TimeUnit.MINUTES);
        }

        /**
         * ★ Spring 默认调度器是单线程的，ping 和孤儿清理都在上面排队。刷新必须交出去、调度线程立刻返回；
         * 上一轮没跑完时，本次检查直接跳过而不是排队。
         */
        @Test
        @DisplayName("★ 定时触发不占调度线程；上一轮没跑完时不叠加")
        void 定时触发不占调度线程() throws Exception {
            givenMysql(1L, "t1");
            givenSnapshot(1L, 9);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            when(schemaService.refresh(1L)).thenAnswer(inv -> {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
                return null;
            });

            assertTrue(job.dispatchSchemaRefresh(), "第一次应当派发出去");
            assertTrue(entered.await(5, TimeUnit.SECONDS), "刷新应当在别的线程上开始");
            // 走到这里时刷新还卡着，说明 dispatch 没有等它跑完。
            assertEquals(1, release.getCount());
            assertFalse(job.dispatchSchemaRefresh(), "上一轮没跑完，本次检查应当跳过");

            release.countDown();
            boolean dispatchedAgain = false;
            long deadline = System.currentTimeMillis() + 5_000L;
            while (!dispatchedAgain && System.currentTimeMillis() < deadline) {
                dispatchedAgain = job.dispatchSchemaRefresh();
                if (!dispatchedAgain) {
                    Thread.sleep(20);
                }
            }
            assertTrue(dispatchedAgain, "上一轮跑完之后应当能再次派发");
            // 等第二轮真的刷完再收尾，免得 tearDown 的 shutdownNow 打断它、在日志里留一段假的中断。
            verify(schemaService, timeout(5_000L).times(2)).refresh(1L);
        }
    }

    // ================================================================ 检查间隔的绑定

    @Nested
    @DisplayName("check-interval-ms 的绑定：@Scheduled 接受的写法，这里一定接受")
    class CheckIntervalBinding {

        @Test
        @DisplayName("解析照抄 @Scheduled：毫秒整数与 ISO-8601 时长（大小写都行）；解析不了是 null")
        void 解析照抄Scheduled() {
            assertEquals(600_000L, ConnectorHealthJob.parseCheckIntervalMs("600000"));
            assertEquals(600_000L, ConnectorHealthJob.parseCheckIntervalMs("PT10M"));
            assertEquals(600_000L, ConnectorHealthJob.parseCheckIntervalMs("pt10m"));
            assertEquals(86_400_000L, ConnectorHealthJob.parseCheckIntervalMs("P1D"));
            assertNull(ConnectorHealthJob.parseCheckIntervalMs("10分钟"));
            assertNull(ConnectorHealthJob.parseCheckIntervalMs(""));
            assertNull(ConnectorHealthJob.parseCheckIntervalMs(null));
        }

        /**
         * ★ 真起一个 Spring 容器、真注册 {@code @Scheduled}：只测解析函数钉不住「bean 建不建得出来」——
         * 上一版的 bug 就在 {@code @Value} 的目标类型上，解析函数里一个字都没有。
         */
        @Test
        @DisplayName("★ Nacos 里写 PT20M：bean 照样建出来、两个 @Scheduled 照样注册，繁忙冷却按 20 分钟的间隔算")
        void 写ISO时长时照样启动() {
            try (AnnotationConfigApplicationContext ctx = contextWith("PT20M")) {
                ctx.registerBean(ConnectorHealthJob.class);
                ctx.refresh();

                ConnectorHealthJob bean = ctx.getBean(ConnectorHealthJob.class);
                assertEquals(20 * 60_000L, bean.effectiveCheckIntervalMs());
                assertEquals(60L, ConnectorHealthJob.busyCooldownMinutes(bean.effectiveCheckIntervalMs()));
                assertEquals(2, ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size(),
                        "sweep 与 scheduleSchemaRefresh 两个定时任务都应当注册上");
            }
        }

        @Test
        @DisplayName("占位符没配：按默认 600000 毫秒")
        void 没配时用默认值() {
            try (AnnotationConfigApplicationContext ctx = contextWith(null)) {
                ctx.registerBean(ConnectorHealthJob.class);
                ctx.refresh();

                assertEquals(ConnectorHealthJob.DEFAULT_CHECK_INTERVAL_MS,
                        ctx.getBean(ConnectorHealthJob.class).effectiveCheckIntervalMs());
            }
        }

        /** 反例：证明上面那条测的确实是这个坑，而不是容器本来就宽容。 */
        @Test
        @DisplayName("反例：同一个占位符按 long 绑定，@Scheduled 接受的 PT20M 会让 bean 建不出来——data-server 起不来")
        void 按long绑定时起不来() {
            try (AnnotationConfigApplicationContext ctx = contextWith("PT20M")) {
                ctx.registerBean(LongBoundCheckInterval.class);

                assertThrows(BeanCreationException.class, ctx::refresh);
            }
        }

        private AnnotationConfigApplicationContext contextWith(String checkInterval) {
            AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
            if (checkInterval != null) {
                ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("nacos",
                        Map.of("connector.schema-refresh.check-interval-ms", checkInterval)));
            }
            // 依赖用「可解析依赖」交给容器，而不是注册成 bean：注册成 bean 的 mock 会被当成真 bean 做一遍注解处理。
            DefaultListableBeanFactory bf = ctx.getDefaultListableBeanFactory();
            bf.registerResolvableDependency(ConnectionMapper.class, connectionMapper);
            bf.registerResolvableDependency(ConnectorRegistry.class, registry);
            bf.registerResolvableDependency(ConnectorInstanceLoader.class, loader);
            bf.registerResolvableDependency(ConnectorProperties.class, properties);
            bf.registerResolvableDependency(RedissonClient.class, redissonClient);
            bf.registerResolvableDependency(ConnectorSchemaMapper.class, schemaMapper);
            bf.registerResolvableDependency(ConnectorSchemaService.class, schemaService);
            ctx.registerBean(ScheduledAnnotationBeanPostProcessor.class);
            return ctx;
        }
    }

    // ================================================================ 真实网关 + 真实 refresh()

    /**
     * ★ 「繁忙」要从 refresh() 转换之后的异常上认出来。refresh() 今天转换时带着 cause，繁忙靠 cause 上的 RATE_LIMITED 认；
     * cause 万一被丢了，错误码在那一层被抹成 INVALID_REQUEST，只剩那句文案可认（{@link ConnectorHealthJob#BUSY_MARK} 的兜底）。
     * 这是跨两个类的隐性契约，mock 钉不住，所以这里用<b>真实的网关</b>和<b>真实的 refresh()</b>：
     * 一条钉「refresh() 带着 cause」，其余几条钉「真实的限流拒绝被认成繁忙」。
     *
     * <p>{@code ConnectorSchemaService} 用反射按参数类型构造：它的构造器可能被别的改动加参数，
     * 按位置 new 会让这组测试在编译期就连累整个测试模块。
     */
    @Nested
    @DisplayName("真实网关 + 真实 refresh()：繁忙的认法")
    class RealRejectionShapes {

        private RedisTemplate<String, Object> redisTemplate;
        private ValueOperations<String, Object> ops;
        private ConnectorGateway gateway;
        private ConnectorSchemaService realService;
        private ConnectorHealthJob realJob;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void wireRealPath() throws Exception {
            redisTemplate = mock(RedisTemplate.class);
            ops = mock(ValueOperations.class);
            when(redisTemplate.opsForValue()).thenReturn(ops);
            when(ops.increment(anyString())).thenReturn(1L);

            Connection row = givenMysql(100L, "t1");
            row.setName("crm");
            givenSnapshot(100L, 9);
            when(connectionMapper.selectById(100L)).thenReturn(row);
            ConnectorInstance inst = new ConnectorInstance(100L, "t1", "MYSQL", "crm", "CRM", Map.of(), "pwd", "direct");
            when(loader.load(any(Connection.class))).thenReturn(inst);
            when(mysql.open(any())).thenReturn(mock(ConnectorSession.class));

            gateway = new ConnectorGateway(connectionMapper, mock(AgentConnectionMapper.class), registry, loader,
                    properties, mock(ConnectorAuditService.class), redisTemplate, mock(ObjectProvider.class));
            realService = realSchemaService();
            realJob = newJob(realService);
        }

        @AfterEach
        void stopRealJob() {
            realJob.releaseOnShutdown();
        }

        private ConnectorSchemaService realSchemaService() throws Exception {
            Constructor<?> ctor = null;
            for (Constructor<?> c : ConnectorSchemaService.class.getConstructors()) {
                if (ctor == null || c.getParameterCount() > ctor.getParameterCount()) {
                    ctor = c;
                }
            }
            Class<?>[] types = ctor.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == ConnectorGateway.class) {
                    args[i] = gateway;
                } else if (types[i] == ConnectionMapper.class) {
                    args[i] = connectionMapper;
                } else if (types[i] == ConnectorRegistry.class) {
                    args[i] = registry;
                } else if (types[i] == ConnectorProperties.class) {
                    args[i] = properties;
                } else {
                    args[i] = mock(types[i]);
                }
            }
            return (ConnectorSchemaService) ctor.newInstance(args);
        }

        /**
         * ★ {@link ConnectorHealthJob#BUSY_MARK} 的注释说「refresh() 今天带着 cause，文案兜底接不到任何东西」。
         * 谁把 cause 丢了，这条红——繁忙从此只能靠文案认，那段注释也跟着不对了。
         */
        @Test
        @DisplayName("★ refresh() 把网关的拒绝转成 ServiceException 时带着 cause：繁忙按错误码认，不靠文案")
        void refresh转换时带着cause() {
            when(ops.increment(anyString())).thenReturn(61L);   // 默认每分钟 60
            TenantContext.set("t1");

            ServiceException thrown = assertThrows(ServiceException.class, () -> realService.refresh(100L));

            assertTrue(thrown.getCause() instanceof ConnectorException ce
                            && ce.getCode() == ConnectorErrorCode.RATE_LIMITED,
                    "refresh() 转换时丢了 cause，实际 cause: " + thrown.getCause());
        }

        @Test
        @DisplayName("★ 平台速率桶满了 → 繁忙，只记繁忙标记（冷却 + 前科）")
        void 平台速率桶满了是繁忙() {
            when(ops.increment(anyString())).thenReturn(61L);   // 默认每分钟 60

            ConnectorHealthJob.RefreshTally t = realJob.refreshDueSchemas();

            assertEquals(1, t.busy, "真实的限流拒绝没被认成繁忙——网关文案或 refresh() 的转换改过了？");
            assertEquals(0, t.failed);
            verify(bucketFor(100L)).set(any(), eq(ConnectorHealthJob.busyMemoryMinutes(600_000L, 6)),
                    eq(TimeUnit.MINUTES));
            verify(bucketFor(100L), never()).set(any(), anyLong(), eq(TimeUnit.HOURS));
        }

        /** ★ 需求里点名的那一种：并发许可被占着、等 2 秒拿不到。这是「忙但健康」，不是故障。 */
        @Test
        @DisplayName("★ 每实例并发许可等不到 → 繁忙，只记繁忙标记（冷却 + 前科）")
        void 并发许可等不到是繁忙() throws Exception {
            properties.getLimit().setPerInstanceConcurrency(1);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                TenantContext.set("t1");
                try {
                    gateway.executeAsPlatform(100L, Capability.DESCRIBE, ConnectorAuditService.OP_SCHEMA_REFRESH, s -> {
                        entered.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } finally {
                    TenantContext.clear();
                }
            }, "permit-holder");
            holder.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                ConnectorHealthJob.RefreshTally t = realJob.refreshDueSchemas();

                assertEquals(1, t.busy, "真实的许可超时没被认成繁忙——网关文案或 refresh() 的转换改过了？");
                assertEquals(0, t.failed);
                verify(bucketFor(100L)).set(any(), eq(ConnectorHealthJob.busyMemoryMinutes(600_000L, 6)),
                        eq(TimeUnit.MINUTES));
                verify(bucketFor(100L), never()).set(any(), anyLong(), eq(TimeUnit.HOURS));
            } finally {
                release.countDown();
                holder.join(5_000L);
            }
        }

        @Test
        @DisplayName("连不上 → 失败，记冷却（反例：不能什么都认成繁忙）")
        void 连不上是失败() {
            when(mysql.open(any())).thenThrow(ConnectorException.of(ConnectorErrorCode.UNREACHABLE, null));

            ConnectorHealthJob.RefreshTally t = realJob.refreshDueSchemas();

            assertEquals(0, t.busy);
            assertEquals(1, t.failed);
            verify(bucketFor(100L)).set(any(), eq(6L), eq(TimeUnit.HOURS));
        }
    }
}
