package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.businessview.BusinessViewGenerator;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorEnrichmentState;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorEnrichmentStateMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.fold;

/**
 * 语义层补全链（数据星图设计 v3 §4）：
 * <pre>
 *   ① 关系发现：规则候选 + 整库模型一遍 → 新增或替换 JOIN 行
 *   ② 采样核对：派发原有的采样核对，范围 = 触发方原本要核对的表 ∪ ① 动过的表（agent 路径跳过）
 *   ③ 业务文字：业务领域 → 对象名称与说明 → 关系角色名；只补缺的和输入变了的
 * </pre>
 * ② 是异步派发的，沿用它自己的闸门和补跑机制，和 ③ 同时进行：一个查客户库，一个调模型，互不依赖。
 *
 * <h3>一条连接同一时刻只跑一条</h3>
 * 靠 {@code connector_enrichment_state.claim_at} 认领（多副本也只有一个拿得到），30 分钟过期，跑的过程中每步续期；
 * 认领丢了就中止、不写结果。运行中又来了触发不排队：跑完后由定时对账按输入指纹发现变化再补跑。
 * 但触发方原本要派发的采样核对不能因此丢——抢不到认领时先把它派出去。
 *
 * <h3>定时对账</h3>
 * 每 10 分钟一次，每次最多派发一个连接：输入指纹与上次尝试时不同（从没跑过的也算）、或上次失败且已过 6 小时。
 * 指纹不含采样核对的结论（{@code verified}），否则每轮核对完都会触发一次重跑；只含「是否已核对通过」这一位，
 * 因为一条终点不是唯一键的关系核对通过后就会画出来，需要角色名。
 */
@Slf4j
@Component
public class SemanticEnrichmentService {

    static final long CLAIM_STALE_MINUTES = 30L;
    static final long FAILED_RETRY_HOURS = 6L;
    static final int NOTE_MAX = 500;

    private final ConnectorEnrichmentStateMapper stateMapper;
    private final ConnectionMapper connectionMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorSemanticMapper semanticMapper;
    private final SemanticRelationDiscovery discovery;
    private final BusinessViewGenerator generator;
    private final ConnectorSemanticDeriveService deriveService;
    private final ConnectorProperties properties;
    /** 字段名与构造器参数名必须叫 semanticStageExecutor：容器里有多个 ThreadPoolTaskExecutor，靠名字消歧。 */
    private final ThreadPoolTaskExecutor semanticStageExecutor;
    private final Clock clock;

    @Autowired
    public SemanticEnrichmentService(ConnectorEnrichmentStateMapper stateMapper, ConnectionMapper connectionMapper,
                                     ConnectorSchemaMapper schemaMapper, ConnectorSemanticMapper semanticMapper,
                                     SemanticRelationDiscovery discovery, BusinessViewGenerator generator,
                                     ConnectorSemanticDeriveService deriveService, ConnectorProperties properties,
                                     ThreadPoolTaskExecutor semanticStageExecutor) {
        this(stateMapper, connectionMapper, schemaMapper, semanticMapper, discovery, generator, deriveService,
                properties, semanticStageExecutor, Clock.systemDefaultZone());
    }

    SemanticEnrichmentService(ConnectorEnrichmentStateMapper stateMapper, ConnectionMapper connectionMapper,
                              ConnectorSchemaMapper schemaMapper, ConnectorSemanticMapper semanticMapper,
                              SemanticRelationDiscovery discovery, BusinessViewGenerator generator,
                              ConnectorSemanticDeriveService deriveService, ConnectorProperties properties,
                              ThreadPoolTaskExecutor semanticStageExecutor, Clock clock) {
        this.stateMapper = stateMapper;
        this.connectionMapper = connectionMapper;
        this.schemaMapper = schemaMapper;
        this.semanticMapper = semanticMapper;
        this.discovery = discovery;
        this.generator = generator;
        this.deriveService = deriveService;
        this.properties = properties;
        this.semanticStageExecutor = semanticStageExecutor;
        this.clock = clock;
    }

