package com.jimeng.dataserver.ai.connector.generation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.dataserver.ai.connector.service.SemanticCoverage;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSemanticGeneration;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticGenerationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 长时语义层生成对 {@code connection.semantic_*} 的唯一写入口。
 *
 * <p>连接认领用秒精度时间戳作 CAS 凭据。凭据既会由心跳线程续期，也会被编排线程拿来写说明或终态，
 * 所以所有连接写都在本对象上串行，并且每次都从 {@link #currentCredential} 读取最新值。
 */
@Slf4j
@Component
public class SemanticConnectionClaim {

    static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private static final int NOTE_MAX = 500;
    private static final String RUNNING = "RUNNING";

    private final ConnectionMapper connectionMapper;
    private final ConnectorSemanticGenerationMapper generationMapper;
    private final Clock clock;
    private final AtomicReference<Credential> currentCredential = new AtomicReference<>();
    private final AtomicBoolean lost = new AtomicBoolean(false);

    @Autowired
    public SemanticConnectionClaim(ConnectionMapper connectionMapper,
                                   ConnectorSemanticGenerationMapper generationMapper) {
        this(connectionMapper, generationMapper, Clock.systemDefaultZone());
    }

    SemanticConnectionClaim(ConnectionMapper connectionMapper,
                            ConnectorSemanticGenerationMapper generationMapper,
                            Clock clock) {
        this.connectionMapper = connectionMapper;
        this.generationMapper = generationMapper;
        this.clock = clock;
    }

    /**
     * 认领连接；准入条件逐项沿用旧推导路径，只额外允许续跑批次接管自己保存的旧凭据。
     * 写库异常与 CAS 失败都返回 {@code null}，长时生成绝不按「无并发」继续。
     */
    public synchronized Credential claim(Long connectorId, Date previousClaimAt, String note) {
        try {
            Connection before = connectionMapper.selectById(connectorId);
            if (before == null) {
                return null;
            }
            Date now = now();
            Date staleBefore = new Date(now.getTime() - STALE_AFTER.toMillis());
            Connection update = new Connection();
            update.setSemanticStatus(RUNNING);
            update.setSemanticClaimAt(now);
            update.setSemanticNote(clip(note));

            LambdaUpdateWrapper<Connection> where = new LambdaUpdateWrapper<Connection>()
                    .eq(Connection::getId, connectorId)
                    .and(w -> {
                        w.ne(Connection::getSemanticStatus, RUNNING)
                                .or().isNull(Connection::getSemanticStatus)
                                .or().isNull(Connection::getSemanticClaimAt)
                                .or().lt(Connection::getSemanticClaimAt, staleBefore);
                        if (previousClaimAt != null) {
                            w.or().eq(Connection::getSemanticClaimAt, previousClaimAt);
                        }
                    });
            if (connectionMapper.update(update, where) == 0) {
                return null;
            }
            Credential credential = new Credential(connectorId, now, before.getSemanticStatus());
            currentCredential.set(credential);
            lost.set(false);
            return credential;
        } catch (RuntimeException e) {
            log.warn("语义层 agent 认领连接失败 connectorId={}: {}", connectorId, safeMessage(e));
            return null;
        }
    }

    /** 达到指定年龄才续期；未到期也返回 {@code true}，表示当前凭据仍可继续使用。 */
    @Transactional(rollbackFor = Exception.class)
    public synchronized boolean renewIfDue(Long generationId, String ownerToken, Duration age) {
        Credential credential = currentCredential.get();
        if (credential == null || lost.get()) {
            lost.set(true);
            return false;
        }
        if (clock.millis() - credential.claimAt().getTime() < age.toMillis()) {
            return true;
        }
        return renewNow(generationId, ownerToken, credential);
    }

    /**
     * 在同一事务里先换连接凭据，再以批次 id + ownerToken 同步批次凭据。
     */
    @Transactional(rollbackFor = Exception.class)
    public synchronized boolean renew(Long generationId, String ownerToken) {
        Credential credential = currentCredential.get();
        if (credential == null || lost.get()) {
            lost.set(true);
            return false;
        }
        return renewNow(generationId, ownerToken, credential);
    }

    private boolean renewNow(Long generationId, String ownerToken, Credential credential) {
        Date next = nextCredentialAfter(credential.claimAt());
        try {
            Connection update = new Connection();
            update.setSemanticClaimAt(next);
            int connectionRows = connectionMapper.update(update, new LambdaUpdateWrapper<Connection>()
                    .eq(Connection::getId, credential.connectorId())
                    .eq(Connection::getSemanticStatus, RUNNING)
                    .eq(Connection::getSemanticClaimAt, credential.claimAt()));
            if (connectionRows == 0) {
                Connection live = connectionMapper.selectById(credential.connectorId());
                if (live == null || !next.equals(live.getSemanticClaimAt())) {
                    lost.set(true);
                    return false;
                }
            }

            ConnectorSemanticGeneration generationUpdate = new ConnectorSemanticGeneration();
            generationUpdate.setClaimAt(next);
            int generationRows = generationMapper.update(generationUpdate,
                    new LambdaUpdateWrapper<ConnectorSemanticGeneration>()
                            .eq(ConnectorSemanticGeneration::getId, generationId)
                            .eq(ConnectorSemanticGeneration::getOwnerToken, ownerToken));
            if (generationRows == 0) {
                markRollbackOnly();
                lost.set(true);
                return false;
            }
            currentCredential.set(new Credential(credential.connectorId(), next,
                    credential.previousSemanticStatus()));
            return true;
        } catch (RuntimeException e) {
            lost.set(true);
            throw e;
        }
    }

    /** 用当前最新凭据 CAS 更新连接上的进度说明。 */
    public synchronized boolean writeNote(Long connectorId, String note) {
        Credential credential = currentCredential.get();
        if (credential == null || !credential.connectorId().equals(connectorId) || lost.get()) {
            lost.set(true);
            return false;
        }
        try {
            Connection update = new Connection();
            update.setSemanticNote(clip(note));
            int rows = connectionMapper.update(update, new LambdaUpdateWrapper<Connection>()
                    .eq(Connection::getId, connectorId)
                    .eq(Connection::getSemanticStatus, RUNNING)
                    .eq(Connection::getSemanticClaimAt, credential.claimAt()));
            if (rows == 0) {
                lost.set(true);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            lost.set(true);
            throw e;
        }
    }

    /**
     * 释放连接级认领并写入终态（或 STAGED 需要恢复的上一状态）。
     *
     * <p>只有仍持有内存中的最新凭据才写得进去；失败路径不清凭据，便于调用方把它识别为 lost，
     * 也绝不尝试用无条件 UPDATE 修补。{@code semantic_synced_at} 只在明确要求时盖当前时间，
     * FAILED/恢复上一版等路径传 {@code false}，从而保留「上次成功」的原值。
     */
    public synchronized boolean release(Long connectorId, String targetStatus, String note,
                                        boolean stampSemanticSyncedAt) {
        return release(connectorId, targetStatus, note, stampSemanticSyncedAt, null);
    }

    /**
     * 同上，外加本次生成「是不是全本」的判定。
     *
     * @param coverage {@code null} = 这条出路不重判覆盖面，{@code semantic_coverage / semantic_gaps}
     *                 两列原样不动（失败、中断、恢复上一版都走这条：那时库里还是上一版说明书，
     *                 这两列描述的也该还是上一版）。非 null 时<b>和 note 装在同一个实体里，
     *                 同一条 UPDATE 落库</b>——分两次写，中间失败就会留下一行「散文说残缺、
     *                 结构化信号说完整」的记录，那比没有信号更坏：它让人相信一个错的答案。
     */
    public synchronized boolean release(Long connectorId, String targetStatus, String note,
                                        boolean stampSemanticSyncedAt, SemanticCoverage.Verdict coverage) {
        Credential credential = currentCredential.get();
        if (credential == null || !credential.connectorId().equals(connectorId) || lost.get()) {
            lost.set(true);
            return false;
        }
        try {
            Connection update = new Connection();
            update.setSemanticStatus(targetStatus);
            update.setSemanticNote(clip(note));
            if (stampSemanticSyncedAt) {
                update.setSemanticSyncedAt(now());
            }
            if (coverage != null) {
                // 两列一起装。完整时 gaps 是空串而不是 null——MyBatis-Plus 的 NOT_NULL 策略写不了 null，
                // 留 null 会把上一轮的成因码留在行上，配出一行「说自己完整却列着缺口」的记录。
                update.setSemanticCoverage(coverage.code());
                update.setSemanticGaps(coverage.gapCodes());
            }
            int rows = connectionMapper.update(update, new LambdaUpdateWrapper<Connection>()
                    .eq(Connection::getId, connectorId)
                    .eq(Connection::getSemanticStatus, RUNNING)
                    .eq(Connection::getSemanticClaimAt, credential.claimAt()));
            if (rows == 0) {
                lost.set(true);
                return false;
            }
            currentCredential.set(null);
            return true;
        } catch (RuntimeException e) {
            lost.set(true);
            throw e;
        }
    }

    /**
     * 锁住连接行；与结构刷新逐字使用同一种 {@code SELECT ... FOR UPDATE}，供后续提交事务保持统一锁序。
     */
    public void lockRow(Long connectorId) {
        connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .eq(Connection::getId, connectorId)
                .last("FOR UPDATE"));
    }

    public Credential currentCredential() {
        return currentCredential.get();
    }

    public boolean lost() {
        return lost.get();
    }

    private Date now() {
        return Date.from(clock.instant().truncatedTo(ChronoUnit.SECONDS));
    }

    private Date nextCredentialAfter(Date previous) {
        Date candidate = now();
        return candidate.after(previous) ? candidate : new Date(previous.getTime() + 1_000L);
    }

    private static String clip(String note) {
        if (note == null) {
            return null;
        }
        String safe = note.replace('｜', '|');
        return safe.length() <= NOTE_MAX ? safe : safe.substring(0, NOTE_MAX);
    }

    private static void markRollbackOnly() {
        try {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        } catch (NoTransactionException ignored) {
            // 纯单元测试直接 new 时没有 Spring 代理；生产调用始终经过 @Transactional 代理。
        }
    }

    private static String safeMessage(RuntimeException e) {
        return e.getClass().getSimpleName();
    }

    /** 当前连接认领凭据，以及认领前状态（STAGED 失败/中断时要据此恢复）。 */
    public record Credential(Long connectorId, Date claimAt, String previousSemanticStatus) {
        public Credential {
            claimAt = copy(claimAt);
        }

        @Override
        public Date claimAt() {
            return copy(claimAt);
        }

        private static Date copy(Date value) {
            return value == null ? null : new Date(value.getTime());
        }
    }
}
