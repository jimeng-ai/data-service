package com.jimeng.dataserver.ai.connector.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.registry.ConnectorRegistry;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorInstanceLoader;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSchemaMapper;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自描述结果快照与<b>结构漂移检测</b>。
 *
 * <h3>为什么要单独存一份，而工具又不读它</h3>
 * 技术架构 §13 把自描述结果单列一张表，理由是它「可能很大、会独立刷新、<b>而且要能对比前后变化
 * ——客户加了一个字段，我们应该知道</b>」。第三条才是重点：语义层（指标口径、业务名、
 * 样例问答）都挂在具体的表和列上，客户悄悄改了结构，挂在上面的口径就跟着失效，
 * 而这件事今天没有任何机制会发现。
 *
 * <p><b>但 {@code conn_catalog} / {@code conn_describe} 仍然读实时的，不读这张表。</b>
 * 这是刻意的：实测 catalog 11ms、describe 6ms，没有性能问题要解决；
 * 而引入一层带有效期的缓存，等于为了一个不存在的问题新开一个「悄悄给出过期结构」的失败面——
 * 模型拿着过期结构写出的 SQL 会报「列不存在」，或者更糟，命中一个语义已经变了的同名列。
 * <b>正确性优先于省那几毫秒。</b>
 *
 * <p>所以这张表的三个真实用途是：结构漂移检测、语义层的挂载锚点（P2）、
 * 以及将来 DESCRIBE 能力降级时「人工录入的结构」的落脚处（技术架构 §7.4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorSchemaService {

    /**
     * 一次快照最多描述多少个对象。
     *
     * <p>不设上限的话，一个 500 张表的库就是 500 次 {@code information_schema} 查询打在
     * <b>客户的生产库</b>上。截断了要明说——静默只快照前 200 张，会让漂移检测对其余的表
     * 永远沉默，而「沉默」会被读成「没有变化」。
     *
     * <h3>★ 截断的是「后 300 个」，而「后」是按什么排的，决定了这条上限是能忍还是致命</h3>
     * 本方法<b>不自己排序</b>，它按 {@link CatalogView#entries()} 给的顺序取前 {@code MAX_OBJECTS} 个。
     * 从前 MySQL 连接器按表名升序返回，于是「前 200 个」= 表名字典序最靠前的 200 个——
     * 中文业务库里 {@code t_} 前缀极常见，实际效果是<b>整片 t_ 开头的核心业务表悄悄没有说明书</b>，
     * 而现象只是「模型答不上来」，没有任何报错指向这里。
     * 现在连接器按重要性（估算行数的数量级）返回，被砍掉的变成了最小的那一批，
     * 排序依据由连接器自己写在 {@link CatalogView#ordering()} 里。
     *
     * <p><b>为什么本方法不自己重排：</b>它手上只有 {@link CatalogEntry} 的名字和一句注释，
     * 行数是被拼进注释文本里的。要重排就得从「（约 N 行…）」这句话里把数字抠回来——
     * 那正是本类反复警告的「原件还在手上就不要去读复印件」。
     * 排序归做得了排序的那一层：连接器有统计信息，也只有它知道自己按什么排。
     */
    private static final int MAX_OBJECTS = 200;

    private final ConnectionMapper connectionMapper;
    private final ConnectorSchemaMapper schemaMapper;
    private final ConnectorRegistry registry;
    /**
     * 语义层的锚点重挂。<b>注入它不会成环</b>：{@code ConnectorSemanticService} 只注入
     * {@code ConnectorSemanticMapper} 和 {@code ConnectionMapper} 两个 mapper，是条叶子；
     * 会叫模型的推导在另一个类（{@code ConnectorSemanticDeriveService}）里，本类不碰。
     */
    private final ConnectorSemanticService semanticService;
    private final PlatformTransactionManager transactionManager;
    /**
     * 对客户库的那一次往返走网关，不再自己 {@code open()}。理由见 {@link #refresh} 的注释。
     *
     * <p><b>不会成环</b>：{@code ConnectorGateway} 依赖的是几个 mapper、注册表、实例加载器、
     * 审计与 Redis，没有一个回头依赖本类（它对 {@code PendingWriteService} 那条反向依赖
     * 已经用 {@code ObjectProvider} 断开了）。
     *
     * <p>放在字段列表<b>最后</b>，与 {@code ConnectorGateway} 里那条注释同理：
     * {@code @RequiredArgsConstructor} 按声明顺序生成构造器，插在中间会让按位置构造本类的地方
     * （测试）静默错位。
     */
    private final ConnectorGateway gateway;

    private TransactionTemplate txTemplate;

    @PostConstruct
    void initTx() {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 对象级变化。{@code details} 对 CHANGED 而言是列级差异。
     *
     * <p><b>刻意不是 record。</b>本仓库实际生效的 jackson-databind 是 <b>2.11.1</b>
     * （由 {@code logstash-logback-encoder:7.4} 传递带入，压过了 Spring Boot BOM），
     * 而 Java record 的序列化支持是 <b>2.12+</b> 才有的——2.11 会报
     * 「No serializer found ... no properties discovered」。
     * 任何要跨 HTTP 出去的 DTO 在这个仓库里都必须是 Lombok 类，不能是 record。
     * 内部用的 record（{@code model/*}）不受影响，它们从不直接序列化。
     */
    @Data
    @AllArgsConstructor
    public static class ObjectDiff {
        private String objectName;
        /** ADDED | REMOVED | CHANGED */
        private String change;
        private List<String> details;
    }

    /** 同上，不是 record。 */
    @Data
    @AllArgsConstructor
    public static class SnapshotResult {
        private int objectCount;
        private int totalObjects;
        /** 对象数超过上限，本次只覆盖了一部分 */
        private boolean truncated;
        /**
         * 截断说明；没截断时是 {@code null}。
         *
         * <p><b>一个布尔值不够。</b>看到「共 500 个、覆盖了 200 个」的人一定会自己脑补
         * 被砍掉的是哪 300 个，而在这条说明出现之前，正确答案是「表名排在后面的那 300 个」——
         * 一个谁都猜不到、且恰好会让 {@code t_} 开头的中文业务库整片消失的答案。
         * 现在顺序改成按重要性了，更要把顺序写在脸上：否则旧的误读会原样沿用到新行为上，
         * 变成「我以为漏的是小表，其实我根本不知道它按什么排」。
         */
        private String truncationNote;
        /** 第一次快照——此时「全是新增」没有信息量，界面不该把它当成结构漂移报警 */
        private boolean firstSnapshot;
        private List<ObjectDiff> diffs;
        private Date syncedAt;
        /**
         * 本次刷新把多少条语义标成了 STALE（挂的结构变了，那句话可能已经不成立）、又恢复了多少条。
         *
         * <p><b>{@code null} 表示这次漂移处置没跑成（原因见日志），不等于「没有变化」。</b>
         * 0 和「不知道」必须分得开——混成 0 会让界面把一次失败显示成一次平安无事，
         * 而语义层失效恰恰是那种不说就没人会发现的事。
         */
        private Integer semanticStaled;
        private Integer semanticRevived;
    }

    // ================================================================ 读

    /** 内部用：拿原始行做差异比对。 */
    List<ConnectorSchema> currentRows(Long connectorId) {
        return schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getConnectorId, connectorId)
                .orderByAsc(ConnectorSchema::getObjectName));
    }

    /** 对外用：视图 DTO，不含 tenantId，且 detail 已解析好。 */
    public List<ConnectorSchemaView> current(Long connectorId) {
        requireRow(connectorId);
        return currentRows(connectorId).stream().map(ConnectorSchemaService::toView).toList();
    }

    @SuppressWarnings("unchecked")
    private static ConnectorSchemaView toView(ConnectorSchema r) {
        List<Map<String, Object>> fields = List.of();
        String error = null;
        if (r.getDetailJson() != null && !r.getDetailJson().isBlank()) {
            try {
                Map<String, Object> m = CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
                Object f = m.get("fields");
                if (f instanceof List<?> list) {
                    fields = (List<Map<String, Object>>) list;
                }
                Object extra = m.get("extra");
                if (extra instanceof Map<?, ?> em && em.get("__error__") != null) {
                    error = String.valueOf(em.get("__error__"));
                }
            } catch (Exception e) {
                // 存量行的 JSON 坏了不该让整个列表打不开——如实标出来，让人看得见哪一行有问题。
                log.warn("解析结构快照失败 objectName={}", r.getObjectName(), e);
                error = "结构数据损坏，请重新刷新结构";
            }
        }
        return ConnectorSchemaView.builder()
                .objectType(r.getObjectType())
                .objectName(r.getObjectName())
                .objectComment(r.getObjectComment())
                .fields(fields)
                .error(error)
                .syncedAt(r.getSyncedAt())
                .build();
    }

    // ================================================================ 刷新

    /**
     * 重新拉取结构、与上一份快照对比、落库，再把语义层的锚点重挂一遍。
     *
     * <h3>★ 这是管理面操作，<b>但它现在走网关</b></h3>
     * 这里从前自己 {@code connector.open()}，注释写的是「这是管理面操作，不走网关」。
     * 那句话的前半截是真的（网关第 4 步判的是「这个 Agent 被授予了这条连接吗」，
     * 而管理面根本没有 Agent），后半截的结论是错的：为了绕开第 4 步，
     * 把第 7 步的每租户速率与每实例并发闸、第 9 步的审计<b>一起</b>绕过了。
     * 于是一次刷新打向客户生产库的 1 + N 次 {@code information_schema} 查询：
     * 既不占并发闸（同一条连接上可以有好几次刷新同时在跑，客户的库自己扛），
     * 也不留任何痕迹——客户的 DBA 拿着他自己的审计日志来问「这些查询是谁发的」，我们答不上来。
     *
     * <p>现在走 {@link ConnectorGateway#executeAsPlatform}：跳过的只有第 4 步，
     * 租户隔离照旧（网关自己再验一次这条连接属不属于当前租户），审计落在
     * {@link ConnectorAuditService#OP_SCHEMA_REFRESH} 这个<b>一眼看得出是平台干的</b>动作名下，
     * 而不是混进模型工具那套名字里。
     *
     * <h3>★ 刻意拆成三段：网络往返 / 落库事务 / 漂移处置。方法上<b>没有</b> {@code @Transactional}</h3>
     * 原来整个方法是一个事务，于是对客户库的 1 + N 次 {@code information_schema} 往返
     * （N 最多 200，跨公网，可能几十秒）全程占着<b>我们自己</b>连接池里的一条连接。现在只有
     * 「删旧行 + 插新行」进 {@code txTemplate}，原子性一点没丢，长事务没了。
     *
     * <p>更要紧的是<b>第三段必须在事务外</b>。{@code applyDrift} 自己带
     * {@code @Transactional(REQUIRED)}：如果它还在本方法的事务里跑，那么它一旦抛异常，
     * Spring 会把整个事务标成 rollback-only，<b>哪怕这里 catch 住了</b>，外层提交时照样抛
     * {@code UnexpectedRollbackException}——刚从客户库拉回来的结构快照跟着一起没了。
     * 「catch 一下就好」在这种嵌套事务里是个假的安全感。
     * 语义层是<b>记账</b>，结构快照是<b>事实</b>；记账失败绝不能把事实弄丢，所以顺序只能是
     * 先提交快照，再另起一个事务去重挂锚点。
     *
     * <p><b>改走网关没有把这个结构弄坏。</b>网关那一段（含并发闸的最长 2 秒等待、
     * 1 + N 次往返、审计落库）整个发生在第一段里，仍然在任何事务之外；
     * {@code txTemplate} 包着的还是只有「删旧行 + 插新行」。这条必须留着——
     * 把 {@code executeAsPlatform} 挪进第二段，等于把刚拆掉的那个长事务原样装回来。
     */
    public SnapshotResult refresh(Long connectorId) {
        Connection row = requireRow(connectorId);
        // 网关会再查一遍状态与 transport。这里自己那份留着，是为了管理台的话术——
        // 「连接已停用，无法拉取结构」比网关那句面向模型的通用文案更贴这个场景。
        // 两份检查只可能更严、不可能更松，不存在「一边放行一边拦」的分叉。
        assertUsable(row);

        // 「这种类型压根不提供自描述」必须在开连接之前判掉，而且必须是 OPERATION_UNSUPPORTED：
        // 语义层靠这个码把「对它不适用」（HTTP 只声明 INVOKE/HEALTH）与「真失败」分开，
        // 归错了会让每一条 HTTP 连接在界面上显示成「语义层：失败」。
        //
        // 用 normalizeKind(row.getKind()) 而不是 loader.load(row).kind()：只为读一个类型名
        // 就解密一次凭据，等于让明文凭据在内存里多出现一次，没有道理。凭据的解密留给网关，
        // 它拿到之后当场就用。
        Connector connector = registry.require(ConnectorInstanceLoader.normalizeKind(row.getKind()));
        if (!connector.declaredCapabilities().contains(Capability.DESCRIBE)) {
            throw new ServiceException(ExceptionCode.OPERATION_UNSUPPORTED,
                    "这种连接器类型不支持自描述，无法拉取结构");
        }

        // ── 第一段：网络往返（事务外；限流、并发闸、审计由网关负责）──
        Pull pull;
        try {
            pull = gateway.executeAsPlatform(connectorId, Capability.DESCRIBE,
                    ConnectorAuditService.OP_SCHEMA_REFRESH, session -> {
                        if (!(session instanceof DescribeCapable describe)) {
                            // 类型声明了 DESCRIBE、会话却没实现——分叉在我们这边，不在客户那边。
                            // 这里抛 ConnectorException 而不是 ServiceException 是有原因的：
                            // 网关的兜底 catch 会把一切非 ConnectorException 归成 UPSTREAM_ERROR
                            // （「目标系统返回了错误」），那会把排查方向带到客户的库上。
                            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                                    "这条连接的自描述能力不可用，请先点「测试连接」重新探测");
                        }
                        return pullDetails(describe, describe.catalog(), row);
                    });
        } catch (ConnectorException e) {
            // 已归一、已脱敏，直接转成业务异常给管理台看。
            throw new ServiceException(ExceptionCode.INVALID_REQUEST,
                    e.getSafeDetail() == null ? e.getCode().title() : e.getSafeDetail());
        }
        CatalogView catalog = pull.catalog();

        // ── 第二段：落库（一个事务，删旧插新必须同生共死）──
        List<ConnectorSchema> fresh = pull.rows();
        SnapshotResult result = txTemplate.execute(status -> {
            Map<String, ConnectorSchema> previous = indexByName(currentRows(connectorId));
            boolean first = previous.isEmpty();
            List<ObjectDiff> diffs = first ? List.of() : diff(previous, fresh);

            // 物理删除重插：唯一键不含 deleted，软删的行会占住键位，下次刷新就撞键。
            // 注意这条 SQL 绕过租户拦截器，所以上面 requireRow 的租户校验是它的前提。
            schemaMapper.physicalDeleteByConnector(connectorId);
            fresh.forEach(schemaMapper::insert);

            boolean truncated = catalog.truncated() || fresh.size() < catalog.total();
            return new SnapshotResult(fresh.size(), catalog.total(), truncated,
                    truncated ? truncationNote(catalog, fresh.size()) : null,
                    first, diffs, new Date(), null, null);
        });

        if (!result.getDiffs().isEmpty()) {
            log.info("连接器结构发生变化 connectorId={} name={} 变化数={}",
                    connectorId, row.getName(), result.getDiffs().size());
        }

        // ── 第三段：漂移处置（另一个事务，失败只记日志）──
        applyDriftQuietly(connectorId, pull.details(), result);
        return result;
    }

    /**
     * 表结构一变，挂在它上面的语义<b>自动标 STALE</b>——而不是悄悄变成假的。这是锚点存在的全部意义。
     *
     * <p>粒度由 {@code applyDrift} 自己按 scope 决定（OBJECT 不锚结构、FIELD 锚自己那一列、
     * JOIN 锚两端），这里只负责把它要的两样东西喂进去：刷新后每张表的列，和这次消失的表名。
     *
     * <p><b>列必须从 {@link ObjectDetail} 直接拿，不能从刚写进库的 {@code detail_json} 解回来。</b>
     * 锚点指纹算的是 {@code name:type:nullable:comment}，从 JSON 再拼一遍等于多一处能悄悄解错
     * 而且不报错的地方——那正好会让所有锚点全部对不上，变成一次全表 STALE 的假警报。
     *
     * <p><b>已知的两种假阳性</b>，都来自「这次没看到这张表」而不是「这张表变了」：
     * 一是对象数超过 {@link #MAX_OBJECTS} 被截断；二是某张表 {@code describe} 失败
     * （只读账号的授权可能只到部分表）。这两种情况下那张表的列不在 {@code fieldsByObject} 里，
     * 挂在它上面的 FIELD/JOIN 会因为「算不出锚点」被标成 STALE。
     * <b>没有为此加特判</b>：要区分「没了」和「没看到」就得改 {@code applyDrift} 的入参形状，
     * 而按「看不到就跳过」处理等于对这些表<b>整个关掉</b>漂移检测——漏报比误报危险得多。
     * STALE 只是「可能过时」，且下一次覆盖到它的刷新会自动撤销（{@code applyDrift} 支持恢复）。
     * 截断本身在 {@code SnapshotResult.truncated} 和 {@code truncationNote} 上是明说的。
     *
     * <p><b>第一种假阳性现在轻得多，但没有消失。</b>目录改成按重要性排序之后，落在 200 名之外的
     * 是估算行数最小的那一批，而语义（指标口径、join 关系）绝大多数挂在大表上——
     * 从前按表名截断时，被挤掉的是「名字排在后面」的表，跟重要性完全无关，命中率高得多。
     */
    private void applyDriftQuietly(Long connectorId, List<ObjectDetail> details, SnapshotResult result) {
        try {
            Map<String, Map<String, FieldDetail>> fieldsByObject = new LinkedHashMap<>();
            for (ObjectDetail d : details) {
                Map<String, FieldDetail> cols = new LinkedHashMap<>();
                if (d.fields() != null) {
                    for (FieldDetail f : d.fields()) {
                        cols.put(f.name(), f);
                    }
                }
                fieldsByObject.put(d.name(), cols);
            }
            // 只有 REMOVED 算「表没了」。ADDED / CHANGED 不是——加一张表跟现有语义无关，
            // 改一张表该不该失效由锚点逐行判，不能整表一刀切。
            List<String> removedObjects = result.getDiffs().stream()
                    .filter(d -> "REMOVED".equals(d.getChange()))
                    .map(ObjectDiff::getObjectName)
                    .toList();

            ConnectorSemanticService.StaleResult stale =
                    semanticService.applyDrift(connectorId, fieldsByObject, removedObjects);
            result.setSemanticStaled(stale.staled());
            result.setSemanticRevived(stale.revived());
            if (stale.staled() > 0 || stale.revived() > 0) {
                log.info("语义层锚点重挂 connectorId={} 标记过期={} 恢复={}",
                        connectorId, stale.staled(), stale.revived());
            }
        } catch (Exception e) {
            // 语义层是记账，结构快照是事实。记账失败不能把刚拉回来的事实弄丢，
            // 也不能把一次成功的刷新变成一个 5000。semanticStaled 保持 null = 「这次没算成」。
            log.warn("语义层漂移处置失败，本次结构快照已保留 connectorId={}", connectorId, e);
        }
    }

    // ================================================================ 内部

    /**
     * 一次拉取的两种形态：落库用的行，和原样的 {@link ObjectDetail}。
     *
     * <p>以前只留行就够了。现在漂移处置要按<b>列</b>重算锚点，而行里的 {@code detailJson}
     * 已经被摊成 Map 了——从那段 JSON 解回 {@code FieldDetail} 等于把刚丢掉的类型再拼一遍，
     * 多一处能悄悄解错的地方。原件还在手上就不要去读复印件。
     *
     * <p>{@code catalog} 也一起带出来：拉取这一步现在跑在网关的 lambda 里，
     * 而 {@code catalog()} 只有在那个会话还开着的时候才拿得到。把它留在外面用一个可变量接，
     * 就是在 lambda 与外层之间偷偷传值——下一个人很容易把它挪到会话关掉之后再取。
     *
     * <p>是 record：只在本类内部流转，从不序列化，不受 jackson 2.11 那条限制。
     */
    private record Pull(CatalogView catalog, List<ConnectorSchema> rows, List<ObjectDetail> details) {}

    private Pull pullDetails(DescribeCapable describe, CatalogView catalog, Connection row) {
        List<CatalogEntry> entries = catalog.entries() == null ? List.of() : catalog.entries();
        List<ConnectorSchema> out = new ArrayList<>();
        List<ObjectDetail> details = new ArrayList<>();
        Date now = new Date();
        int n = 0;
        for (CatalogEntry e : entries) {
            if (n++ >= MAX_OBJECTS) {
                // 排序依据要进日志。只说「只覆盖前 200 个」，看日志的人会按自己的直觉补上
                // 「前」是什么意思——而这正是上一版静默丢掉半个库时没人发现的原因。
                log.warn("连接器结构快照达到对象上限 {}，connectorId={}：{}",
                        MAX_OBJECTS, row.getId(), truncationNote(catalog, MAX_OBJECTS));
                break;
            }
            ObjectDetail detail;
            try {
                detail = describe.describe(e.name());
            } catch (ConnectorException ex) {
                // 单个对象取不到不该让整次刷新失败（权限可能只到部分表）。
                // 但也不能当它不存在——存一条只有名字的行，界面上看得出来「这张表没取到结构」。
                log.warn("连接器对象结构获取失败，跳过细节 connectorId={} object={} code={}",
                        row.getId(), e.name(), ex.getCode());
                detail = new ObjectDetail(e.name(), e.type(), e.comment(), List.of(),
                        Map.of("__error__", "结构获取失败：" + ex.getCode().title()));
            }
            out.add(toRow(row, e, detail, now));
            details.add(detail);
        }
        return new Pull(catalog, out, details);
    }

    /**
     * 截断说明：共几个、留了几个、<b>按什么留的</b>、没留的那批会怎样。
     *
     * <p>排序依据只能问 {@link CatalogView#ordering()} 要，本类不能替连接器写死一句「按行数」。
     * 将来接一个按别的顺序返回目录的连接器，写死的那句会变成一句<b>看起来很具体的假话</b>，
     * 而截断说明存在的全部意义就是别让人误读「只覆盖了 200 个」。
     * 连接器没声明顺序时如实说不知道——「不知道」和「按行数」必须分得开。
     */
    static String truncationNote(CatalogView catalog, int kept) {
        String ordering = catalog.ordering() == null || catalog.ordering().isBlank()
                ? "连接器返回的原始顺序（该连接器未声明排序依据）"
                : catalog.ordering();
        return "共 " + catalog.total() + " 个对象，本次只覆盖了" + ordering + "的前 " + kept
                + " 个；其余对象没有结构快照，也不在结构漂移检测范围内。";
    }

    private ConnectorSchema toRow(Connection row, CatalogEntry entry, ObjectDetail detail, Date now) {
        ConnectorSchema s = new ConnectorSchema();
        s.setTenantId(row.getTenantId());
        s.setConnectorId(row.getId());
        s.setObjectType(entry.type() == null ? "TABLE" : entry.type());
        s.setObjectName(entry.name());
        s.setObjectComment(entry.comment());
        s.setDetailJson(toJson(toDetailMap(detail)));
        // 哈希算的是【结构】而不是整个 JSON：注释里带的「约 N 行」是估算值，每次都在变，
        // 拿它进哈希会让每次刷新都报「变了」，报警变成噪音之后没人会看。
        s.setContentHash(sha256(structureFingerprint(detail)));
        s.setSyncedAt(now);
        return s;
    }

    /** 只取会影响查询正确性的部分：字段名、类型、可空、注释。行数估算这类波动值不进指纹。 */
    static String structureFingerprint(ObjectDetail detail) {
        StringBuilder sb = new StringBuilder();
        sb.append(detail.name()).append('|').append(detail.type()).append('|');
        if (detail.fields() != null) {
            for (FieldDetail f : detail.fields()) {
                sb.append(f.name()).append(':').append(f.type()).append(':')
                        .append(f.nullable()).append(':').append(f.comment() == null ? "" : f.comment())
                        .append(';');
            }
        }
        return sb.toString();
    }

    List<ObjectDiff> diff(Map<String, ConnectorSchema> previous, List<ConnectorSchema> fresh) {
        Map<String, ConnectorSchema> now = indexByName(fresh);
        List<ObjectDiff> out = new ArrayList<>();

        for (ConnectorSchema f : fresh) {
            ConnectorSchema p = previous.get(f.getObjectName());
            if (p == null) {
                out.add(new ObjectDiff(f.getObjectName(), "ADDED", List.of()));
            } else if (!java.util.Objects.equals(p.getContentHash(), f.getContentHash())) {
                out.add(new ObjectDiff(f.getObjectName(), "CHANGED", fieldDiff(p, f)));
            }
        }
        for (String name : previous.keySet()) {
            if (!now.containsKey(name)) {
                // 表被删掉比加字段严重得多：挂在它上面的指标口径、样例问答会直接失效。
                out.add(new ObjectDiff(name, "REMOVED", List.of()));
            }
        }
        return out;
    }

    /** 列级差异。取不到旧细节时退化成「结构有变化」，不编造具体内容。 */
    List<String> fieldDiff(ConnectorSchema before, ConnectorSchema after) {
        Map<String, String> b = fieldsOf(before);
        Map<String, String> a = fieldsOf(after);
        if (b.isEmpty() && a.isEmpty()) {
            return List.of("结构有变化（无法解析细节）");
        }
        List<String> out = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>(a.keySet());
        all.addAll(b.keySet());
        for (String col : all) {
            String bt = b.get(col);
            String at = a.get(col);
            if (bt == null) {
                out.add("新增列 " + col + " " + at);
            } else if (at == null) {
                out.add("删除列 " + col);
            } else if (!bt.equals(at)) {
                out.add("列 " + col + " 由 " + bt + " 变为 " + at);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fieldsOf(ConnectorSchema s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s.getDetailJson() == null || s.getDetailJson().isBlank()) {
            return out;
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(s.getDetailJson(), Map.class);
            Object fields = m.get("fields");
            if (fields instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> fm) {
                        Object name = fm.get("name");
                        if (name != null) {
                            out.put(String.valueOf(name), String.valueOf(fm.get("type")));
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析历史结构失败 objectName={}", s.getObjectName(), e);
        }
        return out;
    }

    static Map<String, ConnectorSchema> indexByName(List<ConnectorSchema> rows) {
        Map<String, ConnectorSchema> m = new LinkedHashMap<>();
        for (ConnectorSchema r : rows) {
            m.put(r.getObjectName(), r);
        }
        return m;
    }

    private Connection requireRow(Long id) {
        Connection row = connectionMapper.selectById(id);   // 租户过滤由拦截器注入
        if (row == null) {
            throw new ServiceException(ExceptionCode.NOT_FOUND, "连接不存在");
        }
        return row;
    }

    private void assertUsable(Connection row) {
        if (!"ACTIVE".equals(row.getStatus())) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "连接已停用，无法拉取结构");
        }
        String t = row.getTransport();
        if (t != null && !t.isBlank() && !"direct".equalsIgnoreCase(t)) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "内网隧道尚未实现");
        }
    }

    /**
     * 把 {@link ObjectDetail} 摊成 Map 再序列化。
     *
     * <p>两个理由：一是 jackson 2.11.1 序列化不了 record（见 {@link ObjectDiff} 的注释）；
     * 二是这份 JSON 要长期躺在库里、还要被后续版本读回来做结构比对——
     * 让它的形状由这里<b>显式</b>决定，而不是隐式跟着 record 的字段名走。
     * record 改个字段名，历史快照就读不出来了，而且不报错、只是「所有对象都显示为变更」。
     */
    static Map<String, Object> toDetailMap(ObjectDetail detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", detail.name());
        m.put("type", detail.type());
        m.put("comment", detail.comment());
        List<Map<String, Object>> fields = new ArrayList<>();
        if (detail.fields() != null) {
            for (FieldDetail f : detail.fields()) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("name", f.name());
                fm.put("type", f.type());
                fm.put("nullable", f.nullable());
                fm.put("comment", f.comment());
                fm.put("extra", f.extra());
                fields.add(fm);
            }
        }
        m.put("fields", fields);
        m.put("extra", detail.extra());
        return m;
    }

    private String toJson(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            // 原始异常必须进日志。第一版这里只抛了一句「结构序列化失败」就把 cause 吞了，
            // 结果线上看到的是一条没有任何线索的 5000——正是本项目反复吃亏的那种静默。
            log.error("连接器结构序列化失败 type={}", o == null ? null : o.getClass().getName(), e);
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "结构序列化失败");
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ServiceException(ExceptionCode.INTERNAL_SERVER_ERROR, "计算结构指纹失败");
        }
    }
}