    private boolean enabled() {
        return properties.getSemantic().getEnrichment().isEnabled();
    }

    // ------------------------------------------------------------------ 触发

    /** 推导成功、增量补写成功、agent 定稿之后发来的请求。只派发，不在调用线程上跑。 */
    @EventListener
    public void onRequest(SemanticEnrichmentRequest request) {
        if (request == null || request.connectorId() == null || request.tenantId() == null) {
            return;
        }
        if (!enabled()) {
            // 推导侧发事件之后开关才被关掉：原本要派发的采样核对照样派出去，别让它夹在中间丢了。
            dispatchOriginalValidation(request);
            return;
        }
        submit(request);
    }

    /** @return 是否真的交给了线程池。池满被拒绝不报错：原本要派的采样核对先派出去，补全链等下一轮对账。 */
    boolean submit(SemanticEnrichmentRequest request) {
        try {
            semanticStageExecutor.execute(MdcAsyncSupport.wrap("semantic-enrich-" + request.connectorId(),
                    () -> run(request)));
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("补全链没有派发出去（后台队列已满），等下一轮定时对账 connectorId={}", request.connectorId());
            dispatchOriginalValidation(request);
            return false;
        }
    }

    // ------------------------------------------------------------------ 一轮

    void run(SemanticEnrichmentRequest request) {
        Long connectorId = request.connectorId();
        if (!request.tenantId().equals(TenantContext.get())) {
            TenantContext.set(request.tenantId());
        }
        ConnectorEnrichmentState state = ensureState(connectorId, request.tenantId());
        Date claimedAt = now();
        if (state == null || stateMapper.claim(request.tenantId(), connectorId, claimedAt,
                new Date(claimedAt.getTime() - Duration.ofMinutes(CLAIM_STALE_MINUTES).toMillis())) != 1) {
            log.info("补全链：这条连接已有一轮在跑，本次不排队，跑完后由定时对账补跑 connectorId={}", connectorId);
            dispatchOriginalValidation(request);
            return;
        }
        Claim claim = new Claim(state.getId(), claimedAt);
        String before = null;
        String passFingerprint = state.getRelationPassFingerprint();
        String relationNote = null;
        String viewNote = null;
        boolean validationDispatched = false;
        try {
            // 收尾记的是这一刻的输入指纹，不是跑完时的：跑的过程中别处落下的改动（又重新生成了语义层、刷新了结构、
            // 业务方改了终点）那次触发抢不到认领，只能靠定时对账发现变化再补跑，跑完时的指纹会把它们一并吞掉。
            // 代价是这一轮自己写下的关系也算「变化」，紧跟着会多一轮空跑：那一轮模型指纹没变、不问模型，业务文字也没有缺的。
            before = fingerprint(connectorId);
            SemanticRelationDiscovery.Result rel = discovery.discover(connectorId, passFingerprint);
            relationNote = rel.note();
            passFingerprint = rel.passFingerprint();
            dispatchValidation(request, rel.write().touchedObjects());
            validationDispatched = true;
            heartbeat(claim);
            BusinessViewGenerator.Result view = generator.generate(connectorId, () -> heartbeat(claim));
            viewNote = view.note();
            String status = rel.modelError() == null
                    ? ConnectorEnrichmentState.STATUS_READY : ConnectorEnrichmentState.STATUS_FAILED;
            release(claim, status, before, passFingerprint, relationNote, viewNote);
            log.info("补全链完成 connectorId={} 触发={} 结果={}；关系：{}；业务文字：{}", connectorId,
                    request.trigger(), status, relationNote, viewNote);
        } catch (ClaimLostException e) {
            log.warn("补全链的认领被别处接走了（跑得太久过了期），这一轮不写结果 connectorId={}", connectorId);
            if (!validationDispatched) {
                dispatchMissedValidation(request);
            }
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("补全链失败，6 小时后或输入变化时重试 connectorId={}: {}", connectorId, reason, e);
            if (!validationDispatched) {
                dispatchMissedValidation(request);
            }
            release(claim, ConnectorEnrichmentState.STATUS_FAILED, before, passFingerprint,
                    relationNote != null ? relationNote : "失败：" + reason,
                    viewNote != null ? viewNote : (relationNote != null ? "失败：" + reason : null));
        }
    }

