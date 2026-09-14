package com.jimeng.dataserver.ai.connector.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.claude.service.ClaudeService;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.web.MdcAsyncSupport;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.entity.ConnectorSemantic;
import com.jimeng.persistence.mapper.ConnectionMapper;
import com.jimeng.persistence.mapper.ConnectorSemanticMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语义层<b>推导</b>：拿已经快照下来的结构，叫一次模型，把产出落成语义行。
 *
 * <h3>为什么它和 {@link ConnectorSemanticService} 是两个类</h3>
 * 那个类会被 {@code ConnectorToolExecutor} 注入，而那条构造链
 * （{@code ProviderRegistry → ChatClient → AiConversationLoop → SkillRuntimeService →
 * SkillToolExecutorRegistryService → ConnectorToolExecutor}）不准再出现任何能回到
 * {@code ClaudeService} 的 bean，否则启动期构造循环依赖直接失败（仓库里已经被咬过两次）。
 * 本类是<b>唯一</b>持有 {@link ClaudeService} 的连接器类，且没有任何人从工具链方向注入它。
 * <b>不要把这两个类合并。</b>
 *
 * <h3>读的是快照，不是客户的库</h3>
 * 推导只读 {@code connector_schema}，一个字节都不发到客户那边。理由有两条：一是推导可能被重试、
 * 被人手动触发，每次都去打客户的生产库没有道理；二是<b>推导的对象必须和漂移锚点的对象是同一份</b>——
 * 锚点算的是快照里那一列的指纹，若推导读的是实时结构而锚点算的是快照，两者错开一次刷新，
 * 新写进去的行会<b>当场</b>就是 STALE。
 *
 * <p><b>唯一的例外是「补拉」，而它不是推导。</b>{@link #deriveAsync(Long)} 的后台线程在发现
 * 快照<b>是空的</b>时会先调一次 {@code ConnectorSchemaService.refresh}——那一次会打客户库
 * （catalog + 最多 200 次 describe），补完之后推导照旧只读快照。这个区分别记反了：
 * 「推导要查客户库」会让人以为重跑有客户侧成本，从而不敢用本来很便宜的重跑入口
 * （已建好快照的连接重跑，客户侧零访问）。
 *
 * <h3>三段，不是一段</h3>
 * <ol>
 *   <li><b>S1 既有 SQL 语料</b>（{@link #readCorpusJoins}）——<b>同步</b>，在模型调用之前。
 *       读客户自己写的视图 / 存储过程的定义，纯 DDL、不出一个业务数据值，20 秒封顶。
 *       它挖出来的关系是<b>人写在客户库里的</b>，比模型「看名字像」强一个数量级，
 *       所以要和模型产出一起进那一批 {@code replaceInferred}。</li>
 *   <li><b>推导</b>——叫一次模型，落一批行，写 {@link #SEM_READY}。<b>说明书到这里就可用了。</b></li>
 *   <li><b>S3 表关系采样验证 + S4 列取值域采集</b>（{@link #validate}）——<b>异步、另一个线程池</b>，
 *       在 READY 之后才派发。这两件事自我节流（30/min 和 20/min），最坏各要 20 / 30 分钟。
 *       它们改的是<b>已经落库的行</b>：一条一条把 {@code verified} 和 {@code detail_json} 补上。</li>
 * </ol>
 * <b>第 3 段绝不能并进第 2 段。</b>并进去就要把 {@code semantic_status} 在 RUNNING 上挂半小时——
 * 管理台看起来是挂了，而且 {@link #claim} 的 CAS 会在那半小时里把每一次重跑都挡回去。
 * 而它们晚半小时到，代价只是「关系那一栏在这半小时里写着未经数据验证」——本来就是实话。
 *
 * <h3>失败处置：只记状态，绝不外抛</h3>
 * 语义层是叠加的注解，不是连接可用的前提。推导挂了，conn_catalog / conn_describe 照常工作，
 * 只是模型少了那份说明书。所以 {@link #derive(Long)} 吞掉一切异常、把原因写进
 * {@code connection.semantic_note}，<b>从不向调用方抛</b>——用一个可选增强的故障去否决一次
 * 正常的建连是错的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorSemanticDeriveService {

    // ── connection.semantic_status ──
    public static final String SEM_RUNNING = "RUNNING";
    public static final String SEM_READY = "READY";
    public static final String SEM_FAILED = "FAILED";

    /**
     * 这种连接器压根不提供结构自描述（{@code HttpConnector} 只声明 {@code INVOKE, HEALTH}），
     * 语义层对它<b>不适用</b>——不是失败。
     *
     * <p>为什么不复用 FAILED：一条健康的 HTTP 连接被标成「语义层：失败」，人第一反应是去修一个
     * 根本没坏的东西；而真正失败的那几条混在一堆假警报里，反而不会有人看。
     * 一个开始喊狼来了的状态字段就不再是状态字段了。
     */
    public static final String SEM_NOT_APPLICABLE = "NOT_APPLICABLE";

    /**
     * {@code ConnectorSchemaService.MAX_OBJECTS} 的镜像（那边是 private）。
     *
     * <p>为什么要在这里再写一遍这个数：快照本身就是<b>按对象名字母序截到前 200 个</b>的，字母序靠后的表
     * 根本没有进过 connector_schema，于是也不会有语义、漂移检测对它们永远沉默。这件事在语义层这一侧
     * 必须说出来——「沉默」会被管理台读成「都覆盖了」。两边的值要一起改。
     */
    static final int SCHEMA_SNAPSHOT_CAP = 200;

    /** gloss 列是 varchar(1000)。超了严格模式下报错、非严格模式下<b>静默截断</b>，两种都不能接受。 */
    private static final int GLOSS_MAX = 1000;

    /**
     * {@code object_name} / {@code field_name} / {@code term} 三列都是 varchar(<b>191</b>)。
     *
     * <p>191 不是随手写的：这三列进 {@code uk_connector_semantic}，utf8mb4 每字符 4 字节，
     * 按 255 建索引会超过 InnoDB 单索引 3072 字节的上限，<b>整张表建不出来</b>
     * （mysql:8.0 实测 ERROR 1071）。改这个数之前先去读那条迁移里的注释。
     *
     * <p>这三列<b>只丢不截</b>：它们全都要拿去做<b>精确匹配</b>（表名/列名要对上快照才算得出锚点，
     * 词条要对上才注入得了）。截短的名字一个都匹配不上，却长得和一条真的一模一样——
     * 那是比少一条更糟的东西。
     */
    private static final int NAME_MAX = 191;

    /**
     * detail_json 里那些<b>模型自由发挥</b>的文本的上限。
     *
     * <p>detail_json 是 TEXT（64KB）。模型返回什么由不得我们，而超长写不进去的后果不是「少一行」：
     * {@code replaceInferred} 是一个事务，插到一半报错就整批回滚、旧行已经删了——
     * 和撞唯一键是同一个坑（见 {@link #dedupKey}）。所以进 detail 之前统一收口。
     */
    private static final int DETAIL_TEXT_MAX = 500;

    /** 同上：typical_questions 这类数组也得有个头，不然 10 条变 1000 条一样能把 TEXT 撑爆。 */
    private static final int DETAIL_LIST_MAX = 10;

    /** semantic_note 列是 varchar(512)，留点余量。 */
    private static final int NOTE_MAX = 500;

    /**
     * 认领超过这么久还停在 RUNNING，就认为上一次推导已经死了（部署重启、进程被杀），可以抢占。
     *
     * <p>没有这个逃生口，一次「推到一半赶上发版」会把 {@code semantic_status} 永久钉死在 RUNNING，
     * 此后每一次重新生成都被自己挡住——表现是「点了没反应，而且永远不会好」，
     * 比并发写坏一次严重得多。推导本身是几十秒的量级，30 分钟是纯余量。
     */
    private static final long CLAIM_STALE_MINUTES = 30L;

    /**
     * 采样验证阶段写进度的节奏：每决完这么多条、且距上次至少
     * {@link #VALIDATION_PROGRESS_MIN_INTERVAL_MS} 毫秒，才更新一次 {@code semantic_note}。
     *
     * <p>两个条件都要，缺一不可：只按条数，S3 在缓存热的时候会连着决出十几条、把库刷一串 UPDATE；
     * 只按时间，一条 200 候选的连接在 20 分钟里只会写几十次，看起来却像卡住了。
     */
    private static final int VALIDATION_PROGRESS_EVERY = 10;

    private static final long VALIDATION_PROGRESS_MIN_INTERVAL_MS = 20_000L;

    /**
     * {@code semantic_note} 里「推导结论」和「验证阶段进度」的分隔符。
     *
     * <p>★ 它必须是个<b>能被回读切开</b>的记号，否则第二次跑验证会把上一次的阶段文字当成推导结论
     * 再接一段，note 于是变成 A ｜ B ｜ C ｜ …，最后被 varchar(512) 从尾巴截掉——
     * 被截掉的正好是最新那一段。见 {@link #notePrefix}。
     */
    private static final String NOTE_SEP = " ｜ ";

    /**
     * 拼对照键用的分隔符。用不可打印的 {@code U+0001} 而不是点或下划线：表名列名里合法地含点、
     * 含下划线，用它们拼出来的键会把 {@code a.b} + {@code c} 和 {@code a} + {@code b.c} 撞成同一个，
     * 而撞了之后的表现是「某条关系的结论落在了另一条关系上」——没有任何地方会报错。
     */
    private static final String KEY_SEP = "\u0001";

    /** 补写字段行时，gloss 里允许列出的取值文本长度。超了只报个数，理由见 {@link #valueGloss}。 */
    private static final int GLOSS_VALUES_MAX = 600;

    /** 拼进阶段 note 时，给「推导结论」留的字数。剩下的留给进度，两边都要放得下。 */
    private static final int NOTE_PREFIX_MAX = 240;

    /** 模型产出里认得的四个键。一个都没有 = 它根本没做这件事，见 {@link #hasAnyExpectedKey}。 */
    private static final List<String> PAYLOAD_KEYS = List.of("objects", "fields", "joins", "ambiguities");

    private static final Set<String> CARDINALITIES = Set.of("1:1", "1:N", "N:1", "N:N");

    private final ConnectorSchemaService schemaService;
    private final ConnectorSemanticService semanticService;
    private final ConnectionMapper connectionMapper;
    private final ClaudeService claudeService;
    private final ConnectorProperties properties;

    /**
     * S1：客户自己写在库里的视图 / 存储过程。<b>推导前就跑，同步</b>，理由见
     * {@link #readCorpusJoins}。它是纯 DDL（第 1 档），不读一个业务数据值。
     */
    private final SemanticSqlCorpusReader corpusReader;

    /** S3：表关系的采样验证。<b>只在验证阶段用</b>，绝不在推导里同步跑（最坏 20 分钟）。 */
    private final SemanticJoinValidator joinValidator;

    /** S4：列取值域采集。同上，最坏 30 分钟。 */
    private final SemanticValueProfiler valueProfiler;

    /**
     * 验证阶段要<b>逐条原地改</b>已经落库的语义行（{@code verified} + {@code detail_json}），
     * 而 {@link ConnectorSemanticService} 今天只有「整批替换 INFERRED」这一个写入口——
     * 拿它来做增量更新等于每决完一条就把整层说明书删了重插。所以这里直接用 mapper。
     *
     * <p><b>安全前提：</b>本类所有走 mapper 的写都带主键（{@code updateById}）或由本类自己
     * 组装 {@code tenant_id} + {@code connector_id}（插入），而且<b>永远跑在真实租户上下文里</b>
     *（{@link #runValidation} 会 {@code TenantContext.set}），所以 MyBatis-Plus 的租户拦截器
     * 照常注入条件。绝不要在 {@code runAsSystem} 里调它们——那时租户条件会整个消失。
     */
    private final ConnectorSemanticMapper semanticMapper;

    /** 值域阶段收尾时补一条阶段级审计，动作名 {@code platform.semantic_values}，理由见那个常量。 */
    private final ConnectorAuditService auditService;

    /** 字段名必须叫 streamExecutor：容器里有多个 {@code ThreadPoolTaskExecutor}，靠字段名消歧。 */
    private final ThreadPoolTaskExecutor streamExecutor;

    /**
     * 采样验证阶段专用池。<b>字段名必须叫 semanticStageExecutor</b>（同上，按名字消歧）。
     *
     * <p>★ 它和 {@link #streamExecutor} 的区别不是「又一个池子」：那个是
     * {@code SynchronousQueue + CallerRunsPolicy}，满了会把任务<b>就地跑在调用线程上</b>，
     * 而这里的任务最长跑半小时。派发它的调用线程是一次建连或一次「重新生成」的 HTTP 请求线程。
     * 详见 {@code StreamExecutorConfig#semanticStageExecutor()}。
     */
    private final ThreadPoolTaskExecutor semanticStageExecutor;

    /**
     * 推导用的模型。留空即<b>不下发 model</b>，由 {@code GenericChatClient} 回落到
     * {@code providers.<active>.chat.model}（它是 putIfAbsent，缺 model 不会报错）。
     *
     * <p>注意一个静默点：配一个 {@code ai_model} 表里<b>不存在</b>的值同样不会报错——
     * {@code ModelResolver} 未命中就回落全局 provider，看起来一切正常，实际用的根本不是配的那个。
     * 所以每次推导都会把「本次请求的模型名」打进日志，否则配错是查不出来的。
     */
    @Value("${connector.semantic.infer-model:}")
    private String inferModel;

    /**
     * 采样验证阶段（S3 + S4）的总开关。
     *
     * <p>关掉之后推导照跑、说明书照出，只是每条关系永远停在 {@code verified=NONE}
     *（注入层据此如实说「未经数据验证」）。<b>不要用它来「省事」</b>：
     * 关掉它不会让任何一条错误的关系消失，只会让没人知道它是错的。
     */
    @Value("${connector.semantic.validate-stage-enabled:true}")
    private boolean validateStageEnabled = true;

    /**
     * 本进程内正在跑验证阶段的连接。
     *
     * <h3>为什么这里<b>没有</b>像推导那样的库级 CAS 认领</h3>
     * 推导的认领戳写在 {@code connection.semantic_status} 上，而这个阶段的第一条纪律就是
     * <b>不许占用那个状态</b>（见 {@link #validateAsync}）：占半小时会让管理台看起来像挂了，
     * 还会把推导自己的重试入口一起堵死。
     *
     * <p>于是这里只有一道进程内的闸，多副本下确实挡不住两个副本同时验同一条连接。
     * 这个代价是有界的，而且会自己收敛：候选是按 {@code verified != NONE} 过滤出来的，
     * 每决完一条就<b>立刻</b>落库，所以跑在后面的那一轮每多走一步、下一轮要做的就少一条。
     * 最坏情况是对同一批候选多打一遍探查——花的是客户的配额，不会写出错误的结论。
     * 真要做成分布式互斥，该加的是一列独立的 {@code semantic_validate_claim_at}，
     * 而不是去借 {@code semantic_status}。
     */
    private final Set<Long> validating = ConcurrentHashMap.newKeySet();

    // ================================================================ 入口

    /**
     * 后台推导。给请求线程调（建连 / 刷新结构成功之后），立刻返回。
     *
     * <p><b>streamExecutor 是 queueCapacity=0 + CallerRunsPolicy：池子打满时这个任务会就地跑在调用
     * 线程上。</b>对一次 create() 请求来说，那意味着这次建连的 HTTP 响应要一直等到模型返回
     * （十几秒，甚至超过网关读超时）才回得去。这是刻意接受的：另一条路是丢任务，表现为
     * 「连接建好了、但永远没有语义层，而且没有任何地方说明为什么」——那正是本项目反复吃亏的静默失败。
     * 慢一次看得见，丢一次看不见。真要避免，该做的是给推导单开一个带队列的池，而不是允许丢弃。
     *
     * <p>{@code MdcAsyncSupport.wrap} 只捎带 MDC / TenantContext / Agent 上下文，
     * <b>不传 RequestContextHolder</b>，所以这条路写进去的行 {@code create_user} 是 null。
     * 这是已知且已写进 DDL 注释的代价：机器推的行本来就没有「谁填的」，
     * 人答的口径另走 {@code answered_by} 显式存。
     *
     * <p><b>★ 推导成功之后还会再派发一段采样验证</b>（{@link #dispatchValidation}），
     * 它跑在<b>另一个</b>线程池上、不占 {@code semantic_status}，最长半小时。
     * 也就是说这一个调用背后会分两次去打客户的库：先是推导可能的补拉结构，之后是验证的探查与取值。
     * 两笔账的大小和落点见那两个方法的 javadoc。
     */
    public void deriveAsync(Long connectorId) {
        if (connectorId == null) {
            return;
        }
        // 第二条通道：wrap 已经会捎带 TenantContext，这里再显式取一份带进去。
        // 租户一旦丢了，推导写库是【静默】错的——查询照常成功，只是落在 __no_tenant__ 哨兵上什么都查不到。
        final String tenantId = TenantContext.get();
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("语义层推导被跳过：当前线程没有租户上下文 connectorId={}", connectorId);
            return;
        }
        streamExecutor.execute(MdcAsyncSupport.wrap("semantic-" + connectorId,
                () -> runDerive(connectorId, tenantId)));
    }

    /**
     * 后台线程上的那一遍。<b>「接入即触发」全靠它</b>，所以补拉结构这件事只能在这里做。
     *
     * <p>为什么补拉必须待在后台线程上：{@code ConnectorSchemaService.refresh} 会打客户库
     * （1 次 catalog + 最多 200 次 describe，跨公网可能几十秒），中间还夹着一段落库事务。
     * 挪到建连的请求线程上，就是让建连接口一直等到客户库跑完才返回——大库上等于必然超时。
     */
    void runDerive(Long connectorId, String tenantId) {
        if (!TenantContext.isSet() && tenantId != null) {
            TenantContext.set(tenantId);
        }
        derive(connectorId, true);
    }

    /**
     * 同步推导一次。<b>不抛异常</b>：成功与失败都只体现在返回值和 {@code connection.semantic_*} 三列上。
     *
     * <p><b>这个公开入口只读快照，一个字节都不发到客户那边</b>（它可能被请求线程调）。
     * 快照是空的就如实失败；去补拉的是后台线程那条路（{@link #runDerive}）。
     *
     * <p>刻意<b>没有</b> {@code @Transactional}：中间夹着一次可能几十秒的模型调用，把数据库事务挂在
     * 上面纯属占着连接不干活。真正需要原子性的只有「删旧的推断行 + 插新的」那一步，
     * 它的事务在 {@link ConnectorSemanticService#replaceInferred} 里。
     */
    public DeriveResult derive(Long connectorId) {
        return derive(connectorId, false);
    }

    /**
     * @param mayBootstrapSnapshot 快照为空时允不允许先去客户库补拉一次。只有后台线程给 true，
     *                             理由见 {@link #runDerive}。<b>补拉只做一次</b>：补完还是空的，
     *                             才是真的失败。
     */
    private DeriveResult derive(Long connectorId, boolean mayBootstrapSnapshot) {
        ConnectorProperties.Semantic cfg = properties.getSemantic();
        if (!cfg.isEnabled()) {
            // 不标 FAILED：开关关着不是失败。但也不能什么都不写——「点了没反应」是最难查的一类。
            String note = "语义层推导已关闭（connector.semantic.enabled=false）";
            log.info("跳过语义层推导，开关已关 connectorId={}", connectorId);
            writeStatus(connectorId, null, note, false, null);
            return DeriveResult.builder().ok(false).note(note).build();
        }

        Connection conn = connectionMapper.selectById(connectorId);   // 租户过滤由拦截器注入
        if (conn == null) {
            // 存在性校验必须在认领之前：认领是一次写，对一条不存在、或不属于本租户的连接不该发出去。
            log.warn("语义层推导找不到连接（或不属于当前租户） connectorId={}", connectorId);
            return DeriveResult.builder().ok(false).note("连接不存在").build();
        }

        Date claimAt = claim(connectorId);
        if (claimAt == null) {
            // 建连自动推 + 有人同时点「重新生成」是真会发生的。两次并发推导会让 semantic_status
            // 和库里的行各走各的：后完成的那次覆盖状态，先完成的那次的行留在库里，从此对不上。
            String note = "同一条连接上已有一次推导在进行中，本次跳过";
            log.info("语义层推导被跳过：认领不到 connectorId={}", connectorId);
            return DeriveResult.builder().ok(false).note(note).build();
        }

        // notes 给人看（管理台那行字），gaps 给模型看（「这份材料缺了什么」）。
        // 两者刻意分开：补拉、JSON 被截断这类过程信息对模型没有意义，塞进提示词只会让
        // 「这份材料不完整」那一段变成噪音，而那一段恰恰是要模型当真的。
        List<String> notes = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        Stats st = new Stats();
        try {
            List<ConnectorSchema> rows = schemaService.currentRows(connectorId);
            Integer sourceTotal = null;
            Bootstrap boot = null;
            if (rows.isEmpty() && mayBootstrapSnapshot) {
                boot = bootstrapSnapshot(connectorId);
                if (!boot.applicable()) {
                    String note = "该连接器不提供结构自描述，语义层不适用"
                            + (boot.failure() == null ? "" : "（" + boot.failure() + "）");
                    writeStatus(connectorId, SEM_NOT_APPLICABLE, note, true, claimAt);
                    return DeriveResult.builder().ok(false).note(note).build();
                }
                notes.add(boot.failure() == null
                        ? "结构快照原本是空的，已自动拉取一次结构后再推导"
                        : "结构快照原本是空的，自动拉取结构没成功：" + boot.failure());
                sourceTotal = boot.totalObjects();
                // 补拉只做一次。补完还是空的，才是真的失败。
                rows = schemaService.currentRows(connectorId);
            }
            if (rows.isEmpty()) {
                // 三种「空」要分得开：还没拉过 / 拉失败了 / 拉过了但这个账号一张表都看不见。
                // 混成一句话，人就只能挨个去猜下一步该做什么。
                String note;
                if (boot == null) {
                    note = "结构快照为空，请先在管理台「刷新结构」，再生成语义层";
                } else if (boot.failure() != null) {
                    note = "结构快照为空，自动拉取结构也没成功：" + boot.failure()
                            + "。请在管理台点「刷新结构」重试";
                } else {
                    note = "已自动拉取一次结构，但这个只读账号看不到任何对象，没有结构就没法生成语义层。"
                            + "请确认账号的授权范围后，在管理台点「刷新结构」重试";
                }
                writeStatus(connectorId, SEM_FAILED, note, true, claimAt);
                return DeriveResult.builder().ok(false).note(note).build();
            }
            // 推导开始那一刻的快照指纹。落库之前要再比一次——中途被刷新过，这批行锚的就是旧结构。
            String stamp = snapshotStamp(rows);

            // 快照自己是【按对象名字母序截到前 200 个】的，字母序靠后的表根本没进过 connector_schema。
            // 这件事在语义层这一侧必须说出来：沉默会被读成「都覆盖了」。
            int beyond = 0;
            boolean beyondIsLowerBound = false;
            if (sourceTotal != null && sourceTotal > rows.size()) {
                beyond = sourceTotal - rows.size();
                gaps.add("客户库里共 " + sourceTotal + " 个对象，结构快照按名字字母序只覆盖了前 "
                        + rows.size() + " 个，其余的没有进过快照，因此也不会有语义");
            } else if (rows.size() >= SCHEMA_SNAPSHOT_CAP) {
                // 这一次没有补拉，真实总数无从知道。取 1 只是为了让「还有你没看到的表」这句话成立，
                // 数不出确切值不是把它说成「覆盖完整」的理由——后者正是 QueryResult 和 catalog
                // 已经各栽过一次的那类 bug。
                beyond = 1;
                beyondIsLowerBound = true;
                gaps.add("结构快照本身已在 " + SCHEMA_SNAPSHOT_CAP
                        + " 个对象处按名字字母序截断，字母序靠后的表没有进过快照，因此也不会有语义");
            }

            Map<String, Map<String, FieldDetail>> fieldsByObject = parseFields(rows);
            Digest digest = buildDigest(rows, fieldsByObject, cfg.getMaxDigestChars(), beyond, beyondIsLowerBound);
            gaps.addAll(digest.notes());
            notes.addAll(gaps);

            // ★ S1 在模型之前、且在同一个方法里同步跑，理由见 readCorpusJoins 的 javadoc。
            List<ConnectorSemantic> corpusJoins = readCorpusJoins(connectorId, fieldsByObject, notes);

            String raw = callModel(connectorId, conn, digest, cfg, gaps);
            Map<String, Object> parsed = parseJson(raw, notes);

            // 一次查，两处用：既是「哪些口径人已经答过了」，也是「上一版说明书里有没有东西可丢」。
            List<ConnectorSemantic> existing = existingRows(connectorId);
            List<ConnectorSemantic> fresh = toRows(parsed, fieldsByObject, answeredTerms(existing), st, corpusJoins);

            String refusal = refuseEmptyReplace(parsed, fresh, corpusJoins, existing, st);
            if (refusal != null) {
                // ★ 空产出【不替换】。replaceInferred 是先物理删再插，拿一份空的去调它等于把上一版
                //   说明书清空，然后写一个 READY——看起来像「重新生成成功了，只是什么都没生成」，
                //   而人是不会去查一次成功的操作的。宁可留着旧的并明说这次失败了。
                log.warn("语义层推导产出为空，已保留上一版 connectorId={}：{}", connectorId, refusal);
                writeStatus(connectorId, SEM_FAILED, refusal, true, claimAt);
                return DeriveResult.builder().ok(false)
                        .droppedGuess(st.droppedGuess)
                        .droppedUnknown(st.droppedUnknown)
                        .droppedTooLong(st.droppedTooLong)
                        .skippedAnswered(st.droppedAnswered)
                        .truncated(digest.includedObjects() < digest.totalObjects())
                        .note(refusal).build();
            }

            String nowStamp = snapshotStamp(schemaService.currentRows(connectorId));
            if (!stamp.equals(nowStamp)) {
                String note = "结构在推导期间已刷新，本批产出锚的是旧结构，已整批作废，请重新生成";
                log.warn("语义层推导期间结构被刷新，本批作废 connectorId={} 开始={} 现在={}",
                        connectorId, stamp, nowStamp);
                writeStatus(connectorId, SEM_FAILED, note, true, claimAt);
                return DeriveResult.builder().ok(false).note(note).build();
            }

            int n = semanticService.replaceInferred(connectorId, fresh);

            String note = summarize(st, digest, notes);
            writeStatus(connectorId, SEM_READY, note, true, claimAt);

            // ★ 说明书【已经可用了】才派发验证阶段：READY 已经写下去，关系那一栏如实写着「未经数据验证」。
            //   验证是把那句话往前推一格，不是这份说明书能不能用的前提——所以它绝不该挂在 READY 前面。
            dispatchValidation(connectorId, tenantOf(conn), note);

            log.info("语义层推导完成 connectorId={} 写入 {} 行：表用途 {} / 字段 {} / 关系 {} / 待确认口径 {}；"
                            + "丢弃：无依据 {}、名字对不上 {}、重复 {}、超长 {}；已答过的口径跳过 {}",
                    connectorId, n, st.objects, st.fields, st.joins, st.caveats,
                    st.droppedGuess, st.droppedUnknown, st.droppedDup, st.droppedTooLong, st.droppedAnswered);
            return DeriveResult.builder()
                    .ok(true)
                    .objectCount(st.objects)
                    .fieldCount(st.fields)
                    .joinCount(st.joins)
                    .caveatCount(st.caveats)
                    .droppedGuess(st.droppedGuess)
                    .droppedUnknown(st.droppedUnknown)
                    .droppedTooLong(st.droppedTooLong)
                    .skippedAnswered(st.droppedAnswered)
                    .truncated(digest.includedObjects() < digest.totalObjects())
                    .note(note)
                    .build();
        } catch (Exception e) {
            // 这里必须吞掉一切。推导是建连之后顺手做的事，让它把建连整个搞失败是本末倒置。
            String reason = describe(e);
            log.error("语义层推导失败 connectorId={}: {}", connectorId, reason, e);
            writeStatus(connectorId, SEM_FAILED, "推导失败：" + reason, true, claimAt);
            return DeriveResult.builder().ok(false).note("推导失败：" + reason).build();
        }
    }

    // ================================================================ 补拉结构（唯一会碰客户库的一步）

    /**
     * 补拉一次结构快照。<b>这是本类唯一会打客户库的地方，而且只在快照为空时走。</b>
     *
     * <p>它存在的理由很具体：{@code ConnectorService.create()} 只插 connection 一行，
     * 全仓库写 {@code connector_schema} 的只有手动端点 {@code POST /{id}/schema/refresh}。
     * 没有这一步，「接入即触发」的那次推导<b>必然</b>读到空快照、必然写 FAILED——
     * 于是每一条新建的连接都显示「语义层：失败」，而 P1 的主打卖点正是「给个只读账号就能用」。
     *
     * @return {@code applicable=false} 表示这种连接器根本不提供自描述（HTTP 只有 INVOKE/HEALTH），
     *         该记 {@link #SEM_NOT_APPLICABLE} 而不是 FAILED；{@code failure!=null} 才是真失败
     */
    private Bootstrap bootstrapSnapshot(Long connectorId) {
        try {
            ConnectorSchemaService.SnapshotResult r = schemaService.refresh(connectorId);
            int count = r == null ? 0 : r.getObjectCount();
            int total = r == null ? 0 : r.getTotalObjects();
            log.info("语义层推导前自动补拉结构 connectorId={} 落库对象={} 客户库共={}", connectorId, count, total);
            return new Bootstrap(true, total > 0 ? total : null, null);
        } catch (ServiceException e) {
            // refresh 对「这种连接器不支持自描述」「这条连接的自描述能力不可用」都抛 OPERATION_UNSUPPORTED。
            // 这两种都不是故障，是语义层对它不适用。
            boolean unsupported = ExceptionCode.OPERATION_UNSUPPORTED.getResultCode().equals(e.getRespCode());
            log.info("语义层推导前补拉结构未成功 connectorId={} unsupported={} reason={}",
                    connectorId, unsupported, e.getRespMsg());
            return new Bootstrap(!unsupported, null, e.getRespMsg());
        } catch (Exception e) {
            // 补拉失败不外抛：它只是让这次推导没料可用，不该把建连或重跑变成一个红色报错。
            String reason = describe(e);
            log.warn("语义层推导前补拉结构失败 connectorId={}: {}", connectorId, reason);
            return new Bootstrap(true, null, reason);
        }
    }

    // ================================================================ S1：客户自己写的 SQL 语料

    /**
     * 读一遍客户自己写的视图 / 存储过程，把里面的等值 join 变成语义行。
     *
     * <h3>为什么它<b>同步跑在推导里</b>，而 S3 / S4 必须异步</h3>
     * 判据是「打客户库的成本」和「产出什么时候有用」，不是「它是不是新东西」：
     * <ul>
     *   <li><b>成本</b>：本阶段读的是 {@code information_schema.views / routines}——纯 DDL，
     *       第 1 档，一个业务数据值都不出库，总时间还有 {@code corpus.parse-budget-ms}（默认 20 秒）
     *       封顶。S3 最坏 20 分钟、S4 最坏 30 分钟，量级差两位数。</li>
     *   <li><b>产出的位置</b>：它的产出是<b>说明书本身的一部分</b>——必须在
     *       {@code replaceInferred} 那一批里，否则下一次推导会把它整批物理删掉。
     *       而 S3 / S4 改的是<b>已经落库的行</b>，晚半小时到只是晚半小时。</li>
     * </ul>
     * 所以它在模型调用之前跑：早跑早知道客户有没有视图可挖，而且它失败也不阻断推导。
     *
     * <h3>★ 名字匹配必须大小写不敏感</h3>
     * 语料里的表名列名是<b>视图正文里的写法</b>，而快照里的是 {@code information_schema} 的写法。
     * 两者一不一致取决于 MySQL 的 {@code lower_case_table_names}——也就是说，取决于<b>客户</b>
     * 那台机器怎么装的。严格匹配在一部分客户那里会把关系整批丢掉，而表现只是「这次挖到 0 条」，
     * 不报错、不出异常、没有任何人会发现。行里存的名字则一律换成<b>快照的写法</b>：
     * 锚点、注入层、S3 的候选都要拿它去和快照精确对齐。
     *
     * @param notes 过程说明，会进 {@code semantic_note} 给人看
     * @return 已对齐快照、可以直接进 {@code byKey} 的 JOIN 行；任何失败都返回空列表，不抛
     */
    private List<ConnectorSemantic> readCorpusJoins(Long connectorId,
                                                    Map<String, Map<String, FieldDetail>> fieldsByObject,
                                                    List<String> notes) {
        if (!TenantContext.isSet()) {
            // ConnectorGateway.executeAsPlatform 要的是【真实】租户，runAsSystem 不算。
            // 没有就别发这一趟，更不要让它在网关那里以一句通用文案失败、看起来像客户库出了问题。
            log.warn("跳过既有 SQL 语料挖掘：当前线程没有租户上下文 connectorId={}", connectorId);
            return List.of();
        }
        SemanticSqlCorpusReader.CorpusResult r;
        try {
            r = corpusReader.read(connectorId);
        } catch (RuntimeException e) {
            // 契约上它从不抛，这里兜底：语料是锦上添花，绝不能把一次正常推导拖失败。
            log.warn("既有 SQL 语料挖掘抛了异常（契约上不该），本次跳过 connectorId={}: {}",
                    connectorId, describe(e));
            return List.of();
        }
        if (r == null) {
            return List.of();
        }
        if (!r.isOk()) {
            if (!blank(r.getNote())) {
                notes.add(clip(r.getNote(), DETAIL_TEXT_MAX));
            }
            return List.of();
        }
        List<SemanticSqlCorpusReader.CorpusJoin> joins =
                r.getJoins() == null ? List.of() : r.getJoins();
        if (joins.isEmpty()) {
            // 「看了 N 个视图、一条关系都没挖到」和「压根没去看」是两件事，note 要分得开。
            if (r.getViewsSeen() > 0 || r.getRoutinesSeen() > 0) {
                notes.add("客户自己写的视图/存储过程共 " + (r.getViewsSeen() + r.getRoutinesSeen())
                        + " 个，没有解析出可用的等值关系");
            }
            return List.of();
        }

        // 折叠索引：折叠名 → 快照里的原写法。putIfAbsent 而不是 put——
        // 真出现两张折叠后同名的表时取先出现的那张，至少是确定性的。
        Map<String, String> objByFolded = new LinkedHashMap<>();
        Map<String, Map<String, String>> colsByFolded = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, FieldDetail>> e : fieldsByObject.entrySet()) {
            String fo = fold(e.getKey());
            objByFolded.putIfAbsent(fo, e.getKey());
            Map<String, String> cols = colsByFolded.computeIfAbsent(fo, k -> new LinkedHashMap<>());
            for (String c : e.getValue().keySet()) {
                cols.putIfAbsent(fold(c), c);
            }
        }

        List<ConnectorSemantic> out = new ArrayList<>();
        int unmatched = 0;
        for (SemanticSqlCorpusReader.CorpusJoin cj : joins) {
            String lo = objByFolded.get(fold(cj.getLeftObject()));
            String ro = objByFolded.get(fold(cj.getRightObject()));
            if (lo == null || ro == null) {
                unmatched++;
                continue;
            }
            String lc = colsByFolded.get(fold(cj.getLeftObject())).get(fold(cj.getLeftColumn()));
            String rc = colsByFolded.get(fold(cj.getRightObject())).get(fold(cj.getRightColumn()));
            if (lc == null || rc == null) {
                unmatched++;
                continue;
            }
            FieldDetail left = lookup(fieldsByObject, lo, lc);
            FieldDetail right = lookup(fieldsByObject, ro, rc);
            if (left == null || right == null) {
                unmatched++;
                continue;
            }
            out.add(corpusRow(lo, lc, ro, rc, cj, left, right));
        }

        // 「挖到 N 条」这一个数字会把三种完全不同的情况混在一起，所以把对不上的那一档单独说出来：
        // unmatched 偏高通常不是解析坏了，而是这些表压根没进过快照（快照自己按字母序截到 200）。
        StringBuilder note = new StringBuilder("从客户自己写的视图/存储过程里挖到 ")
                .append(out.size()).append(" 条表关系（均未经数据验证）");
        if (unmatched > 0) {
            note.append("，另有 ").append(unmatched).append(" 条引用的表或列不在结构快照里，已丢弃");
        }
        notes.add(note.toString());
        log.info("既有 SQL 语料挖掘完成 connectorId={} 视图 {}/{} 存储过程 {}/{} 关系 {} 对不上快照 {} "
                        + "读不到定义 {} 解析不了 {}",
                connectorId, r.getViewsParsed(), r.getViewsSeen(),
                r.getRoutinesParsed(), r.getRoutinesSeen(), out.size(), unmatched,
                r.getViewDefinitionsDenied() + r.getRoutineDefinitionsDenied(),
                r.getViewsSkippedUnparseable() + r.getRoutinesSkippedUnparseable());
        return out;
    }

    /**
     * 一条语料关系 → 一行 {@code SCOPE_JOIN}。
     *
     * <h3>★ {@code verified} 恒为 {@link ConnectorSemanticService#V_NONE}，basis 里必须留着「未经数据验证」</h3>
     * 视图里的 {@code a.x = b.y} 是一句<b>人写的话</b>：「我这样连过」。它不是「数据对得上」的证据——
     * {@code a.status = b.status} 也是一个完全合法的等值连接，两边却谁也不引用谁。
     * 把它标成已验证，等于凭空声称我们做过一次并不存在的采样；更糟的是 S3 之后<b>不会再去核它</b>
     *（候选是按 {@code verified == NONE} 挑的），于是这条从没被验证过的关系永远不会被验证。
     * 这里给出的是<b>更好的候选</b>，不是结论。
     *
     * <p>{@code cardinality} 同理留空：视图里的一个等值条件不含任何基数信息，
     * 而基数正是决定「能不能自动接进 join path」的那一项（接错了 SUM 出来的金额会凭空变大）。
     */
    private ConnectorSemantic corpusRow(String obj, String col, String toObj, String toCol,
                                        SemanticSqlCorpusReader.CorpusJoin cj,
                                        FieldDetail left, FieldDetail right) {
        String provenance = clip(cj.basis(), DETAIL_TEXT_MAX);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("to_object", toObj);
        detail.put("to_column", toCol);
        detail.put("cardinality", null);
        detail.put("basis", clip(provenance + "；未经数据验证", DETAIL_TEXT_MAX));
        // ★ 来源单独存一份，别只活在 basis 里：S3 决完之后会用实测结论【覆盖】basis
        //（JoinVerdict.detailPatch 就是这么做的），来源若只存在那个键上会被一起抹掉，
        //   而它恰恰是这条关系最硬的那半边依据——「客户自己在 v_x 里这样连过」。
        detail.put("source_sql", provenance);

        ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_JOIN, obj, col, "");
        row.setGloss(clip("关联 " + toObj + "." + toCol + "。" + provenance
                + "。本条未经数据验证——视图里的一个等值条件说明「有人这样连过」，"
                + "不说明「两边的数据对得上」，join 前建议先看该列的取值分布。", GLOSS_MAX));
        row.setDetailJson(toJson(detail));
        // evidence / confidence 都由 reader 自己给：那两件事（归哪一档依据、见过几处）只有它知道。
        row.setEvidence(cj.evidence());
        row.setConfidence(cj.confidence());
        row.setAnchorKind(ConnectorSemanticService.ANCHOR_JOIN);
        row.setAnchorHash(ConnectorSemanticService.joinAnchor(left, right));
        row.setVerified(ConnectorSemanticService.V_NONE);
        return row;
    }

    // ================================================================ 结构摘要

    /** 从快照的 detail_json 里把字段还原成 {@link FieldDetail}——算锚点要用它，拼摘要也要用它。 */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, FieldDetail>> parseFields(List<ConnectorSchema> rows) {
        Map<String, Map<String, FieldDetail>> out = new LinkedHashMap<>();
        for (ConnectorSchema r : rows) {
            Map<String, FieldDetail> cols = new LinkedHashMap<>();
            out.put(r.getObjectName(), cols);
            if (r.getDetailJson() == null || r.getDetailJson().isBlank()) {
                continue;
            }
            try {
                Map<String, Object> m = CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
                Object f = m.get("fields");
                if (!(f instanceof List<?> list)) {
                    continue;
                }
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> fm)) {
                        continue;
                    }
                    Object name = fm.get("name");
                    if (name == null) {
                        continue;
                    }
                    // nullable 缺失时按「可空」处理：宁可让锚点多算一次不一致，也不要把一个未知说成 NOT NULL。
                    cols.put(String.valueOf(name), new FieldDetail(
                            String.valueOf(name),
                            asString(fm.get("type")),
                            !Boolean.FALSE.equals(fm.get("nullable")),
                            asString(fm.get("comment")),
                            asString(fm.get("extra"))));
                }
            } catch (Exception e) {
                // 单张表的快照 JSON 坏了不该让整次推导失败：这张表没字段可推，其余的照推。
                log.warn("解析结构快照失败，本次跳过该对象的字段 objectName={}", r.getObjectName(), e);
            }
        }
        return out;
    }

    /**
     * 拼摘要。<b>截断按整张表，绝不截半张表。</b>
     *
     * <p>半张表比少一张表糟得多：模型会对着看不见的列写字段含义，还会拿残缺的列集合去判断这张表是
     * 干什么的——而这两条产出看起来和正常产出一模一样。被丢掉的表名全部记进日志和 semantic_note；
     * 静默截断会被读成「都覆盖了」，那正是 {@code ConnectorSchemaService.SnapshotResult.truncated}
     * 这个标志存在的原因。
     *
     * @param beyondSnapshot      快照<b>之外</b>还有多少个对象（快照本身被 200 截掉的那些）。
     *                            它必须一路带到 {@code totalObjects} 上，否则 200 张表的库会得到
     *                            「覆盖 200/200」这种读起来像全覆盖的结论。
     * @param beyondIsLowerBound  上面那个数是不是「至少这么多」（没补拉过就不知道确切值）
     */
    private Digest buildDigest(List<ConnectorSchema> rows,
                               Map<String, Map<String, FieldDetail>> fieldsByObject,
                               int maxChars, int beyondSnapshot, boolean beyondIsLowerBound) {
        StringBuilder sb = new StringBuilder();
        List<String> dropped = new ArrayList<>();
        int included = 0;
        boolean full = false;
        for (ConnectorSchema r : rows) {
            String block = renderObject(r, fieldsByObject.get(r.getObjectName()));
            // 第一张表无论多大都要进去，否则遇到一张超宽的表会得到一份空摘要。
            // 一旦超限就不再往里塞（而不是挑小的继续填）：让「覆盖到哪儿为止」是一句能说清楚的话。
            if (full || (included > 0 && sb.length() + block.length() > maxChars)) {
                full = true;
                dropped.add(r.getObjectName());
                continue;
            }
            sb.append(block);
            included++;
        }

        List<String> notes = new ArrayList<>();
        if (!dropped.isEmpty()) {
            String head = String.join("、", dropped.subList(0, Math.min(dropped.size(), 10)));
            notes.add("结构摘要超过 " + maxChars + " 字符上限，" + dropped.size()
                    + " 个对象未送进模型（" + head + (dropped.size() > 10 ? " 等" : "") + "），它们不会有语义");
            log.warn("语义层结构摘要截断：共 {} 个对象，本次覆盖 {} 个，未覆盖 {} 个：{}",
                    rows.size(), included, dropped.size(), dropped);
        }
        if (included == 1 && sb.length() > maxChars) {
            notes.add("第一个对象单独就超过了摘要上限，本次只覆盖了它一个");
        }
        int total = rows.size() + Math.max(0, beyondSnapshot);
        return new Digest(sb.toString(), included, total, beyondIsLowerBound && beyondSnapshot > 0, notes);
    }

    /**
     * 一张表一段。列注释为空时照样留出那一格——「客户没写注释」本身就是模型要知道的事实
     * （没有 COMMENT 这一档依据可用），不能让它看起来像是我们没给。
     */
    private String renderObject(ConnectorSchema r, Map<String, FieldDetail> cols) {
        StringBuilder b = new StringBuilder();
        b.append("## ").append(r.getObjectName())
                .append(" [").append(r.getObjectType() == null ? "TABLE" : r.getObjectType()).append("] ")
                .append(blank(r.getObjectComment()) ? "(无表注释)" : r.getObjectComment())
                .append('\n');
        if (cols == null || cols.isEmpty()) {
            // 账号权限只到部分表时，ConnectorSchemaService 会存一条只有名字的行。
            // 如实说出来，别让模型把「没取到」读成「这是张空表」。
            b.append("(本表字段未取到，不要为它写字段或关系)\n");
        } else {
            for (FieldDetail f : cols.values()) {
                b.append(f.name()).append('|')
                        .append(nz(f.type())).append('|')
                        .append(f.nullable() ? "NULL" : "NOT NULL").append('|')
                        .append(nz(f.comment())).append('|')
                        .append(nz(f.extra())).append('\n');
            }
        }
        b.append('\n');
        return b.toString();
    }

    // ================================================================ 模型调用

    /**
     * @param gaps 这份材料缺了什么，<b>原样告诉模型</b>。少给这一段的后果是它把残卷读成全本：
     *             为看不见的表写关系、或者断言某个概念在这个库里不存在。
     *             提示词里那一句「没有出现在下面的表不要为它写任何条目」由 includedObjects &lt;
     *             totalObjects 触发，所以那两个数必须把快照自己的 200 截断也算进去。
     */
    private String callModel(Long connectorId, Connection conn, Digest d, ConnectorProperties.Semantic cfg,
                             List<String> gaps) {
        // 必须用【可变】集合：下游 ModelResolver / GenericChatClient 会就地改写 body 与 messages。
        // Map.of / List.of 会在那里抛 UnsupportedOperationException，而它的 getMessage() 是 null，
        // 在结果里只表现为一个孤零零的 "null"，极难排查。SkillEvalService:332-336 是用血记下来的同一条。
        Map<String, Object> body = new LinkedHashMap<>();
        String model = blank(inferModel) ? null : inferModel.trim();
        if (model != null) {
            body.put("model", model);
        }
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("system", SemanticPrompts.deriveSystem());

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", SemanticPrompts.deriveUser(conn.getName(), conn.getKind(),
                d.includedObjects(), d.totalObjects(), gaps, d.text()));
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(userMsg);
        body.put("messages", messages);

        log.info("语义层推导开始 connectorId={} 请求模型={} 对象={}/{} 摘要={}字符 max_tokens={}",
                connectorId,
                model == null ? "(未配 connector.semantic.infer-model，回落 provider 默认模型)" : model,
                d.includedObjects(), d.totalObjects(), d.text().length(), cfg.getMaxTokens());

        Object resp = claudeService.messages(body);
        return extractText(resp);
    }

    /** 从 Anthropic messages 响应里抽 content[].text。与 {@code SkillEvalService.extractText} 同形。 */
    private String extractText(Object resp) {
        try {
            JSONObject j = new JSONObject(resp);
            var content = j.getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; content != null && i < content.size(); i++) {
                JSONObject blk = content.getJSONObject(i);
                if ("text".equals(blk.getStr("type"))) {
                    sb.append(blk.getStr("text"));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(resp);
        }
    }

    // ================================================================ 解析

    /**
     * 解析模型返回的 JSON。防御三件事：markdown 围栏、正文前的闲话、以及<b>被 max_tokens 截断</b>。
     *
     * <p>第三件才是真正值得写代码的：一次推导要几十秒，因为最后一条 field 被切掉就整份丢弃太亏。
     * 但修复必须<b>出声</b>——把残缺当完整，正是这套设计从头到尾在防的事，所以修复成功也要进 note。
     */
    private Map<String, Object> parseJson(String raw, List<String> notes) throws Exception {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                s = s.substring(nl + 1, end).trim();
            }
        }
        int a = s.indexOf('{');
        if (a > 0) {
            // 只砍前面的闲话，不砍后面的：被截断的响应没有收尾的 '}'，
            // 按 lastIndexOf('}') 去切会把正文一起切掉，反而救不回来。
            // 尾部若有多余的话，Jackson 默认不开 FAIL_ON_TRAILING_TOKENS，会忽略。
            s = s.substring(a);
        }
        try {
            return readMap(s);
        } catch (Exception first) {
            String repaired = repairTruncatedJson(s);
            if (repaired.equals(s)) {
                throw first;
            }
            Map<String, Object> m = readMap(repaired);
            notes.add("模型输出疑似被 max_tokens 截断，已截到最后一个完整条目，"
                    + "说明书不完整（可调大 connector.semantic.max-tokens）");
            log.warn("语义层推导的模型输出被截断，已修复后解析：原长 {} 字符，修复后 {} 字符",
                    s.length(), repaired.length());
            return m;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String s) throws Exception {
        return CommonUtil.getObjectMapper().readValue(s, Map.class);
    }

    /**
     * 把被截断的 JSON 收尾到最后一个<b>完整</b>条目。
     *
     * <p>做法：扫一遍括号栈（跳过字符串内部与转义），每闭合一个嵌套结构、且外层还没收口时，
     * 记下那个位置——它是一个安全切点。扫完若栈还没空说明被截断了，就退回最后一个安全切点，
     * 按栈里剩下的开括号依次补闭括号。栈空则原样返回（本来就是完整的）。
     * 一个完整条目都没有时也原样返回，交给调用方按失败处理——绝不编一个空壳出来冒充成功。
     */
    static String repairTruncatedJson(String s) {
        Deque<Character> stack = new ArrayDeque<>();
        Deque<Character> lastSafeStack = null;
        int lastSafe = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{' || c == '[') {
                stack.push(c);
            } else if (c == '}' || c == ']') {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
                if (!stack.isEmpty()) {
                    lastSafe = i + 1;
                    lastSafeStack = new ArrayDeque<>(stack);
                }
            }
        }
        if (stack.isEmpty() && !inStr) {
            return s;
        }
        if (lastSafe < 0 || lastSafeStack == null) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.substring(0, lastSafe));
        // ArrayDeque 的迭代顺序是栈顶到栈底，正好是该闭合的顺序。
        for (Character open : lastSafeStack) {
            b.append(open == '{' ? '}' : ']');
        }
        return b.toString();
    }

    // ================================================================ 落成语义行

    /**
     * 把模型产出摊成 {@link ConnectorSemantic} 行。<b>三道过滤，一道都不能省：</b>
     * <ol>
     *   <li><b>GUESS 直接丢</b>——没有外部依据的断言不该进说明书。分界线是「有没有依据」，
     *       不是「模型说它确不确定」。</li>
     *   <li><b>名字对不上快照的丢</b>——模型发明出来的表/列既算不出锚点，本身也就是幻觉。</li>
     *   <li><b>撞唯一键的丢</b>——{@code uk_connector_semantic} 是
     *       (租户,连接,scope,表,字段,词条)。JOIN 行的键里只有左侧列，同一列指向两张表就会撞；
     *       撞了不是报一条错，而是 {@code replaceInferred} 那个事务整个回滚、一行都写不进去。
     *       所以必须在进库之前先合并掉。</li>
     * </ol>
     *
     * @param answeredTerms 人已经在对话里答过的口径词条（折叠过大小写）。已经有答案的问题不再重提，
     *                      理由见下面 ambiguities 那一段。
     * @param corpusJoins   S1 从客户自己写的视图 / 存储过程里挖出来的关系，已经按快照对齐过大小写。
     *                      它们和模型产出走<b>同一套去重</b>（否则一样会在 replaceInferred 里撞唯一键），
     *                      但在撞键时<b>压过</b>模型那条，见 {@link #putCorpusJoin}。
     */
    private List<ConnectorSemantic> toRows(Map<String, Object> parsed,
                                           Map<String, Map<String, FieldDetail>> fieldsByObject,
                                           Set<String> answeredTerms,
                                           Stats st,
                                           List<ConnectorSemantic> corpusJoins) {
        Map<String, ConnectorSemantic> byKey = new LinkedHashMap<>();

        // ── OBJECT：不锚结构。connector_schema.content_hash 覆盖每一列，客户加一个无关列它就变，
        //    而加一列并不改变「这张表是订单主表」这句话——锚上去等于给自己造一堆假 STALE。
        for (Map<String, Object> o : arr(parsed, "objects")) {
            String name = str(o, "name");
            String gloss = str(o, "gloss");
            if (name == null || gloss == null) {
                continue;
            }
            if (!fieldsByObject.containsKey(name)) {
                st.droppedUnknown++;
                continue;
            }
            String ev = evidence(o, ConnectorSemanticService.EV_GUESS);
            if (ConnectorSemanticService.EV_GUESS.equals(ev)) {
                st.droppedGuess++;
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("table_shape", clip(str(o, "table_shape"), DETAIL_TEXT_MAX));
            detail.put("typical_questions", detailList(strList(o, "typical_questions")));

            ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_OBJECT, name, "", "");
            row.setGloss(clip(gloss, GLOSS_MAX));
            row.setDetailJson(toJson(detail));
            row.setEvidence(ev);
            row.setConfidence(confidence(o));
            row.setAnchorKind(ConnectorSemanticService.ANCHOR_NONE);
            row.setAnchorHash(null);
            put(byKey, row, st);
        }

        // ── FIELD：锚这一列自己的指纹，改别的列不影响它。
        for (Map<String, Object> f : arr(parsed, "fields")) {
            String obj = str(f, "object");
            String col = str(f, "name");
            String gloss = str(f, "gloss");
            if (obj == null || col == null || gloss == null) {
                continue;
            }
            FieldDetail fd = lookup(fieldsByObject, obj, col);
            if (fd == null) {
                st.droppedUnknown++;
                continue;
            }
            String ev = evidence(f, ConnectorSemanticService.EV_GUESS);
            if (ConnectorSemanticService.EV_GUESS.equals(ev)) {
                st.droppedGuess++;
                continue;
            }
            ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_FIELD, obj, col, "");
            row.setGloss(clip(gloss, GLOSS_MAX));
            row.setEvidence(ev);
            row.setConfidence(confidence(f));
            row.setAnchorKind(ConnectorSemanticService.ANCHOR_FIELD);
            row.setAnchorHash(ConnectorSemanticService.fieldAnchor(fd));
            put(byKey, row, st);
        }

        // ── JOIN：P1 <b>没有</b>采样验证（那是 P2），所以每一条都是未经验证的推测。
        //    verified=NONE + basis 写死「未经数据验证」，注入层据此照实告诉模型，
        //    而不是让它看到一条关系就当外键用。
        for (Map<String, Object> j : arr(parsed, "joins")) {
            String obj = str(j, "object");
            String col = str(j, "column");
            String toObj = str(j, "to_object");
            String toCol = str(j, "to_column");
            if (obj == null || col == null || toObj == null || toCol == null) {
                continue;
            }
            FieldDetail left = lookup(fieldsByObject, obj, col);
            FieldDetail right = lookup(fieldsByObject, toObj, toCol);
            if (left == null || right == null) {
                st.droppedUnknown++;
                continue;
            }
            // 关系缺依据标签时默认按 NAME 处理，与 OBJECT/FIELD 的「缺标签即 GUESS」刻意不对称：
            // 一条关系带着 verified=NONE 和「未经数据验证」的 basis 一起注入，读它的模型知道要自己核；
            // 而一条字段含义是被当成事实读的，错了没有任何地方看得出来。
            // 前者失手的代价有边界，后者没有——不对称的处置对应的是不对称的代价。
            String ev = evidence(j, ConnectorSemanticService.EV_NAME);
            if (ConnectorSemanticService.EV_GUESS.equals(ev)) {
                st.droppedGuess++;
                continue;
            }
            String card = str(j, "cardinality");
            card = card == null ? null : card.toUpperCase(Locale.ROOT);
            if (card != null && !CARDINALITIES.contains(card)) {
                card = null;
            }
            String note = clip(str(j, "note"), DETAIL_TEXT_MAX);

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("to_object", toObj);
            detail.put("to_column", toCol);
            detail.put("cardinality", card);
            detail.put("basis", "未经数据验证");
            if (note != null) {
                detail.put("note", note);
            }

            ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_JOIN, obj, col, "");
            row.setGloss(clip("关联 " + toObj + "." + toCol
                    + (card == null ? "" : "（" + card + "）")
                    + (note == null ? "" : "。" + note)
                    + "。本条未经数据验证，join 前建议先看该列的取值分布。", GLOSS_MAX));
            row.setDetailJson(toJson(detail));
            row.setEvidence(ev);
            row.setConfidence(confidence(j));
            row.setAnchorKind(ConnectorSemanticService.ANCHOR_JOIN);
            row.setAnchorHash(ConnectorSemanticService.joinAnchor(left, right));
            row.setVerified(ConnectorSemanticService.V_NONE);
            put(byKey, row, st);
        }

        // ── S1：客户自己写的 SQL 里的关系。★ 放在模型的 joins 【之后】灌进来，
        //    这样撞键时 putCorpusJoin 能看到模型那条并把它顶掉（顺序反过来就顶不掉了）。
        for (ConnectorSemantic row : corpusJoins) {
            putCorpusJoin(byKey, row, st);
        }

        // ── 歧义 → CAVEAT，<b>不是 METRIC</b>。METRIC 的含义是「已经澄清的口径」，只能由人在对话里
        //    回答出来（{@code ConnectorSemanticService.defineMetric}）。把一个还没人回答的问题写成
        //    METRIC，等于平台自己编了一条口径——这正是整套设计最不许发生的那件事。
        for (Map<String, Object> c : arr(parsed, "ambiguities")) {
            String term = str(c, "term");
            String question = str(c, "question");
            if (term == null || question == null) {
                continue;
            }
            // ★ 人已经答过的口径不再作为「待确认」重提。否则 conn_catalog 会把【答案】和【同一个问题】
            //   一起注入，模型只能二选一——那是我们自己制造的矛盾，而且是在「已经花过一次人力澄清」
            //   之后制造的。注入层另有一道同样的抑制（那边归另一个人管），这里也拦一道：
            //   重新推导不该把一个已经有答案的问题复活。
            if (answeredTerms.contains(fold(term))) {
                st.droppedAnswered++;
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("applies_to", detailList(strList(c, "applies_to")));

            // term 不截断：超长的整条丢（在 put 里），理由见 NAME_MAX。
            ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_CAVEAT, "", "", term);
            row.setGloss(clip(question, GLOSS_MAX));
            row.setDetailJson(toJson(detail));
            // evidence 留空：歧义不是一条断言，它恰恰是「没有依据、必须问人」的那一类。
            // 给它贴任何一个依据标签都是把话说反了。
            row.setEvidence(null);
            row.setAnchorKind(ConnectorSemanticService.ANCHOR_NONE);
            put(byKey, row, st);
        }

        // 计数按实际留下来的行数来，而不是在循环里累加——去重会把已经计过的行换掉。
        for (ConnectorSemantic r : byKey.values()) {
            switch (r.getScope()) {
                case ConnectorSemanticService.SCOPE_OBJECT -> st.objects++;
                case ConnectorSemanticService.SCOPE_FIELD -> st.fields++;
                case ConnectorSemanticService.SCOPE_JOIN -> st.joins++;
                default -> st.caveats++;
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private ConnectorSemantic base(String scope, String objectName, String fieldName, String term) {
        ConnectorSemantic row = new ConnectorSemantic();
        row.setScope(scope);
        // 这三列是 NOT NULL DEFAULT ''：写 null 会让它们在唯一键上「不参与去重」，务必给空串。
        row.setObjectName(objectName);
        row.setFieldName(fieldName);
        row.setTerm(term);
        row.setStatus(ConnectorSemanticService.ST_DRAFT);
        row.setVerified(ConnectorSemanticService.V_NONE);
        return row;
    }

    /** 按唯一键合并，撞了留 confidence 高的那条。两条都留不是多一行，是整批插入回滚、一行都不剩。 */
    private void put(Map<String, ConnectorSemantic> byKey, ConnectorSemantic row, Stats st) {
        if (tooLong(row, st)) {
            return;
        }
        String key = dedupKey(row);
        ConnectorSemantic old = byKey.get(key);
        if (old == null) {
            byKey.put(key, row);
            return;
        }
        st.droppedDup++;
        int a = old.getConfidence() == null ? -1 : old.getConfidence();
        int b = row.getConfidence() == null ? -1 : row.getConfidence();
        if (b > a) {
            byKey.put(key, row);
        }
        log.debug("语义层推导产出撞唯一键，已合并 key={}", key);
    }

    /**
     * S1 产出的关系行入表。与 {@link #put} 的唯一区别：撞键时<b>不比 confidence，直接顶掉</b>模型那条。
     *
     * <h3>为什么不能比 confidence</h3>
     * 两边的 confidence 根本不是一把尺子。模型那个数是它<b>自己报的把握</b>（而且它对
     * {@code cid → users.id} 这种名字像的关系报 90 是常态）；S1 那个数是
     * <b>「这条 join 在客户自己写的 SQL 里出现过几处」</b>。拿两个不同量纲的数比大小，
     * 结果是一条人真的写过的关系被一条模型猜出来的关系挤掉——而 JOIN 行的唯一键里只有左侧列
     *（{@code (scope, 左表, 左列)}），同一列指向两张不同的表就会撞，所以这不是罕见情形。
     *
     * <p>判据回到那条老分界线：<b>有没有外部依据</b>。视图里的 {@code a.x = b.y} 是客户写在自己库里的
     * 一句话（{@code EV_COMMENT}），模型的 {@code EV_NAME} 是「名字像」。前者压后者。
     *
     * <p>两条都来自语料时才比 confidence——那时两个数同量纲，比的是「几处见过它」。
     */
    private void putCorpusJoin(Map<String, ConnectorSemantic> byKey, ConnectorSemantic row, Stats st) {
        if (tooLong(row, st)) {
            return;
        }
        String key = dedupKey(row);
        ConnectorSemantic old = byKey.get(key);
        if (old == null) {
            byKey.put(key, row);
            return;
        }
        st.droppedDup++;
        if (ConnectorSemanticService.EV_COMMENT.equals(old.getEvidence())) {
            int a = old.getConfidence() == null ? -1 : old.getConfidence();
            int b = row.getConfidence() == null ? -1 : row.getConfidence();
            if (b > a) {
                byKey.put(key, row);
            }
            return;
        }
        log.debug("语料关系顶掉了模型推测的同键关系 key={}", key);
        byKey.put(key, row);
    }

    /**
     * ★ 去重键<b>必须按唯一键的排序规则折叠大小写</b>。
     *
     * <p>{@code uk_connector_semantic} 落在 utf8mb4_unicode_ci 上，{@code ord_id} 和 {@code ORD_ID}
     * 在库里<b>是同一个键</b>（mysql:8.0 实测：{@code ERROR 1062 Duplicate entry
     * 't1-100-JOIN-t_ord_dtl-ORD_ID-'}），而模型完全可能一条写小写、一条写大写。
     * 用大小写敏感的键去重，这两条会双双通过这道闸，然后在 {@code replaceInferred} 里撞唯一键——
     * 那是个 {@code @Transactional} 方法，撞一次不是少一行，是<b>旧行已删、新行一条没插</b>，
     * 整条连接的语义层当场清空。
     *
     * <p>折叠只用在<b>键</b>上；行里存的仍然是模型原样给的大小写，因为表名列名要和快照对得上。
     * 尾随空格不用管：所有取值都在 {@link #str} 里 trim 过，而 utf8mb4_unicode_ci 是 PAD SPACE。
     */
    private static String dedupKey(ConnectorSemantic row) {
        return fold(row.getScope()) + '/' + fold(row.getObjectName()) + '/'
                + fold(row.getFieldName()) + '/' + fold(row.getTerm());
    }

    /**
     * 三个键列超过 varchar(191) 的整行丢掉，<b>不截</b>。
     *
     * <p>它们全都要拿去做精确匹配：表名列名对不上快照就算不出锚点，词条对不上就永远注入不了。
     * 一条被截短的行既起不了作用，看起来又和真的一模一样——比少一条糟得多。
     * 落库那一侧同样不能指望：非严格模式下 MySQL 会<b>静默</b>截断，严格模式下整批插入报错回滚。
     */
    private static boolean tooLong(ConnectorSemantic row, Stats st) {
        if (len(row.getObjectName()) > NAME_MAX || len(row.getFieldName()) > NAME_MAX
                || len(row.getTerm()) > NAME_MAX) {
            st.droppedTooLong++;
            log.warn("语义层推导产出的键列超过 {} 字符，整条丢弃 scope={} object={} field={} term={}",
                    NAME_MAX, row.getScope(), abbrev(row.getObjectName()),
                    abbrev(row.getFieldName()), abbrev(row.getTerm()));
            return true;
        }
        return false;
    }

    // ================================================================ 状态与摘要

    /**
     * ★ 认领这一次推导：把 {@code semantic_status} 抢成 RUNNING，抢不到就别跑。
     *
     * <p>没有这道闸，「建连自动推」和「有人同时点重新生成」会同时在跑：两次推导各写各的状态，
     * 后完成的那次覆盖状态、先完成的那次的行留在库里，于是<b>状态和库里的行从此对不上</b>，
     * 而且一次<b>后完成的失败</b>会把一次成功盖成 FAILED。这两件事都没有任何地方会报出来。
     *
     * <p>抢的条件里带一个「上一次认领已经超过 {@link #CLAIM_STALE_MINUTES} 分钟」的逃生口，
     * 理由见那个常量。它换来的是「进程在推导中途被重启之后，这条连接还能再生成」——
     * 少了这个逃生口，失败形态是「点重新生成永远没反应」，没人能从界面上看出原因。
     *
     * <p>认领戳写在<b>专用的 {@code semantic_claim_at}</b> 上，不占用 {@code semantic_synced_at}。
     * 后者要回答的是「上次什么时候还是好的」，一旦被认领戳和失败重试反复覆盖，
     * 排查时最想要的那条信息正好就没了。两个含义不共用一列。
     *
     * @return 认领时间戳——它就是这次推导的<b>凭据</b>，回写终态时要拿它比对；{@code null} = 没抢到
     */
    private Date claim(Long connectorId) {
        Date now = truncateToSecond(new Date());
        Date staleBefore = new Date(now.getTime() - CLAIM_STALE_MINUTES * 60_000L);
        try {
            int n = connectionMapper.update(statusEntity(SEM_RUNNING, "正在生成语义层……", null, now),
                    new LambdaUpdateWrapper<Connection>()
                            .eq(Connection::getId, connectorId)
                            // ne 对 NULL 不成立（SQL 三值逻辑），所以必须把 isNull 单独列出来，
                            // 否则从没推过的连接（status 为 NULL）一次都认领不到。
                            .and(w -> w.ne(Connection::getSemanticStatus, SEM_RUNNING)
                                    .or().isNull(Connection::getSemanticStatus)
                                    .or().isNull(Connection::getSemanticClaimAt)
                                    .or().lt(Connection::getSemanticClaimAt, staleBefore)));
            return n > 0 ? now : null;
        } catch (Exception e) {
            // 认领写不进去（库抖了一下）不该把推导整个挡住：语义层是叠加注解，
            // 「多跑一次」的代价远小于「从此不再生成，且没人知道为什么」。
            log.warn("语义层推导认领失败，本次按无并发继续 connectorId={}: {}", connectorId, describe(e));
            return now;
        }
    }

    /**
     * 回写 {@code connection} 上的三列语义状态。
     *
     * <p>用一个只填了这三列的实体 + 带条件的 wrapper：MyBatis-Plus 默认的 {@code NOT_NULL} 更新策略
     * 只带上非空字段，不会顺手把整行其它列覆盖成 null（同 {@code ConnectorHealthJob.writeBack}）。
     *
     * <p>本方法<b>自己吞异常</b>——状态写不进去，不该把一次成功的推导变成失败。
     *
     * @param claimAt 本次推导的认领凭据。非空时这是一次 CAS：<b>只有仍然是持有者才写得进去</b>。
     *                少了这个条件，一次跑得慢的失败会把后面那次成功的结果盖回 FAILED。
     *                认领之前的写（比如「开关关着」）传 null，那种写不需要也不可能持有凭据。
     *
     * <p><b>{@code semantic_synced_at} 只在 {@link #SEM_READY} 时盖戳。</b>失败和「不适用」都不动它：
     * 那一列要回答的是「上次什么时候还是好的」，让一次失败的重试把它抹掉，
     * 等于在最需要这条信息的时候删掉它。本次尝试发生在什么时候，看 {@code semantic_claim_at}。
     */
    private void writeStatus(Long connectorId, String status, String note, boolean stampTime, Date claimAt) {
        try {
            Date syncedAt = (stampTime && SEM_READY.equals(status)) ? truncateToSecond(new Date()) : null;
            Connection u = statusEntity(status, note, syncedAt, null);
            LambdaUpdateWrapper<Connection> w = new LambdaUpdateWrapper<Connection>()
                    .eq(Connection::getId, connectorId);
            if (claimAt != null) {
                w.eq(Connection::getSemanticStatus, SEM_RUNNING).eq(Connection::getSemanticClaimAt, claimAt);
            }
            int n = connectionMapper.update(u, w);
            if (n == 0 && claimAt != null) {
                // 不是错误：另一次推导把这条连接抢走了（上一次认领被判超时），该以它的结果为准。
                log.warn("语义层状态未回写：本次推导已不再持有该连接 connectorId={} status={}", connectorId, status);
            }
        } catch (Exception e) {
            log.warn("回写语义层状态失败 connectorId={} status={}: {}", connectorId, status, e.getMessage());
        }
    }

    /** 只装 semantic_* 那几列，绝不带 id——id 是 where 条件，不该出现在 set 里。 */
    private Connection statusEntity(String status, String note, Date syncedAt, Date claimAt) {
        Connection u = new Connection();
        if (status != null) {
            u.setSemanticStatus(status);
        }
        u.setSemanticNote(clip(note, NOTE_MAX));
        if (syncedAt != null) {
            u.setSemanticSyncedAt(syncedAt);
        }
        if (claimAt != null) {
            u.setSemanticClaimAt(claimAt);
        }
        return u;
    }

    /**
     * MySQL 的 {@code datetime} 不带小数秒，毫秒进库就没了。而这个时间戳要当认领凭据做<b>等值比较</b>，
     * 带着毫秒去比永远比不中——表现不是报错，是「终态一次都写不回去，状态永远停在 RUNNING」。
     */
    private static Date truncateToSecond(Date d) {
        return new Date(d.getTime() / 1000L * 1000L);
    }

    /**
     * 快照的指纹：行数 + 最新的 {@code synced_at}。{@code refresh()} 是整批删了重插、
     * 每行 {@code synced_at} 都是同一个 now，所以这两个数一起看就能判出「这期间有人刷过结构」。
     *
     * <p><b>为什么落库前非得再比一次：</b>推出来的 FIELD/JOIN 行锚的是<b>推导开始那一刻</b>的列指纹，
     * 而落库时 status 是 DRAFT 不是 STALE。中途若刷新过，这一批行锚的是旧结构却顶着 DRAFT——
     * 那一轮漂移检测已经跑完了，不会回头看它们，于是这批错行<b>永远不会被标过期</b>。
     * 宁可明说作废让人重来，也不要落一批漂移检测覆盖不到的行。
     */
    private static String snapshotStamp(List<ConnectorSchema> rows) {
        long max = 0L;
        for (ConnectorSchema r : rows) {
            if (r.getSyncedAt() != null) {
                max = Math.max(max, r.getSyncedAt().getTime());
            }
        }
        return rows.size() + "@" + max;
    }

    /**
     * 这条连接上已有的语义行。
     *
     * @return 空表就是空表；<b>读失败返回 {@code null}</b>——「读不到」和「没有」必须分得开：
     *         下面那个「空产出要不要替换」的判断，在「不知道」时只能往保守里落（当成有东西可丢）。
     */
    private List<ConnectorSemantic> existingRows(Long connectorId) {
        try {
            List<ConnectorSemantic> rows = semanticService.all(connectorId);
            return rows == null ? List.of() : rows;
        } catch (Exception e) {
            log.warn("读取已有语义层失败 connectorId={}: {}", connectorId, describe(e));
            return null;
        }
    }

    /** 人已经答过的口径词条（{@code METRIC} + {@code CONFIRMED}），折叠大小写后用于比对。 */
    private static Set<String> answeredTerms(List<ConnectorSemantic> existing) {
        Set<String> out = new HashSet<>();
        if (existing == null) {
            return out;
        }
        for (ConnectorSemantic r : existing) {
            if (ConnectorSemanticService.SCOPE_METRIC.equals(r.getScope())
                    && ConnectorSemanticService.ST_CONFIRMED.equals(r.getStatus())
                    && r.getTerm() != null && !r.getTerm().isBlank()) {
                out.add(fold(r.getTerm()));
            }
        }
        return out;
    }

    /**
     * ★ <b>拒绝用「什么都没有」去替换上一版说明书。</b>
     *
     * <p>{@code replaceInferred} 是先物理删 INFERRED 再整批插。模型返回一个 <code>{}</code>、
     * 或者一个四个键都没有的形状时，解析得动、只是一条也产不出来——于是旧的说明书被<b>物理删光</b>，
     * 状态还写成 READY。那是最坏的一种失败：看起来是一次成功的重新生成，只是「这次没生成出东西」，
     * 而人不会去查一次成功的操作。几十秒换来一次静默的数据丢失。
     *
     * @return 拒绝的理由；{@code null} = 可以替换
     */
    private String refuseEmptyReplace(Map<String, Object> parsed, List<ConnectorSemantic> fresh,
                                      List<ConnectorSemantic> corpusJoins,
                                      List<ConnectorSemantic> existing, Stats st) {
        if (!hasAnyExpectedKey(parsed)) {
            return "模型返回里 objects / fields / joins / ambiguities 一个键都没有，"
                    + "本次不替换，上一版说明书原样保留";
        }
        // ★ 这道闸看的必须是【模型】有没有产出，不是 fresh 空不空。
        //   S1 接进来之后，一次「模型返回了正确形状但四个数组全空」的失败也会带着几十条语料关系走到这里，
        //   于是 fresh 非空、闸门放行，结果是拿几十条关系去替换上一版的几百条字段含义——
        //   一次静默的数据丢失，而且比原来那个坑更隐蔽（它甚至真的写进了一些行）。
        Set<ConnectorSemantic> fromCorpus = Collections.newSetFromMap(new IdentityHashMap<>());
        fromCorpus.addAll(corpusJoins);
        boolean modelProducedSomething = false;
        for (ConnectorSemantic r : fresh) {
            if (!fromCorpus.contains(r)) {
                modelProducedSomething = true;
                break;
            }
        }
        if (modelProducedSomething) {
            return null;
        }
        // existing == null 表示没读出来。不知道就当「有东西可丢」——这个判断错在保守一侧只是多一次失败，
        // 错在激进一侧是把人工确认过之外的整层说明书抹掉。
        boolean hadInferred = existing == null || existing.stream()
                .anyMatch(r -> ConnectorSemanticService.SOURCE_INFERRED.equals(r.getSource()));
        if (!hadInferred) {
            return null;
        }
        return "本次模型一条语义都没能留下（无外部依据 " + st.droppedGuess + "、名字对不上结构 "
                + st.droppedUnknown + "、超长 " + st.droppedTooLong + "）"
                + (corpusJoins.isEmpty() ? "" : "，客户 SQL 语料里的 " + corpusJoins.size() + " 条关系也一并作废")
                + "，为免抹掉上一版说明书，本次不替换";
    }

    private static boolean hasAnyExpectedKey(Map<String, Object> parsed) {
        if (parsed == null) {
            return false;
        }
        for (String k : PAYLOAD_KEYS) {
            if (parsed.containsKey(k)) {
                return true;
            }
        }
        return false;
    }

    /** 管理台上那一行字。<b>丢了什么、漏了什么必须出现在里面</b>——只报成功数等于谎报覆盖面。 */
    private String summarize(Stats st, Digest d, List<String> notes) {
        StringBuilder b = new StringBuilder();
        // 总数是估出来的下界时写成 ">200"，不写成一个假的确数：这一行是给人读的，
        // 「覆盖 200/200」会被读成全覆盖，而事实是「至少还有一批表根本没进过快照」。
        String total = d.totalIsLowerBound() ? ">" + (d.totalObjects() - 1) : String.valueOf(d.totalObjects());
        b.append("覆盖 ").append(d.includedObjects()).append('/').append(total).append(" 个对象：")
                .append("表用途 ").append(st.objects)
                .append("、字段含义 ").append(st.fields)
                .append("、关系 ").append(st.joins).append("（均未经数据验证）")
                .append("、待确认口径 ").append(st.caveats).append(" 条。");
        if (st.droppedGuess > 0 || st.droppedUnknown > 0 || st.droppedDup > 0 || st.droppedTooLong > 0) {
            b.append("已丢弃：无外部依据 ").append(st.droppedGuess)
                    .append("、名字对不上结构 ").append(st.droppedUnknown)
                    .append("、重复 ").append(st.droppedDup)
                    .append("、超长 ").append(st.droppedTooLong).append(" 条。");
        }
        if (st.droppedAnswered > 0) {
            b.append("另有 ").append(st.droppedAnswered).append(" 条口径人已经答过，不再重复提问。");
        }
        for (String n : notes) {
            b.append(n).append("。");
        }
        return clip(b.toString(), NOTE_MAX);
    }

    // ================================================================ 采样验证阶段（S3 + S4）

    /**
     * 派发采样验证阶段。<b>在推导写完 {@link #SEM_READY} 之后调，不在推导里面调。</b>
     *
     * <h3>★ 为什么它是独立的一段，而不是推导的最后一步</h3>
     * <ul>
     *   <li><b>时间量级差两位数。</b>推导是几十秒；S3 自我节流到 30 次探查/分钟，200 条候选最坏
     *       约 20 分钟；S4 节流到 20 条语句/分钟，200 列最坏约 30 分钟。把它们并进推导，
     *       {@code semantic_status} 就要在 RUNNING 上挂住半小时——管理台看起来是挂了，
     *       而且 {@link #claim} 的 CAS 在那半小时里会把每一次重跑都挡回去
     *      （直到 {@link #CLAIM_STALE_MINUTES} 判超时），表现是「点重新生成没反应」。</li>
     *   <li><b>说明书已经可用了。</b>关系那一栏如实写着「未经数据验证」，注入层照实转述给模型。
     *       验证是把那句话往前推一格，不是能不能用的前提——让一件锦上添花的事把主产物的
     *       交付时间推迟半小时，方向是反的。</li>
     * </ul>
     *
     * <h3>★ 平台侧速率预算（改这里之前先把这笔账重算一遍）</h3>
     * 网关的<b>管理面</b>令牌桶是每租户每分钟 {@code connector.limit.per-tenant-platform-per-minute}，
     * 它 {@code <= 0} 时沿用 {@code per-tenant-per-minute}，默认 <b>60/min</b>。
     * S3 自我节流 <b>30/min</b>（{@code join-probes-per-minute}），
     * S4 自我节流 <b>20/min</b>（{@code value-statements-per-minute}），合计 <b>50/min</b>——
     * 两者<b>同时</b>跑在同一条连接上仍在预算内，剩 10/min 的余量。
     * 不过本方法派发的这一个任务里，两阶段是<b>先后</b>跑的（见 {@link #validate}），
     * 峰值只有 30/min，把余量留给客户随时可能在管理台点的「刷新结构」
     *（那也走同一个平台桶：1 次 catalog + 最多 200 次 describe）。
     * <p><b>要再加第三条平台侧剖析路径之前，先把这笔加法重算一遍。</b>超预算之后的表现不是报错，
     * 是其中<b>某一条</b>路径被网关以 {@code RATE_LIMITED} 中止——而它会被记成那条路径自己的失败，
     * 没有任何线索指向真正的原因是「我们自己同时开了三条」。
     *
     * @param tenantId   真实租户。{@code TenantContext.runAsSystem} 不算数：
     *                   {@code executeAsPlatform} 在第 2 步就会拒，见 {@link #runValidation}
     * @param deriveNote 推导刚写下去的那句结论，作为阶段 note 的前缀保留，见 {@link #notePrefix}
     */
    private void dispatchValidation(Long connectorId, String tenantId, String deriveNote) {
        if (!validateStageEnabled || connectorId == null) {
            return;
        }
        if (blank(tenantId)) {
            log.warn("语义层采样验证未派发：拿不到租户 connectorId={}", connectorId);
            return;
        }
        try {
            semanticStageExecutor.execute(MdcAsyncSupport.wrap("semantic-validate-" + connectorId,
                    () -> runValidation(connectorId, tenantId)));
        } catch (RuntimeException e) {
            // semanticStageExecutor 挂的是 AbortPolicy（★ 不是 CallerRunsPolicy，理由见那个 bean），
            // 所以队列满时这里会真的抛。绝不能只 log 就算了：
            // 「说明书生成了但没人去验」和「验过了，全都成立」在库里长得一模一样。
            log.warn("语义层采样验证阶段派发失败 connectorId={}: {}", connectorId, describe(e));
            writeStageNote(connectorId, notePrefix(deriveNote),
                    "采样验证没有派发出去（后台队列已满），表关系仍全部是未经数据验证；"
                            + "可在管理台点「验证表关系」重试");
        }
    }

    /**
     * 手工触发一次采样验证阶段（管理台入口用）。立刻返回。
     *
     * <p>与推导刻意分开成两个入口：推导花的是一次模型调用（我们的钱），
     * 验证花的是客户库的配额和客户库的负载（客户的钱）。两笔账落在不同的人身上，
     * 没有理由绑在一起重跑。
     */
    public void validateAsync(Long connectorId) {
        if (connectorId == null) {
            return;
        }
        String tenantId = TenantContext.get();
        if (blank(tenantId)) {
            log.warn("语义层采样验证被跳过：当前线程没有租户上下文 connectorId={}", connectorId);
            return;
        }
        dispatchValidation(connectorId, tenantId, null);
    }

    /**
     * 后台线程上的那一遍。
     *
     * <p>{@code MdcAsyncSupport.wrap} 已经会捎带 TenantContext，这里再显式补一次——理由和
     * {@link #runDerive} 一样，而且在这条路上更硬：S3 / S4 都走
     * {@code ConnectorGateway.executeAsPlatform}，它要的是<b>真实</b>租户，
     * {@code TenantContext.runAsSystem} 不满足（那只开系统模式，{@code CURRENT_TENANT} 仍是空的）。
     * 租户一丢，整轮验证会以「连接不存在」被整批拒掉，而那句话读起来像是客户把连接删了。
     */
    void runValidation(Long connectorId, String tenantId) {
        if (!TenantContext.isSet() && tenantId != null) {
            TenantContext.set(tenantId);
        }
        validate(connectorId);
    }

    /**
     * 同步跑一轮采样验证。<b>不抛异常</b>，与 {@link #derive(Long)} 同一条纪律。
     *
     * <p><b>它不碰 {@code semantic_status}。</b>那三个状态归推导所有（RUNNING/READY/FAILED
     * 加认领 CAS）。本阶段只更新 {@code semantic_note} 的后半段，以及每一行的
     * {@code verified} / {@code detail_json}。一次验证失败不该把一份已经生成好、
     * 且明确标着「未经数据验证」的说明书打成 FAILED。
     *
     * <p>两阶段<b>先后</b>跑而不是并发，速率账见 {@link #dispatchValidation}。
     */
    public ValidationResult validate(Long connectorId) {
        if (connectorId == null) {
            return ValidationResult.builder().ok(false).note("缺少连接 id").build();
        }
        if (!validateStageEnabled) {
            String note = "采样验证阶段已关闭（connector.semantic.validate-stage-enabled=false）";
            log.info("跳过语义层采样验证，开关已关 connectorId={}", connectorId);
            return ValidationResult.builder().ok(false).note(note).build();
        }
        if (!validating.add(connectorId)) {
            String note = "同一条连接上已有一轮采样验证在进行中，本次跳过";
            log.info("语义层采样验证被跳过：本进程内已有一轮在跑 connectorId={}", connectorId);
            return ValidationResult.builder().ok(false).note(note).build();
        }
        long start = System.currentTimeMillis();
        try {
            Connection conn = connectionMapper.selectById(connectorId);   // 租户过滤由拦截器注入
            if (conn == null) {
                log.warn("语义层采样验证找不到连接（或不属于当前租户） connectorId={}", connectorId);
                return ValidationResult.builder().ok(false).note("连接不存在").build();
            }
            String prefix = notePrefix(conn.getSemanticNote());

            List<ConnectorSchema> rows = schemaService.currentRows(connectorId);
            if (rows == null || rows.isEmpty()) {
                // 这一步<b>不补拉</b>：补拉是推导的事（它要拿结构去喂模型）。
                // 在一个「顺手做的增强」里偷偷加 200 次打客户库的 describe，是把成本藏起来。
                String note = "结构快照为空，没有可验证的表关系。请先在管理台「刷新结构」并重新生成语义层";
                writeStageNote(connectorId, prefix, note);
                return ValidationResult.builder().ok(false).note(note).build();
            }
            Map<String, Map<String, FieldDetail>> fieldsByObject = parseFields(rows);

            List<ConnectorSemantic> all = existingRows(connectorId);
            if (all == null) {
                String note = "读取语义层失败，本轮采样验证没有开始";
                writeStageNote(connectorId, prefix, note);
                return ValidationResult.builder().ok(false).note(note).build();
            }

            JoinStage js = runJoinStage(connectorId, all, fieldsByObject, prefix);
            ValueStage vs = runValueStage(connectorId, conn, rows, all, fieldsByObject);

            String note = js.note() + "；" + vs.note();
            writeStageNote(connectorId, prefix, note);
            log.info("语义层采样验证完成 connectorId={} 用时 {}ms；关系：候选 {} 成立 {} 部分成立 {} "
                            + "不成立 {} 判不出 {} 未探查 {} 已落库 {} 探查次数 {}；"
                            + "值域：剖析 {} 列 枚举成功 {} 补写新行 {} 语句 {}",
                    connectorId, System.currentTimeMillis() - start,
                    js.candidates(), js.confirmed(), js.weak(), js.rejected(), js.undecidable(),
                    js.notProbed(), js.written(), js.probeCount(),
                    vs.columns(), vs.enumerated(), vs.created(), vs.statements());
            return ValidationResult.builder()
                    .ok(true)
                    .joinOutcome(js.outcome())
                    .joinCandidates(js.candidates())
                    .joinConfirmed(js.confirmed())
                    .joinWeak(js.weak())
                    .joinRejected(js.rejected())
                    .joinUndecidable(js.undecidable())
                    .joinNotProbed(js.notProbed())
                    .joinRowsUpdated(js.written())
                    .probeCount(js.probeCount())
                    .valueColumns(vs.columns())
                    .valueEnumerated(vs.enumerated())
                    .valueRowsCreated(vs.created())
                    .valueStatements(vs.statements())
                    .note(clip(note, NOTE_MAX))
                    .build();
        } catch (Exception e) {
            String reason = describe(e);
            log.error("语义层采样验证阶段失败 connectorId={}: {}", connectorId, reason, e);
            writeStageNote(connectorId, null, "采样验证失败：" + reason);
            return ValidationResult.builder().ok(false).note("采样验证失败：" + reason).build();
        } finally {
            validating.remove(connectorId);
        }
    }

    // ---------------------------------------------------------------- S3：表关系采样验证

    /**
     * 挑候选 → 交给 {@link SemanticJoinValidator} → <b>每决完一条就落一条</b>。
     *
     * <h3>★ 三条落库纪律</h3>
     * <ol>
     *   <li><b>{@code REJECTED} 必须真的写成 {@code REJECTED}。</b>注入层就是靠
     *       {@code verified == REJECTED} 决定「这条关系不给模型看」的。只把细账并进
     *       {@code detail_json}、不动 {@code verified}，等于验证跑完了、客户的配额也花完了，
     *       而模型眼里什么都没变——一次完全不可见的空转。</li>
     *   <li><b>重跑时跳过已决的</b>（{@code verified != NONE}）。验证器<b>没有</b>跨轮次游标，
     *       这是它明说的设计：过滤是接线方的活。不过滤的话，每点一次「验证表关系」
     *       就把客户的库重新扫一遍，而结论一条都不会变。</li>
     *   <li><b>{@code source=HUMAN} 的行一行不碰。</b>人答过的东西不接受机器覆盖，
     *       和 {@code replaceInferred} 里那条 {@code source='INFERRED'} 是同一条纪律。</li>
     * </ol>
     *
     * <p>回调返回 {@code false} 会让验证器立刻停手，这里用线程中断作判据：应用关停时池子会中断
     * 这些线程，那之后继续打客户的库没有意义。已经决出来的结论一条不丢——它们在决出的那一刻
     * 就已经落库了，这正是那个回调存在的理由。
     */
    private JoinStage runJoinStage(Long connectorId, List<ConnectorSemantic> all,
                                   Map<String, Map<String, FieldDetail>> fieldsByObject, String prefix) {
        Map<String, ConnectorSemantic> pending = new LinkedHashMap<>();
        List<SemanticJoinValidator.JoinCandidate> candidates = new ArrayList<>();
        int alreadyDecided = 0;
        int human = 0;
        int unusable = 0;
        for (ConnectorSemantic r : all) {
            if (!ConnectorSemanticService.SCOPE_JOIN.equals(r.getScope())) {
                continue;
            }
            if (ConnectorSemanticService.SOURCE_HUMAN.equals(r.getSource())) {
                human++;
                continue;
            }
            // null 按「没决过」处理：把未知当成已决 = 永远不验它，而那恰好是最该验的一类。
            if (r.getVerified() != null && !ConnectorSemanticService.V_NONE.equals(r.getVerified())) {
                alreadyDecided++;
                continue;
            }
            String[] to = joinTarget(r);
            if (to == null || blank(r.getObjectName()) || blank(r.getFieldName())) {
                unusable++;
                continue;
            }
            String key = joinKey(r.getObjectName(), r.getFieldName(), to[0], to[1]);
            if (pending.putIfAbsent(key, r) != null) {
                continue;
            }
            candidates.add(SemanticJoinValidator.JoinCandidate.of(
                    r.getObjectName(), r.getFieldName(), to[0], to[1]));
        }

        if (candidates.isEmpty()) {
            String note = alreadyDecided > 0
                    ? "表关系此前已全部验证过（" + alreadyDecided + " 条），本轮没有新候选"
                    : "没有待验证的表关系";
            return new JoinStage(SemanticJoinValidator.OUT_NOTHING_TO_DO, 0, 0, 0, 0, 0, 0, 0, 0, note);
        }

        JoinCounters c = new JoinCounters();
        SemanticJoinValidator.JoinValidationResult res;
        try {
            res = joinValidator.validate(connectorId, candidates, fieldsByObject, (v, decided, total) -> {
                persistVerdict(pending, v, c);
                maybeProgress(connectorId, prefix, decided, total, c);
                return !Thread.currentThread().isInterrupted();
            });
        } catch (RuntimeException e) {
            // 契约上它不抛，这里兜底。
            String reason = describe(e);
            log.warn("表关系采样验证抛了异常（契约上不该） connectorId={}: {}", connectorId, reason);
            return new JoinStage(SemanticJoinValidator.OUT_ABORTED, candidates.size(),
                    c.confirmed, c.weak, c.rejected, c.undecidable, c.notProbed, c.written, 0,
                    "表关系采样验证失败：" + reason);
        }
        if (res == null) {
            return new JoinStage(SemanticJoinValidator.OUT_ABORTED, candidates.size(),
                    c.confirmed, c.weak, c.rejected, c.undecidable, c.notProbed, c.written, 0,
                    "表关系采样验证没有返回结果");
        }

        // ★ 计数以【返回的 verdicts 全集】为准，不以回调累计为准。
        //   回调只在真的决出一条时才响：一次 TIER_BLOCKED / 半路 ABORTED 的运行，
        //   剩下那些「没探查」的结论是随返回值一次性给回来的，回调一次都没走。
        //   照回调计数的话，一次一条都没探查的运行会报「未探查 0」——读起来就是「全都验过了」。
        recount(res.getVerdicts(), c);

        StringBuilder note = new StringBuilder();
        if (res.ran()) {
            note.append("表关系验证 ").append(candidates.size()).append(" 条：成立 ").append(c.confirmed)
                    .append("、部分成立 ").append(c.weak)
                    .append("、不成立 ").append(c.rejected)
                    .append("、判不出 ").append(c.undecidable);
            if (c.notProbed > 0) {
                // ★ 「没探查」和「探查了判不出来」是两句不同的话，绝不能并成一个数字。
                note.append("、未探查 ").append(c.notProbed);
            }
            note.append("（探查 ").append(res.getProbeCount()).append(" 次）");
        } else {
            // 没跑起来时，验证器自己那句话比我们能拼出来的任何一句都准确
            //（档位不够 / 开关关了 / 被中止 / 没有候选各是一句不同的话）。
            note.append(blank(res.getNote()) ? "表关系没有做采样验证" : res.getNote());
        }
        if (alreadyDecided > 0) {
            note.append("；另有 ").append(alreadyDecided).append(" 条此前已验证过，本轮跳过");
        }
        if (human > 0) {
            note.append("；").append(human).append(" 条人工确认的关系不参与验证");
        }
        if (unusable > 0) {
            note.append("；").append(unusable).append(" 条关系缺少目标表列，无法验证");
        }
        return new JoinStage(res.getOutcome(), candidates.size(), c.confirmed, c.weak, c.rejected,
                c.undecidable, c.notProbed, c.written, res.getProbeCount(), note.toString());
    }

    /**
     * 落一条结论。
     *
     * <h3>★ {@code V_NONE} 的那些<b>一个字都不写</b></h3>
     * {@code V_NONE} 的意思是「<b>没探查</b>」（档位不允许、预算用完、被中止、标识符对不上快照），
     * 而不是「探查了判不出来」——后者是 {@code V_UNDECIDABLE}。对没探查过的那条，
     * {@code JoinVerdict.detailPatch()} 会把 {@code basis} 覆盖成一句泛泛的「未经数据验证」，
     * 那会<b>抹掉 S1 写进去的「来自视图 v_x 的定义」</b>：这一轮什么都没做，
     * 却顺手删掉了这条关系唯一的来源线索。所以直接跳过——行保持原样，
     * 下一轮（比如档位被调上去之后）它仍然是 {@code NONE}，仍然会被挑成候选。
     */
    private void persistVerdict(Map<String, ConnectorSemantic> pending,
                                SemanticJoinValidator.JoinVerdict v, JoinCounters c) {
        if (v == null) {
            return;
        }
        String verified = v.getVerified();
        if (blank(verified) || ConnectorSemanticService.V_NONE.equals(verified)) {
            return;
        }
        // 分类计数不在这里累加，统一由 recount 按返回的全集算一次，理由见那个方法。
        ConnectorSemantic row = pending.get(
                joinKey(v.getFromObject(), v.getFromColumn(), v.getToObject(), v.getToColumn()));
        if (row == null) {
            log.debug("采样验证结论找不到对应的语义行，已忽略 {}.{} -> {}.{}",
                    v.getFromObject(), v.getFromColumn(), v.getToObject(), v.getToColumn());
            return;
        }
        if (ConnectorSemanticService.SOURCE_HUMAN.equals(row.getSource())) {
            // 挑候选时已经滤过一道，这里是第二道：覆盖人工确认过的行没有任何一次是对的。
            return;
        }
        try {
            Map<String, Object> detail = readDetail(row.getDetailJson());
            detail.putAll(v.detailPatch());
            if (v.isProbed() && v.getCardinality() == null) {
                // ★ detailPatch 在基数判不出来时【不放】这个键，于是上一轮模型自己标的基数会原样留着。
                //   一条写着「已采样验证」、却带着一个模型猜出来的 N:1 的关系，比没验证过更危险：
                //   自动 join 正是按基数决定的，而一次 fan-out 会让 SUM 出来的金额凭空变大且不报错。
                detail.remove("cardinality");
            }
            ConnectorSemantic u = new ConnectorSemantic();
            u.setId(row.getId());
            u.setVerified(verified);
            u.setDetailJson(toJson(detail));
            // gloss 一起改：它里面写着「本条未经数据验证」，验完还留着就是在说反话——
            // 而注入 conn_catalog 的恰恰是 gloss 的前 80 字，只改对 detail_json 没用。
            u.setGloss(clip(verifiedGloss(v, detail), GLOSS_MAX));
            if (semanticMapper.updateById(u) > 0) {
                c.written++;
            }
        } catch (Exception e) {
            // 单条落库失败不该让整轮停：剩下的候选仍然值得验。
            log.warn("采样验证结论落库失败 semanticId={} verified={}: {}",
                    row.getId(), verified, describe(e));
        }
    }

    /**
     * 用验证器返回的全集重算四个分类计数。{@code written} 不动——那一个只有回调那条路知道。
     *
     * <p>{@code verdicts} 的契约是「每条候选一个结论，顺序与入参一致，<b>一条不少</b>」，
     * 所以它是唯一的权威。为空时保留回调累计值（总比清零强）。
     */
    private static void recount(List<SemanticJoinValidator.JoinVerdict> verdicts, JoinCounters c) {
        if (verdicts == null || verdicts.isEmpty()) {
            return;
        }
        c.confirmed = 0;
        c.weak = 0;
        c.rejected = 0;
        c.undecidable = 0;
        c.notProbed = 0;
        for (SemanticJoinValidator.JoinVerdict v : verdicts) {
            String verified = v == null ? null : v.getVerified();
            if (blank(verified) || ConnectorSemanticService.V_NONE.equals(verified)) {
                c.notProbed++;
            } else {
                switch (verified) {
                    case ConnectorSemanticService.V_CONFIRMED -> c.confirmed++;
                    case ConnectorSemanticService.V_WEAK -> c.weak++;
                    case ConnectorSemanticService.V_REJECTED -> c.rejected++;
                    default -> c.undecidable++;
                }
            }
        }
    }

    /** 验完之后那一句给模型看的话。{@code REJECTED} 由注入层负责不展示，这里仍如实写，便于人查。 */
    private static String verifiedGloss(SemanticJoinValidator.JoinVerdict v, Map<String, Object> detail) {
        StringBuilder b = new StringBuilder("关联 ")
                .append(v.getToObject()).append('.').append(v.getToColumn());
        if (v.getCardinality() != null) {
            b.append('（').append(v.getCardinality()).append('）');
        }
        b.append('。').append(switch (v.getVerified()) {
            case ConnectorSemanticService.V_CONFIRMED -> "已通过采样验证：";
            case ConnectorSemanticService.V_WEAK -> "采样验证只部分成立：";
            case ConnectorSemanticService.V_REJECTED -> "采样验证不成立，不要据此 join：";
            default -> "已探查但判不出来，仍按未经验证对待：";
        });
        if (!blank(v.getBasis())) {
            b.append(v.getBasis()).append('。');
        }
        if (!blank(v.getReason())) {
            b.append(v.getReason()).append('。');
        }
        // 来源和模型原来那句备注都要留着：它们回答「凭什么提出这条关系」，
        // 而采样回答「数据对不对得上」。两个问题都要有答案，少一个就没法判断该信到什么程度。
        appendIfPresent(b, detail.get("source_sql"));
        appendIfPresent(b, detail.get("note"));
        if (!v.isAutoJoinable()
                && (ConnectorSemanticService.V_CONFIRMED.equals(v.getVerified())
                || ConnectorSemanticService.V_WEAK.equals(v.getVerified()))) {
            b.append("右侧不唯一，这条 join 会放大行数，聚合前必须先去重。");
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- S4：列取值域采集

    /**
     * 值域采集，并把结论并进对应的 {@code FIELD} 行。
     *
     * <h3>为什么档位不允许时<b>一行都不写</b></h3>
     * {@code SemanticValueProfiler.notEnabledFragment()} 备好了「未启用样本值采集」那一句，
     * 但把它铺满每一条 FIELD 行是错的：那是几百次 UPDATE，写进去的是一句
     * <b>「这里没有答案」</b>——而「没有 {@code value_domain} 这个键」本来就已经是这个意思。
     * 更糟的是档位后来被调上去之后，这些「未启用」片段还得再被逐条覆盖一遍，
     * 中间任何一次失败都会留下一条说反话的行。这件事写进 {@code semantic_note} 一次就够了。
     *
     * <h3>为什么会<b>新建</b> FIELD 行</h3>
     * 模型只给它读得懂的列写含义，而值域最有用的恰恰是它读不懂的那种列——
     * 一个没有注释的 {@code st tinyint}。那种列多半没有 FIELD 行，
     * 于是「取值只有 0/1/2/3」这条确凿的事实<b>无处可放</b>，S4 白跑。
     * 所以缺行就补一条，依据标 {@code EV_DATA}（「在数据里查得到」，这正是那一档的定义）。
     * <b>只在拿到完整取值集合时补</b>：给一列补一行说「它取值太多」纯属噪音。
     */
    private ValueStage runValueStage(Long connectorId, Connection conn, List<ConnectorSchema> rows,
                                     List<ConnectorSemantic> all,
                                     Map<String, Map<String, FieldDetail>> fieldsByObject) {
        long t0 = System.currentTimeMillis();
        SemanticValueProfiler.ValueProfile p;
        try {
            // 把手上的快照传进去：少一次库查询，更要紧的是保证「剖析看到的结构」和
            // 「推导锚住的结构」是同一份——两次读之间客户加了一列，锚点和值域就对不上了。
            p = valueProfiler.profile(connectorId, rows);
        } catch (Exception e) {
            String reason = describe(e);
            log.warn("值域采集失败 connectorId={}: {}", connectorId, reason);
            auditService.recordPlatformStage(connectorId, conn.getTenantId(), conn.getName(),
                    Capability.QUERY, ConnectorAuditService.OP_SEMANTIC_VALUES,
                    "值域采集失败：" + reason, 0, System.currentTimeMillis() - t0, false);
            return new ValueStage(0, 0, 0, 0, "值域采集失败：" + reason);
        }
        if (p == null) {
            return new ValueStage(0, 0, 0, 0, "值域采集没有返回结果");
        }
        if (!p.isTierAllowed()) {
            // 一条语句都没发就不写审计：审计记的是「我们访问过客户的库」。没访问就不该留痕，
            // 否则 connector_audit 里会多出一批「其实什么都没做」的行，把真的那些淹掉。
            return new ValueStage(0, 0, 0, 0, "未采集列取值（" + nz(p.getTierStatement()) + "）");
        }

        Map<String, ConnectorSemantic> fieldRows = new LinkedHashMap<>();
        for (ConnectorSemantic r : all) {
            if (ConnectorSemanticService.SCOPE_FIELD.equals(r.getScope())
                    && !blank(r.getObjectName()) && !blank(r.getFieldName())) {
                fieldRows.putIfAbsent(fieldKey(r.getObjectName(), r.getFieldName()), r);
            }
        }

        List<SemanticValueProfiler.ColumnValueDomain> domains =
                p.getDomains() == null ? List.<SemanticValueProfiler.ColumnValueDomain>of() : p.getDomains();
        int created = 0;
        int human = 0;
        for (SemanticValueProfiler.ColumnValueDomain d : domains) {
            if (d == null || blank(d.getObjectName()) || blank(d.getFieldName())) {
                continue;
            }
            ConnectorSemantic row = fieldRows.get(fieldKey(d.getObjectName(), d.getFieldName()));
            if (row != null) {
                if (ConnectorSemanticService.SOURCE_HUMAN.equals(row.getSource())) {
                    human++;
                    continue;
                }
                patchValueDomain(row, d);
                continue;
            }
            if (d.hasCompleteValueSet() && createValueRow(connectorId, conn, d, fieldsByObject)) {
                created++;
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        StringBuilder note = new StringBuilder("列取值采集 ")
                .append(p.getAttemptedColumns()).append(" 列（拿到完整取值集合 ")
                .append(p.getEnumeratedColumns()).append(" 列，语句 ")
                .append(p.getStatements()).append(" 条）");
        if (created > 0) {
            note.append("，补写字段行 ").append(created);
        }
        if (human > 0) {
            note.append("，人工确认的 ").append(human).append(" 列未改动");
        }
        if (p.getNotes() != null) {
            for (String n : p.getNotes()) {
                if (!blank(n)) {
                    note.append("；").append(n);
                }
            }
        }
        if (p.getStatements() > 0) {
            // ★ 只有真的发了语句才记这条阶段级审计，动作名 platform.semantic_values ——
            //   客户的 DBA 靠它把「读了真实取值」和「只读了聚合数」分开，不必去逐条读 statement_text。
            auditService.recordPlatformStage(connectorId, conn.getTenantId(), conn.getName(),
                    Capability.QUERY, ConnectorAuditService.OP_SEMANTIC_VALUES,
                    "语义层值域采集：剖析 " + p.getAttemptedColumns() + " 列，取到完整取值集合 "
                            + p.getEnumeratedColumns() + " 列",
                    p.getStatements(), elapsed, true);
        }
        return new ValueStage(p.getAttemptedColumns(), p.getEnumeratedColumns(), created,
                p.getStatements(), note.toString());
    }

    /** 把值域并进一条已有的 FIELD 行。只动 {@code detail_json} 里 {@code value_domain} 这一个键。 */
    private boolean patchValueDomain(ConnectorSemantic row, SemanticValueProfiler.ColumnValueDomain d) {
        try {
            Map<String, Object> detail = readDetail(row.getDetailJson());
            // 片段一律走 detailFragment：「只有完整集合才写 values」这条不变式只有那一个出口。
            // 自己从 getValues() 拼，早晚有一处把高基数列的 null 写成 []，
            // 而 [] 读起来是「这列没有取值」——和事实正好相反。
            detail.put(SemanticValueProfiler.DETAIL_KEY, SemanticValueProfiler.detailFragment(d));
            ConnectorSemantic u = new ConnectorSemantic();
            u.setId(row.getId());
            u.setDetailJson(toJson(detail));
            return semanticMapper.updateById(u) > 0;
        } catch (Exception e) {
            log.warn("值域落库失败 semanticId={} {}.{}: {}",
                    row.getId(), d.getObjectName(), d.getFieldName(), describe(e));
            return false;
        }
    }

    /** 给一列没有 FIELD 行、但取到了完整取值集合的列补一行。理由见 {@link #runValueStage}。 */
    private boolean createValueRow(Long connectorId, Connection conn,
                                   SemanticValueProfiler.ColumnValueDomain d,
                                   Map<String, Map<String, FieldDetail>> fieldsByObject) {
        FieldDetail fd = lookup(fieldsByObject, d.getObjectName(), d.getFieldName());
        if (fd == null) {
            return false;
        }
        if (len(d.getObjectName()) > NAME_MAX || len(d.getFieldName()) > NAME_MAX) {
            // 与推导同一条：键列只丢不截，理由见 NAME_MAX。
            return false;
        }
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put(SemanticValueProfiler.DETAIL_KEY, SemanticValueProfiler.detailFragment(d));

            ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_FIELD,
                    d.getObjectName(), d.getFieldName(), "");
            row.setTenantId(conn.getTenantId());
            row.setConnectorId(connectorId);
            // INFERRED：下一次推导会连它一起物理删掉重来，这是对的——那时值域本来也该重采一遍。
            row.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            row.setGloss(clip(valueGloss(d), GLOSS_MAX));
            row.setDetailJson(toJson(detail));
            row.setEvidence(ConnectorSemanticService.EV_DATA);
            // 不给 100：这是【采样那一刻】的完整集合，客户明天新增一个状态值它就不完整了。
            // 100 会被读成「这件事不会变」，而它会变。
            row.setConfidence(90);
            row.setAnchorKind(ConnectorSemanticService.ANCHOR_FIELD);
            row.setAnchorHash(ConnectorSemanticService.fieldAnchor(fd));
            semanticMapper.insert(row);
            return true;
        } catch (Exception e) {
            // 撞唯一键（并发推导刚好插了同一条）走这里。一条失败不该让整轮停下来。
            log.warn("补写字段值域行失败 connectorId={} {}.{}: {}",
                    connectorId, d.getObjectName(), d.getFieldName(), describe(e));
            return false;
        }
    }

    /**
     * 补写的那一行的 gloss。
     *
     * <p>★ 取值列表<b>宁可不列全，也绝不截一半</b>：被 {@code clip} 从中间切掉的列表看起来仍然
     * 像一个完整集合，模型会照着它写 {@code WHERE status IN (...)}，然后漏掉后面那几种取值——
     * 一个不报错的错答案。放不下就只报个数，让完整集合待在 {@code detail_json} 里。
     */
    private static String valueGloss(SemanticValueProfiler.ColumnValueDomain d) {
        List<String> values = d.getValues() == null ? List.<String>of() : d.getValues();
        String joined = String.join("、", values);
        if (joined.length() <= GLOSS_VALUES_MAX) {
            return "取值只有这 " + values.size() + " 种：" + joined
                    + "。（采样时点的完整集合，之后客户新增的取值不在其中）";
        }
        return "取值共 " + values.size() + " 种，太长放不进这句话，完整集合见本行 detail_json 的 "
                + SemanticValueProfiler.DETAIL_KEY + "。（采样时点的完整集合）";
    }

    // ---------------------------------------------------------------- 阶段小工具

    /** JOIN 行 {@code detail_json} 里的目标表列。两个都在才算数。 */
    private String[] joinTarget(ConnectorSemantic row) {
        Map<String, Object> detail = readDetail(row.getDetailJson());
        String toObj = str(detail, "to_object");
        String toCol = str(detail, "to_column");
        return (toObj == null || toCol == null) ? null : new String[]{toObj, toCol};
    }

    private Map<String, Object> readDetail(String json) {
        if (blank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = readMap(json);
            return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
        } catch (Exception e) {
            // 坏掉的 detail_json 不该让这条行永远验不了。当空处理重建，比原地报错强。
            log.warn("语义行的 detail_json 解析失败，本次按空处理：{}", describe(e));
            return new LinkedHashMap<>();
        }
    }

    /**
     * 候选与结论的对照键。<b>折叠大小写</b>，与 {@link #dedupKey} 同一条理由：
     * 这两头的名字一头来自语义行、一头来自验证器的回声，大小写敏感地比对会让一部分结论
     * <b>对不上任何一行</b>——表现是「验证跑完了，但一行都没改」。
     */
    private static String joinKey(String fromObj, String fromCol, String toObj, String toCol) {
        return fold(fromObj) + KEY_SEP + fold(fromCol) + KEY_SEP + fold(toObj) + KEY_SEP + fold(toCol);
    }

    private static String fieldKey(String objectName, String fieldName) {
        return fold(objectName) + KEY_SEP + fold(fieldName);
    }

    /**
     * 从上一次的 {@code semantic_note} 里切出「推导结论」那一段。
     *
     * <p>不切的话，第二轮验证会把上一轮的进度文字当成推导结论再接一段，note 变成
     * {@code A ｜ B ｜ C ｜ …}，然后被 varchar(512) 从<b>尾巴</b>截掉——而尾巴上正是最新那一段。
     * 这个 bug 要等同一条连接被验过三四次之后才显形，那时已经没人记得是谁加的。
     */
    private static String notePrefix(String semanticNote) {
        if (blank(semanticNote)) {
            return "";
        }
        int i = semanticNote.indexOf(NOTE_SEP.trim());
        return clip(i > 0 ? semanticNote.substring(0, i) : semanticNote, NOTE_PREFIX_MAX);
    }

    /** 只改 {@code semantic_note}：status 归推导所有，本阶段一个字都不碰它。 */
    private void writeStageNote(Long connectorId, String prefix, String stageNote) {
        writeStatus(connectorId, null, blank(prefix) ? stageNote : prefix + NOTE_SEP + stageNote,
                false, null);
    }

    /** 两个条件都要满足才写一次进度，理由见 {@link #VALIDATION_PROGRESS_EVERY}。 */
    private void maybeProgress(Long connectorId, String prefix, int decided, int total, JoinCounters c) {
        long now = System.currentTimeMillis();
        if (decided % VALIDATION_PROGRESS_EVERY != 0
                || now - c.lastNoteAt < VALIDATION_PROGRESS_MIN_INTERVAL_MS) {
            return;
        }
        c.lastNoteAt = now;
        writeStageNote(connectorId, prefix, "正在验证表关系 " + decided + "/" + total + "……");
    }

    private static void appendIfPresent(StringBuilder b, Object v) {
        if (v != null && !String.valueOf(v).isBlank()) {
            b.append(v).append('。');
        }
    }

    /** 优先用线程上的真实租户；请求线程与后台线程都拿得到，取不到再退回行上的那个。 */
    private static String tenantOf(Connection conn) {
        String t = TenantContext.get();
        return blank(t) ? (conn == null ? null : conn.getTenantId()) : t;
    }

    // ================================================================ 小工具

    private static FieldDetail lookup(Map<String, Map<String, FieldDetail>> byObject, String obj, String col) {
        Map<String, FieldDetail> cols = byObject.get(obj);
        return cols == null ? null : cols.get(col);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> arr(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object e : list) {
            if (e instanceof Map<?, ?> em) {
                out.add((Map<String, Object>) em);
            }
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> strList(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object e : list) {
            if (e != null) {
                out.add(String.valueOf(e));
            }
        }
        return out;
    }

    /** 认不出来的依据标签一律当 GUESS。宁可少一条，也不要一条来路不明的。 */
    private static String evidence(Map<String, Object> m, String whenMissing) {
        String e = str(m, "evidence");
        if (e == null) {
            return whenMissing;
        }
        String up = e.toUpperCase(Locale.ROOT);
        return switch (up) {
            case ConnectorSemanticService.EV_COMMENT,
                 ConnectorSemanticService.EV_DATA,
                 ConnectorSemanticService.EV_NAME,
                 ConnectorSemanticService.EV_GUESS -> up;
            default -> ConnectorSemanticService.EV_GUESS;
        };
    }

    /**
     * 置信度归一到 0-100。模型有时给 0.8，有时给 80，两种都得认。
     *
     * <p>{@code <= 1} 一律当小数放大：「置信度 1 分」不是任何人会想表达的意思，
     * 而把 0.8 存成 1，会让一条中等把握的断言在界面上看起来完全不可信。
     */
    private static Integer confidence(Map<String, Object> m) {
        String raw = str(m, "confidence");
        if (raw == null) {
            return null;
        }
        double d;
        try {
            d = Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
        long v = d <= 1.0d ? Math.round(d * 100) : Math.round(d);
        return (int) Math.max(0, Math.min(100, v));
    }

    private String toJson(Object o) {
        try {
            return CommonUtil.getObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            log.warn("序列化语义 detail 失败", e);
            return null;
        }
    }

    /** 异常摘要带上类名：NPE 这类 message 为 null 的异常，否则在界面上只剩一个孤零零的 "null"。 */
    private static String describe(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    /** detail_json 里的数组：条数和每条长度都要收口，理由见 {@link #DETAIL_TEXT_MAX}。 */
    private static List<String> detailList(List<String> in) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (out.size() >= DETAIL_LIST_MAX) {
                break;
            }
            out.add(clip(s, DETAIL_TEXT_MAX));
        }
        return out;
    }

    /** 折叠大小写，用于按 utf8mb4_unicode_ci 的口径比对键与词条。 */
    private static String fold(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /** 日志里带上超长值的头一截就够定位了，整段打出来只会把日志刷爆。 */
    private static String abbrev(String s) {
        return s == null ? "" : (s.length() <= 40 ? s : s.substring(0, 40) + "…(" + s.length() + ")");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** 摘要是按行、按竖线分列的，列注释里真出现换行或竖线会把这个格式撑破，就地换掉。 */
    private static String nz(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ================================================================ 形状

    /**
     * 结构摘要 + 它不完整在哪。纯内部用、不出 HTTP，可以是 record。
     *
     * @param totalObjects      客户库里<b>一共</b>有多少个对象（含快照本身没覆盖到的那些），
     *                          不是快照的行数——后者会把「只快照了前 200 张」读成全覆盖
     * @param totalIsLowerBound 上面那个数是不是「至少这么多」（快照被截过、但这次没去客户库数过）
     */
    private record Digest(String text, int includedObjects, int totalObjects,
                          boolean totalIsLowerBound, List<String> notes) {
    }

    /**
     * 补拉结构的结果。
     *
     * @param applicable   这种连接器提不提供结构自描述。false = 语义层对它不适用（不是失败）
     * @param totalObjects 客户库里的对象总数（拉到了才有），用来算真实覆盖面
     * @param failure      失败原因；null = 这次补拉成功了
     */
    private record Bootstrap(boolean applicable, Integer totalObjects, String failure) {
    }

    /** 过程计数器。纯内部可变状态，不序列化。 */
    private static final class Stats {
        int objects;
        int fields;
        int joins;
        int caveats;
        int droppedGuess;
        int droppedUnknown;
        int droppedDup;
        /** 键列超过 varchar(191)，整条丢掉的条数。 */
        int droppedTooLong;
        /** 人已经答过、因此没有再提一遍的口径问题条数。 */
        int droppedAnswered;
    }

    /**
     * 推导结果。
     *
     * <p><b>刻意不是 record</b>：本仓库实际生效的 jackson-databind 是 2.11.1，而 record 的序列化支持
     * 是 2.12+ 才有的，任何可能跨 HTTP 出去的 DTO 在这里都必须是 Lombok 类
     * （同 {@code ConnectorSchemaService.ObjectDiff} 上那段注释）。
     */
    @Data
    @Builder
    public static class DeriveResult {
        private boolean ok;
        private int objectCount;
        private int fieldCount;
        private int joinCount;
        private int caveatCount;
        /** 因为没有外部依据（GUESS）被丢掉的条数。0 不代表模型没猜，只代表它没承认。 */
        private int droppedGuess;
        /** 因为表名/列名在快照里找不到被丢掉的条数。这一项偏高，说明摘要给少了或者模型在编。 */
        private int droppedUnknown;
        /** 因为键列超过 varchar(191) 被整条丢掉的条数。 */
        private int droppedTooLong;
        /** 人已经答过、因此这次没有再提一遍的口径问题条数。 */
        private int skippedAnswered;
        /**
         * 本次有对象没进模型。<b>两种来源都算</b>：摘要字符数超限丢掉的表，
         * 以及结构快照自己就在 {@link #SCHEMA_SNAPSHOT_CAP} 个对象处按字母序截过的那一批。
         * 漏掉后一种，一个 500 张表的客户会得到一份只覆盖前 200 张、却报「未截断」的说明书。
         */
        private boolean truncated;
        private String note;
    }

    /**
     * S3 那一段的结果。纯内部，不出 HTTP，可以是 record。
     *
     * @param notProbed <b>决不能和 {@code undecidable} 合并</b>：前者是「没查」，后者是「查了判不出来」。
     *                  合成一个数字之后，一次因为档位不够而整批没跑的验证，
     *                  看起来会和一次真的跑完、只是样本不给力的验证一模一样。
     */
    private record JoinStage(String outcome, int candidates, int confirmed, int weak, int rejected,
                             int undecidable, int notProbed, int written, int probeCount, String note) {
    }

    /** S4 那一段的结果。同上。 */
    private record ValueStage(int columns, int enumerated, int created, int statements, String note) {
    }

    /** S3 回调里的可变计数器。回调是单线程顺序调用的（验证器自己节流着一条一条来），无需同步。 */
    private static final class JoinCounters {
        int confirmed;
        int weak;
        int rejected;
        int undecidable;
        /** 验证器返回了结论但那条结论是「没探查」。 */
        int notProbed;
        /** 真的 UPDATE 成功的行数。<b>它和上面四个数的差额是要盯的</b>：差额不为 0 说明结论对不上行。 */
        int written;
        long lastNoteAt;
    }

    /**
     * 采样验证阶段的结果。
     *
     * <p><b>刻意不是 record</b>，理由同 {@link DeriveResult}：jackson-databind 2.11.1 不支持 record，
     * 而这个形状会随管理台接口出网。
     */
    @Data
    @Builder
    public static class ValidationResult {
        private boolean ok;

        /** {@code SemanticJoinValidator.OUT_*}。<b>{@code TIER_BLOCKED} 不等于「跑了但没问题」。</b> */
        private String joinOutcome;
        private int joinCandidates;
        private int joinConfirmed;
        private int joinWeak;
        /** 实测<b>不成立</b>的关系数。这些行的 {@code verified} 已经写成 REJECTED，注入层据此不再展示。 */
        private int joinRejected;
        /** 探查了、但样本判不出来的条数。 */
        private int joinUndecidable;
        /** <b>没探查</b>的条数（档位不够 / 预算用尽 / 被中止）。与上面那个是两回事。 */
        private int joinNotProbed;
        /** 真的落库成功的关系行数。 */
        private int joinRowsUpdated;
        /** 本轮对客户库发出的关系探查次数（含失败的）。成本要可见。 */
        private int probeCount;

        /** 真的发了语句去剖析的列数。 */
        private int valueColumns;
        /** 其中拿到完整取值集合的列数。 */
        private int valueEnumerated;
        /** 因为原本没有字段行而<b>补写</b>出来的行数，理由见 {@code runValueStage}。 */
        private int valueRowsCreated;
        /** 值域采集打到客户库的语句条数。 */
        private int valueStatements;

        private String note;
    }
}
