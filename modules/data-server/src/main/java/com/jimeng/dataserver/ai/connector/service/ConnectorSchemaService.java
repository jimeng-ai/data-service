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
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
     *
     * <h3>★ 刀口外的表不是「消失了」</h3>
     * 目录<b>列出</b>的对象（最多 500）比这里<b>描述</b>的多。一张表的行数估算跨过一个数量级，就可能从第 200 名
     * 掉到第 201 名——它只是这次没被描述，不是被删了。漂移判定因此按四种处境走
     * （{@link ConnectorSemanticService.Presence}）：只有「确定不在了」才算 REMOVED。
     */
    private static final int MAX_OBJECTS = 200;

    /**
     * 待补报名单的 Redis 键前缀（后接 connection.id），值是一个表名集合。见 {@link #rememberPendingRemoved}。
     *
     * <p>放 Redis 而不是我们库里：语义层那张表只放说明书本身，结构快照那张表每次刷新都整批删了重插，
     * 两边都没有「一条连接的待办」该待的地方；而这份名单丢了的代价只是退回到从前的行为（那一次漏报补不回来），不是错报。
     */
    static final String PENDING_REMOVED_KEY_PREFIX = "connector:schema:pending-removed:";

    /** 待补报名单的保留天数。定时刷新以小时计，30 天够跨过任何一次长时间的故障，又不会让名单永远挂着。 */
    static final long PENDING_REMOVED_TTL_DAYS = 30L;

    /** 描述失败时 {@link ObjectDetail#extra()} 里放原因的键。 */
    private static final String DESCRIBE_ERROR_KEY = "__error__";

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

    /**
     * 刷新之后交给增量推导（§8「ADDED：新对象入队，只跑 S2」，以及契约 C-A 的「补齐没覆盖到的对象」）。
     *
     * <p>★ 用 {@link ObjectProvider} 而不是直接注入：{@code ConnectorSemanticDeriveService} 构造期
     * 注入了本类，反过来直接注入就是构造循环，启动即失败。与 {@code ConnectorGateway} 对
     * {@code PendingWriteService} 的处理同一个办法、同一个理由（Lombok 不会把字段上的 {@code @Lazy} 抄到构造器参数上）。
     *
     * <p>放在字段列表<b>最后</b>：按位置构造本类的测试不会静默错位。
     */
    private final ObjectProvider<ConnectorSemanticDeriveService> semanticDerive;

    /**
     * 待补报名单的落脚处（见 {@link #rememberPendingRemoved}）。与 {@code ConnectorHealthJob} 做刷新冷却用的是同一个客户端。
     *
     * <p>Redis 不可用时每一处都退化成「没有名单」：刷新照常做完，只是漂移处置失败过的那一次补报不回来——正是加名单之前的行为。
     * 放在字段列表<b>最后</b>，理由同上。
     */
    private final RedissonClient redissonClient;

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
        /**
         * 本次<b>消失的</b>每张表上，<b>这一轮新</b>带走了多少条语义——管理台据此高亮「表已消失，挂在它上面的 3 条口径失效」。
         *
         * <p>与 {@link #semanticStaled} 同一条约定：{@code null} = 漂移处置没跑成，<b>不等于</b>没有影响；
         * 空列表 = 这一轮没有任何语义因表消失而新失效。只有一个总数时，管理台只能说「有 5 条过期了」，
         * 说不出是哪张表带走的，而人要决定的恰恰是「这张表是真删了还是只是账号看不到了」。
         *
         * <p>「本次消失的」= 本次 diff 的 REMOVED，加上<b>此前某次刷新报过、漂移处置却没做成</b>的表
         * （待补报名单，见 {@link #rememberPendingRemoved}）。计数只算这一轮新归到那张表名下的（契约 K-4），
         * 与 {@link #semanticStaled} 同一个口径：早就过期的行、早就挂着这张表的口径不会每次都报一遍。
         */
        private List<RemovedImpact> semanticStaledByObject;
        /**
         * 本次刷新<b>拒绝落库</b>的原因；正常刷新是 {@code null}（契约 C-D）。
         *
         * <p>非空时这个结果里的数是<b>库里那份快照</b>的：{@code diffs} 为空、漂移三件套为 {@code null}——
         * 结构快照、语义层、增量推导三件事一件都没做。管理台必须把这句话显示出来：
         * 否则「0 个变化」会被读成「客户库没变」。两种情况会拒绝：
         * <ul>
         *   <li>这次拉回来的目录是空的、而上一份快照不是空的（「这次拉回来的东西不可信，我们没敢用」）；</li>
         *   <li>这次拉取期间，已经有一次<b>更晚开始</b>的刷新先落了库（「这次拉回来的比库里的旧，用它会让删掉的表复活」）。</li>
         * </ul>
         */
        private String guardNote;

        /** 新增字段之前的形状。留着是为了别让按位置构造本类的地方（测试、别的分支）在合并时静默断掉。 */
        public SnapshotResult(int objectCount, int totalObjects, boolean truncated, String truncationNote,
                              boolean firstSnapshot, List<ObjectDiff> diffs, Date syncedAt,
                              Integer semanticStaled, Integer semanticRevived) {
            this(objectCount, totalObjects, truncated, truncationNote, firstSnapshot, diffs, syncedAt,
                    semanticStaled, semanticRevived, null, null);
        }

        /** 加 {@link #guardNote} 之前的形状，理由同上。 */
        public SnapshotResult(int objectCount, int totalObjects, boolean truncated, String truncationNote,
                              boolean firstSnapshot, List<ObjectDiff> diffs, Date syncedAt,
                              Integer semanticStaled, Integer semanticRevived,
                              List<RemovedImpact> semanticStaledByObject) {
            this(objectCount, totalObjects, truncated, truncationNote, firstSnapshot, diffs, syncedAt,
                    semanticStaled, semanticRevived, semanticStaledByObject, null);
        }
    }

    /**
     * 一张消失的表的影响面。不是 record，理由同 {@link ObjectDiff}。
     *
     * <p>{@code staledRows} 与 {@code staledMetrics} 不重叠：前者是表用途 / 字段 / 关系 / 告诫，
     * 后者是 {@code applies_to} 含这张表的口径。按表分的数<b>可以跨表重叠</b>
     * （一条关系两端都消失时两张表各记一次），不要加总去和 {@code semanticStaled} 对账。
     * 三个数都只算<b>这一轮新</b>归到这张表名下的（契约 K-4）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RemovedImpact {
        private String objectName;
        /** 挂在它上面、这一轮新标成 STALE 的非口径语义行数。 */
        private Integer staledRows;
        /** 这一轮因它消失而新标过期的口径条数。 */
        private Integer staledMetrics;
        /** 那些口径的词条。 */
        private List<String> metricTerms;
    }

    // ================================================================ 读

    /** 内部用：拿原始行做差异比对。 */
    List<ConnectorSchema> currentRows(Long connectorId) {
        return schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getConnectorId, connectorId)
                .orderByAsc(ConnectorSchema::getObjectName));
    }

    /**
     * 按重要性读快照行：语义层生成 agent 按它对账、切片（先生成最重要的表）。
     *
     * <p>排序 {@code importance_rank IS NULL, importance_rank ASC, id ASC}：
     * <ul>
     *   <li>有排名的按排名。排名是刷新时这张表在连接器目录里的位置（{@link #pullDetails} 写入）。</li>
     *   <li>本列上线之前落的旧快照整份都是 NULL，退回按 {@code id} 升序：一份快照的行由 {@code fresh.forEach(insert)}
     *       按目录顺序在单线程里依次插入，雪花 id 单调递增，所以 id 顺序复现的就是那次刷新的目录顺序。
     *       2026-09-16 在 dev「本地测试」连接上只读核对过：41 行旧快照的 id 升序，与按同一规则
     *       （数量级降序 → 被引用数降序 → 表名升序）现算的目录顺序逐行一致。
     *       引入重要性排序之前的快照，目录顺序本来就是字母序，回退结果与旧行为一致。</li>
     *   <li>不能直接 {@code orderByAsc(importanceRank)}：MySQL 升序把 NULL 排在最前，
     *       混着新旧行时旧行会整批插队到最重要的表前面。</li>
     * </ul>
     *
     * <p><b>不改 {@link #currentRows}</b>：diff、推导摘要与现有单测都依赖它的字母序。
     * {@code last(...)} 里的 {@code IS NULL} 排序表达式经租户拦截器（JSqlParser 4.6）解析改写后原样保留（离线实测过）。
     */
    public List<ConnectorSchema> snapshotRowsByImportance(Long connectorId) {
        requireRow(connectorId);
        return schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                .eq(ConnectorSchema::getConnectorId, connectorId)
                .last("ORDER BY importance_rank IS NULL, importance_rank ASC, id ASC"));
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
                if (extra instanceof Map<?, ?> em && em.get(DESCRIBE_ERROR_KEY) != null) {
                    error = String.valueOf(em.get(DESCRIBE_ERROR_KEY));
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
     * {@code txTemplate} 包着的还是只有「锁连接行 + 读上一份 + 删旧行 + 插新行」。这条必须留着——
     * 把 {@code executeAsPlatform} 挪进第二段，等于把刚拆掉的那个长事务原样装回来。
     *
     * <h3>★ 目录截断时，「上次见过、这次没列出」的名字在同一个会话里单独问一句存不存在</h3>
     * 目录超过上限（500）时，没列出的名字只能判 UNKNOWN，而漂移检测对 UNKNOWN 一个字不碰——
     * 于是超过 500 个对象的库上，一张被删掉的表<b>永远</b>不会被判成消失：它的说明、关系、口径一直注入，屏幕上什么都没有。
     * 所以对「上一份快照里有、或语义层提到过、或在待补报名单里、而这次目录没列出」的那一小撮名字，
     * 在拉结构的同一个会话里调 {@link DescribeCapable#existingObjects}：在 → LISTED_ONLY，不在 → ABSENT，
     * 连接器答不了（{@code null}）→ 仍是 UNKNOWN。问的名字来自我们自己的库，只在目录真的截断时才去读。
     *
     * <h3>★ 空目录闸</h3>
     * 客户库这次列出 0 个对象、而上一份快照不是空的：<b>不落库、不做漂移处置、不派发增量推导</b>，
     * 返回上一份快照的数并在 {@link SnapshotResult#getGuardNote()} 里说明原因。
     * 「一下子全没了」远比「客户真把表删光了」更常见于备份恢复、迁移切换、授权被收回、库名配错——
     * 照常落库的代价是：快照清空、整条连接的说明书全部 STALE、所有口径停止注入、管理台叫业务方把口径重答一遍，
     * 而等授权恢复后这一切又全部复活。判断「真删光了」需要人，这里不替人下。
     *
     * <h3>★ 旧拉取不许覆盖新快照</h3>
     * 一次手工刷新描述 200 张表要几十秒；它开始之后客户 DROP 了表 X，一次更晚开始、更快做完的定时刷新先落库，
     * 快照里已经没有 X、X 上的说明已经过期。手工那次若照常落库，会把它早先描述到的 X 写回快照——X 的说明在下一轮漂移处置里复活，
     * 而 X 已经不存在。所以记下<b>拉取开始的时刻</b>（它也是这份快照每行的 {@code synced_at}），落库前比一次：
     * 库里快照最新的 {@code synced_at} 晚于它 = 已有一次更晚开始的拉取落了库，本次不落库、不做漂移处置、不派发，
     * 在 {@code guardNote} 里说明。落库段先 {@code SELECT ... FOR UPDATE} 锁住这条连接的行：两次落库恰好重叠时，
     * 后到的那一次要等前一次提交完、看得见它的快照再比，否则两边都比「没有更新的」、后提交的照样覆盖。
     * 锁的是连接行而不是快照行：第一次快照时快照行不存在，锁快照行只能锁间隙，两次并发的首次快照会互相死锁。
     *
     * <h3>★ 待补报名单：漂移处置失败过的那次 REMOVED 不能丢</h3>
     * 快照先落库、漂移处置后做，两段之间失败（库抖了）或进程被杀（本仓库 push main 即部署）时，
     * 这次 diff 报的 REMOVED 在下一次 diff 里再也不会出现——那张表带走的口径失效、影响面永远没人知道。
     * 所以快照一落库就先把这次的 REMOVED 记进待补报名单（先记后做，挡住进程被杀那一种），
     * 漂移处置每次把名单并进要归因的表，处置成功后把已经有定论的名字划掉。见 {@link #rememberPendingRemoved}。
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

        // 拉取开始的时刻：既是这份快照每行的 synced_at，也是落库前「有没有更新的快照先落了库」的比较基准。
        Date pullStart = new Date();
        // 开会话之前读好：它要并进归因，也要在目录截断时一起确认存不存在。
        Set<String> pendingRemoved = readPendingRemoved(connectorId);

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
                        return pullDetails(describe, describe.catalog(), row, pullStart,
                                () -> knownObjectNames(connectorId, pendingRemoved));
                    });
        } catch (ConnectorException e) {
            // 已归一、已脱敏，转成业务异常给管理台看。
            // ★ cause 必须带上：错误码（RATE_LIMITED 这类「现在不方便」）只活在 ConnectorException 上。
            //   丢了它，定时刷新（ConnectorHealthJob）只能靠比对文案认「繁忙」——谁改一个字，
            //   繁忙就被记成故障、进冷却。带 cause 不会把原始异常送出网：
            //   GlobalExceptionHandler 对 ServiceException 只回 respCode / respMsg，
            //   而 ConnectorException 自己的 message 也只由错误码标题和已脱敏的 safeDetail 拼成。
            throw new ServiceException(e, ExceptionCode.INVALID_REQUEST.getResultCode(),
                    e.getSafeDetail() == null ? e.getCode().title() : e.getSafeDetail());
        }
        CatalogView catalog = pull.catalog();
        List<ConnectorSchema> fresh = pull.rows();
        boolean emptyCatalog = catalog.entries() == null || catalog.entries().isEmpty();
        ConnectorSemanticService.SnapshotPresence presence =
                presenceOf(catalog, pull.details(), pull.checkedNames(), pull.existingNames());

        // ── 第二段：落库（一个事务，删旧插新必须同生共死）──
        String[] refusedBecause = new String[1];
        SnapshotResult result = txTemplate.execute(status -> {
            // 先锁连接行、再读上一份快照。理由见方法注释「旧拉取不许覆盖新快照」。
            lockConnectionRow(connectorId);
            List<ConnectorSchema> previousRows = currentRows(connectorId);
            Map<String, ConnectorSchema> previous = indexByName(previousRows);
            Date latest = latestSyncedAt(previousRows);
            if (latest != null && latest.after(pullStart)) {
                refusedBecause[0] = "NEWER_SNAPSHOT";
                return guardedResult(previousRows, newerSnapshotNote(pullStart, latest));
            }
            if (emptyCatalog && !previous.isEmpty()) {
                // 空目录闸：一行不删、一行不插。理由见方法注释。
                refusedBecause[0] = "EMPTY_CATALOG";
                return guardedResult(previousRows);
            }
            boolean first = previous.isEmpty();
            List<ObjectDiff> diffs = first ? List.of() : diff(previous, fresh, presence);

            // 物理删除重插：唯一键不含 deleted，软删的行会占住键位，下次刷新就撞键。
            // 注意这条 SQL 绕过租户拦截器，所以上面 requireRow 的租户校验是它的前提。
            schemaMapper.physicalDeleteByConnector(connectorId);
            fresh.forEach(schemaMapper::insert);

            boolean truncated = catalog.truncated() || fresh.size() < catalog.total();
            return new SnapshotResult(fresh.size(), catalog.total(), truncated,
                    truncated ? truncationNote(catalog, fresh.size()) : null,
                    first, diffs, pullStart, null, null, null, null);
        });

        if (result.getGuardNote() != null) {
            if ("NEWER_SNAPSHOT".equals(refusedBecause[0])) {
                log.warn("连接器结构刷新落库前发现已有更晚开始的刷新先落了库，本次拉取结果作废（不做漂移处置、不派发增量推导） "
                        + "connectorId={} name={} 本次开始于={} 库里快照={}", connectorId, row.getName(), pullStart,
                        result.getSyncedAt());
            } else {
                // WARN 而不是 INFO：这是「客户那边多半出事了」的第一个、也可能是唯一的信号。
                log.warn("连接器结构刷新拿到空目录，已拒绝覆盖上一份快照（不做漂移处置、不派发增量推导） "
                        + "connectorId={} name={} 上一份快照对象数={}", connectorId, row.getName(), result.getObjectCount());
            }
            return result;
        }

        if (!result.getDiffs().isEmpty()) {
            log.info("连接器结构发生变化 connectorId={} name={} 变化数={}",
                    connectorId, row.getName(), result.getDiffs().size());
        }

        // ── 第三段：漂移处置（另一个事务，失败只记日志）──
        List<String> removedNow = result.getDiffs().stream()
                .filter(d -> "REMOVED".equals(d.getChange()))
                .map(ObjectDiff::getObjectName)
                .toList();
        // 先记后做：快照已经落库，这次的 REMOVED 从此不会再出现在任何 diff 里。
        rememberPendingRemoved(connectorId, removedNow);
        List<String> toAttribute = mergeNames(removedNow, pendingRemoved);
        ConnectorSemanticService.StaleResult stale =
                applyDriftQuietly(connectorId, pull.details(), presence, toAttribute, result);
        if (stale != null) {
            settlePendingRemoved(connectorId, toAttribute, presence, stale);
        }
        // ── 第四段：交给增量推导（异步，失败只记日志）──
        dispatchAddedQuietly(connectorId, result);
        // ── 第五段：档位不允许真实取值时，顺手清一遍残留（失败只记日志）──
        purgeSampleValuesQuietly(connectorId);
        return result;
    }

    /**
     * 把这次刷新交给增量推导（契约 C-A）。<b>一批一次</b>：50 张新表是一次模型调用，不是 50 次。
     *
     * <h3>★ 没有新表也派发</h3>
     * 推导那边用这一次调用去补「快照里有、说明书里却没有」的对象：被挤出过描述上限又回来的表、
     * 上一次增量推导失败或派发时队列满了的表。那些表在本次 diff 里<b>不是</b> ADDED，
     * 只在有 ADDED 时才派发，它们就只能等到下一次恰好有新表出现——可能永远等不到。
     * 是否真的要调模型由推导那边判，这里不替它省。
     *
     * <h3>★ 第一次快照一律不派发；被拦下的刷新也不派发</h3>
     * 第一次快照里「全是新增」（本方法里 diffs 甚至是空的，但判据写在 firstSnapshot 上才不依赖那个巧合）。
     * 把它们当增量推，等于一次绕过认领 CAS 的全量推导——而「接入即推导」那条路已经在推同一批表了。
     * 被拦下的刷新没有落库，推导读到的仍是库里那份快照，派发没有意义，还会把一次可疑的拉取伪装成正常的一轮。
     *
     * <h3>为什么在这里不等、不抛</h3>
     * 推导要叫模型，几十秒。刷新是管理台上的一次点击，它的职责是把结构快照拉回来；
     * 让它等模型、或者因为推导派发失败而报错，都是让一个叠加增强去否决一次成功的刷新。
     * 派发本身落在带队列的阶段池上（绝不就地跑在这条请求线程上），见推导类的 {@code deriveAddedAsync}。
     */
    private void dispatchAddedQuietly(Long connectorId, SnapshotResult result) {
        if (result == null || result.isFirstSnapshot() || result.getGuardNote() != null) {
            return;
        }
        List<String> added = result.getDiffs() == null ? List.of() : result.getDiffs().stream()
                .filter(d -> "ADDED".equals(d.getChange()))
                .map(ObjectDiff::getObjectName)
                .toList();
        try {
            ConnectorSemanticDeriveService derive = semanticDerive == null ? null : semanticDerive.getIfAvailable();
            if (derive == null) {
                log.warn("刷新后未交给增量推导：推导服务不可用 connectorId={} 新增={}", connectorId, added.size());
                return;
            }
            derive.deriveAddedAsync(connectorId, added);
        } catch (Exception e) {
            log.warn("刷新后交给增量推导失败，本次结构快照不受影响 connectorId={} 新增={}", connectorId, added.size(), e);
        }
    }

    /**
     * 表结构一变，挂在它上面的语义<b>自动标 STALE</b>——而不是悄悄变成假的。这是锚点存在的全部意义。
     *
     * <p>粒度由 {@code applyDrift} 自己按 scope 决定（OBJECT 不锚结构、FIELD 锚自己那一列、
     * JOIN 锚两端），这里只负责把它要的三样东西喂进去：刷新后每张表的列、每个对象名的处境、要按「消失」归因的表名。
     *
     * <p><b>列必须从 {@link ObjectDetail} 直接拿，不能从刚写进库的 {@code detail_json} 解回来。</b>
     * 锚点指纹算的是 {@code name:type:nullable:comment}，从 JSON 再拼一遍等于多一处能悄悄解错
     * 而且不报错的地方——那正好会让所有锚点全部对不上，变成一次全表 STALE 的假警报。
     *
     * <p><b>截断造成的假阳性已经没有了。</b>从前对象数超过 {@link #MAX_OBJECTS} 时，刀口外的表不在列里，
     * 挂在上面的 FIELD/JOIN 被当成「算不出锚点」标 STALE，OBJECT 行被当成「表没了」。现在处境单独传。
     *
     * <p><b>描述失败的表按「列出了、没描述」处理，不按「描述到了、一列都没有」处理。</b>
     * 只读账号的授权可能只到部分表，那张表的 {@code describe} 失败、快照里只有一条名字行。从前它的空列表被当成
     * 「描述到了」：挂在上面的 FIELD / JOIN 全部因为「算不出锚点」假过期；而关系另一端没看到时，
     * 一端「描述到了、列不在」又会被当成确定的过期。表明明在，只是这次没有列可比——这正是 LISTED_ONLY 的定义。
     *
     * @return 漂移处置的结果；{@code null} = 没跑成（原因已记日志）
     */
    private ConnectorSemanticService.StaleResult applyDriftQuietly(Long connectorId, List<ObjectDetail> details,
                                                                  ConnectorSemanticService.SnapshotPresence presence,
                                                                  List<String> removedObjects, SnapshotResult result) {
        try {
            Map<String, Map<String, FieldDetail>> fieldsByObject = new LinkedHashMap<>();
            for (ObjectDetail d : details) {
                if (describeFailed(d)) {
                    continue;
                }
                Map<String, FieldDetail> cols = new LinkedHashMap<>();
                if (d.fields() != null) {
                    for (FieldDetail f : d.fields()) {
                        cols.put(f.name(), f);
                    }
                }
                fieldsByObject.put(d.name(), cols);
            }

            ConnectorSemanticService.StaleResult stale =
                    semanticService.applyDrift(connectorId, fieldsByObject, presence, removedObjects);
            List<RemovedImpact> byObject = new ArrayList<>();
            if (stale.removedImpact() != null) {
                for (ConnectorSemanticService.ObjectStale o : stale.removedImpact()) {
                    byObject.add(new RemovedImpact(o.objectName(), o.staledRows(), o.staledMetrics(),
                            o.metricTerms() == null ? List.of() : o.metricTerms()));
                }
            }
            result.setSemanticStaled(stale.staled());
            result.setSemanticRevived(stale.revived());
            // 三个一起设、在同一个 try 里：要么都有，要么都是 null（「这次没算成」）。
            result.setSemanticStaledByObject(byObject);
            if (stale.staled() > 0 || stale.revived() > 0) {
                log.info("语义层锚点重挂 connectorId={} 标记过期={} 恢复={}",
                        connectorId, stale.staled(), stale.revived());
            }
            return stale;
        } catch (Exception e) {
            // 语义层是记账，结构快照是事实。记账失败不能把刚拉回来的事实弄丢，
            // 也不能把一次成功的刷新变成一个 5000。semanticStaled 保持 null = 「这次没算成」。
            // 这次的 REMOVED 已经在待补报名单里，下一次刷新会补上。
            log.warn("语义层漂移处置失败，本次结构快照已保留，消失的表留在待补报名单里下次补报 connectorId={}", connectorId, e);
            return null;
        }
    }

    // ================================================================ 待补报名单

    /**
     * 把这次 diff 报的 REMOVED 记进这条连接的待补报名单。<b>快照一落库就记，漂移处置之前。</b>
     *
     * <h3>为什么要这份名单</h3>
     * 漂移处置判「这一次消失的表」只认调用方报的名字（理由见 {@code ConnectorSemanticService.applyDrift}）。
     * diff 报 REMOVED 只有一次机会：快照落库之后，下一次 diff 对着的是已经没有这张表的快照。
     * 这一次的漂移处置若失败，没有名单就意味着——那张表带走的口径永远不会失效、影响面永远不会出现在管理台上，
     * 而管理台那句「再刷新一次会补报」是假话。
     *
     * <h3>为什么先记后做</h3>
     * 等漂移处置失败了再记，挡不住「做到一半进程被杀」：本仓库 push main 即部署，一次正在跑的手工刷新随时可能被重启打断。
     *
     * <p>写失败只记日志：名单丢了退化成加名单之前的行为，不是错报。
     */
    private void rememberPendingRemoved(Long connectorId, Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        try {
            RSet<String> set = redissonClient.getSet(PENDING_REMOVED_KEY_PREFIX + connectorId);
            set.addAll(names);
            set.expire(PENDING_REMOVED_TTL_DAYS, TimeUnit.DAYS);
        } catch (RuntimeException e) {
            log.warn("消失的表没能记进待补报名单：这一次漂移处置若也失败，它们带走的口径与影响面将无法补报 connectorId={} 表={}",
                    connectorId, names, e);
        }
    }

    /** 读待补报名单。读不到按「没有名单」处理。 */
    private Set<String> readPendingRemoved(Long connectorId) {
        try {
            RSet<String> set = redissonClient.getSet(PENDING_REMOVED_KEY_PREFIX + connectorId);
            Set<String> all = set == null ? null : set.readAll();
            return all == null ? Set.of() : new LinkedHashSet<>(all);
        } catch (RuntimeException e) {
            log.warn("读取待补报名单失败，本次只按这一次的 diff 归因 connectorId={}", connectorId, e);
            return Set.of();
        }
    }

    /**
     * 漂移处置成功之后，把<b>已经有定论</b>的名字从名单里划掉。
     *
     * <ul>
     *   <li>ABSENT：这一轮已经按「消失」归因过了；</li>
     *   <li>DESCRIBED / LISTED_ONLY：表回来了（或从来就在），没有要补报的；</li>
     *   <li>UNKNOWN：目录截断、连接器又答不了存不存在——<b>留着</b>，等哪一次能确认再处置；</li>
     *   <li>这一轮有行因为被别处抢先改写而没处置（{@code concurrentSkips > 0}）：整份<b>留着</b>，
     *       下一次重判时那些行还能被归因。已经归因过的行下一次不会「新标过期」，不会重复计数。</li>
     * </ul>
     * 名单靠 TTL 兜底，不会永远挂着。
     */
    private void settlePendingRemoved(Long connectorId, List<String> attributed,
                                      ConnectorSemanticService.SnapshotPresence presence,
                                      ConnectorSemanticService.StaleResult stale) {
        if (attributed == null || attributed.isEmpty()) {
            return;
        }
        if (stale.concurrentSkips() > 0) {
            log.info("漂移处置有行被别处抢先改写，待补报名单整份留到下一次 connectorId={} 表={}", connectorId, attributed);
            return;
        }
        List<String> settled = new ArrayList<>();
        for (String name : attributed) {
            if (presence.stateOf(name) != ConnectorSemanticService.Presence.UNKNOWN) {
                settled.add(name);
            }
        }
        if (settled.isEmpty()) {
            return;
        }
        try {
            redissonClient.getSet(PENDING_REMOVED_KEY_PREFIX + connectorId).removeAll(settled);
        } catch (RuntimeException e) {
            // 划不掉的代价：下一次刷新会把这几张表再并进归因。它们的行已经过期、口径已经挂上名单，不会重复计数；
            // 唯一的例外是这期间人重新确认过的口径会被重新挂回名单——所以这里是 WARN。
            log.warn("待补报名单没能划掉已处置的表，下一次刷新会再按消失处理一次 connectorId={} 表={}",
                    connectorId, settled, e);
        }
    }

    private static List<String> mergeNames(List<String> first, Collection<String> second) {
        Set<String> out = new LinkedHashSet<>(first == null ? List.of() : first);
        if (second != null) {
            out.addAll(second);
        }
        return List.copyOf(out);
    }

    // ================================================================ 档位残留

    /**
     * 档位此刻不允许真实取值时，把语义层里残留的取值再清一遍（契约 K-5 的 {@code purgeSampleValues}）。
     *
     * <p>改档位的那一刻已经删过一次。这里再扫，是因为有两种残留那一次删不到，而且都不会自己出声：
     * <ul>
     *   <li><b>档位下调之前就在跑的值域采集</b>：它开跑时档位还允许，半小时后把取值写回来，落在删除之后；</li>
     *   <li><b>这次改动上线之前就已经下调过档位的连接</b>：那时没有删除这一步，取值一直躺在库里。</li>
     * </ul>
     * <b>档位要现读、读不到就不删</b>：这里答错的方向与注入路径相反——读失败按「不允许」去删，会把一条第 3 档连接上
     * 合法采到的取值抹掉。
     */
    private void purgeSampleValuesQuietly(Long connectorId) {
        try {
            Connection now = connectionMapper.selectById(connectorId);
            if (now == null || SemanticDataTier.parse(now.getSemanticDataTier()).allowsSampleValues()) {
                return;
            }
            int n = semanticService.purgeSampleValues(connectorId);
            if (n > 0) {
                // WARN：档位不允许却还存着真实取值，说明有一条写路径绕过了档位（或是存量数据），值得有人看一眼。
                log.warn("结构刷新时发现档位不允许真实取值的连接上仍存着取值，已删除 connectorId={} 行数={}", connectorId, n);
            }
        } catch (Exception e) {
            log.warn("结构刷新后清理档位外的真实取值失败，下一次刷新再试 connectorId={}", connectorId, e);
        }
    }

    // ================================================================ 内部

    /**
     * 一次拉取的几种形态：落库用的行、原样的 {@link ObjectDetail}、目录，以及目录截断时单独确认过的名字。
     *
     * <p>以前只留行就够了。现在漂移处置要按<b>列</b>重算锚点，而行里的 {@code detailJson}
     * 已经被摊成 Map 了——从那段 JSON 解回 {@code FieldDetail} 等于把刚丢掉的类型再拼一遍，
     * 多一处能悄悄解错的地方。原件还在手上就不要去读复印件。
     *
     * <p>{@code catalog} 与存在性确认也一起带出来：拉取这一步跑在网关的 lambda 里，
     * 而它们只有在那个会话还开着的时候才拿得到。把它们留在外面用可变量接，
     * 就是在 lambda 与外层之间偷偷传值——下一个人很容易把它挪到会话关掉之后再取。
     *
     * <p>是 record：只在本类内部流转，从不序列化，不受 jackson 2.11 那条限制。
     *
     * @param checkedNames  目录截断时单独问过存不存在的名字（没问是空列表）
     * @param existingNames 其中存在的；{@code null} = 没问，或连接器答不了
     */
    private record Pull(CatalogView catalog, List<ConnectorSchema> rows, List<ObjectDetail> details,
                        List<String> checkedNames, Set<String> existingNames) {}

    private Pull pullDetails(DescribeCapable describe, CatalogView catalog, Connection row, Date syncedAt,
                             Supplier<Collection<String>> knownNames) {
        List<CatalogEntry> entries = catalog.entries() == null ? List.of() : catalog.entries();
        List<ConnectorSchema> out = new ArrayList<>();
        List<ObjectDetail> details = new ArrayList<>();
        int n = 0;
        for (CatalogEntry e : entries) {
            if (n++ >= MAX_OBJECTS) {
                // 排序依据要进日志。只说「只覆盖前 200 个」，看日志的人会按自己的直觉补上
                // 「前」是什么意思——而这正是上一版静默丢掉半个库时没人发现的原因。
                log.warn("连接器结构快照达到对象上限 {}，connectorId={}：{}",
                        MAX_OBJECTS, row.getId(), truncationNote(catalog, MAX_OBJECTS));
                break;
            }
            // 截断判断通过之后 n 恰好是这张表在目录里从 1 开始的位置，即重要性排名。
            // 描述失败的对象同样占一个位置：它在目录里就排在那儿，跳过它会让后面每一张表的排名错一位。
            int position = n;
            ObjectDetail detail;
            try {
                detail = describe.describe(e.name());
            } catch (ConnectorException ex) {
                // 单个对象取不到不该让整次刷新失败（权限可能只到部分表）。
                // 但也不能当它不存在——存一条只有名字的行，界面上看得出来「这张表没取到结构」。
                log.warn("连接器对象结构获取失败，跳过细节 connectorId={} object={} code={}",
                        row.getId(), e.name(), ex.getCode());
                detail = new ObjectDetail(e.name(), e.type(), e.comment(), List.of(),
                        Map.of(DESCRIBE_ERROR_KEY, "结构获取失败：" + ex.getCode().title()));
            }
            out.add(toRow(row, e, detail, syncedAt, position));
            details.add(detail);
        }

        List<String> checked = List.of();
        Set<String> existing = null;
        if (listingTruncated(catalog)) {
            checked = unlisted(entries, knownNames.get());
            if (!checked.isEmpty()) {
                try {
                    existing = describe.existingObjects(checked);
                } catch (RuntimeException ex) {
                    // 确认失败不让整次刷新失败：这几个名字退回「不知道」，与连接器答不了同一个处置。
                    log.warn("目录截断时确认表是否存在失败，这些名字本次按「不知道」处理 connectorId={} 个数={}",
                            row.getId(), checked.size(), ex);
                    existing = null;
                }
            }
        }
        return new Pull(catalog, out, details, checked, existing);
    }

    /**
     * 我们自己知道的对象名：上一份结构快照里的、语义层提到过的、待补报名单里的。
     * 只在目录截断时读（由 {@link #pullDetails} 按需调用），读失败的那一部分略过。
     */
    private Collection<String> knownObjectNames(Long connectorId, Collection<String> pendingRemoved) {
        Set<String> names = new LinkedHashSet<>();
        try {
            // 只投影名字：快照每行带着 longtext 的 detail_json，这里用不上。
            for (ConnectorSchema s : schemaMapper.selectList(new LambdaQueryWrapper<ConnectorSchema>()
                    .select(ConnectorSchema::getObjectName)
                    .eq(ConnectorSchema::getConnectorId, connectorId))) {
                if (s != null && s.getObjectName() != null && !s.getObjectName().isBlank()) {
                    names.add(s.getObjectName());
                }
            }
        } catch (RuntimeException e) {
            log.warn("读取上一份结构快照的对象名失败，本次只按语义层与待补报名单确认表是否存在 connectorId={}", connectorId, e);
        }
        try {
            Set<String> referenced = semanticService.referencedObjectNames(connectorId);
            if (referenced != null) {
                names.addAll(referenced);
            }
        } catch (RuntimeException e) {
            log.warn("读取语义层提到的对象名失败 connectorId={}", connectorId, e);
        }
        if (pendingRemoved != null) {
            names.addAll(pendingRemoved);
        }
        return names;
    }

    /** {@code known} 里目录没列出的名字（大小写不敏感去重，保留第一次出现的写法）。 */
    private static List<String> unlisted(List<CatalogEntry> entries, Collection<String> known) {
        if (known == null || known.isEmpty()) {
            return List.of();
        }
        Set<String> listed = new HashSet<>();
        for (CatalogEntry e : entries) {
            listed.add(ConnectorSemanticService.ciFold(e.name()));
        }
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String name : known) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String k = ConnectorSemanticService.ciFold(name);
            if (!listed.contains(k) && seen.add(k)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * 目录截断既看连接器自己报的 {@code truncated}，也看「列出来的比总数少」：任一成立，目录外的名字就不能判 ABSENT。
     * 连接器漏报截断时把「没列出来」当成「没了」，是往危险的方向兜底；
     * 反过来的代价只是：COUNT 与列表两条查询之间恰好有表被删时，晚一次刷新才标过期。
     */
    private static boolean listingTruncated(CatalogView catalog) {
        int listed = catalog.entries() == null ? 0 : catalog.entries().size();
        return catalog.truncated() || listed < catalog.total();
    }

    /**
     * 本次刷新对每个对象名「看到了什么」，见 {@link ConnectorSemanticService.Presence}。
     *
     * <p>描述到 = 真拿到了结构的；描述失败的只算列出了（理由见 {@link #applyDriftQuietly}）。
     * 目录截断时单独确认过的名字按确认结果算：在 → LISTED_ONLY，不在 → ABSENT，连接器答不了 → UNKNOWN。
     */
    static ConnectorSemanticService.SnapshotPresence presenceOf(CatalogView catalog, List<ObjectDetail> details,
                                                                Collection<String> checkedNames,
                                                                Collection<String> existingNames) {
        List<CatalogEntry> entries = catalog.entries() == null ? List.of() : catalog.entries();
        List<String> listed = new ArrayList<>(entries.size());
        for (CatalogEntry e : entries) {
            listed.add(e.name());
        }
        List<String> described = new ArrayList<>();
        if (details != null) {
            for (ObjectDetail d : details) {
                if (describeFailed(d)) {
                    listed.add(d.name());
                } else {
                    described.add(d.name());
                }
            }
        }
        return ConnectorSemanticService.SnapshotPresence.of(described, listed, listingTruncated(catalog),
                checkedNames, existingNames);
    }

    /** 这张表 describe 失败了：只有一条名字行，没有列。 */
    private static boolean describeFailed(ObjectDetail d) {
        return d.extra() != null && d.extra().containsKey(DESCRIBE_ERROR_KEY);
    }

    /**
     * 锁住这条连接的行，让同一条连接上两次刷新的落库段排队。理由见 {@link #refresh} 的「旧拉取不许覆盖新快照」。
     * 结果不用：要的只是锁。{@code SELECT ... FOR UPDATE} 经租户拦截器改写后仍带着 {@code FOR UPDATE}（离线实测过）。
     */
    private void lockConnectionRow(Long connectorId) {
        connectionMapper.selectList(new LambdaQueryWrapper<Connection>()
                .eq(Connection::getId, connectorId)
                .last("FOR UPDATE"));
    }

    private static Date latestSyncedAt(List<ConnectorSchema> rows) {
        return rows.stream()
                .map(ConnectorSchema::getSyncedAt)
                .filter(Objects::nonNull)
                .max(Date::compareTo)
                .orElse(null);
    }

    /**
     * 拒绝落库时的返回：<b>库里那份快照的数</b>，外加一句为什么没覆盖。
     *
     * <p>{@code totalObjects} 也填那份快照的对象数：上一次客户库里一共有多少个我们没存，这是手上唯一不编造的数。
     * {@code syncedAt} 是那份快照的时间——这次什么都没同步，写 now 会让管理台显示「刚刚刷新过」。
     * 漂移三件套是 {@code null}：确实没跑，不是跑了没变化。
     */
    static SnapshotResult guardedResult(List<ConnectorSchema> previousRows) {
        return guardedResult(previousRows, emptyCatalogNote(previousRows.size()));
    }

    static SnapshotResult guardedResult(List<ConnectorSchema> previousRows, String note) {
        int n = previousRows.size();
        SnapshotResult r = new SnapshotResult(n, n, false, null, false, List.of(), latestSyncedAt(previousRows),
                null, null, null);
        r.setGuardNote(note);
        return r;
    }

    /** 给管理台看的那句话。要说清三件事：看到了什么、为什么没用它、人该去查什么。 */
    static String emptyCatalogNote(int previousCount) {
        return "本次从客户库列出的对象是 0 个，而上一份结构快照有 " + previousCount + " 个。"
                + "这更常见于备份恢复、迁移切换、只读账号授权被收回或库名配错，而不是真的删光了所有表，"
                + "所以本次没有覆盖结构快照、没有把任何说明书标为过期，也没有派发增量推导。"
                + "请先确认只读账号的授权与库名；在库里重新列出对象之前，结构快照会一直保留上一份。";
    }

    /** 旧拉取被拒时给管理台看的那句话。这件事不需要人处理，要说清的是「为什么这次点了刷新却没变化」。 */
    static String newerSnapshotNote(Date pullStart, Date latest) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return "本次结构拉取开始于 " + f.format(pullStart) + "，拉完时库里已经有一份更晚开始拉取的结构快照（"
                + f.format(latest) + "）。本次拉回来的结构比它旧，用它覆盖会把期间已经删掉的表重新写回快照、"
                + "让挂在上面的说明书复活，所以本次没有覆盖结构快照、没有做漂移处置，也没有派发增量推导。"
                + "库里保留的就是那份更新的快照，不需要处理。";
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

    /**
     * @param importanceRank 这张表在连接器目录里从 1 开始的位置。只存位置，不存档位与引用数：位置已经完整表达了
     *                       连接器的排序结果，而把档位塞进 {@link CatalogEntry} 要动所有连接器与 {@code ConnectorToolExecutor}。
     */
    private ConnectorSchema toRow(Connection row, CatalogEntry entry, ObjectDetail detail, Date syncedAt,
                                  int importanceRank) {
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
        // 是拉取开始的时刻，不是描述到这一张的时刻：「旧拉取不许覆盖新快照」按它比。
        s.setSyncedAt(syncedAt);
        // 语义层生成按它切片（snapshotRowsByImportance）。它不进上面的 content_hash，也不该进：
        // 位置随估算行数跨档而变，算进去会把「排名挪了一位」报成结构漂移。
        s.setImportanceRank(importanceRank);
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

    /** 旧形状：只知道这次描述到的那些，并当目录完整。 */
    List<ObjectDiff> diff(Map<String, ConnectorSchema> previous, List<ConnectorSchema> fresh) {
        List<String> names = new ArrayList<>(fresh.size());
        for (ConnectorSchema f : fresh) {
            names.add(f.getObjectName());
        }
        return diff(previous, fresh, ConnectorSemanticService.SnapshotPresence.describedOnly(names));
    }

    /**
     * ADDED / CHANGED 按原名比；<b>REMOVED 只给 ABSENT 的</b>。
     *
     * <p>上一份快照里有、这次没描述到的名字，还可能是：只改了大小写（DESCRIBED）、被挤出了描述上限（LISTED_ONLY）、
     * 目录本身截断了没列到（UNKNOWN）——这三种都不是「表没了」。报成 REMOVED 的后果不止一行假警报：
     * 语义层据此把挂在上面的口径标过期、停止注入，管理台叫业务方去重答一张好好存在的表上的口径。
     */
    List<ObjectDiff> diff(Map<String, ConnectorSchema> previous, List<ConnectorSchema> fresh,
                          ConnectorSemanticService.SnapshotPresence presence) {
        Map<String, ConnectorSchema> now = indexByName(fresh);
        List<ObjectDiff> out = new ArrayList<>();

        for (ConnectorSchema f : fresh) {
            ConnectorSchema p = previous.get(f.getObjectName());
            if (p == null) {
                out.add(new ObjectDiff(f.getObjectName(), "ADDED", List.of()));
            } else if (!Objects.equals(p.getContentHash(), f.getContentHash())) {
                out.add(new ObjectDiff(f.getObjectName(), "CHANGED", fieldDiff(p, f)));
            }
        }
        for (String name : previous.keySet()) {
            if (now.containsKey(name)) {
                continue;
            }
            if (presence.stateOf(name) == ConnectorSemanticService.Presence.ABSENT) {
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