    /**
     * 补全链在派发采样核对之前就停下了：触发方原本要派发的核对照原样派出去。推导成功之后本来一定会派这一次，
     * 定时对账补跑时只核对它自己动过的表，补不回来；不派的话这批关系就一直是「没验过」，和「验过都成立」在库里长得一样。
     * 派发本身再出错只记日志，不能挡住后面的收尾（否则认领要挂满 30 分钟）。
     */
    private void dispatchMissedValidation(SemanticEnrichmentRequest request) {
        try {
            dispatchOriginalValidation(request);
        } catch (RuntimeException e) {
            log.warn("补全链没跑完，补派原本的采样核对也失败了 connectorId={}", request.connectorId(), e);
        }
    }

    /**
     * ②：派发原有的采样核对。范围 = 触发方原本要核对的表 ∪ 关系发现动过的表；原本就是全部（整体推导）时仍是全部。
     * agent 路径原来就不做采样核对，保持现状；范围为空不派发。
     */
    private void dispatchValidation(SemanticEnrichmentRequest request, List<String> touched) {
        if (request.trigger() == SemanticEnrichmentRequest.Trigger.AGENT) {
            return;
        }
        Set<String> scope = null;
        if (request.validationScope() != null) {
            scope = new LinkedHashSet<>(request.validationScope());
            for (String t : touched) {
                scope.add(fold(t));
            }
            if (scope.isEmpty()) {
                return;
            }
        }
        deriveService.dispatchValidation(request.connectorId(), request.tenantId(), request.deriveNote(), scope);
    }

    /** 补全链没跑成（开关关着、池满、别人在跑）时，把触发方原本就要派发的采样核对照原样派出去。 */
    private void dispatchOriginalValidation(SemanticEnrichmentRequest request) {
        if (request.validates()) {
            deriveService.dispatchValidation(request.connectorId(), request.tenantId(), request.deriveNote(),
                    request.validationScope());
        }
    }

    // ------------------------------------------------------------------ 定时对账

    @Scheduled(fixedDelayString = "${connector.semantic.enrichment.reconcile-interval-ms:600000}", initialDelay = 300_000L)
    public void scheduledReconcile() {
        try {
            reconcileOnce();
        } catch (Exception e) {
            // 调度线程上抛出去没有人接，只会让下一轮看起来莫名其妙地没跑。
            log.warn("补全链定时对账失败，下一轮再来", e);
        }
    }

    /**
     * 挑第一个需要补跑的连接派发出去（按连接 id 升序）。
     *
     * @return 派发了的连接 id；这一轮没有要补跑的为 {@code null}
     */
    Long reconcileOnce() {
        if (!enabled()) {
            return null;
        }
        // 选人是跨租户的，所以 runAsSystem；算指纹要读租户隔离表，得回到那一行的真实租户下（同 ConnectorHealthJob）。
        List<Connection> ready = TenantContext.runAsSystem(() -> connectionMapper.selectList(
                new LambdaQueryWrapper<Connection>()
                        .eq(Connection::getSemanticStatus, ConnectorSemanticDeriveService.SEM_READY)
                        .orderByAsc(Connection::getId)));
        Map<Long, ConnectorEnrichmentState> states = new HashMap<>();
        for (ConnectorEnrichmentState s : TenantContext.runAsSystem(() -> stateMapper.selectList(null))) {
            states.put(s.getConnectorId(), s);
        }
        Date now = now();
        for (Connection c : ready == null ? List.<Connection>of() : ready) {
            if (c.getTenantId() == null) {
                continue;
            }
            ConnectorEnrichmentState state = states.get(c.getId());
            if (state != null && claimAlive(state, now)) {
                continue;
            }
            SemanticEnrichmentRequest request = SemanticEnrichmentRequest.reconcile(c.getId(), c.getTenantId());
            boolean dispatched;
            try {
                dispatched = asTenant(c.getTenantId(), () -> due(c.getId(), state, now) && submit(request));
            } catch (RuntimeException e) {
                // 一条连接出错（比如快照里有一行坏数据）只跳过它：连接按 id 升序走，往外抛的话，持续出错的那一条
                // 会让排在它后面的所有租户的连接永远轮不到，而且没有任何提示。
                log.warn("补全链定时对账：判断这条连接要不要补跑时出错，跳过 connectorId={}", c.getId(), e);
                continue;
            }
            if (dispatched) {
                log.info("补全链定时对账：派发 connectorId={}（{}）", c.getId(),
                        state == null || state.getLastStatus() == null ? "从没跑完过" : "输入变了或失败待重试");
                return c.getId();
            }
        }
        return null;
    }

