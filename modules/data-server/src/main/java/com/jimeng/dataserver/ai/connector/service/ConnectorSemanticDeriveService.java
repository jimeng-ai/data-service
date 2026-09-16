package com.jimeng.dataserver.ai.connector.service;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.AssemblyReport;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.DETAIL_TEXT_MAX;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.GLOSS_MAX;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.NAME_MAX;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.answeredTerms;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.base;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.clip;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.dedupKey;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.fold;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.len;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.lookup;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.nz;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.parseFields;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.renderObject;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.str;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.strList;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.toJson;
import static com.jimeng.dataserver.ai.connector.service.SemanticRowAssembler.toRows;

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

    // GLOSS_MAX / NAME_MAX / DETAIL_TEXT_MAX / DETAIL_LIST_MAX / CARDINALITIES 连同 toRows、parseFields、renderObject 与那批小工具
    // 已原样迁到 SemanticRowAssembler（静态导入），本类只委托：语义层生成 agent 的逐表提交要走同一套过滤，两份实现迟早分叉。

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
     * 推导那一次模型调用的超时夹紧区间（秒），配置项见 {@link ConnectorProperties.Semantic#getModelTimeoutSeconds()}。
     *
     * <p>上限从 {@link #CLAIM_STALE_MINUTES} 倒推，而不是另写一个数：模型还没回来、认领却已过期，另一次推导就能抢进来，
     * 两次推导各写各的状态和行。留 5 分钟：HTTP 层在读超时之上还有 {@code RequestService.CALL_TIMEOUT_GRACE}（60 秒）
     * 的整通调用余量，再加上模型调用之前的读快照、S1 语料挖掘（20 秒封顶）和之后的写库。
     * 注意这 5 分钟<b>不含</b>快照为空时的 {@code bootstrapSnapshot}（补拉结构，没有总时长封顶），
     * 所以上限只是护栏，配置值不要贴着它配。
     *
     * <p>下限 60 秒：配成个位数的表现不是「快速失败」，而是每一次推导都必然超时、说明书永远出不来。
     */
    static final int MODEL_TIMEOUT_MIN_SECONDS = 60;

    static final int MODEL_TIMEOUT_MAX_SECONDS = (int) ((CLAIM_STALE_MINUTES - 5L) * 60L);

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

    /**
     * K-3：值域阶段补写的 FIELD 行在 detail_json 里带的来源标记（{@code "origin": "value_profile"}）。
     *
     * <p>为什么要一个标记，而不是靠 gloss 长什么样去认：这类行的 gloss 从前是把真实取值原样列出来的一句话，
     * 注入层只能在 {@code value_domain} 里还存着取值时把它挡下；一次没有枚举出取值的重采（基数超限、超时、PII、失败）
     * 会把 {@code value_domain} 换掉，那句带着取值的 gloss 于是在任何档位上都照常出库，没有任何信号。
     * 标记让「这一行是谁写的」不再依赖任何一句话的措辞。
     */
    static final String KEY_ORIGIN = "origin";

    static final String ORIGIN_VALUE_PROFILE = "value_profile";

    /**
     * 值域阶段补写的行的 gloss。★ <b>一个取值、一个取值个数都不含</b>（K-3）：真实取值只活在 {@code value_domain} 里，
     * 那里有档位闸；gloss 没有闸，写进去就等于绕过它。个数也不写——它和取值出自同一次第 3 档采集，
     * 注入层在档位不开放时连 {@code distinct_count} 一起不给。
     *
     * <p>措辞刻意不随重采变化：它不说「采到了完整集合」，下一次重采可能采不全，那时这句话就成了假话。
     */
    static final String VALUE_ROW_GLOSS = "这一列没有推导出的字段说明，本行由列取值采集补写；"
            + "取值情况只以 value_domain 为准，这句话不复述任何取值。";

    /**
     * 上线过的旧版补写行 gloss 的开头与结尾记号，原样取自那一版的 {@code valueGloss}，<b>不许改</b>。
     *
     * <p>这里按措辞认是成立的，与注入层「不按措辞认」并不矛盾：要认的是<b>已经写进库里</b>的那批旧行，
     * 它们的文字不会再变；新写的行一律带 {@link #KEY_ORIGIN}，不靠这几个字。
     * 不能只凭「INFERRED + FIELD + DATA」就改写 gloss：提示词允许模型给字段含义标 DATA（从类型 / 约束推得到），
     * 那样会把模型写的说明一起抹掉。
     */
    private static final String LEGACY_VALUE_GLOSS_LISTED = "取值只有这 ";

    private static final String LEGACY_VALUE_GLOSS_COUNTED = "取值共 ";

    private static final String LEGACY_VALUE_GLOSS_MARK = "（采样时点";

    /** 表形态测量冷却键前缀。后面接连接 id 与「折叠表名 + 结构指纹」的摘要，见 {@link #shapeCooldownKey}。 */
    static final String SHAPE_COOLDOWN_KEY_PREFIX = "connector:semantic:shape-cooldown:";

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
     * 表形态测量（键值对表判定）。它读的是派生统计（第 2 档），所以<b>只在验证阶段里跑</b>，
     * 绝不进 {@link #derive}——推导向客户库发送的字节数必须保持为零。
     *
     * <p>新的 final 字段一律<b>往后加</b>：{@code @RequiredArgsConstructor} 按声明顺序生成构造器，
     * 插在中间会让按位置构造本类的测试静默错位。
     */
    private final TableShapeDetector shapeDetector;

    /**
     * 增量补写的「最近试过」冷却标记，见 {@link #planAddedBatch}。
     *
     * <h3>为什么冷却必须落在 Redis，不能放进程内存</h3>
     * 本仓库 push main 即部署。进程内的冷却每次部署清零，于是一张模型怎么都写不出东西的表（只有 GUESS 产出）
     * 会在每次部署后的第一次刷新里再花一次模型调用——与 {@code ConnectorHealthJob} 把刷新冷却放进 Redis
     * 是同一个理由：进程的存活时长不是可靠的时钟。
     *
     * <h3>为什么是这个 bean、为什么不会成环</h3>
     * 用的是 {@code ConnectorHealthJob} 做刷新冷却的同一个 {@link RedissonClient}，写法也照抄那边
     * （{@code RBucket} + TTL，读失败按「不在冷却」）。它由 common-core 的 {@code RedissonConfig} 直接
     * {@code Redisson.create}，只依赖三个 {@code @Value}，是一片叶子；本类的注入方只有管理台控制器和
     * （经 {@code ObjectProvider}）{@code ConnectorSchemaService}，加这条边闭合不了任何环。
     */
    private final RedissonClient redissonClient;

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
     * 为一张表补写过说明书之后，多少小时内不再为它叫模型。
     *
     * <p>它防的是一个真实的死循环：模型对某张表只给得出 GUESS（整批丢弃），这张表就永远算「没覆盖」，
     * 而每次刷新结构（定时每 6 小时一次，外加手动）都会把它重新算进来。24 小时是定时刷新间隔的 4 倍：
     * 一张注定写不出东西的表每天最多花一次模型调用。
     * <b>表结构一变（比如客户补了注释）冷却立刻失效</b>——冷却键里带着那张表的结构指纹，
     * 那一刻恰恰是重试最可能写出东西的时候，见 {@link #addedCooldownKey}。
     *
     * <p>配成 {@code <= 0} 按 1 小时处理：关掉冷却不是一个选项，那等于把上面那个死循环请回来。
     */
    @Value("${connector.semantic.added-cooldown-hours:24}")
    private long addedCooldownHours = 24L;

    /**
     * <b>没有表用途行</b>的表测过表形态之后，多少小时内不再测。
     *
     * <p>有用途行的表测完结论落在行上，下一轮按行跳过；没有用途行的表测出「不是键值对表」无处可放，
     * 从前每一轮验证都重测一遍——而验证一度每天都会被派发，等于每天在客户库上重跑一次分组统计，永远。
     * 冷却键里带着结构指纹，表结构一变立刻失效。默认一周：表形态是很少变的东西，且结构变了就会重测。
     * 配成 {@code <= 0} 按 1 小时处理，理由同 {@link #addedCooldownHours}。
     */
    @Value("${connector.semantic.shape-cooldown-hours:168}")
    private long shapeCooldownHours = 168L;

    /**
     * 本进程内正在跑验证阶段的连接。刷新结构那条心跳上的结构补标（{@link #healStructureOnRefresh}）也占这道闸——
     * 两边都会整份改写 JOIN 行的 detail_json，不能交错。
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

    /**
     * 验证阶段正在跑时又来了一次派发（典型：增量推导刚写下几行 V_NONE）。记下范围，这一轮跑完补跑一轮。
     *
     * <p>不记的话，那几行要等到下一次有人手动点「验证表关系」——正在跑的那一轮开跑时就读完了语义行，
     * 看不见它们；而新派发的那一轮在 {@link #validating} 上被挡回。两边都以为对方会做。
     */
    private final Map<Long, Set<String>> validateAgain = new ConcurrentHashMap<>();

    /** 「全部对象」的哨兵。ConcurrentHashMap 不收 null 值，按引用比较，所以必须是独一份的实例。 */
    private static final Set<String> SCOPE_ALL = Collections.unmodifiableSet(new HashSet<>());

    /**
     * 待补写的<b>提示</b>：键在 = 这条连接要跑一圈覆盖检查；值 = 刷新时报 ADDED 的表名（可以是空集合）。
     * 按连接合并：同一条连接上连着刷新几次，并成一圈。
     *
     * <p>★ 它<b>不是</b>「哪些表还没有说明书」的账本——那本账由 {@link #planAddedBatch} 每一圈从库里现算。
     * 这里只在进程内存里，每次部署清零、池子拒绝时也可能留不住；把它当账本，丢了的表就永远没人补。
     * 值里的名字唯一的额外含义是「允许原地重写」，理由见 {@link #deriveAdded(Long, Set, Set)}。
     */
    private final Map<Long, Set<String>> pendingAdded = new ConcurrentHashMap<>();

    /**
     * 读库失败、这一轮没法消化又不能丢的 ADDED 提示名单。
     *
     * <p><b>不放回 {@link #pendingAdded}</b>：那里的键一在就会让 drain 收尾时「再看一眼」立刻再派一轮，
     * 库挂着的时候就是一轮接一轮原地空转。停在这里，下一次入队（下一次刷新结构）时并回去。
     *
     * <p>为什么不能丢：名字唯一的含义是「允许原地重写」，而下一次刷新的 diff 不会再把这些表报成 ADDED。
     * 丢了之后，删了又建的表身上那批旧化身的行就再也没有机会被换成新结构上的说明。
     */
    private final Map<Long, Set<String>> parkedAdded = new ConcurrentHashMap<>();

    /** 本进程内正在消化 {@link #pendingAdded} 的连接。同一条连接的增量推导串行，避免两批互相覆盖状态。 */
    private final Set<Long> addedRunning = ConcurrentHashMap.newKeySet();

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
        AssemblyReport st = new AssemblyReport();
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
                        .droppedGuess(st.getDroppedGuess())
                        .droppedUnknown(st.getDroppedUnknown())
                        .droppedTooLong(st.getDroppedTooLong())
                        .skippedAnswered(st.getDroppedAnswered())
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
                    connectorId, n, st.getObjects(), st.getFields(), st.getJoins(), st.getCaveats(),
                    st.getDroppedGuess(), st.getDroppedUnknown(), st.getDroppedDup(), st.getDroppedTooLong(), st.getDroppedAnswered());
            return DeriveResult.builder()
                    .ok(true)
                    .objectCount(st.getObjects())
                    .fieldCount(st.getFields())
                    .joinCount(st.getJoins())
                    .caveatCount(st.getCaveats())
                    .droppedGuess(st.getDroppedGuess())
                    .droppedUnknown(st.getDroppedUnknown())
                    .droppedTooLong(st.getDroppedTooLong())
                    .skippedAnswered(st.getDroppedAnswered())
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

    // ================================================================ 增量推导（§8 ADDED：新对象入队，只跑 S2）

    /** 增量推导期间结构又变了时，同一批表最多重新入队几次。再多说明客户那边在持续改结构，等下一次刷新。 */
    private static final int MAX_ADDED_SNAPSHOT_RETRIES = 2;

    /**
     * 一次增量模型调用最多带几张表。多出来的在同一轮 drain 里排下一圈。
     *
     * <p>摘要字符上限（{@code connector.semantic.max-digest-chars}）管的是<b>输入</b>，管不住<b>输出</b>：
     * 35 张表的全量推导就能产出几百条字段含义，而 max_tokens 默认 16000。输出被截断时解析侧会截到最后一个完整条目
     * 并明说，但被截掉的那几张表已经记了冷却，要等一个冷却期才会再补。30 张让一次调用的产出大体落在 max_tokens 之内，
     * 同时「补 140 张没覆盖的表」也只是 5 次调用。
     */
    static final int ADDED_BATCH_MAX = 30;

    /** 冷却键前缀。后面接连接 id 与「折叠表名 + 结构指纹」的摘要，见 {@link #addedCooldownKey}。 */
    static final String ADDED_COOLDOWN_KEY_PREFIX = "connector:semantic:added-cooldown:";

    /**
     * 刷新结构之后补写说明书：入队，立刻返回，<b>不抛</b>。给 {@code ConnectorSchemaService.refresh} 调——
     * <b>每一次</b>非首次、未被拦下的成功刷新都会调，哪怕这次没有新增的表（名单为空）。
     *
     * <h3>★ 为什么名单为空也要跑一圈</h3>
     * 「哪些表还没有说明书」不能靠「谁通知过我」来记：通知只活在进程内存里，每次部署（push main 即部署）清零；
     * 一批里超出摘要上限的那部分从前不会再排队；池子拒绝之后只能干等下一个 ADDED 事件，而那个事件可能永远不来。
     * 所以覆盖改成<b>按数据算</b>：每一圈从快照和语义行现算「有列、却没有表用途行」的表（{@link #planAddedBatch}），
     * 与这次报 ADDED 的名字合在一起补。刷新结构就是心跳，名单为空的这一次调用正是它的脉搏。
     *
     * <h3>为什么不能让新表等下一次「重新生成」</h3>
     * 重新生成走 {@code replaceInferred}：整层 INFERRED 物理删掉重插，S3 的采样验证结论、S4 的值域、
     * 表形态测量全部清零，而且要重新花一轮客户库的配额才补得回来。一张新表的代价不该是这个。
     *
     * <h3>为什么落在阶段池而不是 streamExecutor</h3>
     * 调用线程是一次「刷新结构」的 HTTP 请求。streamExecutor 池满时 CallerRunsPolicy 会让一次模型调用
     * 就地跑在这条请求线程上——刷新要等几十秒。阶段池满了是抛出来，由这里写进 note。
     * 覆盖检查要读快照、读语义行、问 Redis，同样一概放在后台线程上：请求线程上只做一次入队。
     */
    public void deriveAddedAsync(Long connectorId, Collection<String> objectNames) {
        if (connectorId == null || !properties.getSemantic().isEnabled()) {
            return;
        }
        final String tenantId = TenantContext.get();
        if (blank(tenantId)) {
            log.warn("增量补写说明书被跳过：当前线程没有租户上下文 connectorId={}", connectorId);
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        if (objectNames != null) {
            for (String n : objectNames) {
                if (!blank(n)) {
                    names.add(n.trim());
                }
            }
        }
        // 上一轮读库失败停在一边的提示名单，借这一次入队带回去，理由见 parkedAdded。
        Set<String> parked = parkedAdded.remove(connectorId);
        if (parked != null) {
            names.addAll(parked);
        }
        // 空集合照样入队：键在就代表「跑一圈覆盖检查」，理由见上。
        pendingAdded.merge(connectorId, names, ConnectorSemanticDeriveService::union);
        if (addedRunning.add(connectorId)) {
            dispatchAddedDrain(connectorId, tenantId);
        }
    }

    private void dispatchAddedDrain(Long connectorId, String tenantId) {
        try {
            semanticStageExecutor.execute(MdcAsyncSupport.wrap("semantic-added-" + connectorId,
                    () -> drainAdded(connectorId, tenantId)));
        } catch (RuntimeException e) {
            addedRunning.remove(connectorId);
            // 提示名单留在 pendingAdded 里，下一次刷新入队时一起带走；没有名单的表下一次刷新会被重新算出来。
            log.warn("增量补写说明书派发失败 connectorId={}: {}", connectorId, describe(e));
            Set<String> waiting = pendingAdded.get(connectorId);
            if (waiting == null || waiting.isEmpty()) {
                // 只是一圈例行的覆盖检查没派出去，没有哪张新表在等。每次刷新结构都会触发它，
                // 在这里写一句「队列满了」就是一条每次刷新都可能冒出来、却不需要任何人做任何事的警报。
                return;
            }
            // 有新表在等就必须说出来，否则「新表没有说明书」和「新表的说明书还在路上」长得一样。
            try {
                Connection c = connectionMapper.selectById(connectorId);
                writeStageNote(connectorId, notePrefix(c == null ? null : c.getSemanticNote()),
                        "新增表的说明书没有派发出去（后台队列已满），下一次刷新结构时会自动补写");
            } catch (Exception ignore) {
                // note 写不进去只剩日志，不能让它反过来把一次成功的刷新变成失败。
            }
        }
    }

    /**
     * 后台线程上消化这条连接的补写。一圈 = 现算一批 → 一次模型调用 → 记冷却；还有剩的就再来一圈。
     *
     * <h3>★ 这个 while 为什么一定会停</h3>
     * <ul>
     *   <li>每一圈要么至少把一张表送进模型、并把它记进 {@code skip}（本轮 drain 不再送它），
     *       要么一张都没送——后一种<b>不重新入队</b>，只有别的线程新入队的提示能让它再转一圈。</li>
     *   <li>{@code skip} 是进程内的兜底：Redis 冷却读写失败时按「放行」处理，没有这一道，
     *       一张模型写不出东西的表会在这个 while 里被一遍遍送进去。</li>
     *   <li>结构在推导期间变了的重推有 {@link #MAX_ADDED_SNAPSHOT_RETRIES} 次封顶。</li>
     * </ul>
     *
     * <p>收尾那段是标准的「放闸后再看一眼」：别的线程可能正好在本线程最后一次 remove 之后、
     * 放掉 {@link #addedRunning} 之前入队，它看见闸还关着就没有派发——不补这一眼，那批表就只能等下一次刷新。
     *
     * <p>读库失败的那两处（{@link AddedBatch#readFailed()} / {@link AddedOutcome#readFailed()}）同样有界：
     * 原地重试与「结构变了重推」共用 {@link #MAX_ADDED_SNAPSHOT_RETRIES}，用完就把提示名单停到 {@link #parkedAdded}，
     * 那里不触发 drain。
     */
    void drainAdded(Long connectorId, String tenantId) {
        if (!TenantContext.isSet() && tenantId != null) {
            TenantContext.set(tenantId);
        }
        int retries = 0;
        Set<String> skip = new HashSet<>();
        try {
            // ★ 结构形态补标挂在这条心跳上，理由见 healStructureOnRefresh。它在 try 里：它抛出任何东西，
            //   addedRunning 这道闸也必须放掉，否则这条连接在本进程里再也不会补写说明书。
            healStructureOnRefresh(connectorId, tenantId);
            while (true) {
                Set<String> hints = pendingAdded.remove(connectorId);
                if (hints == null) {
                    break;
                }
                AddedBatch batch = planAddedBatch(connectorId, hints, skip);
                if (batch.readFailed()) {
                    // 连接或快照读不出来：这一圈什么都算不了。提示名单停到一边等下一次刷新（没覆盖的表那时会被重新算出来），
                    // 原地重排只会在库挂着的时候空转。
                    park(connectorId, hints);
                    continue;
                }
                if (batch.names().isEmpty()) {
                    continue;
                }
                AddedOutcome out = deriveAdded(connectorId, new LinkedHashSet<>(batch.names()), batch.rewritable());
                // 这一批手上的全部提示名单：送进去的，和这一批装不下的。任何「这批作废、重来」的出路都要整份带回去。
                Set<String> batchHints = union(batch.rewritable(), batch.overflowHints());
                if (out.readFailed()) {
                    // ★ 认领之前读连接失败（库抖了一下）：一张没送、一行没写。从前这一次读在 try 外面，异常直接冲出 while，
                    //   这一批的提示名单连同这一轮剩下的一起没了。先原地重试，重试用完就停到一边等下一次刷新。
                    if (retries++ < MAX_ADDED_SNAPSHOT_RETRIES) {
                        pendingAdded.merge(connectorId, batchHints, ConnectorSemanticDeriveService::union);
                    } else {
                        park(connectorId, batchHints);
                    }
                    continue;
                }
                if (out.sent().isEmpty()) {
                    // 一张都没送进模型：说明书没 READY、认领被全量推导抢走、开关关着、或表已不在快照里。
                    // 原地再排一圈结果不会变，只会空转着打库；这些表下一次刷新会被重新算出来。
                    continue;
                }
                if (out.snapshotMoved() && retries++ < MAX_ADDED_SNAPSHOT_RETRIES) {
                    // 这批一行没落库：不记冷却、不进 skip，用新快照再推一次。
                    // ★ 提示名单整份带回去（它决定「允许重写」）：这一批里的，和这一批装不下的（overflowHints）——
                    //   后者从前在这条路上被丢掉，它们的「允许重写」就此没了。没覆盖的表下一圈会被重新算出来。
                    pendingAdded.merge(connectorId, batchHints, ConnectorSemanticDeriveService::union);
                    continue;
                }
                Set<String> sentFolded = new HashSet<>();
                for (String n : out.sent()) {
                    sentFolded.add(fold(n));
                }
                skip.addAll(sentFolded);
                markAddedAttempted(connectorId, out.sent(), batch.contentHashes());

                // ★ 没送进去的再排一圈：摘要字符上限挤出去的，和这一批装不下的。
                //   从前丢表的正是这里——超出上限的那部分不会再入队，只能等一个可能永远不来的 ADDED 事件。
                boolean leftover = batch.more();
                Set<String> leftoverHints = new LinkedHashSet<>(batch.overflowHints());
                for (String n : batch.names()) {
                    if (!sentFolded.contains(fold(n))) {
                        leftover = true;
                        if (batch.rewritable().contains(n)) {
                            leftoverHints.add(n);
                        }
                    }
                }
                if (leftover) {
                    pendingAdded.merge(connectorId, leftoverHints, ConnectorSemanticDeriveService::union);
                }
            }
        } finally {
            addedRunning.remove(connectorId);
            if (pendingAdded.containsKey(connectorId) && addedRunning.add(connectorId)) {
                dispatchAddedDrain(connectorId, tenantId);
            }
        }
    }

    /**
     * 现算这一圈要送进模型的表。<b>顺序</b>：这次报 ADDED 的在前，然后是快照里「有列、却没有表用途行」的表。
     *
     * <h3>过滤，各防一种空转</h3>
     * <ol>
     *   <li><b>只在 {@link #SEM_READY} 上算。</b>没 READY 时「没覆盖」没有意义：全量推导要么正在跑、要么失败了。
     *       在失败的说明书上逐张补，会把「全量没生成出来」补成「看起来差不多都有」。</li>
     *   <li><b>没取到列的表不算没覆盖</b>（只读账号只授权到部分表时，快照里存的是只有名字的行）。
     *       模型对着一张没有列的表写不出东西，算进来就是每个冷却期白叫一次模型。表名超过 varchar(191) 的同理——
     *       写出来也落不了库，它会永远「没覆盖」。</li>
     *   <li><b>最近试过的跳过</b>：本轮 drain 送过的（{@code skip}），和 Redis 冷却还在的。
     *       Redis 读失败按「放行」：冷却是省钱的，不是一道闸，为省一次模型调用让新表一直没有说明书是本末倒置。
     *       失败一次之后这一圈不再问 Redis——Redis 不可达时 Redisson 每问一次要等上几秒，逐张问会把阶段池的线程拖住几分钟。</li>
     * </ol>
     *
     * <h3>★ 表用途行读失败时，一张都不算没覆盖</h3>
     * 读不出来就当「一张都没覆盖」，会把整个快照送进模型——而这些表身上多半已经挂着验证结论。
     * 读不出来 = 不知道；不知道就只补这次明确报了 ADDED 的那几张。
     *
     * @param skip 本轮 drain 已经送过、或查到还在冷却里的表（折叠名）。本方法会往里加冷却命中的表：
     *             冷却以小时计，同一轮 drain 里没必要再问一遍
     */
    AddedBatch planAddedBatch(Long connectorId, Set<String> hints, Set<String> skip) {
        if (!properties.getSemantic().isEnabled()) {
            return AddedBatch.EMPTY;
        }
        List<ConnectorSchema> rows;
        try {
            Connection conn = connectionMapper.selectById(connectorId);
            // READY，或者一次已经超时的认领（多半是被发版杀掉的补写），理由见 addedEligible。
            if (!addedEligible(conn)) {
                log.info("增量补写这一圈不跑：说明书尚未生成成功（或另一次推导正在进行） connectorId={} status={} 提示名单 {} 张",
                        connectorId, conn == null ? null : conn.getSemanticStatus(), hints.size());
                return AddedBatch.EMPTY;
            }
            rows = schemaService.currentRows(connectorId);
        } catch (Exception e) {
            log.warn("增量补写这一圈不跑：读连接或结构快照失败，提示名单留到下一次刷新 connectorId={}: {}",
                    connectorId, describe(e));
            return AddedBatch.READ_FAILED;
        }
        if (rows == null || rows.isEmpty()) {
            return AddedBatch.EMPTY;
        }
        Map<String, ConnectorSchema> byFolded = new LinkedHashMap<>();
        for (ConnectorSchema r : rows) {
            if (r != null && !blank(r.getObjectName())) {
                byFolded.putIfAbsent(fold(r.getObjectName()), r);
            }
        }

        List<ConnectorSchema> ordered = new ArrayList<>();
        Set<String> hintFolded = new HashSet<>();
        for (String h : hints) {
            ConnectorSchema r = byFolded.get(fold(h));
            if (r != null && hintFolded.add(fold(h))) {
                ordered.add(r);
            }
        }
        int uncovered = 0;
        Set<String> covered = coveredObjects(connectorId);
        if (covered != null) {
            Map<String, Map<String, FieldDetail>> fields = parseFields(rows);
            for (ConnectorSchema r : byFolded.values()) {
                String f = fold(r.getObjectName());
                Map<String, FieldDetail> cols = fields.get(r.getObjectName());
                if (hintFolded.contains(f) || covered.contains(f) || cols == null || cols.isEmpty()
                        || len(r.getObjectName()) > NAME_MAX) {
                    continue;
                }
                ordered.add(r);
                uncovered++;
            }
        }

        List<String> names = new ArrayList<>();
        Set<String> rewritable = new LinkedHashSet<>();
        Map<String, String> hashes = new HashMap<>();
        Set<String> overflowHints = new LinkedHashSet<>();
        boolean more = false;
        boolean askRedis = true;
        int cooling = 0;
        for (ConnectorSchema r : ordered) {
            String name = r.getObjectName();
            String f = fold(name);
            if (names.size() >= ADDED_BATCH_MAX) {
                // 装不下了。剩下的是否冷却留给下一圈去问；提示名单要带回去，没覆盖的表下一圈会被重新算出来。
                more = true;
                if (hintFolded.contains(f)) {
                    overflowHints.add(name);
                }
                continue;
            }
            if (skip.contains(f)) {
                continue;
            }
            if (askRedis) {
                try {
                    if (redissonClient.getBucket(addedCooldownKey(connectorId, name, r.getContentHash())).isExists()) {
                        skip.add(f);
                        cooling++;
                        continue;
                    }
                } catch (Exception e) {
                    askRedis = false;
                    log.warn("读增量补写冷却标记失败，这一圈按不在冷却处理 connectorId={}: {}", connectorId, describe(e));
                }
            }
            names.add(name);
            hashes.put(f, r.getContentHash());
            if (hintFolded.contains(f)) {
                rewritable.add(name);
            }
        }
        if (!names.isEmpty() || cooling > 0) {
            log.info("增量补写这一圈 connectorId={}：新增 {} 张、没覆盖 {} 张、冷却中 {} 张；本批送 {} 张，还有下一圈={}",
                    connectorId, hintFolded.size(), uncovered, cooling, names.size(), more);
        }
        return new AddedBatch(List.copyOf(names), Collections.unmodifiableSet(rewritable), hashes,
                Collections.unmodifiableSet(overflowHints), more, false);
    }

    /** 把提示名单停到 {@link #parkedAdded}，等下一次入队带回去。空的不停：它不代表任何一张表。 */
    private void park(Long connectorId, Set<String> hints) {
        if (hints != null && !hints.isEmpty()) {
            parkedAdded.merge(connectorId, new LinkedHashSet<>(hints), ConnectorSemanticDeriveService::union);
        }
    }

    /**
     * 增量补写能不能在这条连接上跑：说明书 {@link #SEM_READY}；或者停在 {@link #SEM_RUNNING} 的那次认领<b>已经超时</b>、
     * 而且这条连接<b>成功生成过</b>说明书。
     *
     * <h3>为什么要接管超时的 RUNNING</h3>
     * 增量补写认领时把状态抢成 RUNNING。发版恰好杀掉这次补写，状态就永远停在 RUNNING：之后每一次刷新的覆盖检查
     * 都因为「没 READY」静默跳过，唯一的出路是整层重新生成——而那会把 S3 / S4 的结论一并物理删掉。
     * 这里给它与全量推导的 {@link #claim} 同一个逃生口、同一个 {@link #CLAIM_STALE_MINUTES}，凭据同样落在 {@code semantic_claim_at}。
     *
     * <h3>为什么多一个「成功生成过」</h3>
     * 停住的 RUNNING 也可能是一次被杀掉的<b>全量</b>推导，它之前的状态可能是 FAILED 或从没生成过。在那种连接上补几张表再写 READY，
     * 会把「全量没生成出来」盖成「可用」——READY 那道闸防的正是这个。{@code semantic_synced_at} 只在成功时盖戳，
     * 它非空 = 库里有一份成功生成过的说明书（全量替换是一个事务，被杀在哪一步，留下的都是完整的一份），在它上面接着补是实话。
     * 从没成功过的，留给全量推导去接管。
     */
    private static boolean addedEligible(Connection conn) {
        if (conn == null) {
            return false;
        }
        if (SEM_READY.equals(conn.getSemanticStatus())) {
            return true;
        }
        return SEM_RUNNING.equals(conn.getSemanticStatus()) && conn.getSemanticSyncedAt() != null
                && (conn.getSemanticClaimAt() == null
                || conn.getSemanticClaimAt().getTime() < staleBefore(truncateToSecond(new Date())).getTime());
    }

    /** 认领早于这一刻就算超时，理由见 {@link #CLAIM_STALE_MINUTES}。 */
    private static Date staleBefore(Date now) {
        return new Date(now.getTime() - CLAIM_STALE_MINUTES * 60_000L);
    }

    /**
     * 已经有表用途行（OBJECT，不论来源、不论是否过期）的表，折叠名。
     *
     * <p>过期（STALE）的也算覆盖：表消失又回来时 {@code applyDrift} 会把它复活；它若真要换成新结构上的说明，
     * 会以 ADDED 的身份进来。
     *
     * @return <b>读失败返回 {@code null}</b>，理由见 {@link #planAddedBatch}「表用途行读失败时，一张都不算没覆盖」
     */
    private Set<String> coveredObjects(Long connectorId) {
        try {
            List<ConnectorSemantic> objects = semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                    .eq(ConnectorSemantic::getConnectorId, connectorId)
                    .eq(ConnectorSemantic::getScope, ConnectorSemanticService.SCOPE_OBJECT));
            Set<String> out = new HashSet<>();
            if (objects != null) {
                for (ConnectorSemantic o : objects) {
                    if (o != null && !blank(o.getObjectName())) {
                        out.add(fold(o.getObjectName()));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("读表用途行失败，这一圈只补报了 ADDED 的表 connectorId={}: {}", connectorId, describe(e));
            return null;
        }
    }

    /**
     * 冷却键：连接 id + sha256(折叠表名 + 结构指纹)。
     *
     * <p>带上 {@code content_hash}（表名、类型、每列 name:type:nullable:comment）是有意的：客户给表补了注释、加了列，
     * 指纹就变，冷却自然失效——一张只写得出 GUESS 的表最有可能被写出东西的时刻，恰恰是它的结构刚变过的时候。
     * 指纹对同一份结构是确定的，所以这不会反过来绕开冷却、造成空转。
     * 表名先折叠再摘要：唯一键按 utf8mb4_unicode_ci 比较，大小写不同的写法是同一张表；摘要也让键长与表名里的字符无关。
     */
    static String addedCooldownKey(Long connectorId, String objectName, String contentHash) {
        return ADDED_COOLDOWN_KEY_PREFIX + connectorId + ":"
                + ConnectorSemanticService.sha256(fold(objectName) + KEY_SEP + (contentHash == null ? "" : contentHash));
    }

    /**
     * 给这批真的送进过模型的表记冷却——<b>不论这次写出了什么、成没成功</b>：只写得出 GUESS 的表要冷却，
     * 这是冷却存在的理由；模型调用失败的也要，否则一个确定性的失败（比如请求被上游拒掉）会在每次刷新里重演。
     *
     * <p>写失败只记日志：本轮 drain 里有 {@code skip} 兜着，最坏是下一次刷新再试一次。
     */
    private void markAddedAttempted(Long connectorId, List<String> sent, Map<String, String> hashByFolded) {
        long hours = Math.max(1L, addedCooldownHours);
        String stamp = String.valueOf(System.currentTimeMillis());
        for (String name : sent) {
            try {
                RBucket<String> mark = redissonClient.getBucket(
                        addedCooldownKey(connectorId, name, hashByFolded.get(fold(name))));
                mark.set(stamp, hours, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("写增量补写冷却标记失败，本批剩下的不再写 connectorId={}: {}", connectorId, describe(e));
                return;
            }
        }
    }

    /** 整批都按 ADDED 处理（都允许原地重写）。 */
    AddedOutcome deriveAdded(Long connectorId, Set<String> requested) {
        return deriveAdded(connectorId, requested, requested);
    }

    /**
     * 为一批表跑一次 S2，结果<b>原地 UPSERT</b>，一行都不删。<b>不抛</b>。
     *
     * <h3>★ 报过 ADDED 的表可以重写，一直没覆盖到的表只补缺</h3>
     * 两种表走同一次模型调用，落库规矩不一样：
     * <ul>
     *   <li><b>报过 ADDED 的表</b>（{@code rewritable}）：上一份快照里没有它。表上若还挂着旧行（删了又建的表，
     *       行已经是 STALE），那是上一个化身的说明，原地换成新结构上的说明是对的。</li>
     *   <li><b>一直在、只是没有表用途行的表</b>：它身上可能已经有 S4 补写的字段值域、验证过的关系。
     *       模型这次没给它写出表用途，不代表那些结论作废。重写会把 {@code verified} 打回 NONE、把值域抹掉；
     *       而这张表若仍然只写得出 GUESS，每个冷却期就会再抹一次、再花一次客户库的配额把它们补回来。
     *       所以只插入缺的行，已有的一行不改（不进 {@code upsertInferred} 的 {@code updatableObjects}）。</li>
     * </ul>
     *
     * <h3>三道闸，每一道都对应一种静默的错</h3>
     * <ol>
     *   <li><b>只在 {@link #SEM_READY} 上跑。</b>说明书从没生成成功过（FAILED / 从未推导）时，
     *       写一批行再写一个 READY，会把「全量失败」盖成「可用」——9 成的表没有说明书，界面却是绿的。
     *       正在全量推导（RUNNING）时同样让开：它读的是最新快照，要么把新表一起推了，
     *       要么因为快照在它开跑之后变了而作废并要求重来——两种结局都不需要这里插手。</li>
     *   <li><b>认领 CAS 以 READY（或一次已经超时的认领）为前提</b>（{@link #claimAdded}），和全量推导抢的是同一个
     *       {@code semantic_claim_at}：两者不可能交错写。认领之后的每一条出路都回到 READY。</li>
     *   <li><b>落库前复核结构</b>：模型调用期间有人刷新了结构、而且真的改了列，这批行锚的就是旧结构，
     *       而那一轮漂移检测已经跑完、不会回头看它们。此时整批重新入队，用新快照再推一次。
     *       只按列指纹比、不按 synced_at 比：一次什么都没变的刷新不该让这批作废。</li>
     * </ol>
     *
     * <h3>只为这批表写，但已有的表要给模型看</h3>
     * 新表最有价值的往往是指向老表的那条关系。老表上已有的 INFERRED 行一行不改（它们身上可能有
     * 采样验证结论），见 {@link ConnectorSemanticService#upsertInferred} 的 {@code updatableObjects}。
     *
     * @param requested  这一批要补写的表
     * @param rewritable 其中允许原地重写已有推断行的表（报过 ADDED 的），理由见上
     * @return {@code sent} = 真的把结构送进了模型请求的表（快照写法）。调用方据此记冷却、决定哪些要再排一圈
     */
    AddedOutcome deriveAdded(Long connectorId, Set<String> requested, Set<String> rewritable) {
        ConnectorProperties.Semantic cfg = properties.getSemantic();
        if (!cfg.isEnabled()) {
            return AddedOutcome.skipped("语义层推导已关闭（connector.semantic.enabled=false）");
        }
        Connection conn;
        try {
            conn = connectionMapper.selectById(connectorId);
        } catch (Exception e) {
            // ★ 认领之前的这一次读必须接住：它从前在 try 外面，库抖一下异常就冲出 drain 的 while，
            //   这一批的提示名单连同这一轮剩下的一起没了。一行没写、一张没送、什么都没认领，交回 drain 决定重试还是暂存。
            log.warn("增量补写说明书这一批没有开始：读连接失败 connectorId={}: {}", connectorId, describe(e));
            return AddedOutcome.failedToRead("读连接失败：" + describe(e));
        }
        if (conn == null) {
            return AddedOutcome.skipped("连接不存在");
        }
        if (!addedEligible(conn)) {
            log.info("增量补写说明书被跳过：说明书尚未生成成功（或另一次推导正在进行） connectorId={} status={} 待补写={}",
                    connectorId, conn.getSemanticStatus(), requested.size());
            return AddedOutcome.skipped("说明书尚未生成成功（当前状态 " + conn.getSemanticStatus()
                    + "），这些表留给下一次完整生成");
        }
        // 走到这里还是 RUNNING = 一次超时的认领，这次要接管它（addedEligible）。上一版结论那句话已经被那次认领的
        // 「正在……」盖掉了，接在后面只会误导，换成一句说清发生了什么的话。
        boolean takeover = SEM_RUNNING.equals(conn.getSemanticStatus());
        String prevPrefix = takeover
                ? "上一次推导停在「进行中」超过 " + CLAIM_STALE_MINUTES + " 分钟没有结束（多半是被发版打断），本次已接管"
                : notePrefix(conn.getSemanticNote());
        Date claimAt = claimAdded(connectorId, requested.size());
        if (claimAt == null) {
            log.info("增量补写说明书被跳过：认领不到（另一次推导在进行中） connectorId={}", connectorId);
            return AddedOutcome.skipped("同一条连接上已有一次推导在进行中，这些表留给它");
        }
        if (takeover) {
            log.warn("增量补写接管了一次超时的认领 connectorId={} 上次认领={}", connectorId, conn.getSemanticClaimAt());
        }

        // 送进模型请求的表。它在 try 外面，catch 里也要带出去：请求发出去之后抛的异常照样算「试过」。
        List<String> sent = List.of();
        // ★ 认领之后的每一条出路都必须回到 READY。正常出口各自写一句结论并置位；catch 接不住的 Error（OOM、栈溢出）
        //   由 finally 兜底——少了它，状态停在 RUNNING，要等一次超时接管（30 分钟起）才恢复，这期间每次刷新都静默跳过。
        boolean restored = false;
        try {
            List<ConnectorSchema> rows = schemaService.currentRows(connectorId);
            Map<String, Map<String, FieldDetail>> fieldsByObject = parseFields(rows);
            // 结构指纹（不含 synced_at）：「指纹没变」恰好等于「这批行的锚点在新快照上照样成立」。按表指纹与锚点同源，见 tableStamp。
            String structure = ConnectorSemanticService.structureStamp(fieldsByObject);

            Set<String> requestedFolded = new HashSet<>();
            for (String r : requested) {
                requestedFolded.add(fold(r));
            }
            Set<String> rewritableFolded = new HashSet<>();
            if (rewritable != null) {
                for (String r : rewritable) {
                    rewritableFolded.add(fold(r));
                }
            }
            List<ConnectorSchema> targets = rows.stream()
                    .filter(r -> requestedFolded.contains(fold(r.getObjectName())))
                    .toList();
            if (targets.isEmpty()) {
                String note = restoreReady(connectorId, prevPrefix,
                        "待补写的 " + requested.size() + " 张表在推导前已不在结构快照里，没有需要补写的", false, claimAt);
                restored = true;
                return AddedOutcome.skipped(note);
            }
            int addedTargets = 0;
            for (ConnectorSchema t : targets) {
                if (rewritableFolded.contains(fold(t.getObjectName()))) {
                    addedTargets++;
                }
            }

            Digest digest = buildDigest(targets, fieldsByObject, cfg.getMaxDigestChars(), 0, false);
            List<String> includedNames = new ArrayList<>();
            List<String> updatableNames = new ArrayList<>();
            Set<String> included = new HashSet<>();
            for (int i = 0; i < digest.includedObjects(); i++) {
                String name = targets.get(i).getObjectName();
                includedNames.add(name);
                included.add(fold(name));
                if (rewritableFolded.contains(fold(name))) {
                    updatableNames.add(name);
                }
            }
            Set<String> allTargets = new HashSet<>();
            targets.forEach(t -> allTargets.add(fold(t.getObjectName())));
            AddedContext ctx = buildAddedContext(rows, fieldsByObject, allTargets,
                    Math.max(0, cfg.getMaxDigestChars() - digest.text().length()));

            List<String> notes = new ArrayList<>(digest.notes());
            String content = SemanticPrompts.deriveAddedUser(conn.getName(), conn.getKind(),
                    digest.includedObjects(), targets.size(), digest.notes(), digest.text(),
                    ctx.text(), ctx.complete());
            // 从这一刻起就算「试过」：请求发出去之后，不论模型返回什么、抛什么，这批表都该进冷却。
            sent = List.copyOf(includedNames);
            String raw = sendToModel(connectorId, content, cfg, "语义层增量推导开始",
                    digest.includedObjects(), targets.size(), digest.text().length() + ctx.text().length());
            Map<String, Object> parsed = parseJson(raw, notes);

            AssemblyReport st = new AssemblyReport();
            // 写库之前的已有行：既用来挑掉人答过的口径，也用来算「这批真的多出了哪些行」（决定验证派给谁）。
            List<ConnectorSemantic> existing = existingRows(connectorId);
            List<ConnectorSemantic> fresh = toRows(parsed, fieldsByObject, answeredTerms(existing), st, List.of());
            List<ConnectorSemantic> scoped = new ArrayList<>();
            int outOfScope = 0;
            for (ConnectorSemantic r : fresh) {
                if (inAddedScope(r, included)) {
                    scoped.add(r);
                } else {
                    outOfScope++;
                }
            }

            if (!structure.equals(ConnectorSemanticService.structureStamp(parseFields(schemaService.currentRows(connectorId))))) {
                String note = restoreReady(connectorId, prevPrefix,
                        "补写说明书期间结构又变了，本批作废并用新结构重推", false, claimAt);
                restored = true;
                log.warn("语义层增量推导期间结构变化，本批重新入队 connectorId={}", connectorId);
                return new AddedOutcome(DeriveResult.builder().ok(false).note(note).build(), true, sent);
            }

            // ★ 只有报过 ADDED 的表允许原地重写；一直没覆盖到的表只补缺，理由见方法注释。
            ConnectorSemanticService.UpsertResult w =
                    semanticService.upsertInferred(connectorId, scoped, updatableNames);
            String note = restoreReady(connectorId, prevPrefix,
                    addedSummary(digest.includedObjects(), targets.size(), addedTargets, scoped, w, st,
                            outOfScope, notes),
                    true, claimAt);
            restored = true;

            // ★ 验证只派给这次真的多出了行的表（理由见 tablesWithNewRows）。一行都没多出来就不派：
            //   按「送进过模型的表」派，一张模型写不出东西的表会每个冷却期都在客户库上重跑一遍表形态测量和列取值采集。
            Set<String> toValidate = tablesWithNewRows(scoped, existing, updatableNames, w);
            if (!toValidate.isEmpty()) {
                dispatchValidation(connectorId, tenantOf(conn), note, toValidate);
            }

            log.info("语义层增量推导完成 connectorId={} 待补写 {} 张（其中新增 {}，本次覆盖 {}） 插入 {} 原地更新 {} "
                            + "未动人工 {} 保持原样 {} 撞键 {} 超范围丢弃 {}",
                    connectorId, targets.size(), addedTargets, digest.includedObjects(), w.inserted(), w.updated(),
                    w.skippedNotInferred(), w.keptExisting(), w.conflicts(), outOfScope);
            int[] c = countByScope(scoped);
            return new AddedOutcome(DeriveResult.builder().ok(true)
                    .objectCount(c[0]).fieldCount(c[1]).joinCount(c[2]).caveatCount(c[3])
                    .droppedGuess(st.getDroppedGuess()).droppedUnknown(st.getDroppedUnknown())
                    .droppedTooLong(st.getDroppedTooLong()).skippedAnswered(st.getDroppedAnswered())
                    .truncated(digest.includedObjects() < targets.size())
                    .note(note).build(), false, sent);
        } catch (Exception e) {
            String reason = describe(e);
            log.error("语义层增量推导失败 connectorId={}: {}", connectorId, reason, e);
            // 说明书本身没坏（一行没删），状态回到 READY；失败写在 note 里，synced_at 不盖。
            String note = restoreReady(connectorId, prevPrefix, "补写说明书失败：" + reason, false, claimAt);
            restored = true;
            return new AddedOutcome(DeriveResult.builder().ok(false).note(note).build(), false, sent);
        } finally {
            if (!restored) {
                // 只有 catch 接不住的 Error 会走到这里。说明书一行没删（upsert 是一个事务），回到 READY 是实话。
                restoreReady(connectorId, prevPrefix, "补写说明书被意外中断，状态已恢复", false, claimAt);
            }
        }
    }

    /**
     * 这次补写真的多出了（或原地改写了）行的表，折叠名。验证只派给它们。
     *
     * <h3>为什么不能按「送进过模型的表」派</h3>
     * 模型对一张表一个字都写不出来（只给得出 GUESS）时，这张表永远算没覆盖、每个冷却期都会被再送一次；按送过的表派验证，
     * 就是每天为它在客户库上重跑一遍表形态测量（第 2 档）和列取值采集（第 3 档），永远——而这一轮没有任何一行新东西需要验。
     *
     * <p>「多出了行」按写库之前读到的已有行现算（{@code upsertInferred} 只回计数）：唯一键没见过的行必然是插入；
     * 报过 ADDED 的表允许原地改写，改写过的行可能被打回未验证，所以那几张整张算上。口径问题（CAVEAT）不需要验证。
     * 已有行读失败时不知道哪些是新的——只要这批真的写进了东西，就把这批行涉及的表全算上：宁可多验一次，不让新行没人验。
     */
    private static Set<String> tablesWithNewRows(List<ConnectorSemantic> scoped, List<ConnectorSemantic> existing,
                                                 Collection<String> updatableNames,
                                                 ConnectorSemanticService.UpsertResult w) {
        if (w == null || w.inserted() + w.updated() <= 0) {
            return Set.of();
        }
        Set<String> existingKeys = null;
        if (existing != null) {
            existingKeys = new HashSet<>();
            for (ConnectorSemantic e : existing) {
                existingKeys.add(dedupKey(e));
            }
        }
        Set<String> rewritten = new HashSet<>();
        if (updatableNames != null) {
            for (String n : updatableNames) {
                rewritten.add(fold(n));
            }
        }
        Set<String> out = new LinkedHashSet<>();
        for (ConnectorSemantic r : scoped) {
            String scope = r.getScope();
            if (!ConnectorSemanticService.SCOPE_OBJECT.equals(scope) && !ConnectorSemanticService.SCOPE_FIELD.equals(scope)
                    && !ConnectorSemanticService.SCOPE_JOIN.equals(scope)) {
                continue;
            }
            String obj = fold(r.getObjectName());
            if (existingKeys == null || !existingKeys.contains(dedupKey(r)) || rewritten.contains(obj)) {
                out.add(obj);
            }
        }
        return out;
    }

    /**
     * 增量推导的认领：从 {@link #SEM_READY} 抢，或者接管一次<b>已经超时</b>、且这条连接成功生成过说明书的 RUNNING
     * （条件与 {@link #addedEligible} 同一套，理由见那里）。与 {@link #claim} 仍有一处刻意不同：
     * 写不进去就<b>不跑</b>，不像全量那样「按无并发继续」——增量是锦上添花，没认领就跑可能和一次全量推导交错写。
     *
     * <p>接管的判据写在 CAS 条件里而不是先读后写：两个副本同时看见同一个超时的 RUNNING，先写进去的那个把
     * {@code semantic_claim_at} 换成了现在，后一个的条件自然不再成立。
     */
    private Date claimAdded(Long connectorId, int count) {
        Date now = truncateToSecond(new Date());
        Date stale = staleBefore(now);
        try {
            int n = connectionMapper.update(
                    statusEntity(SEM_RUNNING, "正在为 " + count + " 张表补写说明书……", null, now),
                    new LambdaUpdateWrapper<Connection>()
                            .eq(Connection::getId, connectorId)
                            .and(w -> w.eq(Connection::getSemanticStatus, SEM_READY)
                                    .or(x -> x.eq(Connection::getSemanticStatus, SEM_RUNNING)
                                            .isNotNull(Connection::getSemanticSyncedAt)
                                            .and(y -> y.isNull(Connection::getSemanticClaimAt)
                                                    .or().lt(Connection::getSemanticClaimAt, stale)))));
            return n > 0 ? now : null;
        } catch (Exception e) {
            log.warn("新增表增量推导认领失败，本次不跑 connectorId={}: {}", connectorId, describe(e));
            return null;
        }
    }

    /** 回到 READY：本次结论排在前、上一版结论接在后（note 被截断时丢的是最旧的那一截）。 */
    private String restoreReady(Long connectorId, String prevPrefix, String summary, boolean success, Date claimAt) {
        String note = clip(blank(prevPrefix) ? summary : summary + "。此前：" + prevPrefix, NOTE_MAX);
        writeStatus(connectorId, SEM_READY, note, success, claimAt);
        return note;
    }

    /**
     * 这条行在不在本次增量的范围里。范围之外的一律丢：模型被告知不要写，写了也不能进库——
     * 为老表写的 OBJECT / FIELD 在 upsert 里会被「保持原样」挡住，但插入缺失的那部分会让一张
     * 没被重新看过完整结构的老表凭空多出说明。
     */
    private boolean inAddedScope(ConnectorSemantic r, Set<String> included) {
        String scope = r.getScope();
        if (ConnectorSemanticService.SCOPE_OBJECT.equals(scope) || ConnectorSemanticService.SCOPE_FIELD.equals(scope)) {
            return included.contains(fold(r.getObjectName()));
        }
        if (ConnectorSemanticService.SCOPE_JOIN.equals(scope)) {
            String to = str(readDetail(r.getDetailJson()), "to_object");
            return included.contains(fold(r.getObjectName())) || (to != null && included.contains(fold(to)));
        }
        if (ConnectorSemanticService.SCOPE_CAVEAT.equals(scope)) {
            for (String a : strList(readDetail(r.getDetailJson()), "applies_to")) {
                if (included.contains(fold(a))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 已有表的精简清单（表名、注释、列名与类型），给模型写「新表 → 老表」的关系用。
     * 超出预算就停，不挑小的往里塞——「列到哪儿为止」要是一句说得清的话。
     */
    private AddedContext buildAddedContext(List<ConnectorSchema> rows, Map<String, Map<String, FieldDetail>> fieldsByObject,
                                           Set<String> exclude, int budget) {
        StringBuilder b = new StringBuilder();
        boolean complete = true;
        for (ConnectorSchema r : rows) {
            if (exclude.contains(fold(r.getObjectName()))) {
                continue;
            }
            StringBuilder line = new StringBuilder("- ").append(r.getObjectName());
            if (!blank(r.getObjectComment())) {
                line.append("（").append(nz(r.getObjectComment())).append("）");
            }
            line.append("：");
            Map<String, FieldDetail> cols = fieldsByObject.get(r.getObjectName());
            if (cols == null || cols.isEmpty()) {
                line.append("(字段未取到)");
            } else {
                List<String> parts = new ArrayList<>();
                for (FieldDetail f : cols.values()) {
                    parts.add(f.name() + " " + nz(f.type()));
                }
                line.append(String.join(", ", parts));
            }
            line.append('\n');
            if (b.length() + line.length() > budget) {
                complete = false;
                break;
            }
            b.append(line);
        }
        return new AddedContext(b.toString(), complete);
    }

    /**
     * 管理台上那一行字。<b>「新增」只说真的新增了的表</b>：一次例行刷新之后写「新增 30 张表已补写说明书」，
     * 看的人会去找那 30 张根本不存在的新表。
     *
     * @param added 其中报过 ADDED 的表数；其余是一直在库里、此前没有说明书的
     */
    private static String addedSummary(int included, int total, int added, List<ConnectorSemantic> scoped,
                                       ConnectorSemanticService.UpsertResult w, AssemblyReport st, int outOfScope,
                                       List<String> notes) {
        int[] c = countByScope(scoped);
        int uncovered = Math.max(0, total - added);
        StringBuilder b = new StringBuilder();
        if (uncovered == 0) {
            b.append("新增 ").append(total).append(" 张表");
        } else if (added <= 0) {
            b.append("此前没有说明书的 ").append(total).append(" 张表");
        } else {
            b.append("新增 ").append(added).append(" 张、此前没有说明书的 ").append(uncovered).append(" 张表");
        }
        if (included < total) {
            b.append("（本次覆盖 ").append(included).append(" 张，其余超出摘要上限，接着补）");
        }
        b.append("已补写说明书：表用途 ").append(c[0]).append("、字段含义 ").append(c[1])
                .append("、关系 ").append(c[2]).append("（均未经数据验证）、待确认口径 ").append(c[3])
                .append("；插入 ").append(w.inserted()).append(" 行、原地更新 ").append(w.updated()).append(" 行");
        if (w.skippedNotInferred() > 0) {
            b.append("、人工确认的 ").append(w.skippedNotInferred()).append(" 条未改动");
        }
        if (w.keptExisting() > 0) {
            b.append("、已有表上的 ").append(w.keptExisting()).append(" 条保持原样");
        }
        if (w.conflicts() > 0) {
            b.append("、撞键跳过 ").append(w.conflicts()).append(" 条");
        }
        if (st.getDroppedGuess() > 0 || st.getDroppedUnknown() > 0 || outOfScope > 0) {
            b.append("；已丢弃：无外部依据 ").append(st.getDroppedGuess()).append("、名字对不上结构 ")
                    .append(st.getDroppedUnknown()).append("、超出本次范围 ").append(outOfScope);
        }
        for (String n : notes) {
            b.append("；").append(n);
        }
        return b.toString();
    }

    /** [表用途, 字段, 关系, 其余] */
    private static int[] countByScope(List<ConnectorSemantic> rows) {
        int[] c = new int[4];
        for (ConnectorSemantic r : rows) {
            switch (r.getScope()) {
                case ConnectorSemanticService.SCOPE_OBJECT -> c[0]++;
                case ConnectorSemanticService.SCOPE_FIELD -> c[1]++;
                case ConnectorSemanticService.SCOPE_JOIN -> c[2]++;
                default -> c[3]++;
            }
        }
        return c;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> u = new LinkedHashSet<>(a);
        u.addAll(b);
        return u;
    }

    /**
     * 增量推导一批的结局。
     *
     * @param snapshotMoved 结构在推导期间变了，这批要用新快照重推
     * @param sent          真的把结构送进了模型请求的表（快照写法）。<b>空 = 一张都没送</b>（没 READY、认领不到……），
     *                      drain 据此判断「这一圈有没有进展」，见 {@link #drainAdded}
     */
    record AddedOutcome(DeriveResult result, boolean snapshotMoved, List<String> sent, boolean readFailed) {
        AddedOutcome(DeriveResult result, boolean snapshotMoved, List<String> sent) {
            this(result, snapshotMoved, sent, false);
        }

        AddedOutcome(DeriveResult result, boolean snapshotMoved) {
            this(result, snapshotMoved, List.of(), false);
        }

        static AddedOutcome skipped(String note) {
            return new AddedOutcome(DeriveResult.builder().ok(false).note(note).build(), false);
        }

        /** 认领之前读库失败：一张没送、一行没写、什么都没认领。drain 据此重试或暂存提示名单，见 {@link #drainAdded}。 */
        static AddedOutcome failedToRead(String note) {
            return new AddedOutcome(DeriveResult.builder().ok(false).note(note).build(), false, List.of(), true);
        }
    }

    /**
     * 一圈的计划，见 {@link #planAddedBatch}。纯内部，可以是 record。
     *
     * @param names         这一圈送进模型的表（快照写法，顺序即摘要顺序）
     * @param rewritable    其中报过 ADDED 的——只有它们允许原地重写已有的推断行
     * @param contentHashes 折叠表名 → 算计划时的结构指纹，记冷却用
     * @param overflowHints 这一批装不下的 ADDED 名字，下一圈要带回去（它们的「允许重写」不能丢）
     * @param more          这一批装不下，还有下一圈
     * @param readFailed    连接或快照读不出来，这一圈什么都没算。<b>和「没有要补的表」是两回事</b>：前者提示名单不能丢
     */
    record AddedBatch(List<String> names, Set<String> rewritable, Map<String, String> contentHashes,
                      Set<String> overflowHints, boolean more, boolean readFailed) {
        static final AddedBatch EMPTY = new AddedBatch(List.of(), Set.of(), Map.of(), Set.of(), false, false);
        static final AddedBatch READ_FAILED = new AddedBatch(List.of(), Set.of(), Map.of(), Set.of(), false, true);
    }

    private record AddedContext(String text, boolean complete) {
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

    // ================================================================ 模型调用

    /**
     * @param gaps 这份材料缺了什么，<b>原样告诉模型</b>。少给这一段的后果是它把残卷读成全本：
     *             为看不见的表写关系、或者断言某个概念在这个库里不存在。
     *             提示词里那一句「没有出现在下面的表不要为它写任何条目」由 includedObjects &lt;
     *             totalObjects 触发，所以那两个数必须把快照自己的 200 截断也算进去。
     */
    private String callModel(Long connectorId, Connection conn, Digest d, ConnectorProperties.Semantic cfg,
                             List<String> gaps) {
        String content = SemanticPrompts.deriveUser(conn.getName(), conn.getKind(),
                d.includedObjects(), d.totalObjects(), gaps, d.text());
        return sendToModel(connectorId, content, cfg, "语义层推导开始",
                d.includedObjects(), d.totalObjects(), d.text().length());
    }

    /**
     * 全量推导与增量推导共用的那一次模型调用。系统提示词是同一份——四条硬约束对新表一样成立。
     *
     * @param what 日志开头那几个字，区分全量与增量
     */
    private String sendToModel(Long connectorId, String userContent, ConnectorProperties.Semantic cfg,
                               String what, int included, int total, int digestChars) {
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
        userMsg.put("content", userContent);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(userMsg);
        body.put("messages", messages);

        Duration timeout = modelTimeout(cfg);
        log.info("{} connectorId={} 请求模型={} 对象={}/{} 摘要={}字符 max_tokens={} 超时={}秒",
                what, connectorId,
                model == null ? "(未配 connector.semantic.infer-model，回落 provider 默认模型)" : model,
                included, total, digestChars, cfg.getMaxTokens(), timeout.getSeconds());

        // ★ 走 messagesInternal 而不是 messages：不带任何工具（客户的表名列名不能被模型拿去 web_search）、
        //   只调一轮、不套 Agent 上下文，超时按上面这个给。理由见 AiConversationLoop#runInternal。
        Object resp = claudeService.messagesInternal(body, timeout);
        return extractText(resp);
    }

    /**
     * 取推导模型调用的超时，夹紧到 [{@link #MODEL_TIMEOUT_MIN_SECONDS}, {@link #MODEL_TIMEOUT_MAX_SECONDS}]。
     *
     * <p>越界不报错：这是护栏参数，配错不该让推导整个停摆（见 {@link ConnectorProperties} 的类注释）。
     * 但必须打 WARN——否则「配了 3600 秒却 25 分钟就超时」是查不出原因的。
     */
    static Duration modelTimeout(ConnectorProperties.Semantic cfg) {
        int configured = cfg.getModelTimeoutSeconds();
        int clamped = Math.max(MODEL_TIMEOUT_MIN_SECONDS, Math.min(MODEL_TIMEOUT_MAX_SECONDS, configured));
        if (clamped != configured) {
            log.warn("connector.semantic.model-timeout-seconds={} 越界，按 {} 秒处理（合法区间 [{}, {}] 秒；"
                            + "上限必须小于推导认领过期的 {} 分钟，否则模型还没回来认领就会被别人抢走）",
                    configured, clamped, MODEL_TIMEOUT_MIN_SECONDS, MODEL_TIMEOUT_MAX_SECONDS, CLAIM_STALE_MINUTES);
        }
        return Duration.ofSeconds(clamped);
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
                                      List<ConnectorSemantic> existing, AssemblyReport st) {
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
        return "本次模型一条语义都没能留下（无外部依据 " + st.getDroppedGuess() + "、名字对不上结构 "
                + st.getDroppedUnknown() + "、超长 " + st.getDroppedTooLong() + "）"
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
    private String summarize(AssemblyReport st, Digest d, List<String> notes) {
        StringBuilder b = new StringBuilder();
        // 总数是估出来的下界时写成 ">200"，不写成一个假的确数：这一行是给人读的，
        // 「覆盖 200/200」会被读成全覆盖，而事实是「至少还有一批表根本没进过快照」。
        String total = d.totalIsLowerBound() ? ">" + (d.totalObjects() - 1) : String.valueOf(d.totalObjects());
        b.append("覆盖 ").append(d.includedObjects()).append('/').append(total).append(" 个对象：")
                .append("表用途 ").append(st.getObjects())
                .append("、字段含义 ").append(st.getFields())
                .append("、关系 ").append(st.getJoins()).append("（均未经数据验证）")
                .append("、待确认口径 ").append(st.getCaveats()).append(" 条。");
        if (st.getDroppedGuess() > 0 || st.getDroppedUnknown() > 0 || st.getDroppedDup() > 0 || st.getDroppedTooLong() > 0) {
            b.append("已丢弃：无外部依据 ").append(st.getDroppedGuess())
                    .append("、名字对不上结构 ").append(st.getDroppedUnknown())
                    .append("、重复 ").append(st.getDroppedDup())
                    .append("、超长 ").append(st.getDroppedTooLong()).append(" 条。");
        }
        if (st.getDroppedAnswered() > 0) {
            b.append("另有 ").append(st.getDroppedAnswered()).append(" 条口径人已经答过，不再重复提问。");
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
     * 不过本方法派发的这一个任务里，S3 → 表形态测量（{@code shape-statements-per-minute}，默认 <b>20/min</b>）
     * → S4 三段是<b>先后</b>跑的（见 {@link #validate}），峰值只有 30/min。表形态测量是第三条平台侧剖析路径，
     * 它<b>没有</b>让账越过 60，靠的正是「先后跑」这一条——哪天把这三段改成并发，先把加法重算一遍。
     * 余量留给客户随时可能在管理台点的「刷新结构」
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
        dispatchValidation(connectorId, tenantId, deriveNote, null);
    }

    /**
     * @param scope 只验这些表（折叠过大小写的表名）；{@code null} = 全部。
     *              增量推导传它：为 3 张新表把 200 张老表的列取值再全量采一遍，花的是客户的库。
     */
    private void dispatchValidation(Long connectorId, String tenantId, String deriveNote, Set<String> scope) {
        if (!validateStageEnabled || connectorId == null) {
            return;
        }
        if (blank(tenantId)) {
            log.warn("语义层采样验证未派发：拿不到租户 connectorId={}", connectorId);
            return;
        }
        final Set<String> s = scope == null ? null : Set.copyOf(scope);
        try {
            semanticStageExecutor.execute(MdcAsyncSupport.wrap("semantic-validate-" + connectorId,
                    () -> runValidation(connectorId, tenantId, s)));
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
        runValidation(connectorId, tenantId, null);
    }

    void runValidation(Long connectorId, String tenantId, Set<String> scope) {
        if (!TenantContext.isSet() && tenantId != null) {
            TenantContext.set(tenantId);
        }
        validate(connectorId, scope);
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
        return validate(connectorId, null);
    }

    /**
     * @param scope 只验这些表（折叠名）；{@code null} = 全部。范围只收窄「挑候选」与「采哪些表」，
     *              落库纪律一条不变。
     */
    public ValidationResult validate(Long connectorId, Set<String> scope) {
        if (connectorId == null) {
            return ValidationResult.builder().ok(false).note("缺少连接 id").build();
        }
        if (!validateStageEnabled) {
            String note = "采样验证阶段已关闭（connector.semantic.validate-stage-enabled=false）";
            log.info("跳过语义层采样验证，开关已关 connectorId={}", connectorId);
            return ValidationResult.builder().ok(false).note(note).build();
        }
        if (!validating.add(connectorId)) {
            // ★ 不是简单跳过：正在跑的那一轮开跑时就读完了语义行，看不见之后新写的 V_NONE 行。
            //   记下来，由它收尾时补一轮，见 validateAgain。
            validateAgain.merge(connectorId, scope == null ? SCOPE_ALL : Set.copyOf(scope),
                    ConnectorSemanticDeriveService::mergeScope);
            String note = "同一条连接上已有一轮采样验证在进行中，本次并入它结束后的补跑";
            log.info("语义层采样验证推迟：本进程内已有一轮在跑，结束后补跑 connectorId={}", connectorId);
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
            // ★ 唯一键从【同一份】快照行里解，不查任何库。不传它，验证器认不出组合键、关系一律按 SIMPLE 处理——
            //   组合键判定写好了、单测也绿着，在线上却等于没上线。
            Map<String, List<List<String>>> uniqueKeys = SemanticJoinValidator.uniqueKeysByObject(rows);

            List<ConnectorSemantic> all = existingRows(connectorId);
            if (all == null) {
                String note = "读取语义层失败，本轮采样验证没有开始";
                writeStageNote(connectorId, prefix, note);
                return ValidationResult.builder().ok(false).note(note).build();
            }
            // ★ K-3：带着真实取值的补写 gloss 先换掉，再跑任何一段。理由见 neutraliseValueGlosses。
            neutraliseValueGlosses(all);

            List<ConnectorSchema> scopedRows = scope == null ? rows
                    : rows.stream().filter(r -> scope.contains(fold(r.getObjectName()))).toList();

            // 三段先后跑，速率账见 dispatchValidation。表形态排在 S4 前：它是第 2 档、语句少，
            // 而 S4 是第 3 档、默认关着——别让一段通常不跑的阶段排在一段通常要跑的阶段前面。
            JoinStage js = runJoinStage(connectorId, all, fieldsByObject, uniqueKeys, prefix, scope);
            ShapeStage ss = runShapeStage(connectorId, conn, scopedRows, all);
            ValueStage vs = scopedRows.isEmpty()
                    ? new ValueStage(0, 0, 0, 0, "本批表已不在结构快照里，未采集列取值")
                    : runValueStage(connectorId, conn, scopedRows, all, fieldsByObject);

            String note = js.note() + "；" + ss.note() + "；" + vs.note();
            writeStageNote(connectorId, prefix, note);
            log.info("语义层采样验证完成 connectorId={} 用时 {}ms 范围={}；关系：候选 {} 成立 {} 部分成立 {} "
                            + "不成立 {} 判不出 {} 未探查 {} 已落库 {} 探查次数 {} 结构补标 {}；"
                            + "表形态：测量 {} 键值对表 {} 推翻模型 {} 语句 {}；"
                            + "值域：剖析 {} 列 枚举成功 {} 补写新行 {} 语句 {}",
                    connectorId, System.currentTimeMillis() - start, scope == null ? "全部" : scope.size() + " 张表",
                    js.candidates(), js.confirmed(), js.weak(), js.rejected(), js.undecidable(),
                    js.notProbed(), js.written(), js.probeCount(), js.structureMarked(),
                    ss.measured(), ss.keyValue(), ss.overrides(), ss.statements(),
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
                    .shapeMeasured(ss.measured())
                    .shapeKeyValue(ss.keyValue())
                    .shapeOverrides(ss.overrides())
                    .shapeStatements(ss.statements())
                    .note(clip(note, NOTE_MAX))
                    .build();
        } catch (Exception e) {
            String reason = describe(e);
            log.error("语义层采样验证阶段失败 connectorId={}: {}", connectorId, reason, e);
            writeStageNote(connectorId, null, "采样验证失败：" + reason);
            return ValidationResult.builder().ok(false).note("采样验证失败：" + reason).build();
        } finally {
            // 顺序要紧：先放闸再取补跑名单。反过来的话，两步之间进来的那次派发既没被记下、又被闸挡回。
            validating.remove(connectorId);
            Set<String> again = validateAgain.remove(connectorId);
            if (again != null) {
                dispatchValidation(connectorId, TenantContext.get(), null, again == SCOPE_ALL ? null : again);
            }
        }
    }

    private static Set<String> mergeScope(Set<String> a, Set<String> b) {
        if (a == SCOPE_ALL || b == SCOPE_ALL) {
            return SCOPE_ALL;
        }
        Set<String> u = new HashSet<>(a);
        u.addAll(b);
        return Collections.unmodifiableSet(u);
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
     *   <li><b>结构形态（多态外键 / 组合键）不等探查。</b>它只读快照与唯一键，第 1 档就能下：没探查的结论只并结构键
     *       （{@link #persistVerdict}），没有 {@code join_kind} 的关系——不论决没决过、在不在本轮范围里——用 {@code structureOnly}
     *       补标（{@link #examineStructure}）。少了这一条，第 1 档连接和线上存量的关系会一直以普通关系的身份进 joins。</li>
     * </ol>
     *
     * <p>已决过的行只有一种会重新成为候选：档位调上去之后、还没有判别值的多态外键，见 {@link #wantsDiscriminatorReprobe}。
     *
     * <p>回调返回 {@code false} 会让验证器立刻停手，这里用线程中断作判据：应用关停时池子会中断
     * 这些线程，那之后继续打客户的库没有意义。已经决出来的结论一条不丢——它们在决出的那一刻
     * 就已经落库了，这正是那个回调存在的理由。
     */
    private JoinStage runJoinStage(Long connectorId, List<ConnectorSemantic> all,
                                   Map<String, Map<String, FieldDetail>> fieldsByObject,
                                   Map<String, List<List<String>>> uniqueKeys, String prefix,
                                   Set<String> scope) {
        Map<String, ConnectorSemantic> pending = new LinkedHashMap<>();
        List<SemanticJoinValidator.JoinCandidate> candidates = new ArrayList<>();
        JoinCounters c = new JoinCounters();
        int alreadyDecided = 0;
        int human = 0;
        int unusable = 0;
        int reprobe = 0;
        for (ConnectorSemantic r : all) {
            if (!ConnectorSemanticService.SCOPE_JOIN.equals(r.getScope())) {
                continue;
            }
            if (ConnectorSemanticService.SOURCE_HUMAN.equals(r.getSource())) {
                human++;
                continue;
            }
            String[] to = joinTarget(r);
            boolean usable = to != null && !blank(r.getObjectName()) && !blank(r.getFieldName());
            Map<String, Object> detail = readDetail(r.getDetailJson());
            // ★ 结构补标先于一切过滤：不看验证范围、不看决没决过。它一分客户配额都不花；而线上存量关系多半只等得到范围很窄的
            //   自动验证——从前范围之外的 V_NONE 行在任何结构写入之前就被跳过，第 1 档连接上的存量关系于是永远没有结构。
            boolean resetNow = usable && tally(examineStructure(r, detail, to, fieldsByObject, uniqueKeys), c);
            boolean reprobeRow = false;
            // null 按「没决过」处理：把未知当成已决 = 永远不验它，而那恰好是最该验的一类。
            if (r.getVerified() != null && !ConnectorSemanticService.V_NONE.equals(r.getVerified())) {
                if (!usable || !wantsDiscriminatorReprobe(connectorId, detail, c)) {
                    alreadyDecided++;
                    continue;
                }
                reprobeRow = true;
            }
            // 刚被打回未验证的不看范围：打回就是为了让验证按结构重判，这一轮不验，范围窄的自动验证可能永远轮不到它。
            // 每条关系只会被打回一次（打回的同时结构已经写下），客户侧成本有界。
            if (!resetNow && scope != null && !scope.contains(fold(r.getObjectName()))
                    && (to == null || !scope.contains(fold(to[0])))) {
                // 本轮只验这批表（增量推导派来的），两端都不在范围里的关系留给全量那一轮。
                if (reprobeRow) {
                    alreadyDecided++;
                }
                continue;
            }
            if (!usable) {
                unusable++;
                continue;
            }
            String key = joinKey(r.getObjectName(), r.getFieldName(), to[0], to[1]);
            if (pending.putIfAbsent(key, r) != null) {
                continue;
            }
            if (reprobeRow) {
                reprobe++;
            }
            candidates.add(SemanticJoinValidator.JoinCandidate.of(
                    r.getObjectName(), r.getFieldName(), to[0], to[1]));
        }

        if (candidates.isEmpty()) {
            String note = (alreadyDecided > 0
                    ? "表关系此前已全部验证过（" + alreadyDecided + " 条），本轮没有新候选"
                    : "没有待验证的表关系") + structureNote(c);
            return new JoinStage(SemanticJoinValidator.OUT_NOTHING_TO_DO, 0, 0, 0, 0, 0, 0, 0, 0,
                    c.structureMarked, note);
        }

        SemanticJoinValidator.JoinValidationResult res;
        try {
            // ★ 五参版本：唯一键不传，组合键判定在线上就不生效。
            res = joinValidator.validate(connectorId, candidates, fieldsByObject, uniqueKeys, (v, decided, total) -> {
                persistVerdict(connectorId, pending, v, c, uniqueKeys);
                maybeProgress(connectorId, prefix, decided, total, c);
                return !Thread.currentThread().isInterrupted();
            });
        } catch (RuntimeException e) {
            // 契约上它不抛，这里兜底。
            String reason = describe(e);
            log.warn("表关系采样验证抛了异常（契约上不该） connectorId={}: {}", connectorId, reason);
            return new JoinStage(SemanticJoinValidator.OUT_ABORTED, candidates.size(),
                    c.confirmed, c.weak, c.rejected, c.undecidable, c.notProbed, c.written, 0, c.structureMarked,
                    "表关系采样验证失败：" + reason + structureNote(c));
        }
        if (res == null) {
            return new JoinStage(SemanticJoinValidator.OUT_ABORTED, candidates.size(),
                    c.confirmed, c.weak, c.rejected, c.undecidable, c.notProbed, c.written, 0, c.structureMarked,
                    "表关系采样验证没有返回结果" + structureNote(c));
        }

        // ★ 没探查的结论只落结构形态，而且要扫返回的全集：TIER_BLOCKED / DISABLED 整批一次都不回调，
        //   而第 1 档连接恰恰是最需要这句结构警告的客户。理由见 persistUnprobedStructure。
        persistUnprobedStructure(pending, res.getVerdicts(), c, uniqueKeys);

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
        if (reprobe > 0) {
            note.append("；其中 ").append(reprobe)
                    .append(" 条多态外键此前是在读不到取值的档位下验的，现在档位允许读取值，本轮重新探查判别值");
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
        note.append(structureNote(c));
        return new JoinStage(res.getOutcome(), candidates.size(), c.confirmed, c.weak, c.rejected,
                c.undecidable, c.notProbed, c.written, res.getProbeCount(), c.structureMarked, note.toString());
    }

    /**
     * 落一条结论。
     *
     * <h3>★ {@code V_NONE} 的那些<b>只并结构形态，别的一个字都不写</b></h3>
     * {@code V_NONE} 的意思是「<b>没探查</b>」（档位不允许、预算用完、被中止、标识符对不上快照），
     * 而不是「探查了判不出来」——后者是 {@code V_UNDECIDABLE}。对没探查过的那条，
     * {@code JoinVerdict.detailPatch()} 会把 {@code basis} 覆盖成一句泛泛的「未经数据验证」，
     * 那会<b>抹掉 S1 写进去的「来自视图 v_x 的定义」</b>：这一轮什么都没做，
     * 却顺手删掉了这条关系唯一的来源线索。所以 {@code detailPatch()} 不能用。
     *
     * <p>但结构形态（多态外键 / 组合键）是第 1 档就能下的结论，与探没探查无关。连它一起跳过，第 1 档连接——
     * 恰恰是最需要这句警告的客户，它们永远不会有探查结论——以及预算用尽、被中止的那些关系，会以普通关系的身份
     * 继续进 joins。所以只并 {@code structuralPatch()}：{@code verified} / {@code basis} / {@code gloss} 不动，
     * 下一轮（比如档位被调上去之后）它仍然是 {@code NONE}，仍然会被挑成候选。
     */
    private void persistVerdict(Long connectorId, Map<String, ConnectorSemantic> pending,
                                SemanticJoinValidator.JoinVerdict v, JoinCounters c,
                                Map<String, List<List<String>>> uniqueKeys) {
        if (v == null) {
            return;
        }
        // 分类计数不在这里累加，统一由 recount 按返回的全集算一次，理由见那个方法。
        ConnectorSemantic row = pending.get(
                joinKey(v.getFromObject(), v.getFromColumn(), v.getToObject(), v.getToColumn()));
        String verified = v.getVerified();
        if (blank(verified) || ConnectorSemanticService.V_NONE.equals(verified)) {
            persistStructure(row, v, c, uniqueKeys);
            return;
        }
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
            Map<String, Object> patch = new LinkedHashMap<>(v.detailPatch());
            // 结构键单独处理：整组替换（只 putAll 会让一条已经不是多态外键的关系留着旧的判别列和那句警告），
            // 并且目标表唯一键不知道时判成普通关系的不写——理由见 examineStructure。
            STRUCTURE_KEYS.forEach(patch::remove);
            detail.putAll(patch);
            Map<String, Object> structure = knownStructure(v, uniqueKeys);
            if (!structure.isEmpty()) {
                // 与 mergeStructure 不同，判别值这里要写：它正是这一次第 3 档探查、过了 PII 的结论。
                STRUCTURE_KEYS.forEach(detail::remove);
                detail.putAll(structure);
            }
            if (v.isProbed() && v.getCardinality() == null) {
                // ★ detailPatch 在基数判不出来时【不放】这个键，于是上一轮模型自己标的基数会原样留着。
                //   一条写着「已采样验证」、却带着一个模型猜出来的 N:1 的关系，比没验证过更危险：
                //   自动 join 正是按基数决定的，而一次 fan-out 会让 SUM 出来的金额凭空变大且不报错。
                detail.remove("cardinality");
            }
            if (SemanticJoinValidator.KIND_POLYMORPHIC.equals(v.getJoinKind())) {
                // ★ K-2：记的是第 3 档的分组探查【真的跑了没有】，不是「这条连接当时的档位允不允许」。
                //   档位允许、却因为运维急停开着或判别列名没过 PII 而一条分组探查都没发的结论，按档位记成 true，
                //   就再也不会回头补判别值：急停关掉、PII 规则调整之后，这条多态外键仍然永远缺着那个条件，没有任何信号。
                detail.put(KEY_PROBED_WITH_SAMPLE_VALUES, v.isGroupedProbeRan());
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

    /** JOIN 行 detail_json 里的结构键（共享契约）。整组替换的理由见 {@link #mergeStructure}。 */
    private static final List<String> STRUCTURE_KEYS = List.of(
            "join_kind", "discriminator_column", "discriminator_value", "composite_columns", "care_reason");

    private static final String KEY_JOIN_KIND = "join_kind";

    private static final String KEY_DISCRIMINATOR_VALUE = "discriminator_value";

    /**
     * 多态外键决出结论的那一轮，第 3 档的分组判别探查<b>真的跑了没有</b>（布尔，K-2：取自 {@code JoinVerdict.isGroupedProbeRan()}）。
     *
     * <p><b>追加键，不在共享契约里</b>——与 {@code ConnectorSemanticService.KEY_STALE_REMOVED} 同一个做法。
     * 它是「档位调上去之后只重探一次」能停下来的那个记号，理由见 {@link #wantsDiscriminatorReprobe}。
     */
    static final String KEY_PROBED_WITH_SAMPLE_VALUES = "probed_with_sample_values";

    private static final String KEY_DISCRIMINATOR_COLUMN = "discriminator_column";

    private static final String KEY_COMPOSITE_COLUMNS = "composite_columns";

    /**
     * 一条 JOIN 行上<b>采样结论</b>的那组键（不含结构键），外加本类记下的 {@link #KEY_PROBED_WITH_SAMPLE_VALUES}。
     * 结构形态翻成多态外键 / 组合键、与之矛盾的结论整组作废时用，见 {@link #resetContradictedVerdict}。
     * 与 {@code ConnectorSemanticService.JOIN_VERDICT_KEYS} 去掉结构键之后是同一组。
     */
    private static final List<String> VERDICT_KEYS = List.of("sample_n", "match_n", "containment", "cardinality",
            "auto_joinable", "basis", "verify_note", KEY_PROBED_WITH_SAMPLE_VALUES);

    /**
     * 没探查的结论（{@code V_NONE}）只落结构形态。
     *
     * <p>★ 必须扫返回的全集，不能只靠回调：验证器对 TIER_BLOCKED / DISABLED 整批一次都不回调，
     * 半路中止之后剩下的那些也不回调。回调里已经写过的，内存里的行已经带上了结构键（见 {@link #writeStructure}），
     * 这里比对得出「没变」，不会重复写。
     */
    private void persistUnprobedStructure(Map<String, ConnectorSemantic> pending,
                                          List<SemanticJoinValidator.JoinVerdict> verdicts, JoinCounters c,
                                          Map<String, List<List<String>>> uniqueKeys) {
        if (verdicts == null) {
            return;
        }
        for (SemanticJoinValidator.JoinVerdict v : verdicts) {
            if (v != null && (blank(v.getVerified()) || ConnectorSemanticService.V_NONE.equals(v.getVerified()))) {
                persistStructure(pending.get(joinKey(v.getFromObject(), v.getFromColumn(),
                        v.getToObject(), v.getToColumn())), v, c, uniqueKeys);
            }
        }
    }

    private void persistStructure(ConnectorSemantic row, SemanticJoinValidator.JoinVerdict v, JoinCounters c,
                                  Map<String, List<List<String>>> uniqueKeys) {
        if (row == null || ConnectorSemanticService.SOURCE_HUMAN.equals(row.getSource())) {
            return;
        }
        if (writeStructure(row, readDetail(row.getDetailJson()), knownStructure(v, uniqueKeys))) {
            c.structureMarked++;
        }
    }

    /**
     * 验证器结论里的结构键，按 {@link #examineStructure} 的同一条规矩收一道：目标表唯一键不知道时，判成普通关系的<b>不写</b>。
     * 返回空 Map = 没判 / 不该写，{@link #mergeStructure} 据此不覆盖行上已有的结构键。
     */
    private static Map<String, Object> knownStructure(SemanticJoinValidator.JoinVerdict v,
                                                      Map<String, List<List<String>>> uniqueKeys) {
        Map<String, Object> s = v.structuralPatch();
        String kind = upper(str(s, KEY_JOIN_KIND));
        if (kind == null || (SemanticJoinValidator.KIND_SIMPLE.equals(kind) && !keysKnown(uniqueKeys, v.getToObject()))) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(s);
    }

    /** 快照里有没有这张表的唯一键信息。不在 = 不知道（旧快照、那次读索引失败），与「确实没有唯一键」（空列表）是两回事。 */
    private static boolean keysKnown(Map<String, List<List<String>>> uniqueKeys, String table) {
        return uniqueKeys != null && table != null && uniqueKeys.containsKey(table);
    }

    /** 一条关系这一次结构补标的结局，见 {@link #examineStructure}。 */
    private enum StructureMark {
        /** 不需要补，或判不了（标识符对不上快照）：什么都没写。 */
        NONE,
        /** 结构键写下了，采样结论不动。 */
        MARKED,
        /** 结构键写下了，而且已决的采样结论与新结构矛盾，整条打回未验证。 */
        RESET,
        /** 目标表唯一键还不知道、判出来是普通关系：不写，留给唯一键到了之后的那一趟。 */
        PENDING_KEYS
    }

    /** 把一次结构补标的结局记进计数器；返回这条是不是刚被打回未验证。 */
    private static boolean tally(StructureMark mark, JoinCounters c) {
        switch (mark) {
            case MARKED -> c.structureMarked++;
            case RESET -> {
                c.structureMarked++;
                c.structureReset++;
            }
            case PENDING_KEYS -> c.structurePending++;
            default -> {
            }
        }
        return mark == StructureMark.RESET;
    }

    /**
     * 刷新结构这条心跳上补标关系的结构形态（多态外键 / 组合键）。<b>零次客户库访问、不叫模型</b>：只读快照与我们自己的语义行。
     *
     * <h3>为什么非挂在这里不可</h3>
     * 结构补标原本只在验证阶段里跑，而验证只有三个入口：全量推导之后、增量补写真的写出了行之后、有人手动点。
     * 一条早就覆盖完、不再出新表的线上连接，这三件事一件都不会自己发生——它身上结构判定上线之前验过的关系就永远以
     * 普通关系的身份留在 joins 里：组合键上一条带着 {@code auto_joinable=true} 的关系会一直把行数放大，还压着注入层的 fan-out 警告。
     * 刷新结构是唯一一定会发生的事（定时 + 手动），而旧快照也正是在一次刷新之后才带上唯一键——就在这之后补。
     *
     * <h3>和验证阶段不能交错写</h3>
     * 占用同一道 {@link #validating} 闸：两边都会整份改写 JOIN 行的 detail_json，交错时后写的一方会拿读到的旧 detail
     * 覆盖掉对方刚落的采样结论。闸在别人手里就不补——正在跑的那一轮开头已经把全部关系扫过一遍。
     * 这一趟是毫秒级的；其间进来的验证派发照 {@link #validate} 的规矩记进 {@link #validateAgain}，放闸时补派。
     *
     * <p>有关系被打回未验证（结构翻成多态外键 / 组合键，见 {@link #examineStructure}）时，派一轮只限那几张表的验证：
     * 打回就是为了按结构重判，没人派，它们会一直停在「未验证」。每条关系只会被打回一次，客户侧成本有界。
     */
    private void healStructureOnRefresh(Long connectorId, String tenantId) {
        if (!validating.add(connectorId)) {
            return;
        }
        Set<String> resetTables = new LinkedHashSet<>();
        try {
            List<ConnectorSemantic> rows = semanticMapper.selectList(new LambdaQueryWrapper<ConnectorSemantic>()
                    .eq(ConnectorSemantic::getConnectorId, connectorId)
                    .eq(ConnectorSemantic::getScope, ConnectorSemanticService.SCOPE_JOIN));
            List<ConnectorSemantic> joins = new ArrayList<>();
            if (rows != null) {
                for (ConnectorSemantic r : rows) {
                    if (r != null && ConnectorSemanticService.SCOPE_JOIN.equals(r.getScope())
                            && !ConnectorSemanticService.SOURCE_HUMAN.equals(r.getSource())
                            && mayNeedStructure(readDetail(r.getDetailJson()))) {
                        joins.add(r);
                    }
                }
            }
            if (joins.isEmpty()) {
                // 没有要补的就不读快照：这一趟每次刷新都会走，绝大多数连接走到这里就结束。
                return;
            }
            List<ConnectorSchema> snapshot = schemaService.currentRows(connectorId);
            if (snapshot == null || snapshot.isEmpty()) {
                return;
            }
            Map<String, Map<String, FieldDetail>> fieldsByObject = parseFields(snapshot);
            Map<String, List<List<String>>> uniqueKeys = SemanticJoinValidator.uniqueKeysByObject(snapshot);
            JoinCounters c = new JoinCounters();
            for (ConnectorSemantic r : joins) {
                String[] to = joinTarget(r);
                if (to == null || blank(r.getObjectName()) || blank(r.getFieldName())) {
                    continue;
                }
                if (tally(examineStructure(r, readDetail(r.getDetailJson()), to, fieldsByObject, uniqueKeys), c)) {
                    resetTables.add(fold(r.getObjectName()));
                }
            }
            if (c.structureMarked > 0 || c.structurePending > 0) {
                log.info("刷新后补标关系结构形态 connectorId={} 补标 {} 条（其中结论与结构矛盾、打回未验证 {} 条） "
                                + "目标表唯一键未知暂缓 {} 条",
                        connectorId, c.structureMarked, c.structureReset, c.structurePending);
            }
        } catch (Exception e) {
            log.warn("刷新后补标关系结构形态失败，下一次刷新再补 connectorId={}: {}", connectorId, describe(e));
        } finally {
            // 顺序与 validate 的 finally 一样：先放闸再取补跑名单。
            validating.remove(connectorId);
            Set<String> again = validateAgain.remove(connectorId);
            if (again != null) {
                dispatchValidation(connectorId, tenantId, null,
                        again == SCOPE_ALL ? null : (resetTables.isEmpty() ? again : mergeScope(again, resetTables)));
            } else if (!resetTables.isEmpty()) {
                dispatchValidation(connectorId, tenantId, null, resetTables);
            }
        }
    }

    /** 不看快照就能排除的行：已经有 join_kind、且不是「还没有组合键信息的多态外键」。 */
    private static boolean mayNeedStructure(Map<String, Object> detail) {
        String kind = upper(str(detail, KEY_JOIN_KIND));
        return kind == null
                || (SemanticJoinValidator.KIND_POLYMORPHIC.equals(kind) && detail.get(KEY_COMPOSITE_COLUMNS) == null);
    }

    /**
     * 这一行要不要跑结构判定：没有 {@code join_kind} 的；以及目标表唯一键<b>现在已知</b>、却还没有组合键信息的多态外键
     * （它可能是在不知道唯一键时标下的，见 {@link #examineStructure}）。
     */
    private static boolean needsStructureExam(Map<String, Object> detail, String[] to,
                                              Map<String, List<List<String>>> uniqueKeys) {
        String kind = upper(str(detail, KEY_JOIN_KIND));
        if (kind == null) {
            return true;
        }
        return SemanticJoinValidator.KIND_POLYMORPHIC.equals(kind) && detail.get(KEY_COMPOSITE_COLUMNS) == null
                && to != null && keysKnown(uniqueKeys, to[0]);
    }

    /**
     * 给一条关系跑一次结构判定并落库。<b>零次客户库访问</b>：{@code structureOnly} 只读快照与唯一键。不看验证范围、不看决没决过。
     *
     * <h3>★ 不知道目标表唯一键时，普通关系（SIMPLE）一个字都不写</h3>
     * 组合键只能从唯一键认出来；唯一键不在快照里（本功能上线之前拉的快照、或那次读索引失败）时，判定只能落到 SIMPLE。
     * 从前照写 {@code join_kind=SIMPLE}，而写下 join_kind 的行之后再不回头看——等快照带上唯一键、目标表其实是组合键，
     * 这条关系已经永远是普通关系了：只按一列 join 一行连出多行、SUM 放大且不报错。所以此时<b>让 join_kind 缺着</b>
     * （注入层对缺省本来就按普通关系处理，与现状一样），唯一键到了之后的下一趟会重新判它，阶段 note 里如实说「暂缓」。
     *
     * <p>多态外键照写：它按列名判、与唯一键无关，是一句已经成立的警告，压着不写就是让它以普通关系的身份继续进 joins。
     * 它缺的只是「目标表恰好也是组合键」那半句——所以唯一键到了之后，还没有组合键信息的多态外键会被再判一次
     * （{@link #needsStructureExam}），真的多出组合键才写，判别列与判别值原样留着（{@link #mergeStructure}）。
     *
     * <h3>★ 结构翻成多态外键 / 组合键时，与之矛盾的采样结论整条作废</h3>
     * 已决过的存量关系是在不知道结构的时候验的：多态外键上的 CONFIRMED / WEAK 来自不分判别条件的包含率（可能被重叠的自增 id 抬高），
     * REJECTED 可能只是被别的类型的行拉低（而 REJECTED 永不注入，这条关系就此消失）；组合键上的 CONFIRMED / WEAK 带着
     * 前 1000 行测出来的「右侧唯一」、N:1 和 {@code auto_joinable=true}——后者会让注入层不给 fan-out 警告。
     * 留着它们，结构键说「要当心」、结论说「放心连」，模型只能二选一。所以打回 {@code V_NONE}，见 {@link #resetContradictedVerdict}。
     * 组合键上的 REJECTED / UNDECIDABLE 不打回：组合键的包含率探查与从前一模一样，那两个结论仍然成立；
     * 多态外键上的 UNDECIDABLE 同理（第 3 档上还缺判别值的，由 {@link #wantsDiscriminatorReprobe} 回头补）。
     *
     * @param detail 这一行当前的 detail，会被就地改成落库后的样子（调用方接着拿它判断要不要重探）
     */
    private StructureMark examineStructure(ConnectorSemantic row, Map<String, Object> detail, String[] to,
                                           Map<String, Map<String, FieldDetail>> fieldsByObject,
                                           Map<String, List<List<String>>> uniqueKeys) {
        if (to == null || !needsStructureExam(detail, to, uniqueKeys)) {
            return StructureMark.NONE;
        }
        String oldKind = upper(str(detail, KEY_JOIN_KIND));
        Map<String, Object> patch;
        try {
            patch = joinValidator.structureOnly(SemanticJoinValidator.JoinCandidate.of(
                    row.getObjectName(), row.getFieldName(), to[0], to[1]), fieldsByObject, uniqueKeys);
        } catch (RuntimeException e) {
            log.warn("关系结构形态补标失败，本条跳过 semanticId={}: {}", row.getId(), describe(e));
            return StructureMark.NONE;
        }
        String newKind = upper(str(patch, KEY_JOIN_KIND));
        if (newKind == null) {
            return StructureMark.NONE;
        }
        if (oldKind != null && (!SemanticJoinValidator.KIND_POLYMORPHIC.equals(newKind)
                || patch.get(KEY_COMPOSITE_COLUMNS) == null)) {
            // 回头再判的多态外键：唯一键到了也没有多出组合键信息，原样留着——验证侧写下的 care_reason 可能比这里的更具体。
            return StructureMark.NONE;
        }
        if (SemanticJoinValidator.KIND_SIMPLE.equals(newKind) && !keysKnown(uniqueKeys, to[0])) {
            return StructureMark.PENDING_KEYS;
        }
        if (contradicts(row.getVerified(), oldKind, newKind)) {
            return resetContradictedVerdict(row, detail, patch, to) ? StructureMark.RESET : StructureMark.NONE;
        }
        return writeStructure(row, detail, patch) ? StructureMark.MARKED : StructureMark.NONE;
    }

    /** 已决的结论与新判出的结构形态是否矛盾，规则见 {@link #examineStructure}。旧行没有 join_kind 按普通关系算。 */
    private static boolean contradicts(String verified, String oldKind, String newKind) {
        String v = upper(verified);
        if (v == null || v.isEmpty() || ConnectorSemanticService.V_NONE.equals(v)) {
            return false;
        }
        String before = oldKind == null ? SemanticJoinValidator.KIND_SIMPLE : oldKind;
        if (before.equals(newKind)) {
            return false;
        }
        if (SemanticJoinValidator.KIND_POLYMORPHIC.equals(newKind)) {
            return ConnectorSemanticService.V_CONFIRMED.equals(v) || ConnectorSemanticService.V_WEAK.equals(v)
                    || ConnectorSemanticService.V_REJECTED.equals(v);
        }
        if (SemanticJoinValidator.KIND_COMPOSITE.equals(newKind)) {
            return ConnectorSemanticService.V_CONFIRMED.equals(v) || ConnectorSemanticService.V_WEAK.equals(v);
        }
        return false;
    }

    /**
     * 结构写下的同时把矛盾的结论打回未验证：{@code verified=NONE}、结论键整组删掉（{@link #VERDICT_KEYS}，
     * 含 {@code auto_joinable} / {@code cardinality}）、{@code basis} 与 gloss 换回未验证的那句（S1 的来源原样留着）。
     * 一次 UPDATE 写完三列，不会出现「结构已写、结论还在」的中间态。成功后回填内存里的行，调用方据此把它当本轮候选。
     */
    private boolean resetContradictedVerdict(ConnectorSemantic row, Map<String, Object> detail,
                                             Map<String, Object> patch, String[] to) {
        Map<String, Object> after = new LinkedHashMap<>(detail);
        mergeStructure(after, patch);
        VERDICT_KEYS.forEach(after::remove);
        String sourceSql = str(after, ConnectorSemanticService.KEY_SOURCE_SQL);
        after.put("basis", sourceSql == null ? "未经数据验证" : clip(sourceSql + "；未经数据验证", DETAIL_TEXT_MAX));
        String json = toJson(after);
        if (json == null) {
            return false;
        }
        String gloss = clip(unverifiedJoinGloss(to[0], to[1], after), GLOSS_MAX);
        try {
            ConnectorSemantic u = new ConnectorSemantic();
            u.setId(row.getId());
            u.setVerified(ConnectorSemanticService.V_NONE);
            u.setGloss(gloss);
            u.setDetailJson(json);
            if (semanticMapper.updateById(u) > 0) {
                log.info("关系结构形态判为 {}，原有采样结论与之矛盾，已打回未验证 semanticId={} {}.{} -> {}.{} 原结论={}",
                        after.get(KEY_JOIN_KIND), row.getId(), row.getObjectName(), row.getFieldName(), to[0], to[1],
                        row.getVerified());
                row.setVerified(ConnectorSemanticService.V_NONE);
                row.setGloss(gloss);
                row.setDetailJson(json);
                detail.clear();
                detail.putAll(after);
                return true;
            }
        } catch (Exception e) {
            // 单条落库失败不该让整轮停：它下一趟仍然没有 join_kind，还会再判一次。
            log.warn("关系结构形态落库（打回未验证）失败 semanticId={}: {}", row.getId(), describe(e));
        }
        return false;
    }

    /** 未经验证的关系那一句 gloss，与推导写出来的同形；不带基数——基数正是被作废的那一项。 */
    private static String unverifiedJoinGloss(String toObj, String toCol, Map<String, Object> detail) {
        StringBuilder b = new StringBuilder("关联 ").append(toObj).append('.').append(toCol).append('。');
        appendIfPresent(b, detail.get(ConnectorSemanticService.KEY_SOURCE_SQL));
        appendIfPresent(b, detail.get("note"));
        b.append("本条未经数据验证，join 前建议先看该列的取值分布。");
        return b.toString();
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 把结构键并进一行的 detail_json，<b>只写 detail_json 这一列</b>：{@code verified} / {@code gloss} 不碰，
     * {@code basis} / {@code source_sql} 这类非结构键原样留着。写成功后回填内存里的行，同一轮再来一次时比对得出「没变」。
     *
     * @param detail 这一行当前的 detail，会被就地改成合并后的样子（调用方接着拿它判断要不要重探）
     * @return 真的写了一次
     */
    private boolean writeStructure(ConnectorSemantic row, Map<String, Object> detail, Map<String, Object> patch) {
        if (!mergeStructure(detail, patch)) {
            return false;
        }
        String json = toJson(detail);
        if (json == null) {
            return false;
        }
        try {
            ConnectorSemantic u = new ConnectorSemantic();
            u.setId(row.getId());
            u.setDetailJson(json);
            if (semanticMapper.updateById(u) > 0) {
                row.setDetailJson(json);
                return true;
            }
        } catch (Exception e) {
            // 单条落库失败不该让整轮停：剩下的关系仍然值得补标。
            log.warn("关系结构形态落库失败 semanticId={}: {}", row.getId(), describe(e));
        }
        return false;
    }

    /**
     * 结构键<b>整组替换</b>：先清掉行上旧的结构键，再放这次判出来的。
     *
     * <p>为什么不是 putAll：结构判定按的是当前快照，结论可能从 POLYMORPHIC 变成 SIMPLE（判别列被删了）。
     * 只 putAll 会让旧的 {@code discriminator_column} / {@code care_reason} 留在一条已经是 SIMPLE 的关系上，
     * 注入层会照着那句旧警告让模型去加一个已经不存在的条件。
     *
     * <p>{@code discriminator_value} <b>从 patch 里只清不写</b>：结构判定这条路绝不产出真实取值（那只能来自第 3 档、过了 PII 的探查），
     * 上游哪天漏了一个出来，这里也不接。行上<b>已有</b>的判别值只在一种情况下留着：新旧都是多态外键、判别列没变——
     * 那是唯一键到了之后回头补组合键信息的那一趟（{@link #examineStructure}），判别值仍然成立，而结构判定这条路补不回它
     * （那一轮分组探查已经跑过，{@link #wantsDiscriminatorReprobe} 不会再挑它）。其余情况结构都变了，旧判别值一并作废。
     *
     * @param patch 没有 {@code join_kind} 的 patch（标识符对不上快照）= 没判，<b>不</b>覆盖行上已有的结构键
     * @return 合并后与原来不同
     */
    static boolean mergeStructure(Map<String, Object> detail, Map<String, Object> patch) {
        if (patch == null || patch.get(KEY_JOIN_KIND) == null) {
            return false;
        }
        Object keptValue = samePolymorphicDiscriminator(detail, patch) ? detail.get(KEY_DISCRIMINATOR_VALUE) : null;
        Map<String, Object> after = new LinkedHashMap<>(detail);
        STRUCTURE_KEYS.forEach(after::remove);
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            if (STRUCTURE_KEYS.contains(e.getKey()) && !KEY_DISCRIMINATOR_VALUE.equals(e.getKey())
                    && e.getValue() != null) {
                after.put(e.getKey(), e.getValue());
            }
        }
        if (keptValue != null) {
            after.put(KEY_DISCRIMINATOR_VALUE, keptValue);
        }
        if (after.equals(detail)) {
            return false;
        }
        detail.clear();
        detail.putAll(after);
        return true;
    }

    /** 新旧都是多态外键、判别列（忽略大小写）没变。 */
    private static boolean samePolymorphicDiscriminator(Map<String, Object> detail, Map<String, Object> patch) {
        String oldCol = str(detail, KEY_DISCRIMINATOR_COLUMN);
        String newCol = str(patch, KEY_DISCRIMINATOR_COLUMN);
        return SemanticJoinValidator.KIND_POLYMORPHIC.equals(upper(str(detail, KEY_JOIN_KIND)))
                && SemanticJoinValidator.KIND_POLYMORPHIC.equals(upper(str(patch, KEY_JOIN_KIND)))
                && oldCol != null && oldCol.equalsIgnoreCase(newCol);
    }

    /**
     * 已决过的多态外键要不要重新探查判别值。四个条件缺一不可：结构是 POLYMORPHIC；还没有判别值；
     * 决出结论那一轮第 3 档的分组探查<b>没有真的跑</b>（{@link #KEY_PROBED_WITH_SAMPLE_VALUES} 不是 true——档位不够、
     * 运维急停开着、判别列名没过 PII 都算没跑；存量行没有这个键，按没跑处理）；这条连接<b>现在</b>允许读取值。
     *
     * <p>★ 第三条是这件事能停下来的原因。第 3 档上判别值记不下来是常态之一：几张目标表的自增 id 重叠
     * （实测 role_resource.resource_id 按 resource_type 分组，AGENT 2/2、KB 2/2，分不出来）、判别值没过 PII、
     * 样本没采到那一类行。只看「没有判别值」就重探，这些行会在每一轮验证里把客户的库再扫一遍，
     * 而结论一条都不会变——那正是 S3 明确拒绝过的做法。
     */
    private boolean wantsDiscriminatorReprobe(Long connectorId, Map<String, Object> detail, JoinCounters c) {
        return SemanticJoinValidator.KIND_POLYMORPHIC.equals(str(detail, KEY_JOIN_KIND))
                && str(detail, KEY_DISCRIMINATOR_VALUE) == null
                && !Boolean.TRUE.equals(detail.get(KEY_PROBED_WITH_SAMPLE_VALUES))
                && sampleValuesAllowed(connectorId, c);
    }

    /**
     * 这条连接现在允不允许读真实取值（第 3 档）。一轮验证只问一次，缓存在计数器上。
     *
     * <p>走 {@link ConnectorSemanticService#allowsSampleValues}（它 fail-closed），这里再兜一层：
     * 判成「不允许」的代价只是晚一轮补判别值；真正读取值之前验证器还会自己再查一次档位。
     */
    private boolean sampleValuesAllowed(Long connectorId, JoinCounters c) {
        if (c.sampleValues == null) {
            boolean allowed;
            try {
                allowed = semanticService.allowsSampleValues(connectorId);
            } catch (RuntimeException e) {
                log.warn("读取数据出库档位失败，按不允许读取值处理 connectorId={}: {}", connectorId, describe(e));
                allowed = false;
            }
            c.sampleValues = allowed;
        }
        return c.sampleValues;
    }

    /**
     * 结构补标那半句。「暂缓」必须说出来：目标表唯一键一直读不到时，这些关系会一直按普通关系进 joins，
     * 不说的话它和「补过了、都是普通关系」长得一模一样。
     */
    private static String structureNote(JoinCounters c) {
        StringBuilder b = new StringBuilder();
        if (c.structureMarked > 0) {
            b.append("；为 ").append(c.structureMarked)
                    .append(" 条关系补标了结构形态（多态外键 / 组合键 / 普通关联，只读结构快照，没有访问客户库）");
            if (c.structureReset > 0) {
                b.append("，其中 ").append(c.structureReset)
                        .append(" 条原有的采样结论与结构矛盾，已打回未验证、按结构重验");
            }
        }
        if (c.structurePending > 0) {
            b.append("；").append(c.structurePending)
                    .append(" 条关系的目标表还没有唯一键信息，结构形态暂缓到下一次「刷新结构」之后再判");
        }
        return b.toString();
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

    // ---------------------------------------------------------------- 表形态测量（键值对表判定）

    /**
     * 挑表 → 交给 {@link TableShapeDetector} → <b>测一张落一张</b>。
     *
     * <h3>★ 落库纪律</h3>
     * <ol>
     *   <li><b>只有测出键值对表才改 {@code table_shape}</b>，写 {@code MEASURED}；模型原来说的是别的，
     *       原话留在 {@code table_shape_model_guess}。<b>分歧本身是信号</b>——它回答「模型在哪类表上会看走眼」，
     *       丢了就再也没有。</li>
     *   <li>测不出键值对形态<b>不改</b>模型的判断，只把测量留痕写进 {@code table_shape_measurement}：
     *       名列是按结构挑的，挑错列测出的「不是」说明不了任何事（理由见检测器类注释）。</li>
     *   <li>测过的（{@code MEASURED}，或留痕结论不是「判不出」）<b>下一轮跳过</b>——每点一次验证就把客户的库
     *       重新扫一遍、而结论不会变，是 S3 已经明确拒绝过的做法。</li>
     *   <li>{@code source=HUMAN} 的表用途行一个字不碰。</li>
     *   <li>测出键值对表、但说明书里没有这张表的用途行：补一行。键值对表恰恰是名字最晦涩、模型最说不清的那类，
     *       不补的话这条最要紧的结论无处可放。</li>
     * </ol>
     *
     * <p>档位不够（第 1 档）时一行都不写，只在阶段 note 里说一次：行上的 {@code table_shape_source=MODEL}
     * 本身就是「未经数据测量」的标签。理由同 S4 不给每一行铺「未启用」。
     */
    private ShapeStage runShapeStage(Long connectorId, Connection conn, List<ConnectorSchema> rows,
                                     List<ConnectorSemantic> all) {
        Map<String, ConnectorSemantic> objectRows = new LinkedHashMap<>();
        for (ConnectorSemantic r : all) {
            if (ConnectorSemanticService.SCOPE_OBJECT.equals(r.getScope()) && !blank(r.getObjectName())) {
                objectRows.putIfAbsent(fold(r.getObjectName()), r);
            }
        }
        List<TableShapeDetector.Target> targets = new ArrayList<>();
        // 没有用途行、这一轮要测的表：折叠名 → 结构指纹。测完记冷却用，见 markShapeCooldown。
        Map<String, String> unownedHashes = new HashMap<>();
        int measuredBefore = 0;
        int cooling = 0;
        boolean askRedis = true;
        for (ConnectorSchema t : rows) {
            ConnectorSemantic obj = objectRows.get(fold(t.getObjectName()));
            TableShape guess = null;
            if (obj != null) {
                if (!ConnectorSemanticService.SOURCE_INFERRED.equals(obj.getSource())) {
                    continue;
                }
                Map<String, Object> d = readDetail(obj.getDetailJson());
                if (shapeAlreadyMeasured(d)) {
                    measuredBefore++;
                    continue;
                }
                guess = TableShape.parse(str(d, TableShape.KEY_SHAPE)).orElse(null);
            } else {
                // ★ 没有用途行的表测出「不是键值对表」无处落结论，从前每一轮验证都重测一遍——那是每轮一条打在客户库上的分组统计。
                //   冷却落在 Redis，理由见 shapeCooldownHours。读失败按「不在冷却」：冷却是省客户配额的，不是一道闸；
                //   失败一次之后这一轮不再问（Redis 不可达时每问一次要等几秒）。
                if (askRedis) {
                    try {
                        if (redissonClient.getBucket(
                                shapeCooldownKey(connectorId, t.getObjectName(), t.getContentHash())).isExists()) {
                            cooling++;
                            continue;
                        }
                    } catch (Exception e) {
                        askRedis = false;
                        log.warn("读表形态测量冷却标记失败，这一轮按不在冷却处理 connectorId={}: {}", connectorId, describe(e));
                    }
                }
                unownedHashes.put(fold(t.getObjectName()), t.getContentHash());
            }
            targets.add(new TableShapeDetector.Target(t, guess));
        }
        String before = (measuredBefore > 0 ? "；另有 " + measuredBefore + " 张此前已测量过，本轮跳过" : "")
                + (cooling > 0 ? "；另有 " + cooling + " 张没有用途说明的表此前测过、结构没变，冷却中未重测" : "");
        if (targets.isEmpty()) {
            return new ShapeStage(0, 0, 0, 0, measuredBefore + cooling > 0
                    ? "表形态此前已测量过（" + (measuredBefore + cooling) + " 张），本轮没有要测的表"
                    + (cooling > 0 ? "（其中 " + cooling + " 张没有用途说明、结构没变，在冷却中）" : "")
                    : "没有需要测量表形态的表");
        }

        TableShapeDetector.ShapeRun run;
        try {
            run = shapeDetector.detect(connectorId, targets, v -> {
                persistShape(connectorId, conn, objectRows, v);
                return !Thread.currentThread().isInterrupted();
            });
        } catch (RuntimeException e) {
            // 契约上它不抛，这里兜底。已经落库的结论不受影响。
            log.warn("表形态测量抛了异常（契约上不该） connectorId={}: {}", connectorId, describe(e));
            return new ShapeStage(0, 0, 0, 0, "表形态测量失败：" + describe(e) + before);
        }
        if (run == null) {
            return new ShapeStage(0, 0, 0, 0, "表形态测量没有返回结果" + before);
        }
        markShapeCooldown(connectorId, run, unownedHashes);
        int kv = 0;
        int overrides = 0;
        List<TableShapeDetector.ShapeVerdict> verdicts =
                run.getVerdicts() == null ? List.<TableShapeDetector.ShapeVerdict>of() : run.getVerdicts();
        for (TableShapeDetector.ShapeVerdict v : verdicts) {
            if (v.getOutcome() == TableShapeDetector.Outcome.KEY_VALUE) {
                kv++;
                if (v.overridesModel()) {
                    overrides++;
                }
            }
        }
        String note = blank(run.getNote()) ? "表形态没有测量" : run.getNote();
        return new ShapeStage(verdicts.size(), kv, overrides, run.getStatements(), note + before);
    }

    /**
     * 表形态测量冷却键：连接 id + sha256(折叠表名 + 结构指纹)。
     *
     * <p>带结构指纹的理由与 {@link #addedCooldownKey} 相同：表结构一变（加列、改类型、补注释）冷却自然失效，那正是值得重测的时候。
     * 落在 Redis 而不是进程内存，同样因为 push main 即部署，进程内的冷却每次发版清零。
     */
    static String shapeCooldownKey(Long connectorId, String objectName, String contentHash) {
        return SHAPE_COOLDOWN_KEY_PREFIX + connectorId + ":"
                + ConnectorSemanticService.sha256(fold(objectName) + KEY_SEP + (contentHash == null ? "" : contentHash));
    }

    /**
     * 没有表用途行的表测完之后记冷却，理由见 {@link #shapeCooldownHours}。
     *
     * <ul>
     *   <li>测出键值对表的不记：那一张已经补了用途行，下一轮按行跳过；补行失败的，下一轮本来就该重测。</li>
     *   <li>没真的发语句的不记（被中断）。</li>
     *   <li>中途停下的那一轮，最后一条不记：让它停下的多半就是这一张（连接不可用、被限流），它什么都没测出来。</li>
     * </ul>
     * 测过的「不是 / 证据不足 / 判不出」都记：判不出多半是表太小（不到 30 行），天天重测结论也不会变。
     * 在测量返回之后统一记、不在回调里记，就是为了看得到「这一轮是不是中途停下的」。写失败只记日志，最坏是下一轮再测一次。
     */
    private void markShapeCooldown(Long connectorId, TableShapeDetector.ShapeRun run, Map<String, String> unownedHashes) {
        List<TableShapeDetector.ShapeVerdict> vs = run.getVerdicts();
        if (unownedHashes.isEmpty() || vs == null || vs.isEmpty()) {
            return;
        }
        int end = TableShapeDetector.OUT_ABORTED.equals(run.getOutcome()) ? vs.size() - 1 : vs.size();
        long hours = Math.max(1L, shapeCooldownHours);
        String stamp = String.valueOf(System.currentTimeMillis());
        for (int i = 0; i < end; i++) {
            TableShapeDetector.ShapeVerdict v = vs.get(i);
            if (v == null || !v.isProbed() || v.getOutcome() == null
                    || v.getOutcome() == TableShapeDetector.Outcome.KEY_VALUE || blank(v.getObjectName())) {
                continue;
            }
            String f = fold(v.getObjectName());
            if (!unownedHashes.containsKey(f)) {
                continue;
            }
            try {
                RBucket<String> mark = redissonClient.getBucket(
                        shapeCooldownKey(connectorId, v.getObjectName(), unownedHashes.get(f)));
                mark.set(stamp, hours, TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("写表形态测量冷却标记失败，本轮剩下的不再写 connectorId={}: {}", connectorId, describe(e));
                return;
            }
        }
    }

    /** 测过、结论不是「判不出」的表不再测。{@code MEASURED} 必然测过。 */
    private static boolean shapeAlreadyMeasured(Map<String, Object> detail) {
        if (TableShape.SOURCE_MEASURED.equals(str(detail, TableShape.KEY_SOURCE))) {
            return true;
        }
        if (detail.get(TableShape.KEY_MEASUREMENT) instanceof Map<?, ?> m) {
            Object o = m.get("outcome");
            return o != null && !TableShapeDetector.Outcome.UNDECIDABLE.name().equals(String.valueOf(o));
        }
        return false;
    }

    private void persistShape(Long connectorId, Connection conn, Map<String, ConnectorSemantic> objectRows,
                              TableShapeDetector.ShapeVerdict v) {
        if (v == null || blank(v.getObjectName()) || v.getOutcome() == null) {
            return;
        }
        boolean kv = v.getOutcome() == TableShapeDetector.Outcome.KEY_VALUE;
        ConnectorSemantic row = objectRows.get(fold(v.getObjectName()));
        try {
            if (row == null) {
                if (kv) {
                    createShapeObjectRow(connectorId, conn, v);
                }
                // 没有用途行、又不是键值对表：不为一句「测过了，不是」凭空补一行说明。
                return;
            }
            if (!ConnectorSemanticService.SOURCE_INFERRED.equals(row.getSource())) {
                return;
            }
            Map<String, Object> detail = readDetail(row.getDetailJson());
            detail.put(TableShape.KEY_MEASUREMENT, v.measurementFragment());
            if (kv) {
                String previous = str(detail, TableShape.KEY_SHAPE);
                detail.put(TableShape.KEY_SHAPE, TableShape.KEY_VALUE.name());
                detail.put(TableShape.KEY_SOURCE, TableShape.SOURCE_MEASURED);
                detail.put(TableShape.KEY_KV_NAME_COLUMN, v.getNameColumn());
                detail.put(TableShape.KEY_KV_VALUE_COLUMN, v.getValueColumn());
                if (previous != null && !TableShape.KEY_VALUE.name().equals(previous)) {
                    detail.put(TableShape.KEY_MODEL_GUESS, previous);
                    log.info("表形态实测推翻了模型的判断 connectorId={} object={} 模型={} 实测=KEY_VALUE",
                            connectorId, v.getObjectName(), previous);
                }
            } else if (v.getModelGuess() == TableShape.KEY_VALUE) {
                // 反方向的分歧不改模型的判断（理由见 runShapeStage），但要看得见。
                log.info("模型判为键值对表但实测未确认 connectorId={} object={} 结论={}",
                        connectorId, v.getObjectName(), v.getOutcome());
            }
            ConnectorSemantic u = new ConnectorSemantic();
            u.setId(row.getId());
            u.setDetailJson(toJson(detail));
            semanticMapper.updateById(u);
        } catch (Exception e) {
            // 单条落库失败不该让整轮停：剩下的表仍然值得测。
            log.warn("表形态结论落库失败 connectorId={} object={}: {}", connectorId, v.getObjectName(), describe(e));
        }
    }

    /** 测出键值对表、说明书里却没有这张表的用途行：补一行。理由见 {@link #runShapeStage} 第 5 条。 */
    private void createShapeObjectRow(Long connectorId, Connection conn, TableShapeDetector.ShapeVerdict v) {
        if (len(v.getObjectName()) > NAME_MAX) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(TableShape.KEY_SHAPE, TableShape.KEY_VALUE.name());
        detail.put(TableShape.KEY_SOURCE, TableShape.SOURCE_MEASURED);
        detail.put(TableShape.KEY_KV_NAME_COLUMN, v.getNameColumn());
        detail.put(TableShape.KEY_KV_VALUE_COLUMN, v.getValueColumn());
        detail.put(TableShape.KEY_MEASUREMENT, v.measurementFragment());

        ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_OBJECT, v.getObjectName(), "", "");
        row.setTenantId(conn.getTenantId());
        row.setConnectorId(connectorId);
        row.setSource(ConnectorSemanticService.SOURCE_INFERRED);
        row.setGloss(clip("实测为键值对表：一行是某一个指标的一个值，指标名在 " + v.getNameColumn()
                + " 列、指标值在 " + v.getValueColumn() + " 列。聚合前必须先按 " + v.getNameColumn()
                + " 过滤到单个指标，不同指标的值不能相加。这张表原本没有用途说明，业务含义需向用户确认。", GLOSS_MAX));
        row.setDetailJson(toJson(detail));
        // 「在数据里测得到」正是 DATA 这一档的定义。
        row.setEvidence(ConnectorSemanticService.EV_DATA);
        // 不给 90：判据是统计上的代理（单位混存），不是逐行核对过每个指标。
        row.setConfidence(80);
        row.setAnchorKind(ConnectorSemanticService.ANCHOR_NONE);
        semanticMapper.insert(row);
    }

    /** 表形态那一段的结果。纯内部，可以是 record。 */
    private record ShapeStage(int measured, int keyValue, int overrides, int statements, String note) {
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
     * ★ 取值只进 {@code value_domain}，补写行的 gloss 一个取值都不复述（K-3），见 {@link #VALUE_ROW_GLOSS}。
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

    /**
     * 把值域并进一条已有的 FIELD 行。只动 {@code detail_json} 里 {@code value_domain} 这一个键——
     * 唯一的例外是这一行的 gloss 是值域阶段写下、可能带着取值的那种（{@link #carriesValueGloss}）：同一次写里换成
     * {@link #VALUE_ROW_GLOSS}。这一次重采若没枚举出取值，{@code value_domain} 被换掉的那一刻，注入层就不再挡那句 gloss 了。
     */
    private boolean patchValueDomain(ConnectorSemantic row, SemanticValueProfiler.ColumnValueDomain d) {
        try {
            Map<String, Object> detail = readDetail(row.getDetailJson());
            boolean neutralise = carriesValueGloss(row, detail);
            // 片段一律走 detailFragment：「只有完整集合才写 values」这条不变式只有那一个出口。
            // 自己从 getValues() 拼，早晚有一处把高基数列的 null 写成 []，
            // 而 [] 读起来是「这列没有取值」——和事实正好相反。
            detail.put(SemanticValueProfiler.DETAIL_KEY, SemanticValueProfiler.detailFragment(d));
            if (neutralise) {
                detail.put(KEY_ORIGIN, ORIGIN_VALUE_PROFILE);
            }
            ConnectorSemantic u = new ConnectorSemantic();
            u.setId(row.getId());
            u.setDetailJson(toJson(detail));
            if (neutralise) {
                u.setGloss(VALUE_ROW_GLOSS);
            }
            boolean ok = semanticMapper.updateById(u) > 0;
            if (ok && neutralise) {
                row.setGloss(VALUE_ROW_GLOSS);
            }
            return ok;
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
            // ★ K-3 来源标记：之后任何一方（重采、清除样本值、注入层）认这一行都认它，不认 gloss 长什么样。
            detail.put(KEY_ORIGIN, ORIGIN_VALUE_PROFILE);
            detail.put(SemanticValueProfiler.DETAIL_KEY, SemanticValueProfiler.detailFragment(d));

            ConnectorSemantic row = base(ConnectorSemanticService.SCOPE_FIELD,
                    d.getObjectName(), d.getFieldName(), "");
            row.setTenantId(conn.getTenantId());
            row.setConnectorId(connectorId);
            // INFERRED：下一次推导会连它一起物理删掉重来，这是对的——那时值域本来也该重采一遍。
            row.setSource(ConnectorSemanticService.SOURCE_INFERRED);
            // ★ gloss 里一个取值都不写（K-3）。从前这里写的是「取值只有这 N 种：……」——注入层只能在 value_domain
            //   还列着取值时挡它，下一次没枚举出取值的重采一换掉 value_domain，同一批真实取值就在任何档位上出库。
            row.setGloss(VALUE_ROW_GLOSS);
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
     * K-3：把值域阶段补写的 FIELD 行上<b>可能带着取值</b>的 gloss 换成 {@link #VALUE_ROW_GLOSS}。零次客户库访问。
     *
     * <h3>为什么在每一轮验证开头扫一遍，而不只在并值域的那一刻</h3>
     * 并值域只碰这一轮真的采到的列，而档位不开放时值域阶段根本不跑。那句旧 gloss 此时全靠注入层看 {@code value_domain}
     * 里还有没有取值来挡——任何一条把取值从 {@code value_domain} 里拿掉的路（重采没枚举出来、清除样本值）只要有一处漏改 gloss，
     * 同一批真实取值就在任何档位上出库，而且没有任何信号。先扫掉，这类失误就无从发生。
     * 线上旧行只在第一轮改写一次（顺手补上来源标记），之后比对得出「已经是那句」，一次库都不写。
     */
    private int neutraliseValueGlosses(List<ConnectorSemantic> all) {
        int n = 0;
        for (ConnectorSemantic r : all) {
            // 先按 gloss 的记号粗筛，绝大多数行（模型写的字段说明）在这里就过去了，不必逐行解 detail_json。
            if (r == null || !legacyValueGloss(r.getGloss())) {
                continue;
            }
            Map<String, Object> detail = readDetail(r.getDetailJson());
            if (!carriesValueGloss(r, detail)) {
                continue;
            }
            detail.put(KEY_ORIGIN, ORIGIN_VALUE_PROFILE);
            String json = toJson(detail);
            try {
                ConnectorSemantic u = new ConnectorSemantic();
                u.setId(r.getId());
                u.setGloss(VALUE_ROW_GLOSS);
                if (json != null) {
                    u.setDetailJson(json);
                }
                if (semanticMapper.updateById(u) > 0) {
                    r.setGloss(VALUE_ROW_GLOSS);
                    if (json != null) {
                        r.setDetailJson(json);
                    }
                    n++;
                }
            } catch (Exception e) {
                // 单条失败不停：这一行下一轮还会被认出来；这一轮值域阶段并它时还会再换一次。
                log.warn("值域补写行的 gloss 换成不含取值的说明失败 semanticId={}: {}", r.getId(), describe(e));
            }
        }
        if (n > 0) {
            log.info("值域补写行的 gloss 可能带着真实取值，已换成不含取值的说明 条数={}", n);
        }
        return n;
    }

    /**
     * 这一行的 gloss 是不是值域阶段写下、<b>带着取值</b>的那种：FIELD + INFERRED + gloss 是旧版补写的那句话
     * （{@link #legacyValueGloss}），并且是 K-3 行（带来源标记，或标记出现之前的存量行：依据 DATA）。
     *
     * <h3>为什么只认那句话，不把「K-3 行、gloss 不是本类那句」一律改写</h3>
     * 如今没有任何一条路会往 K-3 行的 gloss 里写取值：本类补写的是 {@link #VALUE_ROW_GLOSS}，清除样本值那一处
     * （{@code ConnectorSemanticService.purgeSampleValues}）写的是它自己那句不含取值的话。能带着取值的只剩旧版那句。
     * 一律改写的话，那两处会在每次刷新（清除）与每轮验证（这里）之间来回互相覆盖，一个取值也没多挡住。
     *
     * <p>人写的（HUMAN / IMPORTED）一律不算；模型写的也不算——提示词允许它标 DATA，但它写不出旧版那句话。
     */
    private static boolean carriesValueGloss(ConnectorSemantic row, Map<String, Object> detail) {
        if (!ConnectorSemanticService.SCOPE_FIELD.equals(row.getScope())
                || !ConnectorSemanticService.SOURCE_INFERRED.equals(row.getSource())
                || !legacyValueGloss(row.getGloss())) {
            return false;
        }
        return ConnectorSemanticService.EV_DATA.equals(row.getEvidence())
                || ORIGIN_VALUE_PROFILE.equals(str(detail, KEY_ORIGIN));
    }

    /** 上线过的旧版补写行的那两句话之一，理由见 {@link #LEGACY_VALUE_GLOSS_LISTED}。 */
    static boolean legacyValueGloss(String gloss) {
        return gloss != null
                && (gloss.startsWith(LEGACY_VALUE_GLOSS_LISTED) || gloss.startsWith(LEGACY_VALUE_GLOSS_COUNTED))
                && gloss.contains(LEGACY_VALUE_GLOSS_MARK);
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
     * 候选与结论的对照键。<b>折叠大小写</b>，与 {@link SemanticRowAssembler#dedupKey} 同一条理由：
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

    /** 异常摘要带上类名：NPE 这类 message 为 null 的异常，否则在界面上只剩一个孤零零的 "null"。 */
    private static String describe(Throwable e) {
        if (e == null) {
            return "unknown";
        }
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
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
                             int undecidable, int notProbed, int written, int probeCount, int structureMarked,
                             String note) {
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
        /** 只落了结构形态（没探查的结论 + 存量行补标）的行数。不计入 {@link #written}：那一个是给结论对账用的。 */
        int structureMarked;
        /** 其中结论与新结构矛盾、被打回未验证的行数（已计入 {@link #structureMarked}）。 */
        int structureReset;
        /** 目标表唯一键还不知道、结构形态暂缓的行数。 */
        int structurePending;
        /** 这条连接现在允不允许读取值；{@code null} = 这一轮还没问过。 */
        Boolean sampleValues;
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

        /** 真的发了语句去测表形态的表数。 */
        private int shapeMeasured;
        /** 其中测出是键值对表的表数。 */
        private int shapeKeyValue;
        /** 其中推翻了模型判断的表数。<b>这个数不为 0 是信号</b>：模型在这类表上会看走眼。 */
        private int shapeOverrides;
        /** 表形态测量打到客户库的语句条数。 */
        private int shapeStatements;

        private String note;
    }
}
