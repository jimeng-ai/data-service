package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connection.CredentialCipher;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.generation.SemanticGenerationCleanup;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorInstanceLoader;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProbeService;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ParamField;
import com.jimeng.dataserver.ai.connector.spi.WritePolicy;
import com.jimeng.persistence.entity.AgentConnection;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.mapper.AgentConnectionMapper;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 连接器实例的录入与维护（类型感知）。
 *
 * <h3>与 ConnectionService 的关系：两个入口，一张表</h3>
 * 旧的 {@code ConnectionService} / {@code /data/admin/connections} <b>原样保留</b>，
 * 它服务沙箱 egress 那条链路，写出来的行 {@code kind} 默认是 {@code HTTP}。
 * 本类是类型感知的新入口，覆盖全部 kind。两者读写<b>同一张 {@code connection} 表</b>——
 * 这正是「演化而不是并存」这个决策的落点：两个 API 入口，一张表，<b>一套横切</b>。
 *
 * <h3>★ HTTP 类型必须双写</h3>
 * {@code kind=HTTP} 时，参数除了写进 {@code config_json}，还要<b>同时写回旧列</b>
 * （{@code base_url} / {@code auth_scheme} / {@code allow_methods} / {@code allow_paths}）。
 * 因为 {@code ConnectionResolver}（下发给沙箱边车的那条路）读的是旧列——不双写，
 * 从新界面建的 HTTP 连接在沙箱里<b>就是不存在的</b>，而且不报错。
 * 这是「演化」必须付的代价，<b>改这里之前先想清楚沙箱那条路会不会断</b>。
 *
 * <h3>新建必须探测通过才准保存</h3>
 * 配错的东西必须<b>当场报错</b>，而不是等到 Agent 回答不对时才发现。
 * 这个系统吃过好几次同一个形状的亏：配错了不报错，只是悄悄降级成某种能用但不对的状态，
 * 排查成本远大于修复成本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorService {

    /** 与 egress 代理的 {@code /api/<name>/} 路由正则一致，也是模型调工具时的寻址键。 */
    private static final Pattern NAME_RE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final ConnectionMapper connectionMapper;
    private final AgentConnectionMapper agentConnectionMapper;
    private final ConnectorSchemaMapper connectorSchemaMapper;
    private final ConnectorRegistry registry;
    private final ConnectorInstanceLoader loader;
    private final ConnectorProbeService probeService;
    private final CustomerDataSourceManager dataSourceManager;
    private final CredentialCipher cipher;

    /**
     * 档位从第 3 档降下来时删掉库里存着的真实取值（契约 K-5）。
     *
     * <p><b>不会成环</b>：它只注入两个 mapper，是条叶子。放在字段列表末尾：按位置构造本类的测试不会静默错位。
     */
    private final ConnectorSemanticService semanticService;

    /** {@link #update} 用的编程式事务。为什么不用注解，见那个方法的注释。 */
    private final PlatformTransactionManager transactionManager;

    /**
     * 档位调到第 3 档之后派发一轮采样验证。
     *
     * <p>★ 必须是 {@link ObjectProvider}：推导链上的 {@code SemanticValueProfiler} 构造期注入了本类，
     * 直接注入推导服务就闭合 {@code ConnectorService → ConnectorSemanticDeriveService → SemanticValueProfiler → ConnectorService}，
     * 启动即失败。与 {@code ConnectorSchemaService} 对它的处理同一个办法。
     */
    private final ObjectProvider<ConnectorSemanticDeriveService> semanticDerive;

    /** 删除连接时在同一事务内清理暂存产物并取消未结束批次；放末尾避免位置构造静默错位。 */
    private final ObjectProvider<SemanticGenerationCleanup> semanticGenerationCleanup;

    private volatile TransactionTemplate txTemplate;

    /**
     * 试连用的合成实例 id。
     *
     * <p><b>为什么不能用真实 id，两个理由都致命：</b>
     * <ul>
     *   <li>新建时<b>根本没有</b> id——连接池按 {@code connection.id} 分桶，
     *       传 null 直接 NPE（这个坑踩过一次）。</li>
     *   <li>编辑时<b>更不能用</b>真实 id——那会拿【未保存的】参数去替换掉正在服务的连接池，
     *       一次失败的试连就能把线上正在跑的查询打断。</li>
     * </ul>
     * 用递减的负数：雪花 id 恒为正，永不冲突；每次调用取一个新的，并发试连之间也不会互相顶掉池。
     */
    private final java.util.concurrent.atomic.AtomicLong probeIdSeq = new java.util.concurrent.atomic.AtomicLong(-1);

    // ================================================================ 类型元数据

    /**
     * 所有连接器类型 + 各自的表单 schema。
     *
     * <p><b>这是「新增一种连接器类型，前端零改动」的兑现点。</b>前端按这份 schema 渲染表单，
     * 不硬编码任何字段名、不写 {@code if (kind === 'MYSQL')}。
     * 如果哪天前端不得不为某个类型写分支，那说明 {@code ParamSpec} 表达力不够，
     * 该扩的是 {@code ParamField}，不是在前端加 if。
     */
    public List<Map<String, Object>> kinds() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Connector c : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", c.kind());
            m.put("displayName", c.displayName());
            m.put("capabilities", c.declaredCapabilities().stream()
                    .map(x -> x.name().toLowerCase(Locale.ROOT)).toList());
            m.put("fields", c.paramSpec().toFormSchema());
            out.add(m);
        }
        return out;
    }

    // ================================================================ 读

    public List<ConnectorView> list() {
        return connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                        .orderByDesc(Connection::getCreateTime))
                .stream().map(this::toView).collect(Collectors.toList());
    }

    public ConnectorView get(Long id) {
        return toView(requireRow(id));
    }

    /**
     * 「这条连接允许我读到哪一层？」——数据出库档位的<b>唯一查询入口</b>。
     *
     * <p>后续要去客户库里做事的代码（S3 采样验证算包含率、将来取 top-k 维值）在动手之前问这里，
     * 拿到 {@link SemanticDataTier} 之后问谓词：{@code allowsDerivedStats()} /
     * {@code allowsSampleValues()}。<b>不要自己去读 {@code connection.semantic_data_tier} 这个字符串</b>，
     * 更不要把它换算成 1/2/3 再比大小——兜底规则（空值 → 默认档、认不出来 → 最严档）
     * 只在 {@link SemanticDataTier#parse(String)} 里有一份，绕过去就等于绕过了兜底。
     *
     * <p>走 {@link #requireRow(Long)} 是刻意的：它带租户过滤，连接不存在或不属于本租户直接抛。
     * 一个「查权限」的方法如果能跨租户查到别人的连接，那它查回来的许可也是别人的。
     * 后台线程调用时必须已经由 {@code MdcAsyncSupport.wrap} 带上 TenantContext——
     * 这与推导链路上的其它 selectById 是同一条前提。
     */
    public SemanticDataTier dataTier(Long connectorId) {
        return SemanticDataTier.parse(requireRow(connectorId).getSemanticDataTier());
    }

    // ================================================================ 写

    /**
     * 新建并落库。
     *
     * <h3>★ 语义层推导<b>不在这里</b>发起，在 {@code ConnectorAdminController.create} 里，本方法返回之后</h3>
     * 「接入即触发、接入时零人工」要求建完连接就自动去推导说明书，但推导<b>必须发生在本事务提交之后</b>。
     * 在这里（事务内）调 {@code deriveAsync} 会踩两个坑，而且两个都不报错：
     * <ul>
     *   <li><b>后台线程读不到这一行。</b>{@code insert} 还没提交，推导线程用自己的连接去查
     *       {@code connection}，查不到——{@code requireOwned} 抛「连接不存在」，日志里留下一条
     *       看起来像 bug 的报错，而 {@code semantic_status} 永远停在 NONE。谁都不会发现。</li>
     *   <li><b>更糟的是探不过回滚的那一支。</b>下面 {@code probeOrThrow} 失败会回滚这一行，
     *       但推导任务已经派出去了，它会围着一个根本不存在的连接跑一圈。</li>
     * </ul>
     *
     * <p>本仓库没有任何 after-commit 钩子。备选是
     * {@code TransactionSynchronizationManager.registerSynchronization(...)} 的 afterCommit，
     * <b>没有采用</b>：afterCommit 回调跑在事务已提交、但 {@code cleanupAfterCompletion} 还没执行的
     * 窗口里，此时 {@code TransactionSynchronizationManager} 的连接仍然绑着、
     * {@code isTransactionActive()} 仍然是 true。只要 {@code deriveAsync} 哪天不是真异步
     * （而它在<b>另一个类</b>里，本类管不着），它内部的 {@code @Transactional(REQUIRED)} 就会
     * 「加入」这个已经提交完的事务，写下去的行永远不会被再提交一次——<b>静默丢失</b>。
     * 控制器那一层没有事务，无论 {@code deriveAsync} 是不是真异步都不会错。
     * 用一个依赖别人实现细节才成立的正确性，换一点代码位置上的内聚，不划算。
     *
     * <p>代价说清楚：今天 {@code ConnectorService} 只被那一个控制器注入，所以不存在漏网的调用方。
     * <b>将来若新增第二条建连接的入口（批量导入之类），必须在那里同样补上派发</b>，
     * 否则从新入口建的连接会没有语义层，而且不报错。
     */
    @Transactional
    public ConnectorView create(ConnectorUpsert req) {
        validateBasics(req, true);
        Connector connector = registry.require(req.getKind());

        Connection row = new Connection();
        row.setKind(connector.kind());
        row.setStatus("ACTIVE");
        row.setTransport("direct");
        apply(row, req, connector, null);

        // ★ 必须先 insert 再探测，顺序不能反。
        //
        // 客户库连接池按 connection.id 分桶（CustomerDataSourceManager 的 Map key），而雪花 id
        // 是 MyBatis-Plus 在 insert 时才回填的。先探测的话 inst.id() 是 null，acquire 直接 NPE，
        // 表现为「新建任何一条数据库连接都失败，且报错文案是没用的兜底句」。
        //
        // 「探不过就不落库」这条约束由事务保证：下面抛异常会回滚这一行。
        connectionMapper.insert(row);
        try {
            ConnectorProbeService.ProbeReport report = probeOrThrow(row);
            writeProbeResult(row, report);
            connectionMapper.updateById(row);
        } catch (RuntimeException e) {
            // 连接池不在事务里，回滚不会带走它。不显式作废就会留下一个指向已回滚行的池，
            // 直到空闲回收才消失——期间它还占着客户库的连接。
            dataSourceManager.invalidate(row.getId());
            throw e;
        }
        log.info("创建连接器实例 id={} kind={} name={}", row.getId(), row.getKind(), row.getName());
        return toView(row);
    }

    /**
     * 试连：按表单里的参数实际连一次，<b>不落库</b>。
     *
     * <p>与「创建并验证」跑的是同一套 {@link ConnectorProbeService} 三步探测（探活 → 验只读 → 探能力），
     * 所以这里过了，创建就一定过——不存在「试连说行、创建又说不行」的分叉。
     *
     * <p>用完立刻销毁连接池：一条可能永远不会被创建的连接，不该在客户库上留一个常驻的池。
     *
     * @param existingId 编辑态传原连接 id，用于「敏感参数留空 = 沿用原值」；新建传 null
     */
    public ProbeOutcome dryRun(ConnectorUpsert req, Long existingId) {
        if (req == null || req.getKind() == null || !registry.supports(req.getKind())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "不支持的连接器类型：" + (req == null ? null : req.getKind()));
        }
        Connector connector = registry.require(req.getKind());

        Connection row = new Connection();
        row.setKind(connector.kind());
        row.setStatus("ACTIVE");
        row.setTransport("direct");
        // 名字只是占位：试连不落库，也不参与寻址。给个固定值免得 apply 里的空值判断绕圈。
        row.setName(req.getName() == null || req.getName().isBlank() ? "__probe__" : req.getName().trim());
        // 试连不落库，tenantId 只影响日志与池名，但还是填上——排查时能看出是哪个租户在试。
        row.setTenantId(TenantContext.get());

        Connection existing = existingId == null ? null : requireRow(existingId);
        if (existing != null && !ConnectorRegistry.normalize(existing.getKind()).equals(connector.kind())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "不能用另一种类型的参数试连这条连接");
        }
        apply(row, req, connector, existing);

        // 合成 id 只在这一次调用内有效。
        Long probeId = probeIdSeq.getAndDecrement();
        row.setId(probeId);
        try {
            ConnectorInstance inst = loader.load(row);
            ConnectorProbeService.ProbeReport report = probeService.probe(inst);
            return ProbeOutcome.builder()
                    .ok(report.ok())
                    .failureReason(report.failureReason())
                    .capabilities(report.capabilities() == null ? List.of()
                            : report.capabilities().stream().map(Capability::name).toList())
                    .readonlyVerified(report.readOnly() != null && report.readOnly().acceptable())
                    .readonlyUndetermined(report.readOnly() != null && report.readOnly().undetermined())
                    .readonlyDetail(report.readOnly() == null ? null : report.readOnly().detail())
                    .build();
        } catch (ConnectorException e) {
            // 参数本身就有问题（主机非法、缺凭据…）——这也是试连要回答的问题，不该抛成 500。
            return ProbeOutcome.builder()
                    .ok(false)
                    .failureReason(e.getSafeDetail() == null ? e.getCode().title() : e.getSafeDetail())
                    .capabilities(List.of())
                    .build();
        } finally {
            // 池不在事务里，也不会被别人回收——必须显式销毁，否则每点一次「测试连接」
            // 就在客户库上留一个池，直到空闲回收才消失。
            dataSourceManager.invalidate(probeId);
        }
    }

    /**
     * 编辑并落库。
     *
     * <h3>★ 数据出库档位变了，事情不在 connection 这一行上结束</h3>
     * <ul>
     *   <li><b>结果档位不允许真实取值</b>（从第 3 档降下来，或者本来就不在第 3 档）：在<b>同一个事务里</b>删掉语义层存着的真实取值
     *       （{@link ConnectorSemanticService#purgeSampleValues}）。客户收回授权，意思是他的取值不该再留在我们库里；
     *       只改档位不删，那些取值集合与判别值会一直躺着，而读出侧的拦截只保证「不给模型看」。
     *       删不掉就让这次编辑失败——档位与删除同生共死，不存在「界面上已经降档、库里取值还在」的中间态。
     *       本来就不在第 3 档时也删一遍：把此前的残留（改动上线之前降过档的连接）一起清掉，没有残留时只是一次查询。</li>
     *   <li><b>从第 3 档降下来</b>：事务提交之后再删一遍。事务里那一遍读的是快照，事务期间恰好写回来的采集结果它看不见。</li>
     *   <li><b>调到第 3 档</b>：事务提交之后派发一轮不限范围的采样验证。在读不到取值的档位下验过的多态外键，
     *       要等这一轮才会回头按判别值分组重探；不派发的话，这件事只有等有人去点「验证表关系」才会发生，而没有任何提示让人去点。</li>
     * </ul>
     *
     * <h3>为什么这里用编程式事务，不用 {@code @Transactional}</h3>
     * 派发出去的验证线程读档位读的是<b>已提交</b>的值：在事务里派发，它读到的还是旧档位，重探一条都不会发生，也不报错。
     * {@link #create} 的注释解释过为什么不用 afterCommit 回调（正确性要依赖另一个类的派发一定是真异步）。
     * 编程式事务在 {@code execute} 返回时已经提交并清理完，之后再做的事与事务无关。
     * 只有被外层事务包着调用时（今天没有这样的调用方）才退回 afterCommit，否则就是在外层提交之前派发。
     */
    public ConnectorView update(Long id, ConnectorUpsert req) {
        TierChange[] change = new TierChange[1];
        ConnectorView view = tx().execute(status -> {
            Connection row = requireRow(id);
            boolean samplesBefore = SemanticDataTier.parse(row.getSemanticDataTier()).allowsSampleValues();
            // kind 不可改：config_json 的形状是按类型定的，改 kind 等于把一堆 MySQL 参数
            // 交给 HTTP 连接器去解释。要换类型就删了重建。
            if (req.getKind() != null && !req.getKind().isBlank()
                    && !ConnectorRegistry.normalize(req.getKind()).equals(ConnectorRegistry.normalize(row.getKind()))) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                        "不能修改连接器类型。请删除后重新创建");
            }
            validateBasics(req, false);
            Connector connector = registry.require(row.getKind());
            apply(row, req, connector, row);

            ConnectorProbeService.ProbeReport report = probeOrThrow(row);
            writeProbeResult(row, report);

            connectionMapper.updateById(row);
            boolean samplesAfter = SemanticDataTier.parse(row.getSemanticDataTier()).allowsSampleValues();
            if (!samplesAfter) {
                int purged = semanticService.purgeSampleValues(id);
                if (purged > 0) {
                    log.info("数据出库档位不允许真实取值，已删除语义层里存着的取值 id={} 行数={}", id, purged);
                }
            }
            // 参数或凭据可能变了，旧池必须作废——否则改了密码之后旧池还在用旧凭据，
            // 表现为「改了没生效」，而且要等池自然过期才恢复。
            dataSourceManager.invalidate(id);
            change[0] = new TierChange(samplesBefore, samplesAfter);
            log.info("更新连接器实例 id={} kind={} name={}", id, row.getKind(), row.getName());
            return toView(row);
        });
        afterTierChangeCommitted(id, change[0]);
        return view;
    }

    /** 档位变化在事务提交之后要做的事。理由见 {@link #update}。 */
    private void afterTierChangeCommitted(Long id, TierChange change) {
        if (change == null || change.samplesBefore() == change.samplesAfter()) {
            return;
        }
        Runnable after = change.samplesAfter() ? () -> dispatchValidationQuietly(id) : () -> purgeAgainQuietly(id);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    after.run();
                }
            });
        } else {
            after.run();
        }
    }

    /** 降档提交之后的第二遍删除。失败只记日志：第一遍已经在事务里删过，结构刷新还会再扫。 */
    private void purgeAgainQuietly(Long id) {
        try {
            int n = semanticService.purgeSampleValues(id);
            if (n > 0) {
                log.warn("数据出库档位下调提交后又删到了真实取值（事务期间有采集结果写回） id={} 行数={}", id, n);
            }
        } catch (Exception e) {
            log.warn("数据出库档位下调提交后的第二遍删除失败，结构刷新时会再扫 id={}", id, e);
        }
    }

    /**
     * 调到第 3 档之后派发一轮不限范围的采样验证。失败只记日志、不让一次成功的编辑报错：
     * 派发本身落不下去时（队列满）推导服务会把「没派发出去」写进语义层状态说明，管理台看得见。
     */
    private void dispatchValidationQuietly(Long id) {
        try {
            ConnectorSemanticDeriveService derive = semanticDerive == null ? null : semanticDerive.getIfAvailable();
            if (derive == null) {
                log.warn("数据出库档位已调到第 3 档，但推导服务不可用，没有派发采样验证：多态外键不会按判别值重探 id={}", id);
                return;
            }
            derive.validateAsync(id);
            log.info("数据出库档位已调到第 3 档，已派发一轮采样验证 id={}", id);
        } catch (Exception e) {
            log.warn("数据出库档位已调到第 3 档，但派发采样验证失败：多态外键不会按判别值重探，可在管理台点「验证表关系」 id={}",
                    id, e);
        }
    }

    private TransactionTemplate tx() {
        TransactionTemplate t = txTemplate;
        if (t == null) {
            t = new TransactionTemplate(transactionManager);
            txTemplate = t;
        }
        return t;
    }

    /** 一次编辑前后「允不允许真实取值」。只在本类内部流转。 */
    private record TierChange(boolean samplesBefore, boolean samplesAfter) {
    }

    /** 重新探测并回填。给管理台的「测试连接」按钮用。 */
    @Transactional
    public ConnectorView test(Long id) {
        Connection row = requireRow(id);
        ConnectorProbeService.ProbeReport report = probeService.probe(loader.load(row));
        writeProbeResult(row, report);
        connectionMapper.updateById(row);
        if (!report.ok()) {
            // 探测失败不抛异常：这是「测试」接口，失败本身就是它要返回的结果。
            // 抛异常会让前端拿不到回填后的健康态。
            log.info("连接器测试未通过 id={} reason={}", id, report.failureReason());
        }
        return toView(row);
    }

    @Transactional
    public void setStatus(Long id, String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "status 只能是 ACTIVE / DISABLED");
        }
        Connection row = requireRow(id);
        row.setStatus(status);
        connectionMapper.updateById(row);
        if (!"ACTIVE".equals(status)) {
            dataSourceManager.invalidate(id);
        }
    }

    @Transactional
    public void delete(Long id) {
        Connection row = requireRow(id);
        SemanticGenerationCleanup cleanup = semanticGenerationCleanup == null
                ? null : semanticGenerationCleanup.getIfAvailable();
        if (cleanup != null) {
            cleanup.onConnectorDeleted(row.getTenantId(), id);
        }
        // 顺序照抄 ConnectionService.delete 的理由：先摘授权再删连接。反过来的话，
        // 中间失败会留下指向不存在连接的授权行，而解析那一步对这种行只会跳过——又一处静默失效。
        agentConnectionMapper.delete(new LambdaQueryWrapper<AgentConnection>()
                .eq(AgentConnection::getConnectionId, id));
        // 自描述缓存走物理删除：它的唯一键不含 deleted，软删的行会占住键位，
        // 下次同名连接建起来刷新 schema 就撞键。
        // 注意这条 SQL 绕过了租户拦截器，所以上面 requireRow(id) 的租户校验是它的前提。
        connectorSchemaMapper.physicalDeleteByConnector(id);
        connectionMapper.deleteById(id);
        dataSourceManager.invalidate(id);
        log.info("删除连接器实例 id={} name={}", id, row.getName());
    }

    // ================================================================ 内部

    private Connection requireRow(Long id) {
        // 租户过滤由 MyBatis 拦截器注入（connection 在 TENANT_AWARE_TABLES 白名单里）。
        Connection row = connectionMapper.selectById(id);
        if (row == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        }
        return row;
    }

    private void validateBasics(ConnectorUpsert req, boolean creating) {
        if (req == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "请求体不能为空");
        }
        if (creating) {
            if (req.getKind() == null || !registry.supports(req.getKind())) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                        "不支持的连接器类型：" + req.getKind());
            }
            if (req.getName() == null || !NAME_RE.matcher(req.getName().trim()).matches()) {
                throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                        "name 必须匹配 ^[A-Za-z0-9_-]{1,64}$（它会直接出现在 URL 路径里，也是模型寻址用的键）");
            }
        }
        if (req.getTransport() != null && !req.getTransport().isBlank()
                && !"direct".equalsIgnoreCase(req.getTransport())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "内网隧道（tunnel）尚未实现，当前只支持 direct");
        }
        // 数据出库档位：留空是合法的（= 默认档），但【填了个认不出来的值必须当场报错】。
        // 这里刻意不复用 SemanticDataTier.parse 的宽松兜底：那条兜底是给库里的脏值准备的
        //（没人可问，只能往严的方向猜），而入参是有人可问的——把超管拼错的档位悄悄降一档存下去，
        // 界面上显示的还是他刚选的那个，就成了一次不报错的配置失效。
        if (req.getSemanticDataTier() != null && !req.getSemanticDataTier().isBlank()
                && SemanticDataTier.tryParse(req.getSemanticDataTier()).isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    "semanticDataTier 只能是 METADATA_ONLY / DERIVED_STATS / SAMPLE_VALUES，收到："
                            + req.getSemanticDataTier());
        }
    }

    /**
     * 把录入值落到实体上。<b>校验全部走 {@code ParamSpec.validate()}</b>——
     * 不在这里写一套 if 判断，否则每加一种类型都要改本方法，直接违反「加一种类型只写一个实现类」。
     *
     * @param existing 编辑时的原行，用于「敏感参数留空 = 沿用原值」
     */
    private void apply(Connection row, ConnectorUpsert req, Connector connector, Connection existing) {
        if (req.getName() != null && !req.getName().isBlank()) {
            row.setName(req.getName().trim());
        }
        if (req.getDisplayName() != null) {
            row.setDisplayName(req.getDisplayName());
        }
        // 写策略：留空 / 认不出来一律落 FORBIDDEN（见 WritePolicy.parse）。
        // 编辑时不传等于「改成只读」——这是刻意的：一个决定「能不能改客户数据」的开关，
        // 省略它的语义只能是最严的那个，不能是「保持原样」。
        row.setWritePolicy(WritePolicy.parse(req.getWritePolicy()).name());
        // 数据出库档位：与写策略同一条规则——留空落到【默认档】（DERIVED_STATS），不是「保持原样」。
        //
        // ★ 后果要认下来：编辑一条已经开了第 3 档的连接时不传这个字段，它会被降回第 2 档。
        // 这是两害相权取的那个：
        //   * 降错了 —— 关系推断变差，有人来问「为什么最近答得不准了」，吵闹，能被发现；
        //   * 留错了 —— 客户的真实取值继续出库，而没有任何人再决定过一次，安静，发现不了。
        // 让「省略」等于「沿用」还会造出一个没人审计得到的粘性状态：此后每一次改显示名的保存
        // 都在默默给第 3 档续期，事后谁也说不清当初是谁把它打开的。
        // 前端的编辑表单必须把详情接口返回的 semanticDataTier 原样回填再提交。
        // ★ 降下来的那一刻 update() 会删掉语义层里存着的真实取值——「降错了」的代价因此还包括重新采集一遍。
        //
        // 认不出来的值走不到这里：validateBasics 已经在上面 400 拒绝了。
        // parse 在这里只剩「空 → 默认档」这一条兜底，且它永远到不了 SAMPLE_VALUES。
        row.setSemanticDataTier(SemanticDataTier.parse(req.getSemanticDataTier()).name());

        Map<String, Object> incoming = req.getParams() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(req.getParams());
        Set<String> secretNames = Set.copyOf(connector.paramSpec().secretNames());

        // 编辑时敏感参数留空 = 沿用原值。为了让 validate 通过，先把「原来有值」这件事补进去：
        // 用一个占位串参与校验，随后再剔除——不能把真实密文或明文放进来，它会流进校验失败的错误文案。
        boolean reuseSecret = false;
        if (existing != null) {
            for (String s : secretNames) {
                Object v = incoming.get(s);
                if (v == null || (v instanceof String str && str.isBlank())) {
                    if (existing.getCredentialCipher() != null && !existing.getCredentialCipher().isBlank()) {
                        incoming.put(s, "__KEEP__");
                        reuseSecret = true;
                    }
                }
            }
        }

        List<String> errs = connector.paramSpec().validate(incoming);
        if (!errs.isEmpty()) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, String.join("；", errs));
        }

        // 非敏感 → config_json
        Map<String, Object> nonSecret = connector.paramSpec().normalizeNonSecret(incoming);
        row.setConfigJson(toJson(nonSecret));

        // 敏感 → 密文
        if (!reuseSecret) {
            String plaintext = buildSecretPayload(secretNames, incoming);
            if (plaintext != null) {
                if (!cipher.isAvailable()) {
                    throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                            "未配置 CONNECTION_CREDENTIAL_KEY，无法保存凭据（不会退化为明文存储）");
                }
                row.setCredentialCipher(cipher.encrypt(plaintext));
                row.setEncryptionVersion(CredentialCipher.CURRENT_VERSION);
            }
        }

        applyLegacyColumnsForHttp(row, connector, nonSecret);
    }

    /**
     * 多个敏感参数时序列化成一段 JSON 再整体加密；<b>单个时直接存那个值</b>。
     *
     * <p>单值不套 JSON 是刻意的：现有的 HTTP 连接（沙箱那条路）存的就是裸令牌，
     * {@code ConnectionResolver} 直接把它当 token 下发。套上 JSON 会让所有存量行解不出来。
     */
    private String buildSecretPayload(Set<String> secretNames, Map<String, Object> incoming) {
        if (secretNames.isEmpty()) {
            return null;
        }
        if (secretNames.size() == 1) {
            Object v = incoming.get(secretNames.iterator().next());
            return v == null ? null : String.valueOf(v);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (String s : secretNames) {
            Object v = incoming.get(s);
            if (v != null) m.put(s, v);
        }
        return m.isEmpty() ? null : toJson(m);
    }

    /**
     * ★ HTTP 类型双写旧列。见类注释——不写，沙箱那条路就看不到这条连接，且不报错。
     */
    private void applyLegacyColumnsForHttp(Connection row, Connector connector, Map<String, Object> params) {
        if (!"HTTP".equalsIgnoreCase(connector.kind())) {
            return;
        }
        row.setBaseUrl(str(params.get(ConnectorInstanceLoader.P_BASE_URL)));
        row.setAuthScheme(str(params.getOrDefault(ConnectorInstanceLoader.P_AUTH_SCHEME, "bearer")));
        Object methods = params.get(ConnectorInstanceLoader.P_ALLOW_METHODS);
        row.setAllowMethods(methods instanceof List<?> l && !l.isEmpty()
                ? l.stream().map(String::valueOf).map(s -> s.toUpperCase(Locale.ROOT))
                   .distinct().collect(Collectors.joining(","))
                : "GET");
        Object paths = params.get(ConnectorInstanceLoader.P_ALLOW_PATHS);
        row.setAllowPaths(paths instanceof List<?> l && !l.isEmpty() ? toJson(l) : null);
    }

    private ConnectorProbeService.ProbeReport probeOrThrow(Connection row) {
        ConnectorInstance inst;
        try {
            inst = loader.load(row);
        } catch (ConnectorException e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    e.getSafeDetail() == null ? e.getCode().title() : e.getSafeDetail());
        }
        ConnectorProbeService.ProbeReport report = probeService.probe(inst);
        if (!report.ok()) {
            // 探测失败的文案已经在 ProbeService 里做成可操作的了（「请改用只读账号」「请检查 host 和端口」）。
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, report.failureReason());
        }
        return report;
    }

    private void writeProbeResult(Connection row, ConnectorProbeService.ProbeReport report) {
        Date now = new Date();
        row.setHealthState(report.ok() ? "HEALTHY" : "UNHEALTHY");
        row.setHealthCheckedAt(now);
        row.setHealthReason(report.ok() ? null : report.failureReason());
        row.setCapabilityFlags(report.capabilities() == null || report.capabilities().isEmpty()
                ? null
                : report.capabilities().stream().map(Capability::name).collect(Collectors.joining(",")));
        if (report.readOnly() != null && report.readOnly().acceptable()) {
            row.setReadonlyVerifiedAt(now);
        } else if (!report.ok()) {
            // 探测没过就把只读验证时间清掉：留着一个旧的通过时间，会让界面显示「已验证只读」，
            // 而实际这条连接现在的状态是未知的。
            row.setReadonlyVerifiedAt(null);
        }
    }

    private ConnectorView toView(Connection row) {
        final Map<String, Object> params = new LinkedHashMap<>();
        try {
            params.putAll(loader.readParams(row));
        } catch (ConnectorException e) {
            // 参数坏了也要能看到这条连接（否则用户连删都删不掉），但要显式告诉他坏了。
            params.clear();
            params.put("__error__", "参数已损坏，无法解析。请重新保存配置");
        }
        String kind = ConnectorInstanceLoader.normalizeKind(row.getKind());
        WritePolicy policy = WritePolicy.parse(row.getWritePolicy());
        SemanticDataTier tier = SemanticDataTier.parse(row.getSemanticDataTier());
        // 保险起见再剔一遍敏感字段：loader 只读 config_json 与旧列，理论上不含敏感值，
        // 但「理论上不含」不是可以不删的理由——这类地方漏一次就是凭据出网。
        registry.find(kind).ifPresent(c -> c.paramSpec().secretNames().forEach(params::remove));

        return ConnectorView.builder()
                .id(row.getId() == null ? null : String.valueOf(row.getId()))
                .name(row.getName())
                .displayName(row.getDisplayName())
                .kind(kind)
                .kindLabel(registry.find(kind).map(Connector::displayName).orElse(kind))
                .params(params)
                .transport(row.getTransport() == null ? "direct" : row.getTransport())
                .status(row.getStatus())
                .writePolicy(policy.name())
                .writePolicyLabel(policy.label())
                .capabilities(row.getCapabilityFlags() == null || row.getCapabilityFlags().isBlank()
                        ? List.of()
                        : List.of(row.getCapabilityFlags().split(",")))
                .healthState(row.getHealthState() == null ? "UNKNOWN" : row.getHealthState())
                .healthCheckedAt(row.getHealthCheckedAt())
                .healthReason(row.getHealthReason())
                .readonlyVerified(row.getReadonlyVerifiedAt() != null)
                .readonlyVerifiedAt(row.getReadonlyVerifiedAt())
                .createTime(row.getCreateTime())
                // 存量连接（语义层上线之前建的）这一列是 NULL。归一成 NONE 而不是原样透出 null：
                // 界面上「语义层：null」读不出任何意思，而「没跑过」是一个明确的、可操作的状态。
                .semanticStatus(row.getSemanticStatus() == null || row.getSemanticStatus().isBlank()
                        ? "NONE" : row.getSemanticStatus())
                .semanticSyncedAt(row.getSemanticSyncedAt())
                .semanticClaimAt(row.getSemanticClaimAt())
                .semanticNote(row.getSemanticNote())
                // 同样归一：存量行这一列是 NULL，parse 会把它落到默认档。界面上显示 null
                // 读不出任何意思，而「第 2 档 · 派生统计」外加那句出库说明，客户当场就知道自己在什么位置。
                .semanticDataTier(tier.name())
                .semanticDataTierLabel(tier.label())
                .semanticDataTierEgress(tier.egressStatement())
                .build();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private String toJson(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "参数序列化失败");
        }
    }

    /** 供 {@link ParamField} 的敏感字段判定使用，避免外部重复拼装。 */
    public Set<String> secretNamesOf(String kind) {
        return Set.copyOf(registry.require(kind).paramSpec().secretNames());
    }
}
