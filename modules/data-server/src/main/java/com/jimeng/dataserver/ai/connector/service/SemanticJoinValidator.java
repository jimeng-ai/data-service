package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.common.core.utils.CommonUtil;
import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.QueryResult;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryOptions;
import com.jimeng.persistence.entity.Connection;
import com.jimeng.persistence.entity.ConnectorSchema;
import com.jimeng.persistence.mapper.ConnectionMapper;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * S3 采样验证：拿 S2 推出来的一批 {@code a.x -> b.y}，到客户的库里用<b>真实数据</b>算一次包含率，
 * 给每一条一个能被下游信任的结论。
 *
 * <h3>为什么值得为此去碰客户的生产库</h3>
 * 同一批真实生产库上的对照：纯元数据推断（专为隐私约束设计的那一类）精确率约 0.49；
 * 允许库内采样的精确率 1.00。本组件做的正是后者那一步——设计自己的实验里，
 * join 推断的精确率从 92.5% 升到 <b>100%</b>。
 *
 * <p>这件事的收益不对称，所以判定的偏向也是不对称的：一条<b>假阳性</b>的关系不会报错，
 * 它会让模型 join 出一个看着很正常的错数字，并且顺着 join path 污染后面每一跳；
 * 而一条被漏掉的关系，最坏结果是模型多问一句或自己先跑一条 COUNT。
 * 所以本类<b>一律 P 优先于 R</b>：判不出来就说判不出来，绝不猜。
 *
 * <h3>★ 四条把「判不出来」和「判错了」分开的硬规则</h3>
 * 这四条是本类的全部要点，其余都是实现细节：
 * <ol>
 *   <li><b>档位不允许就说不允许，不返回空结果。</b>包含率、基数、distinct 数都是派生统计
 *       （第 2 档）。客户把连接配成第 1 档时，本类返回的是「未启用派生统计，本次没查」，
 *       而不是一个空列表——<b>「没查」和「查了没发现」对下游的含义完全相反</b>，
 *       返回空列表会让下游把一批从没被验过的关系当成「验过、没问题」。</li>
 *   <li><b>超时一律 {@code UNDECIDABLE}，绝不 {@code REJECTED}。</b>对抗审查在本仓库实测过：
 *       高基数列上的聚合能跑到 24 秒。超时回来是一个通用失败，它和「这条关系是错的」
 *       长得一模一样——把它判成 REJECTED，等于用一次慢查询<b>静默删掉一条正确的关系</b>。</li>
 *   <li><b>左侧非空样本 &lt; 10 也是 {@code UNDECIDABLE}。</b>一张几乎空的表判不出任何东西，
 *       而「表太空」不等于「关系是错的」。</li>
 *   <li><b>一次探查 = 一次 {@code executeAsPlatform}。</b>见 {@link #probe}。</li>
 * </ol>
 *
 * <h3>★ 不能把客户自己的 Agent 饿死</h3>
 * {@code executeAsPlatform} 会占一个<b>每实例并发闸</b>的许可，并消耗每分钟速率预算。
 * 一轮剖析几百条探查，若整批包在一个 {@code executeAsPlatform} 里，那条连接在整段时间里
 * 对模型是<b>不可用</b>的——客户问一句数就被告知「并发已达上限」。所以三件事一起做：
 * <ul>
 *   <li>一次探查一次调用，许可在两次探查之间归还；</li>
 *   <li>自己按 {@link #probesPerMinute} 节流，跑得比预算慢得多；</li>
 *   <li>网关那一侧给管理面换了独立的限流桶（{@code connector:rate:platform:*}），
 *       所以平台剖析根本不花客户的问数额度。</li>
 * </ul>
 * 审计动作名固定用 {@link ConnectorAuditService#OP_SEMANTIC_PROBE}：客户的 DBA 拿着他自己的
 * 数据库审计日志来问「这些查询是谁发的」时，这批必须能和模型的查询择得开——
 * 而且它与 {@code platform.schema_refresh} 也不是一回事，那个读元数据，<b>这个真的 SELECT 业务数据</b>。
 *
 * <h3>本类不写库</h3>
 * 它只产出结论，落库（改 {@code verified} / 合并 {@code detail_json}）由接线的那一方做。
 * 理由和 {@link ConnectorSemanticDeriveService} 与 {@link ConnectorSemanticService} 分家一样：
 * 谁碰客户的库、谁碰我们自己的库，要一眼看得出来。
 *
 * <h3>调用方必须自己准备好租户上下文</h3>
 * {@code executeAsPlatform} 要求一个<b>真实</b>的 {@code TenantContext}，
 * {@code TenantContext.runAsSystem(...)} 不算数（它只打开 SYSTEM_MODE，{@code CURRENT_TENANT} 仍为空）。
 * 后台线程请按那一行的 {@code tenant_id} 自己 {@code TenantContext.set(...)}，并在 finally 里清掉。
 *
 * <h3>★ 两类「数据对得上、直接 join 仍然是错数」的关系</h3>
 * 包含率只回答「左边的取值在右边找不找得到」。有两类关系这个问题的答案是「找得到」，而只按这一列 join 照样是错数：
 * <ul>
 *   <li><b>多态外键</b>（{@link #KIND_POLYMORPHIC}）：{@code resource_type + resource_id} 这种，
 *       {@code resource_id} 指向哪张表由 {@code resource_type} 决定。不带类型条件的 join 会把指向别的表的行也连上；
 *       几张目标表的自增 id 范围重叠时，不分条件的包含率还会高到 100%。设计自己的实验里 4 个「假阳性」
 *       之一就是它（「需 resource_type 条件」）。</li>
 *   <li><b>组合键</b>（{@link #KIND_COMPOSITE}）：目标列只是 {@code (tenant_id, code)} 这类多列唯一键的一部分，
 *       单独不唯一。只按 {@code code} 连会一行连出多行，SUM 被放大且不报错。
 *       <b>前 1000 行的唯一性侧写救不了它</b>：InnoDB 按主键物理排序，前 1000 行多半全属于同一个租户，
 *       在那一段里 {@code code} 恰好唯一。所以这一类按<b>索引结构</b>判，不按样本判。</li>
 * </ul>
 * 两类都只标记（{@code join_kind} + {@code care_reason}）、一律不自动 join；注入层把它们放进「需要当心的关系」，
 * 连同所需的条件一起给模型。结构判定只读快照与唯一键、不碰客户库，所以<b>没探查的那一条也带着它</b>。
 *
 * <h3>★ 判别列的取值是第 3 档，连分组探查都是</h3>
 * {@code 'AGENT'} 这种判别值是真实的低基数维值，正是 {@link SemanticDataTier#SAMPLE_VALUES} 管的东西。
 * {@code GROUP BY resource_type} 的结果每一行都带着一个真实取值——哪怕我们只想要计数，取值也已经离开了客户库。所以：
 * <ul>
 *   <li>第 1、2 档：只按列名做结构标记、记下判别列<b>名</b>，不发分组探查；</li>
 *   <li>运维急停 {@code connector.semantic.value-profile-enabled=false}（与值域剖析<b>同一个键</b>）时，第 3 档也按第 2 档处理；</li>
 *   <li>第 3 档：按判别值分组算条件包含率。判别列先过 {@link PiiFilter#screenColumn}（过不了就不查），
 *       带回的取值再过 {@link PiiFilter#screenValues}，过了才记 {@code discriminator_value}；
 *       审计动作名用 {@link ConnectorAuditService#OP_SEMANTIC_VALUES}——客户的 DBA 按动作名过滤就能回答
 *       「平台读没读过我的业务取值」，这一条探查确实读了。</li>
 *   <li>★ 判别列为 NULL / 空串的行<b>不滤</b>，各自成组、算进「采全了没有」，但<b>从不记成</b> {@code discriminator_value}，
 *       也不按「过没过半」否定——它们常是按默认类型写入的历史行，滤掉它们会把一条真关系静默判成 REJECTED。
 *       见 {@link #decidePolymorphic}。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticJoinValidator {

    // ================================================================ 对外常量

    /** 真的跑了采样验证（哪怕其中某几条判不出来）。 */
    public static final String OUT_RAN = "RAN";

    /**
     * 档位不允许：这条连接是第 1 档 · 纯元数据，包含率这类派生统计不许出库。
     * <b>不是失败，也不是「没有发现问题」。</b>
     */
    public static final String OUT_TIER_BLOCKED = "TIER_BLOCKED";

    /** 采样验证被配置关掉了。 */
    public static final String OUT_DISABLED = "DISABLED";

    /** 中途停了：被取消、被中断、采样预算用尽、或连接整体不可用。已决的结论仍然有效。 */
    public static final String OUT_ABORTED = "ABORTED";

    /** 没有候选可验。 */
    public static final String OUT_NOTHING_TO_DO = "NOTHING_TO_DO";

    public static final String CARD_ONE_ONE = "1:1";
    public static final String CARD_ONE_MANY = "1:N";
    public static final String CARD_MANY_ONE = "N:1";
    public static final String CARD_MANY_MANY = "N:N";

    /** 普通关系：按这一列等值 join 即可（仍受包含率与基数约束）。历史行没有 {@code join_kind} 时按它处理。 */
    public static final String KIND_SIMPLE = "SIMPLE";

    /** 多态外键：左表另有一列决定这一列指向哪张表，join 必须带那一列的条件。 */
    public static final String KIND_POLYMORPHIC = "POLYMORPHIC";

    /** 组合键：目标列只是多列唯一键的一部分，join 要把整组键都对上。 */
    public static final String KIND_COMPOSITE = "COMPOSITE";

    /**
     * 结构快照 {@code detail_json.extra} 里唯一键（含主键）的键名，形状
     * {@code [{"name": "PRIMARY", "primary": true, "columns": ["id"]}, ...]}，主键在前。
     * 与 {@code MySqlSession.EXTRA_UNIQUE_KEYS} <b>刻意同名</b>，由单测钉住——两边互不 import，
     * 连接器实现不该依赖语义层，语义层也不该依赖某一个连接器实现。
     */
    public static final String DETAIL_UNIQUE_KEYS = "unique_keys";

    // ================================================================ 设计给定的参数

    /**
     * 主键判定：样本内 {@code count_distinct >= 0.95 * count} 即认为这一列「基本唯一」。
     *
     * <p>不取 1.0 是因为样本里混进几条脏数据（补数留下的重复、历史迁移的残留）非常常见，
     * 而卡死 1.0 会让一张真正的主键表因为 3 条脏行被判成非唯一，
     * 于是这条关系从 {@code N:1} 掉进 {@code N:N}、从「可自动 join」掉进「只标注」。
     */
    static final double UNIQUE_RATIO = 0.95;

    /** 包含率 &gt;= 0.9 → 确认。 */
    static final double TH_CONFIRMED = 0.9;

    /** 包含率 &gt;= 0.5 → 弱支持（注入时会让模型自己先核一次）。低于它才是「数据不支持」。 */
    static final double TH_WEAK = 0.5;

    /**
     * 左侧非空样本少于这个数就拒绝下结论。
     *
     * <p>★ 它和 {@link #TH_WEAK} 是<b>两件事</b>：低于 0.5 是「查了，数据说不对」；
     * 样本不足 10 是「这张表太空，查了等于没查」。合并成一个判定会把后者写成 REJECTED，
     * 那是拿一张空表去删一条可能完全正确的关系。
     */
    static final int MIN_SAMPLE = 10;

    /**
     * 标识符白名单，与 {@code MySqlSession.IDENT_RE} <b>刻意同形</b>。
     *
     * <p>表名列名有两个来源：客户的库（{@code information_schema}）和<b>模型的输出</b>。
     * 后者尤其不能当可信输入——S2 让模型自由生成 {@code to_object} / {@code to_column}，
     * 一段带反引号的字符串拼进 SQL 就是注入。所以这里是<b>双保险</b>：
     * 先按白名单验一次形状，再对反引号做转义；两者都过不去的一律不探查。
     *
     * <p>快照里的名字本身已经过 {@code MySqlSession.describe} 的同一道白名单，
     * 所以这一道不会把任何本来能用的表拦在外面。
     */
    private static final Pattern IDENT_RE = Pattern.compile("^[A-Za-z0-9_$]{1,64}$");

    /** 探查结果永远只有 1 行（全是聚合），行数与字节上限给一点余量即可。 */
    private static final int PROBE_MAX_ROWS = 10;
    private static final int PROBE_MAX_BYTES = 4096;

    /** 侧写（判唯一性）用的采样行数。它只回答「这一列在样本里重不重复」，不需要很大。 */
    private static final int PROFILE_SAMPLE_ROWS = 1000;

    /**
     * 多态外键分组探查最多带回多少组。真判别列的取值只有几个；几十组说明这一列多半不是判别列，
     * 多带回来的每一组都是一个多离开客户库的真实取值，而它们换不来任何判定。
     */
    static final int POLY_MAX_GROUPS = 20;

    /** 分组探查的结果带着判别值，类名形状的取值（{@code App\Models\Post}）比纯计数长，字节上限给宽一点。 */
    private static final int POLY_MAX_BYTES = 16 * 1024;

    /** 判别值超过这个长度就不记：那不像类型码，更像一段自由文本，记下来只是多存一份客户数据。 */
    static final int POLY_MAX_VALUE_LENGTH = 128;

    /**
     * 运维急停关着时，第 3 档不发分组探查的那半句话。接在「但」后面、也接在 care_reason 里，两处都要读得通。
     *
     * <p>★ <b>不写配置键名</b>。这半句会进 {@code care_reason} 与 {@code verify_note}，两者都原样进模型上下文：
     * 模型拿一个内部配置名什么也做不了，只会把实现细节复述给客户，或者照着它去「建议打开开关」。
     * 配置键只进日志（见 {@link #decide}），那是运维排查时真正会看的地方。
     */
    static final String VALUES_SWITCHED_OFF = "平台侧已暂停读取真实取值";

    /**
     * 判别列名 = FK 列去掉 id 之后的前缀 + 这些后缀之一（比较时忽略大小写与下划线）。
     * {@code res_model} 是 Odoo 的写法，{@code *_table} 常见于审计 / 日志表。
     */
    private static final List<String> DISCRIMINATOR_SUFFIXES = List.of("type", "kind", "class", "table", "model");

    /** 这些类型的列不可能拿来区分目标表：时间、小数、大对象、JSON、空间类型。 */
    private static final Set<String> NON_DISCRIMINATOR_TYPES = Set.of(
            "date", "datetime", "timestamp", "time", "year",
            "float", "double", "real", "decimal", "numeric",
            "json", "tinyblob", "blob", "mediumblob", "longblob", "binary", "varbinary",
            "geometry", "point", "linestring", "polygon", "multipoint", "multilinestring",
            "multipolygon", "geometrycollection");

    /**
     * 缓存键分隔符。取一个不可能出现在标识符里的控制字符（白名单只允许字母、数字、下划线和 $），
     * 否则 {@code ab} 拼 {@code c} 与 {@code a} 拼 {@code bc} 会得到同一个键——
     * 那种撞键不会报错，只会让一条关系悄悄拿到另一条的结论。
     */
    private static final String KEY_SEP = "\u0001";

    // ================================================================ 依赖与配置

    private final ConnectorGateway gateway;
    private final ConnectionMapper connectionMapper;
    /**
     * 第 3 档判别值的两道 PII 闸（列名一道、取值一道）。{@code PiiFilter} 没有任何依赖，注入它不会成环。
     *
     * <p>放在最后：{@code @RequiredArgsConstructor} 按声明顺序生成构造器，插在中间会让按位置构造本类的测试静默错位。
     */
    private final PiiFilter piiFilter;

    @Value("${connector.semantic.join-probe-enabled:true}")
    boolean configuredEnabled;

    /**
     * 单条探查的超时，<b>刻意比查询默认的 15 秒短得多</b>。
     *
     * <p>5 秒和 {@code MySqlSession.ESTIMATE_TIMEOUT_SEC} 取的是同一个理由：这是平台自己发起的、
     * 可有可无的剖析，它产出的是给模型看的参考信息，不是任何人在等的答案。
     * 一条卡住的聚合会占着客户库的连接和我们的并发闸——宁可放弃这一条判成「判不出来」，
     * 也不要拖住那条连接。
     */
    @Value("${connector.semantic.join-probe-timeout-seconds:5}")
    int configuredTimeoutSeconds;

    /** 每条候选左侧采多少个去重后的非空取值。设计给的数量级是 1000。 */
    @Value("${connector.semantic.join-probe-sample-values:1000}")
    int configuredSampleValues;

    /** 自己的节流：每分钟最多打这么多条探查。 */
    @Value("${connector.semantic.join-probes-per-minute:30}")
    int configuredProbesPerMinute;

    /**
     * 一轮剖析的<b>总采样行数</b>预算。
     *
     * <p>10^6 是设计给的收敛点：join score 在约 100 万行之后就不再变化，再加样本没有任何收益，
     * 只剩客户那一侧的成本。所以它不是「最多允许查这么多」的安全阀，而是
     * <b>「超过这里继续查是纯浪费」</b>的事实；用完就停，剩下的候选如实标成未验证。
     */
    @Value("${connector.semantic.join-probe-row-budget:1000000}")
    long configuredRowBudget;

    /**
     * 一轮最多验多少条候选。
     *
     * <p>与 {@link #configuredRowBudget} 不重复：预算管的是客户库被扫了多少行，这个管的是
     * <b>这轮要跑多久</b>。按 30 次/分钟节流，200 条候选（最多 3 次探查一条）已经是十几分钟，
     * 再多就不该在一次推导里同步做完了。超出的部分如实标成「没轮到」。
     */
    @Value("${connector.semantic.join-probe-max-candidates:200}")
    int configuredMaxCandidates;

    /**
     * 读真实取值的运维急停，与 {@code SemanticValueProfiler} 读的是<b>同一个键</b>。
     *
     * <p>第 3 档的多态外键分组探查带回的每一行都是一个真实判别值，和值域剖析是同一类出库。运维关掉这个开关，
     * 表达的是「平台现在一个真实取值都不许读」，不是「值域剖析这一个功能先停一下」——只有值域剖析听它、这里不听，
     * 急停就漏了一个口子，而且没有任何报错会提醒。关掉时退回第 2 档的做法（不分条件的包含率，只能判不出来），不读任何取值。
     * 它不管不分条件的包含率探查：那是派生统计，由 {@code join-probe-enabled} 管。
     */
    @Value("${connector.semantic.value-profile-enabled:true}")
    boolean configuredValueProfileEnabled = true;

    // 上面那组是配置原值、下面这组是夹紧之后真正生效的值。两组都是包级可见，这是有意的：
    // 单测要把节流调快（不然一条用例光睡觉就要两秒），也要能钉住夹紧本身的行为。
    // 写成 private 再配一组 setXxxForTest，只是同一件事多一层壳。
    boolean enabled = true;
    int timeoutSeconds = 5;
    int sampleValues = 1000;
    int probesPerMinute = 30;
    long rowBudget = 1_000_000L;
    int maxCandidates = 200;
    boolean valueProfileEnabled = true;

    /**
     * 配置只在启动时校验一次并夹紧，<b>夹紧而不是启动失败</b>——与 {@link MetricRewriter} 同一条纪律：
     * 采样验证是叠加增强，用一个配置笔误去否决整个服务是错的。
     *
     * <p><b>但夹紧必须留下日志。</b>静默地把 0 当成「不节流」，表现是某天一次推导把客户的库打满，
     * 而配置文件上写着一个看起来很克制的数字。
     */
    @PostConstruct
    void resolveLimits() {
        enabled = configuredEnabled;
        valueProfileEnabled = configuredValueProfileEnabled;
        timeoutSeconds = clamp("join-probe-timeout-seconds", configuredTimeoutSeconds, 1, 30);
        sampleValues = clamp("join-probe-sample-values", configuredSampleValues, MIN_SAMPLE, 100_000);
        // 下限是 1 而不是 0：节流不允许被关掉。配成 0 的人想表达的多半是「不限」，
        // 而「不限」在这里等于允许一轮推导把客户的生产库当成压测目标。
        probesPerMinute = clamp("join-probes-per-minute", configuredProbesPerMinute, 1, 600);
        maxCandidates = clamp("join-probe-max-candidates", configuredMaxCandidates, 1, 2000);
        rowBudget = configuredRowBudget > 0 ? configuredRowBudget : 1_000_000L;
        if (rowBudget != configuredRowBudget) {
            log.warn("connector.semantic.join-probe-row-budget={} 不是正数，本次按 {} 生效",
                    configuredRowBudget, rowBudget);
        }
    }

    private int clamp(String key, int v, int min, int max) {
        int out = Math.min(Math.max(v, min), max);
        if (out != v) {
            log.warn("connector.semantic.{}={} 超出允许区间 [{},{}]，本次按 {} 生效", key, v, min, max, out);
        }
        return out;
    }

    // ================================================================ 对外形状

    /** S2 推出来的一条待验关系：{@code fromObject.fromColumn -> toObject.toColumn}。 */
    @Data
    @Builder
    public static class JoinCandidate {
        private String fromObject;
        private String fromColumn;
        private String toObject;
        private String toColumn;
        /** 模型自己标的基数，可为空。<b>只作参考，最终以实测为准。</b> */
        private String declaredCardinality;

        public static JoinCandidate of(String fromObject, String fromColumn, String toObject, String toColumn) {
            return JoinCandidate.builder()
                    .fromObject(fromObject).fromColumn(fromColumn)
                    .toObject(toObject).toColumn(toColumn)
                    .build();
        }

        String key() {
            return fromObject + KEY_SEP + fromColumn + KEY_SEP + toObject + KEY_SEP + toColumn;
        }
    }

    /**
     * 一条关系的结论。
     *
     * <p>不是 record：本仓库实际生效的 jackson-databind 是 2.11.1，record 序列化要 2.12+。
     * 这个对象最终会被接线方摊平进 {@code detail_json}（见 {@link #detailPatch()}），
     * 写成 record 会在某次「顺手直接序列化一下」时静默失败。
     */
    @Data
    @Builder
    public static class JoinVerdict {
        private String fromObject;
        private String fromColumn;
        private String toObject;
        private String toColumn;

        /** {@code ConnectorSemanticService.V_*}。 */
        private String verified;

        /** 实测基数，判不出来时为空。<b>不会照抄模型自己标的那个。</b> */
        private String cardinality;

        /**
         * 允不允许被自动接进 join path。
         *
         * <p>只有<b>右侧唯一</b>（{@code 1:1} / {@code N:1}）才为 true。
         * 右侧不唯一意味着 join 会放大行数（MetricFlow 的 fan-out 禁止表：
         * primary×foreign / unique×foreign / foreign×foreign 全禁），
         * 一次 fan-out 会让 SUM 出来的金额凭空变大而不报任何错。
         * {@code N:N} 一律只标注、不自动 join。
         *
         * <p><b>跳数限制（最多 2 跳）不在这里</b>，那是拼 join path 那一层的事：
         * 本类只认识单条边，看不见路径。
         */
        private boolean autoJoinable;

        /** 左侧采到的去重非空取值数。没探查过时为空。 */
        private Integer sampleN;
        /** 其中在右侧命中的个数。 */
        private Integer matchN;
        /** {@code matchN / sampleN}。 */
        private Double containment;

        /** 给模型看的一句话，形如「采样 1000 个取值，命中 970（包含率 97.0%）」。 */
        private String basis;

        /** 判不出来 / 被拒 / 没探查的具体原因。<b>已脱敏</b>，不含任何 JDBC 原文。 */
        private String reason;

        /** 有没有真的打到客户的库上。用来把「没查」和「查了」分开。 */
        private boolean probed;

        /**
         * {@link #KIND_SIMPLE} / {@link #KIND_POLYMORPHIC} / {@link #KIND_COMPOSITE}。
         * 结构判定只读快照与唯一键、不碰客户库，所以<b>没探查的那一条也有</b>；
         * 为空只出现在标识符对不上快照、或连接归属都没确认的那几种情况，以及不是本类造出来的结论上。
         */
        private String joinKind;

        /** 多态外键的判别列（左表上的列名）。只在 {@link #KIND_POLYMORPHIC} 时有。 */
        private String discriminatorColumn;

        /**
         * 让条件包含率达到确认线的那个判别值。<b>只在第 3 档、且过了 PII 过滤时才有</b>——
         * 它是一个真实取值，其余任何情况下都不许出现。
         */
        private String discriminatorValue;

        /** 组合键的完整列（目标表上的列名，按索引顺序）。只在 {@link #KIND_COMPOSITE} 时有。 */
        private List<String> compositeColumns;

        /** 一句中文：这条关系为什么要当心、要带什么条件。{@link #KIND_SIMPLE} 时为空。 */
        private String careReason;

        /**
         * ★ 第 3 档那条按判别值分组的探查<b>真的发出去、并带着结果回来了</b>（契约 K-2）。
         *
         * <p>只有这时，这条结论里「记了哪个判别值 / 没记判别值」才出自一次真实的分组测量，下游才能据此认定「这一轮读过取值了」。
         * 以下一律 {@code false}：不是多态外键；档位不到第 3 档；档位允许但没发（运维急停、判别列名没过 PII、标识符对不上）；
         * 发了没回来（超时、权限、限流、连接故障、被中断）。
         * <b>拿「档位允许」代替它是错的</b>：一次超时会被记成「探过了、没有判别值」，这条关系从此不再被重探，而且不报错。
         *
         * <p>结果回来却解析不了时仍是 {@code true}：取值已经离开客户库，重探只会把同一段解析不了的结果再读一遍。
         *
         * <p><b>不进 {@link #detailPatch()} / {@link #structuralPatch()}</b>：它说的是这一轮发生了什么，不是这条关系本身的属性。
         */
        private boolean groupedProbeRan;

        /**
         * 摊平成能直接并进 {@code ConnectorSemantic.detail_json} 的形状。
         *
         * <p>键名用下划线，与 S2 写进去的 {@code to_object} / {@code to_column} / {@code cardinality}
         * 一致；{@code basis} 覆盖 S2 写死的那句「未经数据验证」。
         *
         * <p>注入层（{@code ConnectorToolExecutor.joinBasis}）是按 {@code evidence + verified}
         * 两列自己拼一句话给模型的，本方法产出的 {@code basis} 是给<b>人</b>和管理台看的那一份细账
         * ——两者措辞不同是有意的：一个回答「凭什么这么说」，一个回答「具体量了多少」。
         */
        public Map<String, Object> detailPatch() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("to_object", toObject);
            out.put("to_column", toColumn);
            if (cardinality != null) {
                out.put("cardinality", cardinality);
            }
            out.put("auto_joinable", autoJoinable);
            out.put("basis", basis);
            if (sampleN != null) {
                out.put("sample_n", sampleN);
            }
            if (matchN != null) {
                out.put("match_n", matchN);
            }
            if (containment != null) {
                // 留四位小数：detail_json 是给人看的，0.9700000000000001 只会让人以为哪里算错了。
                out.put("containment", Math.round(containment * 10000d) / 10000d);
            }
            if (reason != null) {
                out.put("verify_note", reason);
            }
            out.putAll(structuralPatch());
            return out;
        }

        /**
         * 只含结构判定的那几个键：{@code join_kind} / {@code discriminator_column} / {@code discriminator_value} /
         * {@code composite_columns} / {@code care_reason}。{@link #detailPatch()} 已经包含它们。
         *
         * <p>单独拆出来是给<b>没探查</b>（{@code V_NONE}）的结论用的。接线方对 V_NONE 一个字都不写，理由成立：
         * {@link #detailPatch()} 会把 {@code basis} 覆盖成「未经数据验证」、抹掉 S1 留下的来源线索。
         * 但结构判定是第 1 档就能下的结论，与探没探查无关——第 1 档连接上的多态外键要是也跟着不写，
         * 它会以普通关系的身份继续进 {@code joins}。只并这几个键不碰 {@code basis}，两件事都保得住。
         */
        public Map<String, Object> structuralPatch() {
            Map<String, Object> out = new LinkedHashMap<>();
            if (joinKind == null) {
                return out;
            }
            out.put("join_kind", joinKind);
            if (discriminatorColumn != null) {
                out.put("discriminator_column", discriminatorColumn);
            }
            if (discriminatorValue != null) {
                out.put("discriminator_value", discriminatorValue);
            }
            if (compositeColumns != null && !compositeColumns.isEmpty()) {
                out.put("composite_columns", List.copyOf(compositeColumns));
            }
            if (careReason != null) {
                out.put("care_reason", careReason);
            }
            return out;
        }
    }

    /** 一轮采样验证的结果。 */
    @Data
    @Builder
    public static class JoinValidationResult {
        /** {@code OUT_*}。<b>{@link SemanticJoinValidator#OUT_TIER_BLOCKED} 与「跑了但没发现问题」不是一回事。</b> */
        private String outcome;
        /** 一句人话，解释这一轮到底发生了什么。管理台直接显示。 */
        private String note;
        /** 每条候选一个结论，<b>顺序与入参一致，一条不少</b>——包括没轮到探查的那些。 */
        private List<JoinVerdict> verdicts;
        /**
         * 本轮发出的探查次数（<b>含失败的那些</b>）。用来回答「这次剖析在客户那边留下了多少痕迹」——
         * 失败的那几条同样在客户的数据库审计里留了痕，所以要一起算。
         */
        private int probeCount;
        /** 本轮消耗的采样行数（对照 {@code join-probe-row-budget}）。 */
        private long sampledRows;

        public boolean ran() {
            return OUT_RAN.equals(outcome);
        }
    }

    /**
     * 每决完一条回调一次。
     *
     * <p>存在的两个理由都和「这活儿随时可能半路停」有关：
     * <ul>
     *   <li><b>可续跑</b>：接线方可以一条一条落库。推导被部署重启打断时，
     *       已经花掉的客户资源不会白花，重跑时把已决的挑掉即可。</li>
     *   <li><b>可中止</b>：返回 {@code false} 就停。探查花的是客户的钱，
     *       「上层已经不要这个结果了」必须能立刻传达到这里。</li>
     * </ul>
     */
    @FunctionalInterface
    public interface ProbeProgress {
        boolean onDecided(JoinVerdict verdict, int decided, int total);
    }

    // ================================================================ 入口

    public JoinValidationResult validate(Long connectorId, List<JoinCandidate> candidates,
                                         Map<String, Map<String, FieldDetail>> snapshot) {
        return validate(connectorId, candidates, snapshot, null, null);
    }

    /**
     * 不带唯一键的旧入口：认不出组合键（{@link #KIND_COMPOSITE}），其余与五参版本完全一致。
     *
     * <p>留着不删，是因为接线方今天调的就是它。换成五参版本、把 {@link #uniqueKeysByObject(List)}
     * 的结果传进来之前，组合键判定在线上是<b>不生效</b>的——关系按 {@link #KIND_SIMPLE} 处理，与引入它之前一样。
     */
    public JoinValidationResult validate(Long connectorId, List<JoinCandidate> candidates,
                                         Map<String, Map<String, FieldDetail>> snapshot,
                                         ProbeProgress progress) {
        return validate(connectorId, candidates, snapshot, null, progress);
    }

    /**
     * 对一批候选做采样验证。
     *
     * <p><b>本方法不抛业务异常</b>：语义层是叠加的注解，用一次剖析失败去否决一次正常的推导是错的。
     * 出了什么事全部如实写进 {@code outcome} / {@code note} / 每条的 {@code reason}。
     *
     * @param connectorId        连接 id。档位与租户归属都由本方法自己查，<b>不信调用方转述</b>
     * @param candidates         S2 推出来的候选，顺序保留
     * @param snapshot           结构快照：表名 → 列名 → {@link FieldDetail}。
     *                           <b>它是标识符的唯一合法来源</b>：对不上快照的一律不探查，
     *                           而不是拼进 SQL 让客户的库去报错
     * @param uniqueKeysByObject 表名 → 这张表的唯一键（含主键；每个键是一组列名，主键在前），
     *                           通常由 {@link #uniqueKeysByObject(List)} 从<b>同一份</b>快照解出来。
     *                           {@code null} 或缺某张表 = <b>不知道</b>，不是「没有唯一键」：此时认不出组合键，
     *                           关系按 {@link #KIND_SIMPLE} 处理——与引入组合键判定之前一样，不会更糟
     * @param progress           每决完一条的回调，可为 {@code null}；返回 {@code false} 即中止
     */
    public JoinValidationResult validate(Long connectorId, List<JoinCandidate> candidates,
                                         Map<String, Map<String, FieldDetail>> snapshot,
                                         Map<String, List<List<String>>> uniqueKeysByObject,
                                         ProbeProgress progress) {
        List<JoinCandidate> input = candidates == null ? List.<JoinCandidate>of() : candidates.stream()
                .filter(c -> c != null && c.getFromObject() != null && c.getFromColumn() != null
                        && c.getToObject() != null && c.getToColumn() != null)
                .toList();
        if (input.isEmpty()) {
            return done(OUT_NOTHING_TO_DO, "没有待验证的表关系。", List.of(), new RunState());
        }
        Map<String, Map<String, FieldDetail>> snap = snapshot == null ? Map.of() : snapshot;

        if (!enabled) {
            // 开关只管「去不去碰客户的库」。结构判定不碰库，照样给。
            return blocked(input, OUT_DISABLED,
                    "采样验证已被配置关闭（connector.semantic.join-probe-enabled=false），本次没有做任何验证。",
                    "采样验证未启用，本条未经数据验证", snap, uniqueKeysByObject);
        }

        // ---- 租户上下文。网关那边同样会拒，但那句话是给模型看的通用文案，这里要说清楚是谁的责任 ----
        if (!TenantContext.isSet()) {
            log.error("采样验证跑在没有租户上下文的线程上 connectorId={}（后台任务漏了 TenantContext.set？）",
                    connectorId);
            return blocked(input, OUT_ABORTED,
                    "当前线程没有租户上下文，无法访问客户系统，本次没有做任何验证。",
                    "缺少租户上下文，本条未经数据验证", null, null);
        }

        Connection row = connectorId == null ? null : connectionMapper.selectById(connectorId);
        if (row == null || !TenantContext.get().equals(row.getTenantId())) {
            // 与网关同一条取舍：「不存在」与「不属于你」对外同形。连这条连接是谁的都没确认，结构判定也不给。
            log.warn("采样验证找不到连接，或它不属于当前租户 connectorId={}", connectorId);
            return blocked(input, OUT_ABORTED,
                    "连接不存在，或它不属于当前租户，本次没有做任何验证。",
                    "连接不可用，本条未经数据验证", null, null);
        }

        // ---- ★ 档位闸。必须在打出任何一条 SQL 之前 ----
        SemanticDataTier tier = SemanticDataTier.parse(row.getSemanticDataTier());
        if (!tier.allowsDerivedStats()) {
            // 第 1 档允许的正是「表名、列名、索引」——结构判定恰好只用这些，所以照样给。
            return blocked(input, OUT_TIER_BLOCKED,
                    "这条连接的数据出库档位是「" + tier.label() + "」，未启用派生统计。"
                            + "包含率、基数、distinct 数都属于派生统计，所以本次【没有】做任何采样验证——"
                            + "这不代表这些表关系有问题，只代表没人验过它们。"
                            + "需要验证请由企业超管把档位调到「" + SemanticDataTier.DERIVED_STATS.label() + "」。",
                    "未启用派生统计（" + tier.label() + "），本条未做采样验证", snap, uniqueKeysByObject);
        }

        return run(connectorId, input, snap, uniqueKeysByObject, tier, progress);
    }

    // ================================================================ 主循环

    private JoinValidationResult run(Long connectorId, List<JoinCandidate> input,
                                     Map<String, Map<String, FieldDetail>> snapshot,
                                     Map<String, List<List<String>>> uniqueKeysByObject,
                                     SemanticDataTier tier, ProbeProgress progress) {
        RunState run = new RunState();
        List<JoinVerdict> out = new ArrayList<>(input.size());
        Map<String, JoinVerdict> decidedByKey = new LinkedHashMap<>();
        String abortNote = null;
        int total = input.size();

        for (int i = 0; i < total; i++) {
            JoinCandidate c = input.get(i);
            // 结构判定是本地的，只读快照与唯一键。没轮到探查的那条也照样带上，理由见 JoinVerdict#structuralPatch。
            // 标识符对不上快照时返回 null：名字都对不上，按名字下的任何判定都没有意义。
            Structure st = structure(c, snapshot, uniqueKeysByObject);

            if (abortNote != null) {
                out.add(notProbed(c, abortNote, st, snapshot));
                continue;
            }

            // 同一条关系被 S2 吐出来两次是真实存在的（模型自由生成，去重靠的是别处的唯一键）。
            // 探查一次就够了——重复探查花的是客户的钱。
            JoinVerdict same = decidedByKey.get(c.key());
            if (same != null) {
                out.add(same);
                continue;
            }

            if (decidedByKey.size() >= maxCandidates) {
                out.add(notProbed(c, "本轮候选数超过上限 " + maxCandidates + " 条，这一条没轮到验证", st, snapshot));
                continue;
            }
            if (run.sampledRows >= rowBudget) {
                // 预算用尽不是失败：join score 在 10^6 行后就收敛了，再采没有收益。
                abortNote = "本轮采样预算（" + rowBudget + " 行）已用尽，这一条没轮到验证";
                out.add(notProbed(c, abortNote, st, snapshot));
                continue;
            }
            if (Thread.currentThread().isInterrupted()) {
                abortNote = "本轮推导被中断，这一条没轮到验证";
                out.add(notProbed(c, abortNote, st, snapshot));
                continue;
            }

            String bad = checkIdentifiers(c, snapshot);
            if (bad != null) {
                // 不探查，也不判错：快照对不上多半是结构刷新与推导错开了一次，不是关系本身有问题。
                out.add(notProbed(c, bad, null, snapshot));
                continue;
            }

            JoinVerdict v = decide(connectorId, c, snapshot, st, tier, run);
            decidedByKey.put(c.key(), v);
            out.add(v);

            if (run.fatal != null) {
                abortNote = run.fatal;
            }
            if (progress != null && !progress.onDecided(v, i + 1, total)) {
                abortNote = "上层已中止本轮验证，这一条没轮到";
            }
        }

        if (abortNote != null) {
            return done(OUT_ABORTED, "采样验证中途停止：" + abortNote + "。已经得出的结论仍然有效。", out, run);
        }
        return done(OUT_RAN, "已对 " + decidedByKey.size() + " 条表关系做采样验证，共 "
                + run.probeCount + " 次探查。", out, run);
    }

    /**
     * 一条候选的完整判定：先包含率（决定性的那一步），必要时再补两次侧写定基数。
     *
     * <p>多态外键与组合键在这条主线上各有一处分叉，理由见类注释「两类数据对得上、直接 join 仍然是错数的关系」：
     * <ul>
     *   <li>多态外键 + 第 3 档：改走按判别值分组的探查（{@link #decidePolymorphicByGroups}），不打不分条件的那一条；</li>
     *   <li>多态外键 + 第 1/2 档（或判别列名没过 PII）：照打不分条件的包含率，但结论只能是 UNDECIDABLE；</li>
     *   <li>组合键：包含率照测（单列对得上是必要条件，对不上照样 REJECTED），但不打唯一性侧写、不自动 join。</li>
     * </ul>
     */
    private JoinVerdict decide(Long connectorId, JoinCandidate c, Map<String, Map<String, FieldDetail>> snapshot,
                               Structure st, SemanticDataTier tier, RunState run) {
        JoinVerdict.JoinVerdictBuilder b = JoinVerdict.builder()
                .fromObject(c.getFromObject()).fromColumn(c.getFromColumn())
                .toObject(c.getToObject()).toColumn(c.getToColumn())
                .autoJoinable(false);
        applyStructure(b, c, st, snapshot);
        boolean polymorphic = st != null && st.polymorphic();

        // 只有第 3 档才看判别值。运维急停在最前面；判别列名再过 PII。任一道过不了就不查它的取值，退回第 2 档的做法。
        String groupBlocked = null;
        if (polymorphic && tier.allowsSampleValues()) {
            if (valueProfileEnabled) {
                groupBlocked = discriminatorBlocked(c, st, snapshot);
            } else {
                // 配置键只写在这里：给模型的那半句（VALUES_SWITCHED_OFF）刻意不带它，理由见那个常量。
                log.debug("读取真实取值的运维急停已打开（connector.semantic.value-profile-enabled=false），"
                        + "多态外键不发分组探查 connectorId={} {}.{} -> {}.{}", connectorId,
                        c.getFromObject(), c.getFromColumn(), c.getToObject(), c.getToColumn());
                groupBlocked = VALUES_SWITCHED_OFF;
            }
            if (groupBlocked == null) {
                return decidePolymorphicByGroups(connectorId, c, snapshot, st, b, run);
            }
            b.careReason(polymorphicCare(c, st, null, targetColumns(snapshot, c))
                    + groupBlocked + "，平台没有按它的取值分组探查。");
        }

        FieldDetail leftField = field(snapshot, c.getFromObject(), c.getFromColumn());
        String sql = containmentSql(c, isTextLike(leftField));

        QueryResult qr;
        try {
            qr = probe(connectorId, sql, run);
        } catch (ProbeFailure f) {
            return b.probed(f.probed).verified(f.verified).basis(f.basis).reason(f.reason).build();
        }

        Long sampleN = column(qr, "sample_n", 0);
        Long matchN = column(qr, "match_n", 1);
        if (sampleN == null || matchN == null) {
            log.warn("采样探查返回了看不懂的结果 connectorId={} columns={}", connectorId, qr.columns());
            return b.probed(true)
                    .verified(ConnectorSemanticService.V_UNDECIDABLE)
                    .basis("采样探查没有返回可解析的计数，判不出来（不代表这条关系是错的）")
                    .reason("探查结果无法解析")
                    .build();
        }
        run.sampledRows += sampleN;

        int sn = (int) Math.min(sampleN, Integer.MAX_VALUE);
        int mn = (int) Math.min(matchN, Integer.MAX_VALUE);
        b.probed(true).sampleN(sn).matchN(mn);

        // ★ 样本不足优先于包含率判定：0/0 不是 0%。
        if (sn < MIN_SAMPLE) {
            return b.verified(ConnectorSemanticService.V_UNDECIDABLE)
                    .basis("左侧只采到 " + sn + " 个非空取值（少于 " + MIN_SAMPLE + " 个），判不出来")
                    .reason("这张表几乎是空的，样本不足以判定——这不等于这条关系是错的")
                    .build();
        }

        double containment = (double) mn / (double) sn;
        String measured = "采样 " + sn + " 个取值，命中 " + mn + "（包含率 " + pct(containment) + "）";

        if (polymorphic) {
            // ★ 不分条件的包含率判不了多态外键，两个方向都会错：被别的类型的行拉低（按它 REJECTED 就是删掉一条正确的关系），
            //   或被几张目标表重叠的自增 id 抬高（按它 CONFIRMED 就是把一条静默出错数的 join 放进 joins）。
            //   所以既不给 REJECTED 也不给 CONFIRMED；这个数也不写进 containment，免得被当成这条关系本身的包含率。
            String disc = st.discriminatorColumn();
            return b.verified(ConnectorSemanticService.V_UNDECIDABLE)
                    .basis(measured + "——疑似多态外键，不分 " + disc + " 条件的包含率既可能被别的类型的行拉低，"
                            + "也可能被几张目标表重叠的自增 id 抬高，判不出来（不代表这条关系是错的）")
                    .reason(groupBlocked != null
                            ? "需要按 " + disc + " 的取值分条件验证，但" + groupBlocked
                            : "需要按 " + disc + " 的取值分条件验证，那要读取真实取值（第 3 档），这条连接当前是「"
                            + tier.label() + "」")
                    .build();
        }
        b.containment(containment);

        if (containment < TH_WEAK) {
            // 唯一会「否定」一条关系的分支，且只可能来自一次真实的测量。
            return b.verified(ConnectorSemanticService.V_REJECTED)
                    .basis(measured + "——数据不支持这条关系")
                    .reason("左侧过半取值在右侧找不到，按错误关系处理")
                    .build();
        }

        String verified = containment >= TH_CONFIRMED
                ? ConnectorSemanticService.V_CONFIRMED
                : ConnectorSemanticService.V_WEAK;

        if (st != null && st.composite()) {
            // ★ 不打唯一性侧写：索引已经说了目标列单独不唯一，前 1000 行的样本在这件事上只可能说错（见类注释）。
            //   基数留空而不是写 N:N——右侧确实没测，编一个出来比留空危险。
            String basis = measured + "。目标列只是组合键 (" + String.join(", ", st.compositeColumns())
                    + ") 的一部分，单独不唯一，只标注、不自动 join";
            if (ConnectorSemanticService.V_WEAK.equals(verified)) {
                basis = basis + "。包含率未达 " + pct(TH_CONFIRMED) + "，用之前先自己核一次";
            }
            return b.verified(verified).basis(basis).build();
        }

        // 基数只在关系站得住的时候才去测：被否掉的关系没人会拿去 join，
        // 为它再打两条 SQL 是白花客户的资源。
        Boolean leftUnique = uniqueness(connectorId, c.getFromObject(), c.getFromColumn(), run);
        Boolean rightUnique = uniqueness(connectorId, c.getToObject(), c.getToColumn(), run);
        String card = cardinality(leftUnique, rightUnique);
        boolean autoJoinable = Boolean.TRUE.equals(rightUnique);

        String basis = measured;
        if (card != null) {
            basis = basis + "；实测基数 " + card;
        }
        if (!autoJoinable) {
            basis = basis + "。" + (CARD_MANY_MANY.equals(card)
                    ? "两侧都不唯一（N:N），只标注、不自动 join：一次 fan-out 会让聚合出来的金额凭空变大而不报错"
                    : "右侧不是唯一键，只标注、不自动 join（join 会放大行数）");
        }
        if (ConnectorSemanticService.V_WEAK.equals(verified)) {
            basis = basis + "。包含率未达 " + pct(TH_CONFIRMED) + "，用之前先自己核一次";
        }
        return b.verified(verified).cardinality(card).autoJoinable(autoJoinable).basis(basis).build();
    }

    // ================================================================ 探查

    /**
     * ★ <b>一次探查 = 一次 {@code executeAsPlatform}</b>。
     *
     * <p>绝不能把整批包在一个调用里：那个调用会<b>全程占着</b>该实例的一个并发许可
     * （默认总共 2 个），几百条探查跑完之前，客户的模型每问一句数都会撞上「并发已达上限」。
     * 一次一调用，许可在两次探查之间归还，客户的 Agent 最多多等一下。
     *
     * <p>节流也在这里：{@link #probesPerMinute} 是我们自己给自己定的速度，远低于网关那个
     * 管理面速率桶——限流回来是一个失败，而我们不希望「跑得太快」变成「判不出来」。
     */
    private QueryResult probe(Long connectorId, String sql, RunState run) throws ProbeFailure {
        return probe(connectorId, sql, run, ConnectorAuditService.OP_SEMANTIC_PROBE, PROBE_MAX_ROWS, PROBE_MAX_BYTES);
    }

    /**
     * 同上，审计动作名与结果上限由调用方指定。
     *
     * <p>只有一个调用方传的不是 {@code OP_SEMANTIC_PROBE}：第 3 档的多态外键分组探查。它的结果行里带着真实判别值，
     * 按 {@link ConnectorAuditService#OP_SEMANTIC_VALUES} 记账，客户的 DBA 才能只看动作名就分清
     * 「平台只数了数」和「平台读了我的取值」。节流、超时、一次一调用、失败归类，与所有探查共用这一条路。
     */
    private QueryResult probe(Long connectorId, String sql, RunState run, String auditOp,
                              int maxRows, int maxBytes) throws ProbeFailure {
        if (!pace(run)) {
            run.fatal = "本轮推导被中断";
            throw ProbeFailure.notProbed("本轮推导被中断，这一条没验");
        }
        try {
            run.probeCount++;
            return gateway.executeAsPlatform(connectorId, Capability.QUERY, auditOp,
                    session -> {
                        if (!(session instanceof QueryCapable q)) {
                            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                                    "这条连接不支持查询能力，无法做采样验证");
                        }
                        // ★ 超时是本方法自己的，不是 connector.query.timeout-seconds 那 15 秒。
                        return q.query(sql, new QueryOptions(maxRows, maxBytes, timeoutSeconds));
                    });
        } catch (ConnectorException e) {
            throw classify(e, run);
        } catch (RuntimeException e) {
            // 没归过类的失败。原始异常只进日志——它可能带着完整 SQL、主机名、连接参数，
            // 而这些结论最终会随 detail_json 一路流到模型上下文里。
            log.error("采样探查出现未归类异常 connectorId={}", connectorId, e);
            throw ProbeFailure.undecidable("采样探查失败，判不出来", "探查过程出错");
        }
    }

    /**
     * 把网关归一过的失败翻译成一个结论。
     *
     * <p><b>没有任何一条分支会翻成 {@code REJECTED}。</b>这是本类最要紧的一条不变式：
     * 失败与「关系是错的」在返回值上长得一模一样，而只有后者可以删掉一条关系。
     * 超时尤其如此——高基数聚合实测能跑到 24 秒，按 REJECTED 处理就是用一次慢查询
     * 静默抹掉一条正确的关系。
     */
    private ProbeFailure classify(ConnectorException e, RunState run) {
        ConnectorErrorCode code = e.getCode();
        switch (code) {
            case TIMEOUT:
                return ProbeFailure.undecidable(
                        "采样探查超时（上限 " + timeoutSeconds + " 秒），判不出来",
                        "探查超时，样本没取回来——这不等于这条关系是错的");
            case FORBIDDEN:
                return ProbeFailure.undecidable("采样探查被数据库拒绝（权限不足），判不出来",
                        "只读账号没有读这两张表的权限，无法验证");
            case NOT_FOUND:
                return ProbeFailure.undecidable("采样探查找不到表或列，判不出来",
                        "客户库里找不到这张表或这一列，结构快照可能已经过时");
            case GUARD_BLOCKED:
                // 这一条是我们自己的 bug：探查 SQL 是本类生成的，被自家护栏拦下说明生成器坏了。
                // 必须 error 级——它不影响客户，所以除了日志没有任何地方会暴露它。
                log.error("采样探查 SQL 被只读护栏拦下，这是本类的 SQL 生成器出了问题: {}", e.getSafeDetail());
                return ProbeFailure.undecidable("采样探查未能执行，判不出来", "平台内部原因，本条未能验证");
            case RATE_LIMITED:
                run.fatal = "触发了平台侧限流";
                return ProbeFailure.notProbed("触发限流，本轮剩下的没验");
            case UNREACHABLE:
            case AUTH_FAILED:
            case CONFIG_ERROR:
                // 整条连接级别的故障，继续往下打没有意义，只会给客户的库添堵。
                run.fatal = "连接当前不可用（" + code.title() + "）";
                return ProbeFailure.notProbed("连接当前不可用（" + code.title() + "），本条未验证");
            default:
                return ProbeFailure.undecidable("采样探查失败，判不出来", "探查未成功");
        }
    }

    /**
     * 一列的唯一性侧写。<b>按（表, 列）缓存</b>：几十条关系通常指向同一张主表的同一个主键，
     * 不缓存就是把同一条 SQL 往客户库上打几十遍。
     *
     * @return true/false；{@code null} 表示测不出来
     */
    private Boolean uniqueness(Long connectorId, String object, String column, RunState run) {
        String key = object + KEY_SEP + column;
        if (run.uniqueness.containsKey(key)) {
            return run.uniqueness.get(key);
        }
        Boolean result;
        try {
            QueryResult qr = probe(connectorId, profileSql(object, column), run);
            Long rows = column(qr, "row_n", 0);
            Long distinct = column(qr, "distinct_n", 1);
            if (rows == null || distinct == null || rows < MIN_SAMPLE) {
                result = null;
            } else {
                run.sampledRows += rows;
                // COUNT(DISTINCT) 天然忽略 NULL，所以 NULL 很多的列会被判成「不唯一」。
                // 这个偏向是对的：它把关系推向「只标注、不自动 join」那一边。
                result = distinct >= UNIQUE_RATIO * rows;
            }
        } catch (ProbeFailure f) {
            // 侧写失败只让基数变成「判不出来」，不该反过来推翻已经测准的包含率。
            result = null;
        }
        run.uniqueness.put(key, result);
        return result;
    }

    /**
     * 实测基数。
     *
     * <p>用实测覆盖模型自己标的那个是有意的：模型标 {@code N:1} 只是它<b>觉得</b>右边是主键，
     * 而这里刚刚数过。两者冲突时信数据。任一侧测不出来就整体留空——
     * 编一个基数出来比留空危险得多，下游会拿它去决定要不要自动 join。
     */
    static String cardinality(Boolean leftUnique, Boolean rightUnique) {
        if (leftUnique == null || rightUnique == null) {
            return null;
        }
        if (leftUnique && rightUnique) return CARD_ONE_ONE;
        if (!leftUnique && rightUnique) return CARD_MANY_ONE;
        if (leftUnique) return CARD_ONE_MANY;
        return CARD_MANY_MANY;
    }

    // ================================================================ SQL 生成

    /**
     * 包含率探查：左表采一批<b>去重后的非空取值</b>，看其中多少个在右表出现过。
     *
     * <h4>为什么按「取值」算而不是按「行」算</h4>
     * 按行算会被热点键带偏，而且两个方向都偏：一张表里 99% 的行都填着同一个合法外键时，
     * 剩下 1% 全是脏值也能算出 99% 的包含率；反过来，一个占了 99% 行的脏值（比如历史遗留的 0）
     * 会把一条完全正确的关系压到 1%。<b>去重之后，一个取值就是一票。</b>
     * 这也正是包含依赖（inclusion dependency）的标准定义。
     *
     * <h4>为什么用 {@code COUNT(DISTINCT CASE ... )} 而不是 {@code COUNT(DISTINCT 右列)}</h4>
     * 后者在大小写不敏感的排序规则下会把 {@code 'ABC'} 和 {@code 'abc'} 数成一个，
     * 于是命中数偏小、包含率偏低——<b>偏向「拒绝」</b>，而本类绝不能往那个方向出错。
     *
     * <h4>为什么右表不采样</h4>
     * 右表只要采样就可能「本来有、只是没采到」，那会直接制造假阴性，
     * 而假阴性在这里等于删掉一条正确的关系。右表要么全扫，要么这条判不出来（交给超时兜底）。
     *
     * <h4>为什么 LEFT JOIN 右表不会把结果放大</h4>
     * 右表有重复值时这条 JOIN 确实会出多行，但两个计数都是 {@code COUNT(DISTINCT 左侧取值)}，
     * 重复几次都只算一票。<b>换成 {@code COUNT(*)} 就会被 fan-out 撑爆</b>，
     * 而撑爆的方向是包含率虚高——正好是最不能错的那个方向。
     *
     * <h4>关于抽样偏倚，要说在前面</h4>
     * {@code LIMIT} 取的是物理顺序的前 N 个取值，不是随机样本（{@code ORDER BY RAND()}
     * 要全表排序，在客户的生产库上不可接受）。所以样本偏向「老数据」。
     * 阈值定在 0.9 而不是 0.99，留的就是这个偏倚的余量。
     *
     * <h4>清洗只做「去 NULL / 去空串」，<b>不做数值的 IQR 截尾</b></h4>
     * IQR 截尾是采集<b>列统计量</b>时的常规清洗（不让离群值把 min/max、均值带偏），
     * 但把它用在包含率的样本上是反的：悬空外键恰恰住在取值分布的尾部
     * （被清理过的历史 id、迁移时留下的越界值），截掉尾部会让包含率<b>虚高</b>，
     * 制造假阳性——与本类「P 优先于 R」的偏向正好相反。所以这里刻意不截。
     */
    String containmentSql(JoinCandidate c, boolean textLikeLeft) {
        String left = quote(c.getFromObject());
        String leftCol = quote(c.getFromColumn());
        String right = quote(c.getToObject());
        String rightCol = quote(c.getToColumn());
        StringBuilder where = new StringBuilder(leftCol).append(" IS NOT NULL");
        if (textLikeLeft) {
            // ★ 空串过滤只加在字符型列上。在数值列上写 `x <> ''`，MySQL 会把 '' 隐式转成 0，
            //   于是把 0 这个完全合法的取值整批剔掉——一次不报错、只会让包含率莫名其妙的数据错误。
            where.append(" AND ").append(leftCol).append(" <> ''");
        }
        return "SELECT COUNT(DISTINCT jm_s.jm_k) AS sample_n,"
                + " COUNT(DISTINCT CASE WHEN jm_r." + rightCol + " IS NULL THEN NULL ELSE jm_s.jm_k END) AS match_n"
                + " FROM (SELECT DISTINCT " + leftCol + " AS jm_k FROM " + left
                + " WHERE " + where + " LIMIT " + sampleValues + ") jm_s"
                + " LEFT JOIN " + right + " jm_r ON jm_r." + rightCol + " = jm_s.jm_k";
    }

    /**
     * 唯一性侧写：在前 {@value #PROFILE_SAMPLE_ROWS} 行里数一数这一列重不重复。
     *
     * <p>它<b>不是</b>全表的 {@code COUNT(*)}：那正是实测能跑到 24 秒的那种查询。
     * 样本内的聚簇（比如表按这个外键排过序）会让 distinct 偏低、从而把列判成「不唯一」——
     * 这个偏向同样是安全的那一边：不唯一 → 不自动 join。
     */
    String profileSql(String object, String column) {
        String t = quote(object);
        String col = quote(column);
        return "SELECT COUNT(*) AS row_n, COUNT(DISTINCT jm_z." + col + ") AS distinct_n"
                + " FROM (SELECT " + col + " FROM " + t + " LIMIT " + PROFILE_SAMPLE_ROWS + ") jm_z";
    }

    /**
     * 标识符转义。反引号加倍是 MySQL 的转义方式。
     *
     * <p>调用前 {@link #checkIdentifiers} 已按白名单验过形状，这里是<b>第二道</b>：
     * 白名单哪天被谁放宽（比如为了支持中文表名），转义仍然在。
     * 两道都在，是因为这些名字里混着模型的自由输出。
     */
    private static String quote(String ident) {
        return "`" + ident.replace("`", "``") + "`";
    }

    /**
     * 四个标识符必须<b>同时</b>满足：形状过白名单、并且在结构快照里真实存在。
     *
     * <p>「在快照里存在」这一条才是主判据。白名单只挡住注入，挡不住「模型编了一张不存在的表」——
     * 那种名字拼进 SQL，客户的库会回一个错误，而我们要为此付一次真实的连接、一条真实的查询，
     * 换回一个本来在本地就能得出的结论。
     *
     * @return 不能探查的原因；{@code null} 表示可以
     */
    private static String checkIdentifiers(JoinCandidate c, Map<String, Map<String, FieldDetail>> snapshot) {
        String shape = badShape(c.getFromObject(), c.getFromColumn(), c.getToObject(), c.getToColumn());
        if (shape != null) {
            return shape;
        }
        if (field(snapshot, c.getFromObject(), c.getFromColumn()) == null) {
            return "结构快照里没有 " + c.getFromObject() + "." + c.getFromColumn() + "，本条未验证";
        }
        if (field(snapshot, c.getToObject(), c.getToColumn()) == null) {
            return "结构快照里没有 " + c.getToObject() + "." + c.getToColumn() + "，本条未验证";
        }
        return null;
    }

    private static String badShape(String... idents) {
        for (String s : idents) {
            if (s == null || !IDENT_RE.matcher(s).matches()) {
                // 刻意不把那个名字回显出来：它可能是模型编的一段带引号的字符串，
                // 原样带进 detail_json 等于把一段可疑输入又存了一份。
                return "表名或列名含有平台不接受的字符（只允许字母、数字、下划线和 $），本条未验证";
            }
        }
        return null;
    }

    private static FieldDetail field(Map<String, Map<String, FieldDetail>> snapshot, String object, String column) {
        Map<String, FieldDetail> cols = snapshot.get(object);
        return cols == null ? null : cols.get(column);
    }

    /**
     * 这一列是不是字符型。只影响要不要加空串过滤，见 {@link #containmentSql}。
     *
     * <p>取不到类型时按<b>非字符型</b>处理：少一道清洗顶多让包含率偏低一点点，
     * 而在数值列上误加空串过滤会把 0 整批剔掉。两种错的代价不对称。
     */
    static boolean isTextLike(FieldDetail f) {
        if (f == null || f.type() == null) {
            return false;
        }
        String t = f.type().toLowerCase(Locale.ROOT);
        return t.contains("char") || t.contains("text") || t.contains("enum")
                || t.contains("set") || t.contains("json");
    }

    // ================================================================ 小工具

    /**
     * 按列名取一个计数，取不到再按下标兜底。
     *
     * <p>按名字优先是因为 {@code MySqlSession} 用的是 {@code getColumnLabel}，别名一定在；
     * 下标兜底是防某个方言不回别名——那时列顺序仍然是我们自己写的那个顺序。
     */
    private static Long column(QueryResult qr, String label, int fallbackIndex) {
        if (qr == null || qr.rows() == null || qr.rows().isEmpty()) {
            return null;
        }
        List<Object> row = qr.rows().get(0);
        List<String> cols = qr.columns();
        int idx = cols == null ? -1 : cols.indexOf(label);
        if (idx < 0 || idx >= row.size()) {
            idx = fallbackIndex;
        }
        if (idx < 0 || idx >= row.size()) {
            return null;
        }
        return asLong(row.get(idx));
    }

    /** 值可能是 Number，也可能是字符串——{@code MySqlSession.safeValue} 会把 BigDecimal 转成字符串。 */
    private static Long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String pct(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100d);
    }

    /**
     * 节流：两次探查之间至少隔 {@code 60000 / probesPerMinute} 毫秒。
     *
     * <p>状态挂在<b>本轮</b>上而不是本 bean 上：两轮并发剖析会各自按这个速度跑，合起来是两倍。
     * 这是已知的、可接受的：真正的总闸在网关那个管理面限流桶上，这里限的是单轮不要一口气打爆。
     * 把状态提到 bean 上会引入一个所有租户共享的串行点，代价比它解决的问题大。
     *
     * @return false 表示线程被中断，本轮该停了
     */
    private boolean pace(RunState run) {
        long interval = 60_000L / Math.max(1, probesPerMinute);
        long wait = run.nextProbeAtMs - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                // 中断标志必须还回去：上层（推导的后台线程）靠它决定要不要继续。
                Thread.currentThread().interrupt();
                return false;
            }
        }
        run.nextProbeAtMs = System.currentTimeMillis() + interval;
        return true;
    }

    /**
     * 一条 SQL 都没打就结束的一轮。
     *
     * @param snapshot 为 {@code null} 表示这次连「这条连接是谁的」都没确认，不下任何结构判定
     */
    private JoinValidationResult blocked(List<JoinCandidate> input, String outcome, String note, String reason,
                                         Map<String, Map<String, FieldDetail>> snapshot,
                                         Map<String, List<List<String>>> uniqueKeysByObject) {
        List<JoinVerdict> out = new ArrayList<>(input.size());
        for (JoinCandidate c : input) {
            Structure st = snapshot == null ? null : structure(c, snapshot, uniqueKeysByObject);
            out.add(notProbed(c, reason, st, snapshot));
        }
        return done(outcome, note, out, new RunState());
    }

    /**
     * 没探查过的那一条。
     *
     * <p>★ 用 {@code V_NONE} 而不是 {@code V_UNDECIDABLE}：这两个词在注入层是两句不同的话。
     * {@code UNDECIDABLE} 的意思是「<b>查了</b>，但样本判不出来」，{@code NONE} 是「<b>没查</b>」。
     * 档位不允许、预算用尽、被中止、快照对不上，全都属于后者。
     * 把「没查」写成「查了判不出来」，等于凭空声称我们做过一次并不存在的验证。
     *
     * <p>结构判定（{@code st}）照样带上：它不需要探查，见 {@link JoinVerdict#structuralPatch()}。
     */
    private static JoinVerdict notProbed(JoinCandidate c, String reason, Structure st,
                                         Map<String, Map<String, FieldDetail>> snapshot) {
        JoinVerdict.JoinVerdictBuilder b = JoinVerdict.builder()
                .fromObject(c.getFromObject()).fromColumn(c.getFromColumn())
                .toObject(c.getToObject()).toColumn(c.getToColumn())
                .verified(ConnectorSemanticService.V_NONE)
                .autoJoinable(false)
                .probed(false)
                .basis("未经数据验证")
                .reason(reason);
        applyStructure(b, c, st, snapshot);
        return b.build();
    }

    // ================================================================ 结构判定：多态外键与组合键

    /** 结构判定的结果。内部形状，从不序列化。 */
    record Structure(String kind, String discriminatorColumn, List<String> compositeColumns) {
        static final Structure SIMPLE = new Structure(KIND_SIMPLE, null, null);

        boolean polymorphic() {
            return KIND_POLYMORPHIC.equals(kind);
        }

        boolean composite() {
            return KIND_COMPOSITE.equals(kind);
        }
    }

    /**
     * 一条候选在结构上属于哪一类。<b>纯本地判断</b>：只读快照与唯一键，不碰客户库、不看任何取值。
     *
     * <p>两者都命中时记成多态外键：判别条件要读取值才知道填什么，是更需要被单独看见的那一半；
     * 组合键那一半接在 {@code care_reason} 后面，不丢（{@code composite_columns} 按约定只在 COMPOSITE 上出现）。
     *
     * @return 标识符对不上快照时为 {@code null}——名字都对不上，按名字下的判定没有意义
     */
    static Structure structure(JoinCandidate c, Map<String, Map<String, FieldDetail>> snapshot,
                               Map<String, List<List<String>>> uniqueKeysByObject) {
        if (c == null || snapshot == null || checkIdentifiers(c, snapshot) != null) {
            return null;
        }
        String disc = discriminatorFor(snapshot.get(c.getFromObject()), c.getFromColumn());
        if (disc != null && prefixNamesTable(c.getFromColumn(), c.getToObject())) {
            // ★ orders.user_type + user_id → users.id：外键列的前缀本身就念出了目标表，user_id 是一条普通外键，
            //   user_type 是挨着它的分类列（普通用户 / 企业用户），不决定 user_id 指向哪张表。
            //   误标成多态外键的代价不只是吵：第 3 档会为它发分组探查，而两个分类都「全中」时只能判不出来——
            //   一条本来能直接用的外键就这样被挤出了 joins。为什么只认「念出目标表」见 prefixNamesTable。
            disc = null;
        }
        List<String> composite = compositeKeyFor(
                uniqueKeysByObject == null ? null : uniqueKeysByObject.get(c.getToObject()), c.getToColumn());
        if (disc != null) {
            return new Structure(KIND_POLYMORPHIC, disc, composite);
        }
        if (composite != null) {
            return new Structure(KIND_COMPOSITE, null, composite);
        }
        return Structure.SIMPLE;
    }

    /**
     * 按列名找外键列的判别列：{@code resource_id} ↔ {@code resource_type}，{@code targetId} ↔ {@code targetKind}，
     * {@code res_id} ↔ {@code res_model}；另认 Django 的 {@code object_id} ↔ {@code content_type_id}。
     *
     * <h4>它会认错什么，要说在前面</h4>
     * 只看这一张表的列名，分不出「{@code user_type} 是用户的分类」和「{@code user_type} 决定 {@code user_id} 指向哪张表」。
     * 本方法只回答「有没有一个名字配得上的判别列」；前缀念得出目标表（{@code user_id → users}）时改判普通关系——
     * 那一步要看候选的目标表，所以在 {@link #structure} 里做，理由见 {@link #prefixNamesTable}。
     * 剩下认不准的仍然宁可多标：多标的代价是一条关系进了「需要当心」那一栏；漏标的代价是一条静默出错数的 join。
     * 反过来<b>刻意不认</b>没有前缀的 {@code type + id}：那一对几乎每张表都有，全认等于全标。
     *
     * @return 左表上判别列的原名；认不出为 {@code null}
     */
    static String discriminatorFor(Map<String, FieldDetail> leftColumns, String fkColumn) {
        if (leftColumns == null || leftColumns.isEmpty() || fkColumn == null) {
            return null;
        }
        String prefix = fkPrefix(fkColumn);
        if (prefix == null) {
            return null;
        }
        List<String> wanted = new ArrayList<>();
        for (String suffix : DISCRIMINATOR_SUFFIXES) {
            wanted.add(prefix + suffix);
        }
        if ("object".equals(prefix)) {
            wanted.add("contenttypeid");
            wanted.add("contenttype");
        }
        // 外层按后缀顺序、内层按列序：同一张表有两个候选时，结果只取决于这两份固定的顺序。
        for (String w : wanted) {
            for (FieldDetail f : leftColumns.values()) {
                if (f == null || f.name() == null || f.name().equalsIgnoreCase(fkColumn)) {
                    continue;
                }
                if (normalize(f.name()).equals(w) && plausibleDiscriminatorType(f.type())) {
                    return f.name();
                }
            }
        }
        return null;
    }

    /** {@code resource_id} / {@code RESOURCE_ID} / {@code resourceId} → {@code resource}；不是这几种形状返回 {@code null}。 */
    static String fkPrefix(String column) {
        String raw = fkPrefixRaw(column);
        if (raw == null) {
            return null;
        }
        String p = normalize(raw);
        return p.isEmpty() ? null : p;
    }

    /** 同上，但保留原样不归一：{@link #prefixNamesTable} 要按下划线与驼峰分词，归一之后词界就没了。 */
    private static String fkPrefixRaw(String column) {
        if (column == null) {
            return null;
        }
        if (column.length() > 3 && column.toLowerCase(Locale.ROOT).endsWith("_id")) {
            return column.substring(0, column.length() - 3);
        }
        if (column.length() > 2 && column.endsWith("Id")
                && Character.isLetterOrDigit(column.charAt(column.length() - 3))
                && !Character.isUpperCase(column.charAt(column.length() - 3))) {
            return column.substring(0, column.length() - 2);
        }
        // paid / valid / uuid 这类碰巧以 id 结尾的词不算：没有分词边界。
        return null;
    }

    /**
     * 外键列的前缀「念不念得出」这张表：{@code user_id} ↔ {@code user} / {@code users} / {@code t_user} / {@code sys_user}，
     * {@code order_item_id} ↔ {@code t_order_items}，{@code categoryId} ↔ {@code categories}。
     *
     * <h4>★ 为什么只比候选的<b>目标表</b>，而不是「快照里有没有这张表」</h4>
     * 判「分类列 + 普通外键」的依据是命名约定说 {@code user_id} 指向用户表——这句话只在候选确实指向那张表时才是在替它作证。
     * {@code user_id → admins.id} 而库里另有一张 {@code users}：约定说该指向 users，候选却说指向 admins，
     * 要么候选错了，要么这一列真按 {@code user_type} 分指 users / admins，两种都不该当普通外键放行。
     * 另有两个好处：结论不随快照里<b>别的表</b>在不在而翻转（快照只取前 200 张，别的表进出刀口不该改写这条关系的 join_kind）；
     * 设计里那个多态外键 {@code sys_role_resource.resource_id → agent.id}，库里就算另有一张 {@code sys_resource} 也仍是多态外键。
     *
     * <h4>为什么只比表名的<b>尾部</b>、只认复数这几种变形</h4>
     * 表名常带业务前缀（{@code t_} / {@code sys_} / {@code crm_}），实体名在尾部；只比尾部，Odoo 的 {@code res_id → res_partner}
     * （前缀 res 在表名头部）就不会被误认成「念出了目标表」。刻意<b>不</b>认 {@code user_info} / {@code user_base} 这类带后缀的表名：
     * 每多认一种变形，就多放一类真多态外键按普通关系去测包含率——几张表自增 id 重叠时它会被判成 CONFIRMED，
     * 是一条静默出错数的 join；少认的代价只是多一条「需要当心」。
     * {@code resource_id → sys_resource.id} 这种「角色名恰好也是目标表名」的真多态外键会被判成普通关系，这是按名字判定躲不开的边界。
     */
    static boolean prefixNamesTable(String fkColumn, String table) {
        String raw = fkPrefixRaw(fkColumn);
        if (raw == null || table == null) {
            return false;
        }
        List<String> prefix = words(raw);
        List<String> name = words(table);
        if (prefix.isEmpty() || name.size() < prefix.size()) {
            return false;
        }
        int offset = name.size() - prefix.size();
        int last = prefix.size() - 1;
        for (int i = 0; i < last; i++) {
            if (!prefix.get(i).equals(name.get(offset + i))) {
                return false;
            }
        }
        String p = prefix.get(last);
        String n = name.get(offset + last);
        return n.equals(p) || n.equals(p + "s") || n.equals(p + "es")
                || (p.length() > 1 && p.endsWith("y") && n.equals(p.substring(0, p.length() - 1) + "ies"));
    }

    /** 按非字母数字字符与驼峰边界分词并小写：{@code t_OrderItem} → [t, order, item]，{@code T_USER} → [t, user]。 */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!Character.isLetterOrDigit(ch)) {
                flushWord(cur, out);
                continue;
            }
            if (Character.isUpperCase(ch) && cur.length() > 0) {
                char prev = s.charAt(i - 1);
                if (Character.isLowerCase(prev) || Character.isDigit(prev)) {
                    flushWord(cur, out);
                }
            }
            cur.append(Character.toLowerCase(ch));
        }
        flushWord(cur, out);
        return out;
    }

    private static void flushWord(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    /** 小写并去掉非字母数字：{@code resource_type} 与 {@code resourceType} 归一成同一个串。 */
    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** 取不到类型时只凭名字；取得到时排除时间、小数、大对象、JSON、空间类型。 */
    static boolean plausibleDiscriminatorType(String type) {
        if (type == null || type.isBlank()) {
            return true;
        }
        String base = type.trim().toLowerCase(Locale.ROOT).replaceFirst("[^a-z].*$", "");
        return !NON_DISCRIMINATOR_TYPES.contains(base);
    }

    /**
     * 目标列若只是某个多列唯一键的一员（自己单独没有唯一键），返回那个完整键；否则 {@code null}。
     *
     * <p>属于好几个组合键时取<b>列数最少</b>的（同样少时按传入顺序，即主键在前）——那是最容易满足的完整条件，
     * 与 {@code MySqlSession} 写在列 extra 上的那一句取法一致。
     *
     * @param keys 这张表的唯一键；{@code null} = 不知道，此时<b>不</b>判成组合键
     */
    static List<String> compositeKeyFor(List<List<String>> keys, String column) {
        if (keys == null || column == null) {
            return null;
        }
        List<String> best = null;
        for (List<String> k : keys) {
            if (k == null || k.isEmpty() || k.stream().anyMatch(Objects::isNull)
                    || k.stream().noneMatch(column::equalsIgnoreCase)) {
                continue;
            }
            if (k.size() == 1) {
                // 自己就有唯一键：单独唯一，是普通关系。
                return null;
            }
            if (best == null || k.size() < best.size()) {
                best = k;
            }
        }
        return best == null ? null : List.copyOf(best);
    }

    /**
     * 从结构快照行里解出每张表的唯一键，喂给 {@link #validate(Long, List, Map, Map, ProbeProgress)}。
     *
     * <p>只读调用方手上<b>已经有的</b>快照行（{@code connector_schema.detail_json.extra.unique_keys}，
     * 由 {@code MySqlSession.describe} 写入），不查任何库。
     *
     * <p>三种情况分得开：键在且非空 → 那些唯一键；键在但为空列表 → 这张表确实没有唯一键；
     * 键不在（本功能上线前拉的快照，或那次读索引失败）→ 这张表<b>不出现在结果里</b>，即「不知道」。
     * 旧快照要等下一次「刷新结构」才会有这个键；刷新不改 {@code content_hash}，不会因此引发漂移。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, List<List<String>>> uniqueKeysByObject(List<ConnectorSchema> rows) {
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        if (rows == null) {
            return out;
        }
        for (ConnectorSchema r : rows) {
            if (r == null || r.getObjectName() == null || r.getDetailJson() == null || r.getDetailJson().isBlank()) {
                continue;
            }
            try {
                Map<String, Object> m = CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
                if (!(m.get("extra") instanceof Map<?, ?> extra)
                        || !(extra.get(DETAIL_UNIQUE_KEYS) instanceof List<?> keys)) {
                    continue;
                }
                List<List<String>> parsed = new ArrayList<>(keys.size());
                for (Object k : keys) {
                    if (!(k instanceof Map<?, ?> km) || !(km.get("columns") instanceof List<?> cols)
                            || cols.isEmpty() || cols.stream().anyMatch(Objects::isNull)) {
                        continue;
                    }
                    parsed.add(cols.stream().map(o -> String.valueOf((Object) o)).toList());
                }
                out.put(r.getObjectName(), List.copyOf(parsed));
            } catch (Exception e) {
                // 单张表的快照 JSON 坏了，只让这张表「不知道唯一键」，不影响其余的表。
                log.debug("解析快照里的唯一键失败 objectName={}", r.getObjectName(), e);
            }
        }
        return out;
    }

    /**
     * 只做结构判定（第 1 档就能下的那部分），产出能直接并进 {@code detail_json} 的结构键：
     * {@code join_kind} / {@code discriminator_column} / {@code composite_columns} / {@code care_reason}。
     *
     * <p>给<b>不走采样验证</b>的关系用（增量推导里没轮到探查的、档位不许探查的）：结构判定只读快照与唯一键，
     * 与探没探查无关，理由见 {@link JoinVerdict#structuralPatch()}。
     *
     * <p>三条硬约束：
     * <ul>
     *   <li><b>不碰客户库</b>：不经网关、不查连接行、不需要租户上下文；</li>
     *   <li><b>永不返回 {@code discriminator_value}</b>：那是真实取值，只能来自第 3 档的分组探查并过 PII；</li>
     *   <li>标识符对不上快照时返回<b>空 Map</b>，不是 {@code {join_kind: SIMPLE}}——「没判」与「判成普通关系」必须分得开，
     *       前者不该覆盖掉行上已有的结构键。</li>
     * </ul>
     * 返回的 Map 每次新建，调用方可以随意改。
     */
    public Map<String, Object> structureOnly(JoinCandidate c, Map<String, Map<String, FieldDetail>> snapshot,
                                             Map<String, List<List<String>>> uniqueKeysByObject) {
        Structure st = structure(c, snapshot, uniqueKeysByObject);
        if (st == null) {
            return new LinkedHashMap<>();
        }
        JoinVerdict.JoinVerdictBuilder b = JoinVerdict.builder()
                .fromObject(c.getFromObject()).fromColumn(c.getFromColumn())
                .toObject(c.getToObject()).toColumn(c.getToColumn());
        applyStructure(b, c, st, snapshot);
        Map<String, Object> out = b.build().structuralPatch();
        // applyStructure 从不写判别值；这里再钉一次契约，免得哪天那条路上加了它、这里跟着漏出去。
        out.remove("discriminator_value");
        return out;
    }

    /** 目标表的列：组合键那一句要看键列是否允许 NULL，而键列在目标表上。 */
    private static Map<String, FieldDetail> targetColumns(Map<String, Map<String, FieldDetail>> snapshot, JoinCandidate c) {
        return snapshot == null ? null : snapshot.get(c.getToObject());
    }

    /** 把结构判定写进结论：join_kind、判别列 / 组合键列、以及那一句 care_reason。 */
    private static void applyStructure(JoinVerdict.JoinVerdictBuilder b, JoinCandidate c, Structure st,
                                       Map<String, Map<String, FieldDetail>> snapshot) {
        if (st == null) {
            return;
        }
        b.joinKind(st.kind());
        if (st.polymorphic()) {
            b.discriminatorColumn(st.discriminatorColumn())
                    .careReason(polymorphicCare(c, st, null, targetColumns(snapshot, c)));
        } else if (st.composite()) {
            b.compositeColumns(st.compositeColumns())
                    .careReason(compositeCare(c, st.compositeColumns(), targetColumns(snapshot, c)));
        }
    }

    /**
     * 多态外键的 care_reason。{@code value} 为空时只能说「要带条件、但平台不知道是哪个值」，并告诉模型怎么自己确认；
     * 同时是组合键时把组合键那一句接在后面。
     *
     * <p>条件里的取值单引号加倍：模型会照抄这个条件写 SQL，一个带引号的判别值原样抄进去就是一条语法错误。
     */
    static String polymorphicCare(JoinCandidate c, Structure st, String value, Map<String, FieldDetail> targetColumns) {
        return polymorphicCare(c, st, value, targetColumns, "");
    }

    /**
     * 同上，另带判别列为空的那几类行的说明（{@link #blankCaveat}）。
     *
     * @param blankCaveat 非空时<b>不再说「只有在 … 时才」</b>：判别列为空的行也许同样指向目标表，「只有」这句话就是假的，
     *                    而模型会照着它把那些行排除在外、少算且不报错
     */
    static String polymorphicCare(JoinCandidate c, Structure st, String value, Map<String, FieldDetail> targetColumns,
                                  String blankCaveat) {
        String from = c.getFromObject();
        String disc = st.discriminatorColumn();
        String target = c.getToObject() + "." + c.getToColumn();
        boolean caveated = blankCaveat != null && !blankCaveat.isEmpty();
        String s;
        if (value != null) {
            s = "多态外键：" + from + "." + c.getFromColumn() + (caveated ? " 在 " : " 只有在 ") + from + "." + disc + " = '"
                    + value.replace("'", "''") + "' 时" + (caveated ? "" : "才") + "指向 " + target
                    + "。关联时必须带上这个条件，否则指向别的表的行也会被连上，而且不报错。";
            if (caveated) {
                s = s + blankCaveat;
            }
        } else {
            s = "疑似多态外键：" + from + "." + c.getFromColumn() + " 指向哪张表可能由同表的 " + disc + " 决定。"
                    + "关联 " + target + " 时需要加条件 " + from + "." + disc + " = <代表 " + c.getToObject() + " 的取值>，"
                    + "否则指向别的表的行也会被连上。平台没有记录是哪个取值：先按 " + disc + " 分组看一眼再决定；"
                    + "如果每个取值都指向 " + c.getToObject() + "，它只是分类列，这个条件可以不加。";
        }
        if (st.compositeColumns() != null) {
            s = s + compositeCare(c, st.compositeColumns(), targetColumns);
        }
        return s;
    }

    /** 「owner_type 为 NULL」/「owner_type 为空串（或只有空白）」。传进来的列名可以带表名前缀。 */
    static String blankLabel(String discriminator, Group g) {
        return discriminator + (g.isNull() ? " 为 NULL" : " 为空串（或只有空白）");
    }

    /**
     * 选出判别列为空的那一类行的条件，模型可以照抄进 SQL。
     *
     * <p>NULL 写 {@code IS NULL}：{@code = NULL} 永远不为真，照抄就一行都连不上，而且不报错。
     * 空串写 {@code TRIM(列) = ''}：PAD SPACE 排序规则下 {@code ''} 与 {@code '  '} 本来就分在一组，NO PAD 下不是，TRIM 两种都选得到。
     */
    static String blankCondition(String qualifiedDiscriminator, Group g) {
        return g.isNull() ? qualifiedDiscriminator + " IS NULL" : "TRIM(" + qualifiedDiscriminator + ") = ''";
    }

    /**
     * 达到确认线的是判别列为空的那一类行时的 care_reason。<b>不含任何客户取值</b>：为空不是取值，条件只写 IS NULL / TRIM(...) = ''。
     *
     * @param blankCaveat 其余判别列为空、也可能指向目标表的那几类（见 {@link #blankCaveat}），没有就传空串
     */
    static String blankCare(JoinCandidate c, Structure st, Group g, Map<String, FieldDetail> targetColumns,
                            String blankCaveat) {
        String from = c.getFromObject();
        String qualified = from + "." + st.discriminatorColumn();
        String s = "多态外键：" + from + "." + c.getFromColumn() + " 在 " + blankLabel(qualified, g) + " 的行上指向 "
                + c.getToObject() + "." + c.getToColumn() + "，带类型取值的行没有一类达到确认线。"
                + "关联时必须带上条件 " + blankCondition(qualified, g) + "，否则指向别的表的行也会被连上，而且不报错。"
                + "这类行没有类型取值，常是加类型列之前写入、按默认类型处理的历史行。"
                + (blankCaveat == null ? "" : blankCaveat);
        if (st.compositeColumns() != null) {
            s = s + compositeCare(c, st.compositeColumns(), targetColumns);
        }
        return s;
    }

    /**
     * 判别列为空、却可能也指向目标表的那几类行（{@link #blankGroupMayPointToTarget}），接在确认结论的 care_reason 后面。没有就返回空串。
     *
     * <p>★ 为什么非说不可：确认下来的条件（{@code = 'AGENT'} 或 {@code IS NULL}）选不到这几类行。它们若正是按默认类型写入的历史行，
     * 模型照着条件去连，就把它们整批排除在外——少算，而且不报错。平台分不出它们到底属不属于目标表，
     * 能做的是把量到的数和确认它的办法交给模型，而不是替它写一个「只有」。
     */
    static String blankCaveat(JoinCandidate c, String discriminator, List<Group> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        String qualified = c.getFromObject() + "." + discriminator;
        String target = c.getToObject() + "." + c.getToColumn();
        StringBuilder s = new StringBuilder("另外，");
        List<String> conditions = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            Group g = hits.get(i);
            if (i > 0) {
                s.append("；");
            }
            s.append(blankLabel(qualified, g)).append(" 的行里采样 ").append(g.sampleN()).append(" 个取值，有 ")
                    .append(g.matchN()).append(" 个也能在 ").append(target).append(" 找到");
            conditions.add(blankCondition(qualified, g));
        }
        return s.append("。这类行没有类型取值，常是按默认类型写入的历史行：上面的条件选不到它们，少算了也不报错。")
                .append("它们是不是也指向 ").append(c.getToObject()).append("，平台判断不了——先单独查一下这类行，确认属于 ")
                .append(c.getToObject()).append(" 再把 ").append(String.join(" 或 ", conditions))
                .append(" 用 OR 并进条件。")
                .toString();
    }

    /**
     * 组合键的 care_reason：完整的键是什么、哪一对是已知的、其余哪些要先确认，以及键列允许 NULL 时的两个坑。
     *
     * <h4>★ 不按列名替左表配对应列</h4>
     * 上一版在左表有同名列时直接写出 {@code from.k = to.k}。这在 {@code tenant_id} 上多半碰对，在别的列上会言之凿凿地写出错条件：
     * MySQL 要求分区表的每个唯一键都包含分区列，于是按时间分区的订单表主键常是 {@code (id, create_time)}
     * （本地 semval.orders_p 就是这个形状）。明细表上的 {@code create_time} 是明细自己的创建时间，
     * 照写 {@code order_item.create_time = orders.create_time} 会把几乎所有明细都连丢，不报错。
     * 平台真正知道的只有候选自己那一对 {@code from_column → to_column}；其余列的对应关系是一个没人验过的猜测，不能写成条件。
     *
     * <h4>★ 键列允许 NULL 时</h4>
     * UNIQUE 不约束含 NULL 的组合：本地 MySQL 8.0.46 实测 {@code UNIQUE(shop_id, code)} 能插入两行 {@code (NULL, 'A')}。
     * 于是两件事同时成立：用 {@code =} 关联时，键里任一列为 NULL 的行永远连不上（{@code NULL = NULL} 不为真），被静默漏掉；
     * 改用 {@code <=>} / {@code IS NULL} 把它们连上时，那些重复的 NULL 组合又会一行连出多行（实测一行连出 2 行）。
     * 主键列不可能为 NULL，所以这一句只会出现在唯一索引上。
     *
     * @param targetColumns 目标表的列，只用来看键列是否允许 NULL；{@code null} = 不知道，此时不说 NULL 那一句
     */
    static String compositeCare(JoinCandidate c, List<String> key, Map<String, FieldDetail> targetColumns) {
        String from = c.getFromObject();
        String to = c.getToObject();
        String known = from + "." + c.getFromColumn() + " = " + to + "." + c.getToColumn();
        List<String> rest = key.stream().filter(k -> !k.equalsIgnoreCase(c.getToColumn())).toList();
        StringBuilder s = new StringBuilder()
                .append(to).append('.').append(c.getToColumn())
                .append(" 只是组合唯一键 (").append(String.join(", ", key)).append(") 的一部分，单独不唯一：")
                .append("只按这一列关联会一行连出多行，SUM / COUNT 会被放大且不报错。")
                .append("平台只知道 ").append(known).append(" 这一对；")
                .append(to).append(" 的 ").append(String.join(", ", rest))
                .append(" 在 ").append(from).append(" 上对应哪一列，平台不知道，要先确认再加进关联条件——")
                .append("同名列不一定是同一件事（例如分区表被迫放进主键的时间列，在关联表上往往是那张表自己的时间）。")
                .append("确认不了时，可以先查 ").append(to).append(" 里 ").append(c.getToColumn())
                .append(" 实际是不是一行一个（COUNT(*) 与 COUNT(DISTINCT ").append(c.getToColumn())
                .append(") 相等），相等才按这一列单独关联。");
        List<String> nullable = new ArrayList<>();
        for (String k : key) {
            FieldDetail f = fieldIgnoreCase(targetColumns, k);
            if (f != null && f.nullable()) {
                nullable.add(f.name() == null ? k : f.name());
            }
        }
        if (!nullable.isEmpty()) {
            s.append("另外 ").append(to).append(" 的 ").append(String.join(", ", nullable)).append(" 允许为 NULL：")
                    .append("键里任一列为 NULL 的行用 = 永远连不上（NULL = NULL 不为真），会被静默漏掉；")
                    .append("而唯一约束不限制含 NULL 的组合，同样的 (NULL, 值) 可以出现多行，")
                    .append("改用 <=> 或 IS NULL 把它们连上时照样会一行连出多行。");
        }
        return s.toString();
    }

    /** 按列名取列，先精确再忽略大小写（MySQL 列名不分大小写，唯一键里记的大小写不保证与列定义一致）。 */
    private static FieldDetail fieldIgnoreCase(Map<String, FieldDetail> columns, String name) {
        if (columns == null || name == null) {
            return null;
        }
        FieldDetail exact = columns.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, FieldDetail> e : columns.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    // ================================================================ 多态外键：第 3 档的分组探查

    /**
     * 分组探查的一行：一个判别值下采到几个去重外键取值、其中几个在右表命中。
     *
     * @param value 判别值；判别列为 NULL 的那一组是 {@code null}
     */
    record Group(String value, int sampleN, int matchN) {
        double containment() {
            return sampleN <= 0 ? 0d : (double) matchN / (double) sampleN;
        }

        /** 判别列为 NULL 的那一组。 */
        boolean isNull() {
            return value == null;
        }

        /**
         * 判别列为空的那一组：NULL、空串、或只有空白（PAD SPACE 排序规则下 {@code ''} 与 {@code ' '} 本来就分在一组）。
         * 它<b>没有取值可记</b>，也写不成「= 某个取值」的条件；判定上的待遇见 {@link #decidePolymorphic}。
         */
        boolean blank() {
            return value == null || value.isBlank();
        }
    }

    /**
     * 一个组要达到确认线，命中数至少是多少：样本至少 {@link #MIN_SAMPLE} 个，其中至少 {@link #TH_CONFIRMED} 命中（样本越多要求越高，
     * 所以下界取在 {@code MIN_SAMPLE} 处）。用 {@link Group#containment()} 同一个算式逐个试出来，而不写成 {@code ceil(10 * 0.9)}：
     * 浮点乘法和判定用的除法一旦分叉，这条下界就会在边界上差一。
     */
    static final int MIN_CONFIRM_MATCH = minConfirmMatch();

    private static int minConfirmMatch() {
        for (int m = 0; m <= MIN_SAMPLE; m++) {
            if (new Group("", MIN_SAMPLE, m).containment() >= TH_CONFIRMED) {
                return m;
            }
        }
        return MIN_SAMPLE;
    }

    /**
     * 分组结果的判定。
     *
     * @param verified        {@code ConnectorSemanticService.V_*}
     * @param best            给出数字的那一组；没有可用的组时为 {@code null}
     * @param recordValue     {@code best} 的取值是不是所求的判别值（还要再过 PII 才能真的记）
     * @param byName          是否靠「取值与表名对得上」从多个达标组里选出来的
     * @param confirmedGroups 看得到的组里达到确认线的组数（判别列为空的那一类也算一组）
     * @param groupsCapped    结论是不是因为「组没带全」才只能判不出来
     * @param blankBlocksReject 其余条件都够 REJECTED，只因为判别列为空的某一类行可能指向目标表才判不出来
     */
    record PolyDecision(String verified, Group best, boolean recordValue, boolean byName, int confirmedGroups,
                        boolean groupsCapped, boolean blankBlocksReject) {}

    /**
     * 判别列能不能拿去查它的取值。
     *
     * @return 不能的原因（不含任何取值）；{@code null} 表示可以
     */
    private String discriminatorBlocked(JoinCandidate c, Structure st, Map<String, Map<String, FieldDetail>> snapshot) {
        String disc = st.discriminatorColumn();
        if (badShape(disc) != null) {
            return "判别列名含有平台不接受的字符";
        }
        FieldDetail f = field(snapshot, c.getFromObject(), disc);
        PiiFilter.PiiVerdict v = piiFilter.screenColumn(c.getFromObject(), disc, f == null ? null : f.comment());
        return v.sensitive() ? "判别列 " + disc + " 疑似个人信息列（" + v.reason() + "）" : null;
    }

    /**
     * 第 3 档：按判别值分组算条件包含率，<b>一条 SQL、一次 {@code executeAsPlatform}</b>。
     *
     * <h4>为什么是一条分组查询，而不是「先取判别值、再逐个值查一次」</h4>
     * 逐个值查能让每个类型各采满一份样本，更准；但 k 个取值就是 k+1 次探查，按 30 次/分钟的节流，
     * 一张十几种类型的表要吃掉半分钟的预算。按类型分区采样要窗口函数，MySQL 5.7 没有——在 5.7 上它是语法错误，
     * 会被归成连接级故障、把整轮验证停掉。所以接受一个已知偏倚：样本是物理顺序的前 N 个 (判别值, 外键值) 组合，
     * 某个类型的行可能一条都没采到。这个偏倚只会把结论推向「判不出来」，由 {@link #decidePolymorphic} 兜住：
     * 没采全就绝不 REJECTED。
     *
     * <h4>★ 判别列为空的行自成一组，而且算进「采全了没有」</h4>
     * 上一版在 SQL 里滤掉了判别列为 NULL / 空串的行。那些行最常见的来历是加类型列之前写入、按默认类型处理的历史行——
     * 它们恰恰可能全都指向目标表。滤掉之后，返回的组看起来是齐的（没触到任何 LIMIT），剩下的类型又都对不上，
     * 一条真实存在的关系就被判成 REJECTED，不报任何错。本地 MySQL 8.0.46 实测（slice_data.attachment.owner_id → orders.id）：
     * 旧 SQL 只带回 {@code USER 15/0}（按旧判定是 REJECTED），新 SQL 带回 {@code NULL 25/25、'' 3/3、USER 15/0}。
     * 现在它们照常分组、照常占 LIMIT 名额、照常计入 Σsample_n；判定上的特殊待遇（从不记成判别值、不按过没过半否定）见 {@link #decidePolymorphic}。
     */
    private JoinVerdict decidePolymorphicByGroups(Long connectorId, JoinCandidate c,
                                                  Map<String, Map<String, FieldDetail>> snapshot, Structure st,
                                                  JoinVerdict.JoinVerdictBuilder b, RunState run) {
        String disc = st.discriminatorColumn();
        String sql = polymorphicSql(c, disc, isTextLike(field(snapshot, c.getFromObject(), c.getFromColumn())));
        QueryResult qr;
        try {
            qr = probe(connectorId, sql, run, ConnectorAuditService.OP_SEMANTIC_VALUES, POLY_MAX_GROUPS, POLY_MAX_BYTES);
        } catch (ProbeFailure f) {
            // 没带回结果（超时、权限、限流、连接故障、被中断）：groupedProbeRan 保持 false，理由见那个字段。
            return b.probed(f.probed).verified(f.verified).basis(f.basis).reason(f.reason).build();
        }
        // K-2：分组探查带着结果回来了——判别值此刻已经离开客户库。之后哪怕解析不了，这一次分组测量也确实发生过。
        b.groupedProbeRan(true);
        List<Group> groups = groups(qr);
        if (groups == null) {
            log.warn("多态外键分组探查返回了看不懂的结果 connectorId={} columns={}", connectorId, qr.columns());
            return b.probed(true)
                    .verified(ConnectorSemanticService.V_UNDECIDABLE)
                    .basis("分组探查没有返回可解析的计数，判不出来（不代表这条关系是错的）")
                    .reason("探查结果无法解析")
                    .build();
        }
        long sampled = 0;
        for (Group g : groups) {
            sampled += g.sampleN();
        }
        // ★ 「组带全了没有」只能数返回的行数，不能只看 qr.truncated()：SQL 自己带着 LIMIT POLY_MAX_GROUPS，
        //   数据库在 LIMIT 处就停了，回到 readResultSet 的行数永远不超过上限——这条 SQL 上 truncated 恒为 false。
        //   本地 MySQL 实测：外层 LIMIT 1 时第二组直接消失，结果里没有任何「还有更多」的信号。
        //   数原始行数：LIMIT 数的就是行。判别值为 NULL / 空串的那几组也各占一个名额，groups() 把它们照常解析成组，两个数相等。
        int rowsReturned = qr.rows() == null ? 0 : qr.rows().size();
        boolean allGroupsSeen = !qr.truncated() && rowsReturned < POLY_MAX_GROUPS;
        // 派生表里每个 (判别值, 外键值) 组合恰好一行，所以组带全时 Σsample_n 就是派生表的行数，
        // 它小于内层 LIMIT 才说明没有哪一类行因为 LIMIT 没被采到（本地 MySQL 实测：内层 LIMIT 3 时 Σsample_n = 3）。
        // 组没带全时这个和本身就缺了几项，不能拿来判断，直接算没采全。
        boolean sampleComplete = allGroupsSeen && sampled < sampleValues;
        // 组没带全时按「派生表采满了」记账：看不到的组也花了预算，宁可高估也不要低估。
        run.sampledRows += allGroupsSeen ? sampled : Math.max(sampled, sampleValues);

        PolyDecision d = decidePolymorphic(groups, allGroupsSeen, sampleComplete, c.getToObject());
        b.probed(true);
        Group best = d.best();
        if (best != null) {
            b.sampleN(best.sampleN()).matchN(best.matchN());
        }
        Map<String, FieldDetail> toCols = targetColumns(snapshot, c);
        String head = "按 " + disc + " 的取值分组采样";
        String target = c.getToObject() + "." + c.getToColumn();

        switch (d.verified()) {
            case ConnectorSemanticService.V_CONFIRMED -> {
                b.containment(best.containment());
                // 判别列为空、却可能也指向目标表的那几类行：它们写不进「= 取值」的条件，确认时必须说出来，见 blankCaveat。
                String caveat = blankCaveat(c, disc, blankGroupsMayPointTo(groups, best));
                String basis;
                if (best.blank()) {
                    // ★ 达标的是判别列为空的那一类：照样确认（这一类行确实指向目标表），但没有取值可记，条件写成 IS NULL / TRIM(...) = ''。
                    basis = head + "：" + blankLabel(disc, best) + " 的那一类行下采样 " + best.sampleN() + " 个取值，命中 "
                            + best.matchN() + "（包含率 " + pct(best.containment()) + "），带类型取值的行没有一类达到确认线";
                    b.careReason(blankCare(c, st, best, toCols, caveat));
                } else {
                    basis = head + "：达到确认线的那个判别值下采样 " + best.sampleN() + " 个取值，命中 "
                            + best.matchN() + "（包含率 " + pct(best.containment()) + "）";
                    if (d.byName()) {
                        basis = basis + "；共有 " + d.confirmedGroups() + " 类行达到确认线，按取值与表名的对应选定其中一个";
                    }
                    String withheld = withheldReason(groups, best);
                    if (withheld == null) {
                        b.discriminatorValue(best.value()).careReason(polymorphicCare(c, st, best.value(), toCols, caveat));
                    } else {
                        log.info("多态外键的判别值没有记录 connectorId={} {}.{} -> {} 原因={}",
                                connectorId, c.getFromObject(), c.getFromColumn(), target, withheld);
                        b.careReason(polymorphicCare(c, st, null, toCols) + "满足确认线的那个取值没有记录：" + withheld + "。"
                                + caveat);
                    }
                }
                basis = basis + "。多态外键只标注、不自动 join";
                return b.verified(ConnectorSemanticService.V_CONFIRMED).basis(basis).build();
            }
            case ConnectorSemanticService.V_WEAK -> {
                b.containment(best.containment());
                String where = best.blank()
                        ? "包含率最高的是 " + blankLabel(disc, best) + " 的那一类行，采样 "
                        : "包含率最高的判别值下采样 ";
                return b.verified(ConnectorSemanticService.V_WEAK)
                        .basis(head + "：" + where + best.sampleN() + " 个取值，命中 " + best.matchN()
                                + "（包含率 " + pct(best.containment()) + "），未达 " + pct(TH_CONFIRMED)
                                + "，用之前先自己核一次。多态外键只标注、不自动 join")
                        .reason("没有哪一类行的包含率达到确认线，所以没有记录判别值")
                        .build();
            }
            case ConnectorSemanticService.V_REJECTED -> {
                b.containment(best.containment());
                long blanks = groups.stream().filter(Group::blank).count();
                return b.verified(ConnectorSemanticService.V_REJECTED)
                        .basis(head + "：左表按 " + disc + " 分出的全部 " + groups.size() + " 类行"
                                + (blanks > 0 ? "（含 " + disc + " 为空的 " + blanks + " 类）" : "")
                                + "都已采全，每一类下面都有过半取值在 " + target + " 找不到——数据不支持这条关系")
                        .reason("样本没有触及上限，没有哪一类行被漏采；没有任何一类行与这张表对得上")
                        .build();
            }
            default -> {
                // UNDECIDABLE：组没带全、多类行都达标（自增 id 重叠 / 这一列只是分类列）、判别列为空的行挡住了否定、或样本不够。
                //   几种都不写 containment，也不写任何取值——带回来的取值一个都没被选中，没有理由把它们存下来。
                String basis;
                String reason;
                List<Group> blankHits = blankGroupsMayPointTo(groups, null);
                if (d.groupsCapped()) {
                    basis = head + "：带回的判别值分组达到上限 " + POLY_MAX_GROUPS + " 组，排在后面的组没有带回来，"
                            + "其中可能正有指向 " + c.getToObject() + " 的那一类，判不出来（不代表这条关系是错的）";
                    reason = "分组探查只带回前 " + POLY_MAX_GROUPS + " 组；看不到的组既可能正对得上这张表，"
                            + "也可能和看得到的组一起达标、让结论变成分不出来，所以不能凭前 " + POLY_MAX_GROUPS + " 组确认或否定";
                } else if (d.confirmedGroups() > 1) {
                    boolean blankAmong = groups.stream().anyMatch(g -> g.blank() && g.sampleN() >= MIN_SAMPLE
                            && g.containment() >= TH_CONFIRMED);
                    basis = head + "：有 " + d.confirmedGroups() + " 类行" + (blankAmong ? "（含 " + disc + " 为空的那一类）" : "")
                            + "下的取值在 " + target + " 里都能找到（包含率都达到 " + pct(TH_CONFIRMED)
                            + "），分不出哪一类对应这张表，判不出来（不代表这条关系是错的）";
                    reason = "常见原因是几张目标表的自增 id 范围重叠；也可能 " + disc + " 只是分类列，并不决定目标表"
                            + (blankAmong ? "；" + disc + " 为空的行常是按默认类型写入的历史行，它和带类型取值的那一类谁才对应这张表，数据分不出来" : "");
                } else if (d.blankBlocksReject() && !blankHits.isEmpty()) {
                    Group h = blankHits.get(0);
                    basis = head + "：带类型取值的行都对不上 " + target + "，但 " + blankLabel(disc, h) + " 的那一类行里采样 "
                            + h.sampleN() + " 个取值有 " + h.matchN() + " 个能找到，判不出来（不代表这条关系是错的）";
                    reason = disc + " 为空的行不属于任何一个类型取值，常是按默认类型写入的历史行、也可能混着几种类型，"
                            + "包含率会被混在一起的行摊薄——不能凭它不过半就否定这条关系";
                } else {
                    basis = head + "：没有足够的样本判定——样本按物理顺序取，可能没采到指向 " + c.getToObject()
                            + " 的那一类行，判不出来（不代表这条关系是错的）";
                    reason = "采到的判别值分组都不足 " + MIN_SAMPLE + " 个取值，或都没有过半命中但样本没有采全";
                }
                return b.verified(ConnectorSemanticService.V_UNDECIDABLE).basis(basis).reason(reason).build();
            }
        }
    }

    /**
     * 判别值能不能记下来。{@code null} = 能。
     *
     * <p>PII 过的是<b>这次带回来的全部取值</b>，不只是被选中的那一个：同一列里只要有一个取值长得像邮箱 / 手机号，
     * 这一列多半根本不是判别列、只是名字像——此时连被选中的那个也不记。与值域剖析「命中一个就丢整列」同一条纪律。
     */
    private String withheldReason(List<Group> groups, Group best) {
        // 判别列为空的那几组没有取值，不参与 PII 判定（PiiFilter 本来也跳过空值，这里写明是为了不让 null 混进列表）。
        PiiFilter.PiiVerdict pii = piiFilter.screenValues(
                groups.stream().filter(g -> !g.blank()).map(Group::value).toList());
        if (pii.sensitive()) {
            return "分组探查带回的取值命中了个人信息判据（" + pii.reason() + "）";
        }
        if (best.value().length() > POLY_MAX_VALUE_LENGTH) {
            return "判别值长度超过 " + POLY_MAX_VALUE_LENGTH + " 个字符，不像类型码";
        }
        return null;
    }

    /**
     * 分组结果 → 结论。纯函数，单测直接钉。
     *
     * <ol>
     *   <li>样本不足 {@link #MIN_SAMPLE} 的组不参与判定——几个取值判不出任何东西，哪怕全中。</li>
     *   <li>★ 组没带全（返回的组数到了 {@link #POLY_MAX_GROUPS}），而看不到的组里<b>可能</b>藏着一个达标的 → UNDECIDABLE。
     *       「恰好一个达标」与「谁都对不上」这两句话都要求看到全部的组：看不到的那一个若也达标，前者其实是分不出来；
     *       若它正是指向目标表的那一类，后者其实是对得上。见 {@link #hiddenGroupMayConfirm}。</li>
     *   <li>恰好一组达到确认线 → CONFIRMED。它若带类型取值，这个取值就是所求的判别值；
     *       它若是判别列为空的那一类，照样确认（这一类行确实指向目标表），但<b>没有取值可记</b>。</li>
     *   <li>多组达到确认线 → 只在<b>带类型取值</b>的组里找「念得出」目标表名的那一个（{@code 'Post'} ↔ {@code posts}），恰好一个就选它，
     *       否则 UNDECIDABLE。多组达标最常见的原因是几张目标表的自增 id 范围重叠，数据本身分不出来
     *       （本地 MySQL 实测 role_resource.resource_id → agent.id：AGENT 与 KB 两组都是全中）。
     *       ★ 判别列为空的那一类<b>也算进「多组」</b>：它与某个类型都全中时同样可能是 id 重叠，而为空的历史行恰恰更可能才是指向目标表的那一类——
     *       只看带类型的组、记下唯一达标的那个取值，模型照着 {@code = 'KB'} 去连 agent，是一条静默出错数的 join。</li>
     *   <li>没有达标、但有组过了弱支持线 → WEAK，不记判别值。</li>
     *   <li>全都没过弱支持线 → <b>只有组带全、内层样本没触到 LIMIT、每一组都够样本、且没有哪一类判别列为空的行可能指向目标表，才 REJECTED</b>，
     *       否则 UNDECIDABLE：没对上的那一类可能只是没被采到、排在被截掉的组里、或者混在为空的行里被摊薄了，
     *       拿它删掉一条正确的关系是这套机制能犯的最坏的错。为空的那一类为什么不按「过没过半」判，见 {@link #blankGroupMayPointToTarget}。</li>
     * </ol>
     *
     * @param allGroupsSeen  带回的是不是全部的组（返回行数小于 {@link #POLY_MAX_GROUPS} 且结果没被截断）；
     *                       本方法按 {@code groups.size()} 自己再守一道
     * @param sampleComplete 内层派生表有没有触到采样 LIMIT（组带全时 Σsample_n 小于采样上限；为空的那几组同样计入）
     */
    static PolyDecision decidePolymorphic(List<Group> groups, boolean allGroupsSeen, boolean sampleComplete,
                                          String toObject) {
        // 纯函数自己也守一道：组数到了上限就不可能「带全」，不管调用方传的是什么。
        boolean seenAll = allGroupsSeen && groups.size() < POLY_MAX_GROUPS;
        List<Group> decidable = groups.stream().filter(g -> g.sampleN() >= MIN_SAMPLE).toList();
        List<Group> confirmed = decidable.stream().filter(g -> g.containment() >= TH_CONFIRMED).toList();
        if (!seenAll && hiddenGroupMayConfirm(groups)) {
            return new PolyDecision(ConnectorSemanticService.V_UNDECIDABLE, null, false, false, confirmed.size(),
                    true, false);
        }
        if (confirmed.size() == 1) {
            Group only = confirmed.get(0);
            return new PolyDecision(ConnectorSemanticService.V_CONFIRMED, only, !only.blank(), false, 1, false, false);
        }
        if (confirmed.size() > 1) {
            // 打破平局只在带类型取值的组里找：为空的那一类没有名字可对，而它的存在本身已经让「只有一类达标」不成立。
            List<Group> named = confirmed.stream()
                    .filter(g -> !g.blank() && valueNamesTable(g.value(), toObject))
                    .toList();
            if (named.size() == 1) {
                return new PolyDecision(ConnectorSemanticService.V_CONFIRMED, named.get(0), true, true,
                        confirmed.size(), false, false);
            }
            return new PolyDecision(ConnectorSemanticService.V_UNDECIDABLE, null, false, false, confirmed.size(),
                    false, false);
        }
        // maxBy 在并列时保留先出现的那个；组的顺序由 SQL 的 ORDER BY 定死，所以结果可重复。
        Group best = decidable.stream()
                .max(Comparator.comparingDouble(Group::containment).thenComparingInt(Group::matchN))
                .orElse(null);
        if (best != null && best.containment() >= TH_WEAK) {
            return new PolyDecision(ConnectorSemanticService.V_WEAK, best, false, false, 0, false, false);
        }
        boolean rejectable = best != null && seenAll && sampleComplete && decidable.size() == groups.size();
        boolean blankBlocks = rejectable && groups.stream().anyMatch(SemanticJoinValidator::blankGroupMayPointToTarget);
        if (rejectable && !blankBlocks) {
            return new PolyDecision(ConnectorSemanticService.V_REJECTED, best, false, false, 0, false, false);
        }
        return new PolyDecision(ConnectorSemanticService.V_UNDECIDABLE, best, false, false, 0, !seenAll, blankBlocks);
    }

    /**
     * 判别列为空的那一类行，<b>可不可能</b>也指向目标表。只对为空的组有意义，带类型取值的组一律 {@code false}。
     *
     * <h4>为什么不按「过没过半」判</h4>
     * 带类型取值的一组是同一种类型：包含率不过半，就是这一类不指向目标表。为空的一组却不是一种类型——
     * 按默认类型写入的历史行、漏写类型的新行、脏数据都落在这里，指向目标表的那一部分被其余行摊薄，百分比能被压到任意低。
     * 兜得住它的只有绝对数：命中数达到 {@link #MIN_CONFIRM_MATCH}，就足以在里面藏下一个自己够样本、自己达确认线的子类——
     * 与 {@link #hiddenGroupMayConfirm} 推断「看不到的组能不能达标」用的是同一条下界。
     * 命中数不够、但比例过了弱支持线的小组（5 个取值全中）同样算可能：几个取值证明不了它不指向目标表。
     * 只有一个都没命中、或只是大样本里零星几个命中，才算不指向。
     */
    static boolean blankGroupMayPointToTarget(Group g) {
        if (g == null || !g.blank() || g.matchN() <= 0) {
            return false;
        }
        return g.matchN() >= MIN_CONFIRM_MATCH || g.containment() >= TH_WEAK;
    }

    /** 可能也指向目标表的那几组判别列为空的行，按组的原顺序；{@code exclude}（通常是已被选中的那一组）不算。 */
    static List<Group> blankGroupsMayPointTo(List<Group> groups, Group exclude) {
        return groups.stream()
                .filter(g -> g != exclude && blankGroupMayPointToTarget(g))
                .toList();
    }

    /**
     * 组没带全时，看不到的组里<b>有没有可能</b>藏着一个达到确认线的。
     *
     * <p>不是猜：{@link #polymorphicSql} 按 {@code match_n DESC} 排序后才 LIMIT，所以每个看不到的组的命中数都不超过看得到的组里最小的那个；
     * 而一个组要达标，命中数至少是 {@link #MIN_CONFIRM_MATCH}。看得到的最小命中数都够不上它，看不到的就一个都不可能达标——
     * 此时「恰好一个达标」这句话仍然成立。够得上就说不准，只能判不出来。
     * <b>这条推理依赖那条 SQL 的排序键</b>，改排序键要一起改这里（有单测钉住排序键）。
     */
    static boolean hiddenGroupMayConfirm(List<Group> visible) {
        int minMatch = visible.stream().mapToInt(Group::matchN).min().orElse(Integer.MAX_VALUE);
        return minMatch >= MIN_CONFIRM_MATCH;
    }

    /**
     * 判别值「念不念得出」目标表名：取值的最后一段（{@code App\Models\Post} → {@code Post}）与表名归一后比较，
     * 允许复数与表名前缀（{@code Post} ↔ {@code posts}，{@code AGENT} ↔ {@code ai_agent}）。
     *
     * <p><b>只在多个判别值都达标时用来打破平局</b>，从不单独据此下结论：它是名字上的对应，不是测量。
     * 少于 3 个字符的取值（{@code 'A'}、{@code '1'}）不猜——对得上什么都是碰巧。
     */
    static boolean valueNamesTable(String value, String table) {
        if (value == null || table == null) {
            return false;
        }
        String last = "";
        String[] segments = value.split("[\\\\/.:]");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isBlank()) {
                last = segments[i];
                break;
            }
        }
        String v = normalize(last);
        String t = normalize(table);
        if (v.length() < 3 || t.isEmpty()) {
            return false;
        }
        List<String> forms = new ArrayList<>(List.of(v, v + "s", v + "es"));
        if (v.endsWith("y")) {
            forms.add(v.substring(0, v.length() - 1) + "ies");
        }
        for (String f : forms) {
            if (t.equals(f) || t.endsWith(f)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 多态外键的分组探查 SQL：左表按判别值分组，组内算一次包含率。
     *
     * <p>包含率的算法与 {@link #containmentSql} 完全同形（按去重取值、右表不采样、{@code COUNT(DISTINCT CASE ...)}
     * 防大小写合并与 fan-out），只多了一个分组键；那边每一条「为什么」在这里同样成立。
     * 外键列去 NULL（空串过滤仍只加在字符型外键列上）。
     *
     * <p>★ <b>判别列一个值都不滤</b>：判别值为 NULL 的行自成一组，为空串的行自成一组。上一版滤掉了它们，
     * 返回的组看起来是齐的，却少了最可能正指向目标表的那一类（按默认类型写入的历史行），一条真关系因此被 REJECTED，
     * 见 {@link #decidePolymorphicByGroups}。本地 MySQL 8.0.46 实测：GROUP BY 把 NULL 与 {@code ''} 分成两组；
     * 数值判别列上 {@code 0} 与 NULL 也是两组——这也是不在判别列上写 {@code <> ''} 的另一个理由：数值列上 MySQL 会把 {@code ''} 转成 0，
     * 把合法的 0 整批剔掉。
     *
     * <p>{@code ORDER BY match_n DESC} 让截断时留下命中最多的几组：真判别列上，指向目标表的那一类命中数最多。
     * 最后按 {@code disc_v} 兜底（NULL 在升序里排最前），同一份数据两次探查带回同一批组。
     * ★ 这个排序键同时是 {@link #hiddenGroupMayConfirm} 推断「看不到的组命中数不超过看得到的最小值」的前提，改它要一起改那边。
     *
     * <p>★ {@code LIMIT} 在 SQL 里，被截掉的组根本回不到 Java，结果集上也没有「还有更多」的标记——
     * 组带没带全只能靠数返回的行数（见 {@link #decidePolymorphicByGroups}），不能靠 {@code QueryResult.truncated()}。
     */
    String polymorphicSql(JoinCandidate c, String discriminator, boolean textLikeKey) {
        String left = quote(c.getFromObject());
        String key = quote(c.getFromColumn());
        String disc = quote(discriminator);
        String right = quote(c.getToObject());
        String rightCol = quote(c.getToColumn());
        StringBuilder where = new StringBuilder(key).append(" IS NOT NULL");
        if (textLikeKey) {
            where.append(" AND ").append(key).append(" <> ''");
        }
        return "SELECT jm_s.jm_d AS disc_v, COUNT(DISTINCT jm_s.jm_k) AS sample_n,"
                + " COUNT(DISTINCT CASE WHEN jm_r." + rightCol + " IS NULL THEN NULL ELSE jm_s.jm_k END) AS match_n"
                + " FROM (SELECT DISTINCT " + disc + " AS jm_d, " + key + " AS jm_k FROM " + left
                + " WHERE " + where + " LIMIT " + sampleValues + ") jm_s"
                + " LEFT JOIN " + right + " jm_r ON jm_r." + rightCol + " = jm_s.jm_k"
                + " GROUP BY jm_s.jm_d ORDER BY match_n DESC, sample_n DESC, disc_v ASC LIMIT " + POLY_MAX_GROUPS;
    }

    /**
     * 解析分组结果。形状不对返回 {@code null}，与「一组都没有」（空列表）分开。
     *
     * <p>★ 判别值为 NULL 的那一行照常解析成一组（{@code value == null}），<b>不跳过</b>：跳过它就等于在 Java 侧把 SQL 刚放进来的那一类又滤掉，
     * 「组带全了没有」「没对上的那一类会不会只是没看到」这两件事又会在看不见的地方算错。
     */
    private static List<Group> groups(QueryResult qr) {
        if (qr == null || qr.rows() == null) {
            return null;
        }
        List<String> cols = qr.columns();
        int vi = labelIndex(cols, "disc_v", 0);
        int si = labelIndex(cols, "sample_n", 1);
        int mi = labelIndex(cols, "match_n", 2);
        List<Group> out = new ArrayList<>(qr.rows().size());
        for (List<Object> row : qr.rows()) {
            if (row == null || row.size() <= Math.max(vi, Math.max(si, mi))) {
                return null;
            }
            Long s = asLong(row.get(si));
            Long m = asLong(row.get(mi));
            if (s == null || m == null) {
                return null;
            }
            Object v = row.get(vi);
            out.add(new Group(v == null ? null : String.valueOf(v), (int) Math.min(s, Integer.MAX_VALUE),
                    (int) Math.min(m, Integer.MAX_VALUE)));
        }
        return out;
    }

    private static int labelIndex(List<String> cols, String label, int fallback) {
        int i = cols == null ? -1 : cols.indexOf(label);
        return i >= 0 ? i : fallback;
    }

    private static JoinValidationResult done(String outcome, String note, List<JoinVerdict> verdicts, RunState run) {
        return JoinValidationResult.builder()
                .outcome(outcome)
                .note(note)
                .verdicts(List.copyOf(verdicts))
                .probeCount(run.probeCount)
                .sampledRows(run.sampledRows)
                .build();
    }

    /** 一轮的可变状态。刻意不放在字段上——本类是单例 bean，字段状态会在并发剖析之间串味。 */
    private static final class RunState {
        int probeCount;
        long sampledRows;
        long nextProbeAtMs;
        /** (表,列) → 是否基本唯一；值为 null 表示测过但测不出来，所以用 containsKey 判缓存命中。 */
        final Map<String, Boolean> uniqueness = new LinkedHashMap<>();
        /** 非空表示整轮该停了（连接级故障 / 限流 / 中断）。 */
        String fatal;
    }

    /**
     * 探查没拿到数的内部信号。
     *
     * <p>做成受检异常而不是返回值，是为了让「拿不到数就不准往下算包含率」在<b>编译期</b>成立——
     * 返回一个可能为 null 的 QueryResult，迟早有人忘了判。
     */
    private static final class ProbeFailure extends Exception {
        final String verified;
        final String basis;
        final String reason;
        final boolean probed;

        private ProbeFailure(String verified, String basis, String reason, boolean probed) {
            // 不填 message、不填 cause、不收栈：它是一个内部信号，不该被打印，更不该被抛给上层。
            super(null, null, false, false);
            this.verified = verified;
            this.basis = basis;
            this.reason = reason;
            this.probed = probed;
        }

        /** 打到客户库上了，但判不出来。 */
        static ProbeFailure undecidable(String basis, String reason) {
            return new ProbeFailure(ConnectorSemanticService.V_UNDECIDABLE,
                    basis + "（不代表这条关系是错的）", reason, true);
        }

        /** 压根没打出去。 */
        static ProbeFailure notProbed(String reason) {
            return new ProbeFailure(ConnectorSemanticService.V_NONE, "未经数据验证", reason, false);
        }
    }
}
