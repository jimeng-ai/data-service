package com.jimeng.dataserver.ai.connector.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.ConnectionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 定时健康探测：让「连接坏了」在界面上可见，而不是等某次对话里 Agent 答错才发现。
 *
 * <h3>★ 只 ping，不做接入探测那三步</h3>
 * 接入探测是 探活 → <b>验只读</b> → 探能力，其中验只读会往客户库发一条
 * {@code UPDATE ... WHERE 1 = 0}（靠权限拒绝来证明账号写不了）。那是<b>录入时验一次</b>的动作。
 * 每 5 分钟往客户的生产库发一次写尝试，无论多无害都不可接受——客户的 DBA 看到审计日志
 * 会先来找我们。所以这里只调 {@link ConnectorSession#ping()}。
 *
 * <p>推论：本任务<b>绝不触碰</b> {@code readonly_verified_at} 和 {@code capability_flags}。
 * 那两个字段的含义是「接入时验过/探过」，由完整探测负责。健康探测把它们刷掉，
 * 等于把一次网络抖动说成「只读验证失效了」。
 *
 * <h3>健康态不参与放行判定</h3>
 * {@code ConnectorGateway} 不检查 health——一次瞬时抖动把连接标成 UNHEALTHY 就拦掉正常查询，
 * 代价大于收益。健康态的作用是<b>显示</b>：管理台上看得见，以及经 {@code conn_list} 给到模型，
 * 让它在调用前先向用户说明「这条连接当前状态异常」。
 *
 * <h3>三个执行期约束</h3>
 * <ul>
 *   <li><b>跨租户</b>：定时线程里没有 {@code TenantContext}，必须 {@code runAsSystem}，
 *       否则租户拦截器回落到 {@code __no_tenant__} 哨兵，一行都扫不到<b>而且不报错</b>。</li>
 *   <li><b>多副本</b>：这是对<b>客户外部系统</b>的调用，每个副本各扫一遍就是 N 倍的外部流量。
 *       用 Redis 锁让同一时刻只有一个副本在扫。拿不到锁就安静跳过——不是错误。</li>
 *   <li><b>单条失败不能中断整轮</b>：一条连不上的连接不该让它后面的连接都得不到探测。</li>
 * </ul>
 *
 * <p>顺带一提：{@code MyMetaObjectHandler} 从请求头取 user-id 填 update_user，
 * 定时线程里没有请求，所以本任务写回的行 {@code update_user} 是 null。这是预期的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorHealthJob {

    private static final String LOCK_KEY = "connector:health:sweep";

    private final ConnectionMapper connectionMapper;
    private final ConnectorRegistry registry;
    private final ConnectorInstanceLoader loader;
    private final ConnectorProperties properties;
    private final RedissonClient redissonClient;

    /**
     * initialDelay 给足 2 分钟：启动瞬间就去连一圈客户的库没有意义，
     * 而且那会儿本进程自己的连接池、Nacos 配置都还在预热。
     */
    @Scheduled(fixedDelayString = "${connector.health.interval-ms:300000}", initialDelay = 120_000L)
    public void sweep() {
        if (!properties.getHealth().isEnabled()) {
            return;
        }
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            // waitTime=0：抢不到就是别的副本正在扫，直接放弃本轮，不排队。
            // leaseTime 用整轮超时上限，防止本副本崩了之后锁永远不释放。
            acquired = lock.tryLock(0, properties.getHealth().getSweepTimeoutSeconds(), TimeUnit.SECONDS);
            if (!acquired) {
                log.debug("连接器健康探测：另一个副本正在执行，本轮跳过");
                return;
            }
            TenantContext.runAsSystem(this::doSweep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 定时任务抛出去会被 Spring 吞掉且只打一行栈，这里自己记清楚。
            log.error("连接器健康探测整轮失败", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doSweep() {
        List<Connection> rows = connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .eq(Connection::getStatus, "ACTIVE"));
        if (rows.isEmpty()) {
            return;
        }
        int ok = 0;
        int bad = 0;
        for (Connection row : rows) {
            // 隧道未实现，探了也只会永远失败，没必要每轮都去撞一次。
            String transport = row.getTransport();
            if (transport != null && !transport.isBlank() && !"direct".equalsIgnoreCase(transport)) {
                continue;
            }
            if (probeOne(row)) {
                ok++;
            } else {
                bad++;
            }
        }
        if (bad > 0) {
            log.warn("连接器健康探测完成：{} 正常，{} 异常", ok, bad);
        } else {
            log.debug("连接器健康探测完成：{} 正常", ok);
        }
    }

    /** @return 是否健康 */
    private boolean probeOne(Connection row) {
        try {
            ConnectorInstance inst = loader.load(row);
            Connector connector = registry.require(inst.kind());
            try (ConnectorSession session = connector.open(inst)) {
                session.ping();
            }
            writeBack(row, "HEALTHY", null);
            return true;
        } catch (ConnectorException e) {
            // safeDetail 已脱敏，可以直接写进库并展示给客户。
            writeBack(row, "UNHEALTHY",
                    e.getSafeDetail() == null ? e.getCode().title() : e.getSafeDetail());
            return false;
        } catch (Exception e) {
            // 没归类的异常：原始信息只进日志，写回库的文案用固定句子。
            log.warn("连接器健康探测出现未归类异常 connectorId={} kind={}", row.getId(), row.getKind(), e);
            writeBack(row, "UNHEALTHY", "探测失败，请在管理台点「测试连接」查看详情");
            return false;
        }
    }

    /**
     * 只写健康三件套。<b>刻意用 LambdaUpdateWrapper 精确列更新，而不是 updateById(entity)</b>：
     * 后者会把实体上的全部非空字段一起写回，而这一行是几分钟前查出来的快照——
     * 期间有人在管理台改过配置的话，就会被这次探测悄悄覆盖回去。
     */
    private void writeBack(Connection row, String state, String reason) {
        try {
            connectionMapper.update(null, new LambdaUpdateWrapper<Connection>()
                    .eq(Connection::getId, row.getId())
                    .set(Connection::getHealthState, state)
                    .set(Connection::getHealthCheckedAt, new Date())
                    .set(Connection::getHealthReason, reason));
        } catch (Exception e) {
            log.warn("连接器健康态写回失败 connectorId={}", row.getId(), e);
        }
    }
}