    private boolean due(Long connectorId, ConnectorEnrichmentState state, Date now) {
        if (state == null || state.getLastStatus() == null) {
            return true;
        }
        if (!fingerprint(connectorId).equals(state.getInputFingerprint())) {
            return true;
        }
        return ConnectorEnrichmentState.STATUS_FAILED.equals(state.getLastStatus()) && state.getLastAttemptAt() != null
                && state.getLastAttemptAt().getTime() <= now.getTime() - Duration.ofHours(FAILED_RETRY_HOURS).toMillis();
    }

    private static boolean claimAlive(ConnectorEnrichmentState state, Date now) {
        return state.getClaimAt() != null
                && state.getClaimAt().getTime() > now.getTime() - Duration.ofMinutes(CLAIM_STALE_MINUTES).toMillis();
    }

    private static <T> T asTenant(String tenantId, Supplier<T> body) {
        String previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    // ------------------------------------------------------------------ 输入指纹

    /**
     * 补全链的输入指纹：结构快照里每个对象的名称、content_hash、唯一键、外键；语义层 OBJECT 行的说明；
     * JOIN 行的两端、状态、是否已核对通过、业务方结论。只读需要的列：{@code detail_json} 整份读出来一张宽表就是几十 KB。
     */
    String fingerprint(Long connectorId) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> row : schemaMapper.selectMaps(new QueryWrapper<ConnectorSchema>()
                .select("object_name", "content_hash",
                        "JSON_EXTRACT(detail_json, '$.extra.unique_keys') AS uk",
                        "JSON_EXTRACT(detail_json, '$.extra.foreign_keys') AS fk")
                .eq("connector_id", connectorId))) {
            lines.add("S|" + row.get("object_name") + '|' + row.get("content_hash") + '|' + row.get("uk") + '|'
                    + row.get("fk"));
        }
        for (ConnectorSemantic s : semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                .select(ConnectorSemantic::getScope, ConnectorSemantic::getObjectName, ConnectorSemantic::getFieldName,
                        ConnectorSemantic::getGloss, ConnectorSemantic::getDetailJson, ConnectorSemantic::getStatus,
                        ConnectorSemantic::getVerified)
                .eq(ConnectorSemantic::getConnectorId, connectorId)
                .in(ConnectorSemantic::getScope, ConnectorSemanticService.SCOPE_OBJECT,
                        ConnectorSemanticService.SCOPE_JOIN))) {
            if (ConnectorSemanticService.SCOPE_OBJECT.equals(s.getScope())) {
                lines.add("O|" + fold(s.getObjectName()) + '|' + s.getGloss());
                continue;
            }
            Map<String, Object> d = detail(s.getDetailJson());
            lines.add("J|" + fold(s.getObjectName()) + '|' + fold(s.getFieldName()) + '|'
                    + d.get(ConnectorSemanticService.KEY_TO_OBJECT) + '|' + d.get(ConnectorSemanticService.KEY_TO_COLUMN)
                    + '|' + s.getStatus() + '|' + ConnectorSemanticService.V_CONFIRMED.equals(s.getVerified()) + '|'
                    + d.get(ConnectorSemanticService.KEY_HUMAN_VERDICT));
        }
        lines.sort(null);
        return ConnectorSemanticService.sha256(String.join("\n", lines));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(json, Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ 认领与状态

    /** 状态行不存在就建一行（并发建撞唯一键时重读）。 */
    private ConnectorEnrichmentState ensureState(Long connectorId, String tenantId) {
        ConnectorEnrichmentState state = selectState(connectorId);
        if (state != null) {
            return state;
        }
        ConnectorEnrichmentState fresh = new ConnectorEnrichmentState();
        fresh.setTenantId(tenantId);
        fresh.setConnectorId(connectorId);
        try {
            stateMapper.insert(fresh);
            return fresh;
        } catch (DuplicateKeyException e) {
            return selectState(connectorId);
        }
    }

    private ConnectorEnrichmentState selectState(Long connectorId) {
        return stateMapper.selectOne(new LambdaQueryWrapper<ConnectorEnrichmentState>()
                .eq(ConnectorEnrichmentState::getConnectorId, connectorId)
                .last("limit 1"));
    }

    /** 续期：认领时间仍是自己写下的那个才续得上；续不上说明过期被别人接走了，中止这一轮。 */
    private void heartbeat(Claim claim) {
        Date next = now();
        if (next.equals(claim.claimedAt)) {
            // 同一秒内不必续；而且驱动若按「实际改动行数」计数，写一个相同的值会报 0 行，被误判成认领丢了。
            return;
        }
        int n = stateMapper.update(null, new LambdaUpdateWrapper<ConnectorEnrichmentState>()
                .eq(ConnectorEnrichmentState::getId, claim.stateId)
                .eq(ConnectorEnrichmentState::getClaimAt, claim.claimedAt)
                .set(ConnectorEnrichmentState::getClaimAt, next));
        if (n != 1) {
            throw new ClaimLostException();
        }
        claim.claimedAt = next;
    }

    private void release(Claim claim, String status, String inputFingerprint, String passFingerprint,
                         String relationNote, String viewNote) {
        int n = stateMapper.update(null, new LambdaUpdateWrapper<ConnectorEnrichmentState>()
                .eq(ConnectorEnrichmentState::getId, claim.stateId)
                .eq(ConnectorEnrichmentState::getClaimAt, claim.claimedAt)
                .set(ConnectorEnrichmentState::getClaimAt, null)
                .set(ConnectorEnrichmentState::getLastStatus, status)
                .set(ConnectorEnrichmentState::getInputFingerprint, inputFingerprint)
                .set(ConnectorEnrichmentState::getRelationPassFingerprint, passFingerprint)
                .set(ConnectorEnrichmentState::getFinishedAt, now())
                .set(ConnectorEnrichmentState::getRelationNote, clip(relationNote))
                .set(ConnectorEnrichmentState::getViewNote, clip(viewNote)));
        if (n != 1) {
            log.warn("补全链收尾时认领已经不是自己的了，结果不写 stateId={}", claim.stateId);
        }
    }

    /** DATETIME 只到秒：认领时间截到秒再写，之后按它比对才对得上。 */
    private Date now() {
        return Date.from(Instant.now(clock).truncatedTo(ChronoUnit.SECONDS));
    }

    private static String clip(String s) {
        return s == null || s.length() <= NOTE_MAX ? s : s.substring(0, NOTE_MAX - 1) + "…";
    }

    private static final class Claim {
        private final Long stateId;
        private Date claimedAt;

        private Claim(Long stateId, Date claimedAt) {
            this.stateId = stateId;
            this.claimedAt = claimedAt;
        }
    }

    /** 认领过期被别处接走：这一轮的结果不能再写。 */
    static final class ClaimLostException extends RuntimeException {
        ClaimLostException() {
            super("补全链的认领已被别处接走", null, false, false);
        }
    }
}
