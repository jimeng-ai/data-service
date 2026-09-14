package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.enums.ExceptionCode;
import com.jimeng.common.core.exception.ServiceException;
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
import com.jimeng.persistence.entity.ConnectorSchema;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * S4 值域剖析：把<b>低基数列在客户库里实际存在的那一组取值</b>取回来，让模型不再对着
 * 不存在的值写查询条件。
 *
 * <h3>它解决的那一个具体失败</h3>
 * 用户问「华东区的销售额」，库里存的是「华东」。模型写出
 * {@code WHERE region = '华东区'}，SQL 语法正确、执行成功、<b>返回 0 行</b>，
 * 于是它回答「华东区没有销售记录」。整条链上没有任何一处报错。
 * 有了这一列的取值集合 {@code [华东, 华北, 华南, 西南]}，这次失败根本不会发生。
 *
 * <h3>★ 不采样。一次全表 SELECT DISTINCT，或者明说放弃</h3>
 * 「先抽 10% 看看有哪些值」是这件事上最自然、也最错的做法。设计阶段实测过：
 * 在一个只有 4 种取值的列上，按 10% 采样率独立采 20 次——
 * <b>13/20 只看到 1 种取值，0/20 拿到完整的 4 种</b>。
 * 而采样查询返回的 {@code truncated} 是 <b>false</b>，于是模型会把一个残缺集合当成全集，
 * 写出 {@code WHERE region IN ('华东')} 并对结果深信不疑——
 * <b>比没有值域更糟</b>：没有值域时它至少知道自己不知道。
 *
 * <p>所以这里只有两种结局，没有中间态：
 * <ul>
 *   <li>基数在阈值以内 → <b>全表</b> {@code SELECT DISTINCT}，拿到完整集合；</li>
 *   <li>基数超过阈值、或者测不出来 → <b>放弃并明说</b>，绝不给一个部分集合。</li>
 * </ul>
 * 代价是一次全表扫描。它发生在<b>接入时</b>，不在提问路径上，一条列一次。
 *
 * <h3>★ 基数闸必须先跑，而且要用自己的短超时</h3>
 * 顺序不能反：先 {@code COUNT(DISTINCT c)}，过了闸才去取值。
 * 而 {@code COUNT(DISTINCT)} 在高基数列上<b>本仓库实测跑到过 24 秒</b>
 *（见 {@code MySqlSession.ESTIMATE_TIMEOUT_SEC} 的注释），远超查询的 15 秒默认超时。
 * 所以这一步用 {@link #cardinalityTimeoutSeconds} 这个更短的专用超时，
 * <b>超时按「高基数」处理</b>——跳过这一列、记一句话，而不是让整次剖析失败。
 * 「这列基数太大我不查」和「这次剖析挂了」对调用方是两件事。
 *
 * <h3>★ 第 3 档，默认关闭</h3>
 * 一列的取值集合<b>就是客户的真实数据</b>，所以它属于 {@link SemanticDataTier#SAMPLE_VALUES}，
 * 默认关闭、须企业超管显式开启。档位不允许时返回的是
 * {@link #NOT_ENABLED_NOTE} 这句话，<b>不是一个空集合</b>——
 * 「这列没有枚举值」和「没去查这列的枚举值」对模型的含义完全相反，
 * 前者会让它放心写死条件。
 *
 * <p>设计文档 §4 说「枚举值域，机器全表查一次，不用人管」，与档位默认关闭看似冲突。
 * 实际含义是：<b>不需要人去填任何一个取值</b>（这件事机器自己做得比人好也比人全），
 * 但<b>要不要让真实取值离开这个库</b>仍然是客户的决定。两者不矛盾：
 * 档位决定这件事跑不跑，跑起来之后一个字都不用人输。
 *
 * <h3>★ 只给集合，绝不给含义</h3>
 * 本类的产出是 {@code st 的取值是 0/1/2/3} 这一个事实，<b>永远不会是</b>
 * {@code 0 = 待支付}。后面那句话完全合理、完全可能错，而且<b>没有任何验证能发现它错</b>——
 * 它不会让查询报错，只会让一个数字悄悄算错。这是整个语义层设计里最硬的一条纪律，
 * 见 {@code ConnectorSemantic.evidence} 的注释。所以本类不叫模型、不产生任何一句解释，
 * 连 {@link Outcome#modelNote()} 都会明写「平台不知道每个取值代表什么」。
 *
 * <h3>PII 过滤不是可选项</h3>
 * 取值集合天然会把个人信息捞出来。每一列在<b>去查之前</b>先过 {@link PiiFilter#screenColumn}
 *（列名那一层，空表上的手机号列同样拦下），取回来之后<b>落库之前</b>再过
 * {@link PiiFilter#screenValues}。两道都命中即整列丢弃，理由与取舍见 {@link PiiFilter} 的类注释。
 *
 * <h3>节奏与审计</h3>
 * <b>一条语句一次 {@link ConnectorGateway#executeAsPlatform}</b>，不攒成一个大调用。
 * 网关按调用记审计，语句原文是从 {@link QueryResult#effectiveStatement()} 取的——
 * 攒成一次就只有最后一条语句进审计表，而客户的 DBA 在他自己的审计里看到的是几百条。
 * 两边对不上的审计等于没有审计。同时并发闸、平台侧限流也都是按调用计的。
 *
 * <p>★ 还要<b>自己节流</b>（{@link #statementsPerMinute}），与 {@code SemanticJoinValidator} 同一条纪律。
 * 不节流不是「跑得快一点」，是<b>跑不完</b>：网关给管理面的速率桶默认 60 次/分钟，
 * 而一次剖析要打几百条语句，于是每一轮都会在同一个地方撞上 RATE_LIMITED 然后中止——
 * 而本类没有断点续跑的游标，下一轮又从第一列开始，<b>后面那些列永远采不到，且没有任何报错</b>。
 * 数值取得比关系探查（30/分钟）更低，因为两者共用那一个平台桶
 *（20 + 30 = 50 &lt; 60），而且值域的每一条语句都是<b>全表扫描</b>，对客户的库更重。
 *
 * <p>审计动作名用 {@link ConnectorAuditService#OP_SEMANTIC_VALUES}，<b>刻意与关系采样验证的
 * {@link ConnectorAuditService#OP_SEMANTIC_PROBE} 分开</b>：本类读的是客户的<b>真实取值</b>（第 3 档），
 * 关系验证只读聚合数（第 2 档）。两者敏感度差一个量级，而客户 DBA 在 {@code connector_audit} 里
 * 最常做的事就是按 operation 过滤——分名之后他不必读语句原文就能回答
 * 「平台到底有没有读过我的业务数据值」。合在一个名字下，这个问题只能靠逐条看 SQL 来回答，
 * 而那恰恰是审计表存在的意义。
 *
 * <h3>★ 列数上限是一条真的天花板，不是一次「大概够了」</h3>
 * 超过 {@link #maxColumns} 的列本轮不采，而本类<b>不持有断点游标</b>——
 * 下一轮刷新仍从第一列开始，于是排在后面的列<b>永远</b>采不到。
 * 所以两件事必须做到：候选列按「最可能是维度列」排序（{@link #priority}），让被切掉的是最没价值的尾巴；
 * 以及超限时在 {@code notes} 里明说还剩多少列。
 * 真正的解法是一个跨轮次的游标，但那需要一张表，<b>故意留给集成期决定</b>——
 * 在这里偷偷加一列存状态，会让本类从「一个可重跑的纯剖析」变成有持久化状态的东西。
 *
 * <h3>方言</h3>
 * 语句是 MySQL 形状（反引号包标识符）。今天 {@link QueryCapable} 只有 MySQL 一个实现，
 * 所以没有为一个不存在的第二方言引入抽象；引号与语句拼装集中在
 * {@link #quoteIdent} 与两处 SQL 常量上，将来加方言时只改那几处。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticValueProfiler {

    /**
     * 建议写进 {@code connector_semantic.detail_json} 的键名。
     * 提成常量是为了让写入方（集成期）和读出方（注入 conn_describe 的那段）用同一个字符串——
     * 这两处一旦分叉，表现是「值域采了但模型看不到」，没有任何报错。
     */
    public static final String DETAIL_KEY = "value_domain";

    /**
     * ★ 档位不允许时，<b>每一列</b>都该带上这句话，而不是什么都不写。
     *
     * <p>沉默会被模型读成「这列没有可枚举的取值」，于是它放心写死条件；
     * 而事实是我们<b>压根没去查</b>。这两件事对下一步的影响正好相反。
     */
    public static final String NOT_ENABLED_NOTE =
            "未启用样本值采集（数据出库档位第 3 档默认关闭），平台【没有去查】这一列的取值集合。"
                    + "这不等于这一列没有枚举值——不要据此写死查询条件。";

    /**
     * 一列剖析下来的结论。<b>每一种都必须能被区分</b>：
     * 「查了，就这些值」「查了，太多，放弃」「没查」对模型是三个不同的指令，
     * 混成「没有值域」这一种状态，就会让「我们没查」被读成「它没有枚举值」。
     */
    public enum Outcome {

        /** 拿到了完整取值集合。<b>只有这一种结局才会带 values</b>。 */
        ENUMERATED,

        /** 这一列当前一条非空取值都没有。注意它<b>不是</b>「没有枚举值」。 */
        EMPTY,

        /** 取值种类超过阈值，主动放弃。 */
        HIGH_CARDINALITY,

        /** 基数没测出来（超时或查询失败）。按高基数处理——见类注释第 4 条。 */
        CARDINALITY_UNKNOWN,

        /** 取回来了，但证明不了它完整（采集期间取值集合变大 / 命中字节上限）。宁可不要。 */
        INCOMPLETE,

        /** 疑似个人信息，整列不采。 */
        PII_BLOCKED,

        /** 类型或结构决定了这一列不会有有意义的枚举取值，没去查。 */
        NOT_ELIGIBLE,

        /** 数据出库档位不允许取真实取值。 */
        TIER_DISABLED,

        /** 查询失败。 */
        FAILED;

        /**
         * 这一条结论<b>对模型意味着什么</b>，一句话。
         *
         * <p>做成方法而不是散落在各个分支里拼字符串，是因为这句话会随语义层注入进模型上下文，
         * 它是本类防「静默残缺」的最后一道：每一种非 {@link #ENUMERATED} 的结局都必须
         * <b>明说平台没有拿到完整取值</b>，否则沉默就会被读成「这列没有别的取值了」。
         */
        public String modelNote() {
            return switch (this) {
                case ENUMERATED -> "以下是该列在客户库中的全部实际取值。"
                        + "平台【不知道】每个取值代表什么业务含义，也不会去猜——需要用到含义时请向用户确认。";
                case EMPTY -> "该列当前一条非空取值都没有。这不等于它没有枚举值，只说明此刻是空的。";
                case HIGH_CARDINALITY -> "该列取值种类过多，平台【没有】采集它的取值集合。"
                        + "不要假设它只有某几个取值。";
                case CARDINALITY_UNKNOWN -> "平台没能测出该列有多少种取值（查询超时或失败），"
                        + "因此【没有】采集它的取值集合。不要假设它只有某几个取值。";
                case INCOMPLETE -> "采集过程中该列的取值集合发生了变化，平台拿到的不是完整集合，因此【不采用】。"
                        + "不要把它当成全集。";
                case PII_BLOCKED -> "该列疑似个人信息，平台【不采集】它的取值。";
                case NOT_ELIGIBLE -> "该列的类型或结构决定了它不会有有意义的枚举取值，平台【没有】去查。";
                case TIER_DISABLED -> NOT_ENABLED_NOTE;
                case FAILED -> "采集该列取值时查询失败，平台【没有】拿到取值集合。不要假设它只有某几个取值。";
            };
        }
    }

    // ================================================================ 常量

    /**
     * 标识符白名单，与 {@code MySqlSession.describe} 用的那条<b>刻意同形</b>。
     *
     * <p>表名列名来自我们自己的 {@code information_schema} 快照而不是模型，但仍然要验：
     * 快照可能是旧的、可能被手工改过，而这两个名字是要<b>拼进 SQL 文本</b>的
     *（标识符没法用 PreparedStatement 占位）。验不过就不给这一列采值——
     * 少一个值域，换「本类永远拼不出一条奇怪的语句」。
     */
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9_$]{1,64}$");

    /**
     * 取值查询的字节上限。值域本身很小（阈值 × 单值长度上限，几 KB），
     * 给到 64KB 是纯余量；真撑爆说明这一列根本不是枚举列，那时 {@code truncated} 会让它落到
     * {@link Outcome#INCOMPLETE}，而不是悄悄少几个值。
     */
    private static final int FETCH_MAX_BYTES = 64 * 1024;

    /**
     * 不可能有有用值域的类型，按<b>类型</b>判，不按名字猜。
     *
     * <ul>
     *   <li>大文本 / 二进制 / JSON / 空间类型：取值即正文，{@code COUNT(DISTINCT)} 还特别贵；</li>
     *   <li>时间类型：取值是连续的，枚举它没有意义；</li>
     *   <li>浮点与定点小数：那是度量值（金额、比率），不是维度。</li>
     * </ul>
     * 刻意<b>没有</b>把整数类型排掉：{@code st tinyint} 取值 0/1/2/3 这种状态列是本功能最主要的目标。
     * 整数里的 id 列由「唯一 / 自增 / 单列主键」那几条元数据判据挡掉，剩下的漏网之鱼由基数闸兜底。
     */
    private static final Set<String> INELIGIBLE_TYPES = Set.of(
            "text", "tinytext", "mediumtext", "longtext",
            "blob", "tinyblob", "mediumblob", "longblob", "binary", "varbinary",
            "json", "geometry", "point", "linestring", "polygon",
            "multipoint", "multilinestring", "multipolygon", "geometrycollection",
            "date", "datetime", "timestamp", "time", "year",
            "float", "double", "real", "decimal", "numeric", "dec");

    /** {@code MySqlSession.describe} 把 {@code COLUMN_KEY}/{@code EXTRA} 拼进 extra 时用的词。 */
    private static final String EXTRA_PRIMARY = "主键";
    private static final String EXTRA_UNIQUE = "唯一";
    private static final String EXTRA_AUTO_INCREMENT = "auto_increment";

    // ================================================================ 配置

    /*
     * ★ 这些字段同时有 @Value 的内联默认和 Java 字段初始值，两者<b>必须相等</b>。
     *   Spring 里生效的是前者，单测里（没有容器）生效的是后者；写岔了的表现是
     *   「测试全绿、线上是另一套阈值」。SemanticValueProfilerTest 里有一条用反射把
     *   两者钉在一起的测试，改这里时它会替你报错。
     *
     *   配置键都挂在 connector.semantic.* 下但<b>不进 ConnectorProperties</b>：
     *   那个类同时被其它几条并行开发的分支改着，加字段必冲突；
     *   而 @ConfigurationProperties 会忽略它不认识的键，所以这样共存是安全的
     *   （先例：connector.semantic.infer-model）。
     */

    /** 运维总开关。与档位是两件事：档位是客户的选择，这个是平台自己的急停。 */
    @Value("${connector.semantic.value-profile-enabled:true}")
    private boolean valueProfileEnabled = true;

    /**
     * 基数阈值：超过这么多种取值就放弃。
     *
     * <p>50 的依据是「值域是拿来注入提示词的」：再多，模型读不完也用不上，
     * 而一个 200 项的列表占的上下文比它挽回的那次查询失败贵。
     */
    @Value("${connector.semantic.value-max-distinct:50}")
    private int maxDistinct = 50;

    /**
     * {@code COUNT(DISTINCT)} 的专用超时，<b>刻意比查询默认的 15 秒短</b>。
     * 理由见类注释：高基数列上它实测跑到过 24 秒，而我们在这一步<b>宁可放弃也不要拖住</b>——
     * 一条卡住的聚合占着客户库的连接、占着我们的并发闸，换来的只是一个我们本来就打算放弃的列。
     */
    @Value("${connector.semantic.value-cardinality-timeout-seconds:5}")
    private int cardinalityTimeoutSeconds = 5;

    /** 取值查询的超时。基数已经确认在阈值内，这一步只是把那几十个值取回来。 */
    @Value("${connector.semantic.value-fetch-timeout-seconds:10}")
    private int fetchTimeoutSeconds = 10;

    /**
     * 单个取值的长度上限。超了说明这一列是自由文本而不是枚举——
     * 此时<b>丢掉整列</b>而不是截断那个值：截断会造出一个库里根本不存在的取值，
     * 而模型照着它写条件，正好复现本功能要解决的那个「查不到还以为没数据」。
     */
    @Value("${connector.semantic.value-max-length:64}")
    private int maxValueLength = 64;

    /**
     * {@code varchar(n)} 声明长度超过它就不当枚举列看。
     *
     * <p>取 255 而不是更小：中文库里 {@code varchar(255)} 是懒惰 DDL 的默认值，
     * 大量真正的维度列（地区、渠道、状态名）都声明成 255。按声明长度把它们排掉，
     * 等于把本功能最主要的目标排掉了。真正的防线是基数闸和 {@link #maxValueLength}。
     */
    @Value("${connector.semantic.value-max-declared-length:255}")
    private int maxDeclaredLength = 255;

    /** 一次剖析最多碰多少列。超出的部分<b>明说</b>，不静默丢。 */
    @Value("${connector.semantic.value-max-columns:200}")
    private int maxColumns = 200;

    /**
     * 自己给自己定的速度：每分钟最多发多少条语句。理由见类注释「节奏与审计」那一节——
     * 这不是礼貌，是本轮能不能跑完的前提。
     *
     * <p>{@code <= 0} 会被 {@link #clampConfig()} 夹回 1，<b>不允许关掉</b>：
     * 配 0 的人想表达的多半是「不限速」，而不限速在这里等于让一次接入把客户的生产库当压测目标
     *（每一条语句都是全表扫描），并且必然撞上网关的平台速率桶。
     */
    @Value("${connector.semantic.value-statements-per-minute:20}")
    private int statementsPerMinute = 20;

    /**
     * 整次剖析的墙钟预算（秒）。{@code <= 0} 表示不设预算。
     *
     * <p>为什么要有：单列的两条语句各有超时，但列数没有天然上限——
     * 200 列全部撞上 5 秒的基数超时，加上节流的等待，就是小时级，全程压在客户的生产库上。
     * 预算用完就停下并<b>记一句「还有 N 列没剖析」</b>。
     *
     * <p>★ 默认值不是拍的，它必须<b>大于</b>「{@link #maxColumns} × 2 条语句 ÷
     * {@link #statementsPerMinute}」：小了就会出现「列数上限写着 200、实际每轮只采得到 45 列」
     * 这种两个配置项互相拆台的局面，而且表现上完全正常。{@link #clampConfig()} 在启动时
     * 把这个关系检查一遍并告警——两个数字分别看都合理、合起来不成立，是最难自己发现的那类配置错。
     */
    @Value("${connector.semantic.value-budget-seconds:1800}")
    private int budgetSeconds = 1800;

    private final ConnectorGateway gateway;
    private final ConnectorService connectorService;
    private final ConnectorSchemaService schemaService;
    private final PiiFilter piiFilter;

    /**
     * 启动时把配置夹回可用区间并告警，<b>夹紧而不是启动失败</b>——与 {@code SemanticJoinValidator}
     * 同一条纪律：值域剖析是叠加增强，拿一个配置笔误去否决整个服务是错的。
     *
     * <p>真正非做不可的是最后那条<b>互相拆台</b>的检查：{@code maxColumns} 与
     * {@code budgetSeconds} 分开看都合理，合起来却可能让「上限 200 列」实际只跑得到 45 列。
     * 这种配置错没有任何运行期症状——剖析每次都「正常结束」，只是后面的列永远没有值域。
     */
    @PostConstruct
    void clampConfig() {
        statementsPerMinute = clamp("value-statements-per-minute", statementsPerMinute, 1, 600);
        maxDistinct = clamp("value-max-distinct", maxDistinct, 1, 1000);
        cardinalityTimeoutSeconds = clamp("value-cardinality-timeout-seconds", cardinalityTimeoutSeconds, 1, 60);
        fetchTimeoutSeconds = clamp("value-fetch-timeout-seconds", fetchTimeoutSeconds, 1, 60);
        maxValueLength = clamp("value-max-length", maxValueLength, 1, 4000);
        maxDeclaredLength = clamp("value-max-declared-length", maxDeclaredLength, 1, 65535);
        maxColumns = clamp("value-max-columns", maxColumns, 1, 5000);

        long needSeconds = requiredBudgetSeconds(maxColumns, statementsPerMinute);
        if (budgetSeconds > 0 && budgetSeconds < needSeconds) {
            log.warn("connector.semantic 配置互相拆台：value-max-columns={} 在 value-statements-per-minute={} 的节流下"
                            + "至少需要 {} 秒，而 value-budget-seconds={}。每轮只会采到约 {} 列，剩下的列永远采不到"
                            + "（本类没有跨轮次游标）。请调大预算或调小列数上限",
                    maxColumns, statementsPerMinute, needSeconds, budgetSeconds,
                    Math.max(1, budgetSeconds * statementsPerMinute / 60 / 2));
        }
    }

    /** 一轮把 {@code columns} 列全部剖析完，光节流就至少要这么多秒（每列两条语句）。 */
    static long requiredBudgetSeconds(int columns, int statementsPerMinute) {
        return (long) columns * 2 * 60 / Math.max(1, statementsPerMinute);
    }

    private int clamp(String key, int value, int min, int max) {
        int v = Math.min(max, Math.max(min, value));
        if (v != value) {
            log.warn("connector.semantic.{}={} 超出可用区间 [{}, {}]，本次按 {} 生效", key, value, min, max, v);
        }
        return v;
    }

    // ================================================================ 对外

    /** 自己去读结构快照的版本。 */
    public ValueProfile profile(Long connectorId) {
        return profile(connectorId, null);
    }

    /**
     * 剖析一条连接上所有够格的列。
     *
     * <p><b>只有两种情况会抛</b>：连接 id 缺失，以及这条连接不存在或不属于当前租户
     *（由 {@link ConnectorService#dataTier} 抛 {@code ServiceException}）。
     * 那两种都是调用方传错了参数，必须让它知道。
     * 其余一切——档位不允许、快照为空、某一列查超时、整条连接连不上——
     * 都只体现在返回值里：这是一条<b>可选的增强</b>路径，它整体失败不该让语义层推导跟着失败。
     *
     * <p><b>★ 前提一：这是个后台作业，绝不能挂在请求线程上。</b>
     * 一轮最长会跑到 {@link #budgetSeconds}（默认 1800 秒）——节流本身就要求它慢。
     * 放在一次建连的 HTTP 请求里，或者放在一个 {@code CallerRunsPolicy} 的线程池里
     *（队列满时任务会就地跑在调用线程上），都会把这半小时加到某次请求的响应时间上。
     *
     * <p><b>前提二：调用线程必须有真实的租户上下文。</b>
     * {@code TenantContext.runAsSystem} 不算——它把 {@code CURRENT_TENANT} 留空，
     * 网关第 2 步会直接拒。后台任务要按那一行的 {@code tenant_id} 自己
     * {@code TenantContext.set(...)}，理由见 {@code ConnectorGateway.executeAsPlatform}。
     *
     * @param snapshot 已经在手上的结构快照；传 {@code null} 则自己去读。
     *                 集成期（推导链路）本来就持有这份快照，让它传进来是为了少一次库查询，
     *                 也保证剖析看到的结构和推导看到的<b>是同一份</b>——
     *                 两次读之间客户加了一列，会让锚点和值域对不上。
     */
    public ValueProfile profile(Long connectorId, List<ConnectorSchema> snapshot) {
        if (connectorId == null) {
            throw new ServiceException(ExceptionCode.INVALID_REQUEST, "缺少连接 id");
        }
        long start = System.currentTimeMillis();
        List<String> notes = new ArrayList<>();

        if (!valueProfileEnabled) {
            String note = "值域剖析已关闭（connector.semantic.value-profile-enabled=false）";
            return finish(ValueProfile.builder().tierAllowed(false).tierStatement(note), notes, note, start);
        }

        SemanticDataTier tier = connectorService.dataTier(connectorId);
        if (!tier.allowsSampleValues()) {
            // ★ 这里返回的是「没去查」，不是「查了没有」。两者对模型含义相反，见 NOT_ENABLED_NOTE。
            log.info("值域剖析未执行：数据出库档位不允许取真实取值 connectorId={} tier={}", connectorId, tier);
            return finish(ValueProfile.builder().tierAllowed(false).tierStatement(NOT_ENABLED_NOTE),
                    notes, NOT_ENABLED_NOTE, start);
        }

        List<ConnectorSchema> rows = snapshot != null ? snapshot : schemaService.currentRows(connectorId);
        if (rows == null || rows.isEmpty()) {
            String note = "结构快照为空，没有可剖析的列。请先刷新结构快照";
            return finish(ValueProfile.builder().tierAllowed(true).tierStatement(tier.egressStatement()),
                    notes, note, start);
        }

        ValueProfile.ValueProfileBuilder builder = ValueProfile.builder()
                .tierAllowed(true)
                .tierStatement(tier.egressStatement());

        List<Candidate> candidates = new ArrayList<>();
        List<ColumnValueDomain> domains = new ArrayList<>();
        RunState state = new RunState();
        pick(rows, candidates, domains, state, notes);

        // ★ 排序必须在截断之前：本类没有跨轮次游标，被切掉的尾巴是【永久】采不到的，
        //   所以要让切口落在最没价值的那一头，而不是落在表名字母序的后半段。
        candidates.sort(BY_PRIORITY);
        if (candidates.size() > maxColumns) {
            // 静默丢列会让下一次有人问「为什么这列没值域」时无从查起。
            notes.add("够格的列有 " + candidates.size() + " 个，超过单次上限 " + maxColumns
                    + "，本次只剖析优先级最高的 " + maxColumns + " 个；"
                    + "剩下 " + (candidates.size() - maxColumns) + " 列本轮及以后都不会被采集"
                    + "（本类没有跨轮次游标），需要的话请调大 connector.semantic.value-max-columns");
            candidates = candidates.subList(0, maxColumns);
        }

        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (budgetExhausted(start, System.currentTimeMillis(), budgetSeconds)) {
                // 数字必须给全：「还有列没采」这种说法会被读成「差不多都采了」。
                notes.add("剖析耗时已达预算 " + budgetSeconds + " 秒，已完成 " + state.attempted
                        + " 列，还有 " + (candidates.size() - i) + " 列没有剖析");
                break;
            }
            try {
                domains.add(profileColumn(connectorId, c, state));
            } catch (AbortProfiling e) {
                // 这一类失败对每一列都会重演（连不上、认证失败、被限流、没有查询能力），
                // 继续打剩下几百列只会把客户的库和限流桶一起耗光，还刷出几百条一模一样的审计。
                notes.add("剖析中止：" + e.getMessage() + "（已完成 " + state.attempted + " 列）");
                log.warn("值域剖析中止 connectorId={} reason={}", connectorId, e.getMessage());
                break;
            }
        }

        builder.domains(domains)
                .examinedColumns(state.examined)
                .eligibleColumns(state.eligible)
                .attemptedColumns(state.attempted)
                .enumeratedColumns(state.enumerated)
                .statements(state.statements);
        return finish(builder, notes, null, start);
    }

    /**
     * 把一列的结论摊成可以塞进 {@code connector_semantic.detail_json} 的片段。
     *
     * <p>★ 这里是「只有完整集合才写 values」这条不变式的<b>唯一出口</b>：
     * 调用方不要自己从 {@link ColumnValueDomain#getValues()} 拼 JSON，
     * 那样早晚会有一处把 {@code HIGH_CARDINALITY} 的 null 写成 {@code []}，
     * 而 {@code []} 读起来就是「这列没有取值」。
     */
    public static Map<String, Object> detailFragment(ColumnValueDomain domain) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean complete = domain != null && domain.hasCompleteValueSet();
        m.put("complete", complete);
        if (domain != null) {
            m.put("outcome", domain.getOutcome() == null ? null : domain.getOutcome().name());
            if (domain.getDistinctCount() != null) {
                m.put("distinct_count", domain.getDistinctCount());
            }
            if (complete) {
                m.put("values", domain.getValues());
            }
            m.put("note", domain.getNote());
        }
        return m;
    }

    /**
     * 档位没开时，每一列该写的那个片段。
     *
     * <p>提供它是为了让集成期<b>不必</b>自己拼这句话——
     * 「未启用样本值采集」如果有两份写法，其中一份迟早会退化成什么都不写。
     */
    public static Map<String, Object> notEnabledFragment() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("complete", false);
        m.put("outcome", Outcome.TIER_DISABLED.name());
        m.put("note", NOT_ENABLED_NOTE);
        return m;
    }

    // ================================================================ 选列（纯本地，不碰客户库）

    /**
     * 从结构快照里挑出够格的列。<b>这一整步不发一条语句</b>：
     * 类型、主键、自增、唯一这些判据全在快照里，先在本地把不该碰的列筛掉，
     * 能省下的是几百次真的打在客户生产库上的 {@code COUNT(DISTINCT)}。
     */
    private void pick(List<ConnectorSchema> rows, List<Candidate> out,
                      List<ColumnValueDomain> domains, RunState state, List<String> notes) {
        int skippedViews = 0;
        for (ConnectorSchema r : rows) {
            if (!isBaseTable(r.getObjectType())) {
                // 视图上的 SELECT DISTINCT 背后可能是一串 join，成本完全不可预估，
                // 而视图的列几乎总能在它依赖的基表上找到同一份取值。
                skippedViews++;
                continue;
            }
            List<FieldDetail> fields = parseFields(r);
            long primaryKeyColumns = fields.stream()
                    .filter(f -> hasExtra(f, EXTRA_PRIMARY))
                    .count();
            for (FieldDetail f : fields) {
                state.examined++;
                if (ineligible(f, primaryKeyColumns)) {
                    // NOT_ELIGIBLE 的列不进 domains：一个库几千列，把「这是个 datetime」也写成一条
                    // 说明，只会把真正要紧的那几条（高基数放弃了、PII 拦下了）淹掉。
                    continue;
                }
                state.eligible++;
                PiiFilter.PiiVerdict pii = piiFilter.screenColumn(r.getObjectName(), f.name(), f.comment());
                if (pii.sensitive()) {
                    // 与 NOT_ELIGIBLE 相反，这一条要留痕：这一列<b>看起来</b>完全够格，
                    // 不解释一句，下次就会有人以为是漏采了，然后去把过滤放宽。
                    state.piiBlocked++;
                    domains.add(domain(r.getObjectName(), f.name(), Outcome.PII_BLOCKED, null, null,
                            Outcome.PII_BLOCKED.modelNote() + "（判据：" + pii.reason() + "）"));
                    continue;
                }
                out.add(new Candidate(r.getObjectName(), f.name(), f.comment(),
                        priority(f), out.size()));
            }
        }
        if (skippedViews > 0) {
            notes.add("跳过了 " + skippedViews + " 个视图（视图上的全表去重成本不可预估，其列的取值应到基表上采）");
        }
        if (state.piiBlocked > 0) {
            notes.add("有 " + state.piiBlocked + " 列按列名/列注释判定为疑似个人信息列，未去采集它们的取值");
        }
    }

    /** MySQL 的 {@code TABLE_TYPE} 是 {@code BASE TABLE} / {@code VIEW} / {@code SYSTEM VIEW}。 */
    private static boolean isBaseTable(String objectType) {
        if (objectType == null || objectType.isBlank()) {
            // 老快照没记类型。当基表处理——基数闸本来就兜着，而一律跳过会让存量连接一个值域都采不到。
            return true;
        }
        String t = objectType.trim().toUpperCase(Locale.ROOT);
        return t.equals("TABLE") || t.equals("BASE TABLE");
    }

    /**
     * 这一列<b>不</b>够格采值域吗。
     *
     * <p>判据只用<b>类型</b>和<b>快照里的元数据</b>（主键 / 唯一 / 自增 / 声明长度），
     * 不看列名——按名字猜「这看着像个 id」会在客户的命名习惯之外立刻失效，
     * 而且失效的方向是随机的：同一个库里两列同样的东西，一列采了一列没采，没人能解释。
     *
     * @param primaryKeyColumns 这张表有几列带主键标记。恰好 1 列时它是单列主键 ⇒ 天然唯一。
     */
    private boolean ineligible(FieldDetail f, long primaryKeyColumns) {
        if (f.name() == null || !IDENT.matcher(f.name()).matches()) {
            return true;
        }
        if (hasExtra(f, EXTRA_AUTO_INCREMENT) || hasExtra(f, EXTRA_UNIQUE)) {
            // 自增和唯一索引意味着 distinct 数 == 行数，基数闸必然放弃它，
            // 但那要先付一次全表 COUNT(DISTINCT) 的代价。元数据已经把答案写在这儿了。
            return true;
        }
        if (primaryKeyColumns == 1 && hasExtra(f, EXTRA_PRIMARY)) {
            // 复合主键的成员列可以是低基数维度（比如按地区分区的联合主键），所以只排单列主键。
            return true;
        }
        String type = baseType(f.type());
        if (type == null || INELIGIBLE_TYPES.contains(type)) {
            return true;
        }
        Integer declared = declaredLength(f.type());
        if (declared != null && declared > maxDeclaredLength) {
            return true;
        }
        return false;
    }

    /** {@code varchar(32)} / {@code int(11) unsigned} / {@code enum('a','b')} → {@code varchar} / {@code int} / {@code enum}。 */
    static String baseType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return null;
        }
        String t = rawType.trim().toLowerCase(Locale.ROOT);
        int cut = t.length();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '(' || c == ' ') {
                cut = i;
                break;
            }
        }
        String base = t.substring(0, cut);
        return base.isEmpty() ? null : base;
    }

    /**
     * {@code varchar(255)} → 255。{@code enum('a','b')} 括号里不是长度，返回 {@code null}——
     * 把 {@code 'a','b'} 当成长度解析会抛，而 enum 恰恰是最该采值域的那种列。
     */
    static Integer declaredLength(String rawType) {
        if (rawType == null) {
            return null;
        }
        int open = rawType.indexOf('(');
        int close = rawType.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }
        String inner = rawType.substring(open + 1, close).trim();
        // 逗号分隔（decimal(10,2)、enum(...)）一律不当长度看。
        for (int i = 0; i < inner.length(); i++) {
            if (!Character.isDigit(inner.charAt(i))) {
                return null;
            }
        }
        if (inner.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(inner);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 这一列<b>像不像一个维度列</b>，越大越先采。判据全部来自类型与声明长度，
     * 不看列名——按名字猜会在客户的命名习惯之外立刻失效，而且失效方向是随机的。
     *
     * <p>它只在 {@link #maxColumns} 真的绑住时才起作用，但那时它决定的是<b>哪些列永远没有值域</b>：
     * 没有排序的话，切口落在表名字母序上，于是「t_z 开头的表一个值域都没有」——
     * 一个完全说不出道理、也没人会想到去查的覆盖缺口。
     */
    private static int priority(FieldDetail f) {
        String type = baseType(f.type());
        if (type == null) {
            return 0;
        }
        // enum / set 的取值集合本来就写在 DDL 里，基数必然很小，采起来几乎不花钱，收益最确定。
        if (type.equals("enum") || type.equals("set")) {
            return 4;
        }
        // 窄整数就是状态码那一类列：`st tinyint` 取值 0/1/2/3 正是本功能的原型案例。
        if (type.equals("tinyint") || type.equals("smallint") || type.equals("bit")
                || type.equals("bool") || type.equals("boolean")) {
            return 3;
        }
        Integer declared = declaredLength(f.type());
        if (type.equals("char") || type.equals("varchar")) {
            // 短字符串是编码 / 维值（「华东」「PAID」）；长的多半是名称或描述，采到的概率低得多。
            return declared != null && declared <= 32 ? 2 : 1;
        }
        // 宽整数：大多是 id 和外键，基数闸八成会放弃它们。排最后。
        return 0;
    }

    /** 优先级降序；同级按快照原始次序，保证同一份快照两次剖析选中的是同一批列。 */
    private static final Comparator<Candidate> BY_PRIORITY =
            Comparator.comparingInt(Candidate::priority).reversed()
                    .thenComparingInt(Candidate::seq);

    private static boolean hasExtra(FieldDetail f, String token) {
        return f.extra() != null && f.extra().contains(token);
    }

    /** 从快照的 {@code detail_json} 还原列。形状由 {@code ConnectorSchemaService.toDetailMap} 决定。 */
    @SuppressWarnings("unchecked")
    private List<FieldDetail> parseFields(ConnectorSchema r) {
        List<FieldDetail> out = new ArrayList<>();
        if (r.getDetailJson() == null || r.getDetailJson().isBlank()) {
            return out;
        }
        try {
            Map<String, Object> m = CommonUtil.getObjectMapper().readValue(r.getDetailJson(), Map.class);
            if (!(m.get("fields") instanceof List<?> list)) {
                return out;
            }
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> fm) || fm.get("name") == null) {
                    continue;
                }
                out.add(new FieldDetail(
                        String.valueOf(fm.get("name")),
                        asString(fm.get("type")),
                        !Boolean.FALSE.equals(fm.get("nullable")),
                        asString(fm.get("comment")),
                        asString(fm.get("extra"))));
            }
        } catch (Exception e) {
            // 一张表的快照 JSON 坏了不该让整次剖析失败：这张表没列可采，其余的照采。
            log.warn("解析结构快照失败，本次跳过该对象的列 objectName={}", r.getObjectName(), e);
        }
        return out;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ================================================================ 单列剖析（两条语句）

    private ColumnValueDomain profileColumn(Long connectorId, Candidate c, RunState state) {
        state.attempted++;

        // ── 第一条：基数闸。★ 必须先跑，理由见类注释 ──
        Long distinct;
        try {
            QueryResult qr = run(connectorId, countDistinctSql(c), 1, cardinalityTimeoutSeconds, state);
            distinct = firstLong(qr);
        } catch (ConnectorException e) {
            abortIfFatal(e);
            if (e.getCode() == ConnectorErrorCode.TIMEOUT) {
                // ★ 超时不是失败，它本身就是一条证据：能把专用短超时跑满的列，基数大概率很大。
                //   按「高基数」处理、跳过这一列，而不是让整次剖析挂掉。
                log.info("基数查询超时，按高基数处理 connectorId={} object={} column={}",
                        connectorId, c.objectName(), c.columnName());
                return domain(c, Outcome.CARDINALITY_UNKNOWN, null, null,
                        Outcome.CARDINALITY_UNKNOWN.modelNote()
                                + "（基数查询超过 " + cardinalityTimeoutSeconds + " 秒被放弃）");
            }
            return domain(c, Outcome.FAILED, null, null,
                    Outcome.FAILED.modelNote() + "（" + safeReason(e) + "）");
        }

        if (distinct == null) {
            return domain(c, Outcome.CARDINALITY_UNKNOWN, null, null,
                    Outcome.CARDINALITY_UNKNOWN.modelNote() + "（基数查询没有返回可用结果）");
        }
        if (distinct == 0L) {
            return domain(c, Outcome.EMPTY, 0L, null, Outcome.EMPTY.modelNote());
        }
        if (distinct > maxDistinct) {
            // ★ 这里<b>不</b>退而求其次去取前 N 个：一个带着 truncated=false 的部分集合，
            //   比没有值域更危险。明说放弃。
            return domain(c, Outcome.HIGH_CARDINALITY, distinct, null,
                    Outcome.HIGH_CARDINALITY.modelNote()
                            + "（共 " + distinct + " 种取值，超过阈值 " + maxDistinct + "）");
        }

        // ── 第二条：全表 SELECT DISTINCT。不采样，理由见类注释 ──
        // 多要一个：LIMIT 只要没被撑满，就证明这就是全集；撑满了说明期间取值变多了，
        // 这时宁可整列不要，也不给一个看起来完整的残缺集合。
        int fetchLimit = (int) (distinct + 1);
        QueryResult qr;
        try {
            qr = run(connectorId, distinctSql(c, fetchLimit), fetchLimit, fetchTimeoutSeconds, state);
        } catch (ConnectorException e) {
            abortIfFatal(e);
            return domain(c, Outcome.FAILED, distinct, null,
                    Outcome.FAILED.modelNote() + "（" + safeReason(e) + "）");
        }

        List<String> values = firstColumn(qr);
        if (qr.truncated() || values.size() >= fetchLimit) {
            return domain(c, Outcome.INCOMPLETE, distinct, null,
                    Outcome.INCOMPLETE.modelNote()
                            + "（基数闸测得 " + distinct + " 种，取回时不止这些）");
        }
        for (String v : values) {
            if (v.length() > maxValueLength) {
                // 不截断，丢整列。截出来的值在客户库里不存在，模型照着它写条件就是 0 行。
                return domain(c, Outcome.NOT_ELIGIBLE, distinct, null,
                        Outcome.NOT_ELIGIBLE.modelNote()
                                + "（取值长度超过 " + maxValueLength + " 字符，判定为自由文本而非枚举列）");
            }
        }

        // ★ 落库之前的第二道 PII 闸。列名那一层已经过了，这一层管的是名字看不出来的列（f1、col3）。
        PiiFilter.PiiVerdict pii = piiFilter.screenValues(values);
        if (pii.sensitive()) {
            state.piiBlocked++;
            log.info("值域命中个人信息判据，整列丢弃 connectorId={} object={} column={} reason={}",
                    connectorId, c.objectName(), c.columnName(), pii.reason());
            return domain(c, Outcome.PII_BLOCKED, distinct, null,
                    Outcome.PII_BLOCKED.modelNote() + "（判据：" + pii.reason() + "）");
        }

        // 排序只在 Java 侧做：SQL 里加 ORDER BY 会让数据库为了排序把提前结束的机会也放弃掉，
        // 而我们要的只是「同一份数据两次剖析产出同一个 JSON」——否则 detail_json 每次都变，
        // 结构漂移检测会永远报一对 ADDED/REMOVED。
        List<String> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        state.enumerated++;
        return domain(c, Outcome.ENUMERATED, distinct, sorted,
                Outcome.ENUMERATED.modelNote() + "（共 " + sorted.size() + " 个取值，已全量采集）");
    }

    // ================================================================ 语句

    /** {@code SELECT COUNT(DISTINCT `col`) FROM `tbl`} */
    private String countDistinctSql(Candidate c) {
        return "SELECT COUNT(DISTINCT " + quoteIdent(c.columnName()) + ") FROM " + quoteIdent(c.objectName());
    }

    /**
     * {@code SELECT DISTINCT `col` FROM `tbl` WHERE `col` IS NOT NULL LIMIT n}
     *
     * <p>{@code IS NOT NULL} 不是为了隐藏 NULL，是为了让这条语句和基数闸<b>数的是同一件事</b>：
     * {@code COUNT(DISTINCT c)} 本来就不计 NULL，不加这个条件，取回来的行数会比基数多一
     *（多出来的那行是 NULL），于是完整性判据永远判成「不完整」。
     * 「这一列可不可以为空」在结构快照的 {@code nullable} 里已经有了，不靠值域回答。
     */
    private String distinctSql(Candidate c, int limit) {
        String col = quoteIdent(c.columnName());
        return "SELECT DISTINCT " + col + " FROM " + quoteIdent(c.objectName())
                + " WHERE " + col + " IS NOT NULL LIMIT " + limit;
    }

    /** 标识符已由 {@link #IDENT} 验过（不含反引号），这里只负责包起来。 */
    private static String quoteIdent(String ident) {
        return "`" + ident + "`";
    }

    /**
     * 一条语句一次网关调用。
     *
     * <p>不在这里 catch：调用方要按错误码分别处置（超时 → 当高基数、致命 → 中止整次剖析），
     * 而网关保证抛出来的<b>只有</b>已归一、已脱敏的 {@link ConnectorException}。
     */
    private QueryResult run(Long connectorId, String sql, int maxRows, int timeoutSec, RunState state) {
        pace(state);
        state.statements++;
        return gateway.executeAsPlatform(connectorId, Capability.QUERY,
                ConnectorAuditService.OP_SEMANTIC_VALUES, session -> {
                    if (!(session instanceof QueryCapable q)) {
                        // 抛 ConnectorException 而不是别的：网关的兜底 catch 会把非 ConnectorException
                        // 一律归成 UPSTREAM_ERROR（「目标系统返回了错误」），那会把排查方向带到客户库上，
                        // 而这其实是我们这边的能力声明与实现分叉。
                        throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                                "这条连接不支持查询能力，无法采集取值集合");
                    }
                    return q.query(sql, new QueryOptions(maxRows, FETCH_MAX_BYTES, timeoutSec));
                });
    }

    /**
     * 节流：两条语句之间至少隔 {@code 60000 / statementsPerMinute} 毫秒。
     *
     * <p>状态挂在<b>本轮</b>上而不是本 bean 上：两轮并发剖析会各按这个速度跑，合起来是两倍。
     * 这是已知且可接受的——总闸在网关那个平台速率桶上，这里限的是单轮不要一口气打爆客户的库。
     * 提到 bean 上会造出一个所有租户共享的串行点，代价比它解决的问题大。
     *
     * <p>被中断时<b>把中断标志还回去</b>并中止本轮：上层（推导的后台线程）靠那个标志决定要不要继续，
     * 吞掉它会让一个已经被要求停止的线程继续往客户的库上打全表扫描。
     */
    private void pace(RunState state) {
        long wait = paceWaitMs(state.nextStatementAtMs, System.currentTimeMillis());
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AbortProfiling("线程被中断");
            }
        }
        state.nextStatementAtMs = System.currentTimeMillis() + 60_000L / Math.max(1, statementsPerMinute);
    }

    /** 抽成纯函数才测得到；留在 {@link #pace} 里的话只能靠让测试真的睡一觉。 */
    static long paceWaitMs(long nextStatementAtMs, long nowMs) {
        return nextStatementAtMs - nowMs;
    }

    /**
     * 这一类错在每一列上都会重演，继续跑没有意义，只会白白耗掉客户库的连接、
     * 平台的限流额度，还往审计表里刷几百条一模一样的失败。
     */
    private static void abortIfFatal(ConnectorException e) {
        switch (e.getCode()) {
            case UNREACHABLE, AUTH_FAILED, RATE_LIMITED, CONFIG_ERROR -> throw new AbortProfiling(safeReason(e));
            default -> {
                // NOT_FOUND / FORBIDDEN / TIMEOUT / UPSTREAM_ERROR 都可能只针对某一张表
                // （表被删了、这个账号在这张表上没授权、这一列特别大），继续剖析后面的列。
            }
        }
    }

    /**
     * ★ 只取已脱敏的那套文案。
     *
     * <p>{@code e.getMessage()} 在这条链上是安全的（{@code ConnectorException} 的 message
     * 本来就是标题 + safeDetail），但这句话会被写进我们的库、注入模型上下文、落进
     * {@code ai_model_call_content}——所以这里显式只碰 {@code code/safeDetail} 两个字段，
     * 让「原始 JDBC 消息（含完整 SQL、主机名、连接参数）永远不会流到这里」成为一眼可验的事，
     * 而不是一条需要沿着调用链推导的结论。
     */
    private static String safeReason(ConnectorException e) {
        String detail = e.getSafeDetail();
        return detail == null || detail.isBlank() ? e.getCode().title() : e.getCode().title() + "：" + detail;
    }

    // ================================================================ 结果解析

    private static Long firstLong(QueryResult qr) {
        if (qr == null || qr.rows() == null || qr.rows().isEmpty()) {
            return null;
        }
        List<Object> row = qr.rows().get(0);
        if (row == null || row.isEmpty() || row.get(0) == null) {
            return null;
        }
        Object v = row.get(0);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            // MySqlSession.safeValue 会把 BigDecimal 转成字符串，数值也可能以字符串形态回来。
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取第一列的非 null 取值。空串是一个真实取值，<b>不过滤</b>。 */
    private static List<String> firstColumn(QueryResult qr) {
        List<String> out = new ArrayList<>();
        if (qr == null || qr.rows() == null) {
            return out;
        }
        for (List<Object> row : qr.rows()) {
            if (row == null || row.isEmpty() || row.get(0) == null) {
                continue;
            }
            out.add(String.valueOf(row.get(0)));
        }
        return out;
    }

    // ================================================================ 杂项

    /**
     * 预算判定抽成纯函数，是为了让它能被直接测到。
     * 把它留在循环里的话，唯一的测法是让测试真的跑满预算——于是要么测试很慢，要么这条根本没测。
     */
    static boolean budgetExhausted(long startMs, long nowMs, int budgetSeconds) {
        if (budgetSeconds <= 0) {
            return false;
        }
        return nowMs - startMs >= budgetSeconds * 1000L;
    }

    private ValueProfile finish(ValueProfile.ValueProfileBuilder builder, List<String> notes,
                                String extraNote, long start) {
        if (extraNote != null) {
            notes.add(extraNote);
        }
        return builder.notes(List.copyOf(notes))
                .elapsedMs(System.currentTimeMillis() - start)
                .build();
    }

    private ColumnValueDomain domain(Candidate c, Outcome outcome, Long distinct,
                                     List<String> values, String note) {
        return domain(c.objectName(), c.columnName(), outcome, distinct, values, note);
    }

    private ColumnValueDomain domain(String object, String column, Outcome outcome, Long distinct,
                                     List<String> values, String note) {
        return ColumnValueDomain.builder()
                .objectName(object)
                .fieldName(column)
                // ★ values 只在 ENUMERATED 下赋值。这一句是「空集合 ≠ 没查过」那条不变式的入口，
                //   detailFragment 是它的出口，两头都堵上。
                .values(outcome == Outcome.ENUMERATED ? values : null)
                .outcome(outcome)
                .distinctCount(distinct)
                .note(note)
                .build();
    }

    /**
     * 待剖析的一列。只在一次调用内存在，从不序列化。
     *
     * @param priority 见 {@link SemanticValueProfiler#priority}，越大越先采
     * @param seq      快照里的原始次序，只用来做确定性 tie-break
     */
    private record Candidate(String objectName, String columnName, String comment,
                             int priority, int seq) {}

    /**
     * 本轮的可变状态：计数器 + 节流游标。record 不合适（要累加），
     * 提成类是为了不在方法之间传六个 int。<b>每轮一个新实例</b>，理由见 {@link #pace(RunState)}。
     */
    private static final class RunState {
        private int examined;
        private int eligible;
        private int attempted;
        private int enumerated;
        private int piiBlocked;
        private int statements;
        /** 下一条语句最早可以在什么时候发出去。0 = 第一条不用等。 */
        private long nextStatementAtMs;
    }

    /**
     * 中止整次剖析的内部信号。
     *
     * <p>刻意不是 {@code ConnectorException} 也不是 {@code ServiceException}：
     * 它既不该被上层当成一次连接器故障往模型那边归一，也不该变成一个业务错误——
     * 值域剖析整体失败只是少一批增强信息，不该让语义层推导跟着失败。
     */
    private static final class AbortProfiling extends RuntimeException {
        AbortProfiling(String message) {
            super(message, null, false, false);
        }
    }

    // ================================================================ 返回形状

    /**
     * <b>刻意不是 record。</b>本仓库实际生效的 jackson-databind 是 2.11.1，
     * 而 record 的序列化支持是 2.12+ 才有的——这个形状会随管理台接口出网，
     * 用 record 会在运行期报 {@code InvalidDefinitionException}，而且只在真被序列化时才报。
     * 同一条理由见 {@code ConnectorSchemaService.ObjectDiff}。
     */
    @Data
    @Builder
    public static class ValueProfile {

        /** 数据出库档位允不允许取真实取值。为 false 时 {@link #domains} 一定是空的。 */
        private boolean tierAllowed;

        /** 这一档具体什么东西会出库，给人看。档位不允许时就是 {@link #NOT_ENABLED_NOTE}。 */
        private String tierStatement;

        /**
         * 有结论的列。<b>只包含「尝试过」和「因 PII 主动拦下」的列</b>——
         * 类型上根本不可能有值域的列（datetime、blob、自增主键）不在这里，
         * 否则几千条噪音会把真正要紧的那几条淹掉。
         */
        @Builder.Default
        private List<ColumnValueDomain> domains = List.of();

        /** 给人看的过程说明：跳过了多少视图、超了列数上限、预算用完提前停了。 */
        @Builder.Default
        private List<String> notes = List.of();

        /** 快照里一共看了多少列。 */
        private int examinedColumns;

        /** 其中够格采值域的有多少列。 */
        private int eligibleColumns;

        /** 真的发了语句去剖析的列数。与 {@link #eligibleColumns} 的差额 = PII 拦下 + 超限 + 预算耗尽。 */
        private int attemptedColumns;

        /** 拿到完整取值集合的列数。 */
        private int enumeratedColumns;

        /** 本次打到客户库的语句条数。<b>成本要可见</b>：这是一次全表扫描级别的开销。 */
        private int statements;

        private long elapsedMs;
    }

    /** 一列的值域结论。不是 record，理由同 {@link ValueProfile}。 */
    @Data
    @Builder
    public static class ColumnValueDomain {

        private String objectName;

        private String fieldName;

        private Outcome outcome;

        /** 基数闸测出来的取值种类数。{@code null} = 没测出来。 */
        private Long distinctCount;

        /**
         * ★ <b>只有 {@link Outcome#ENUMERATED} 时非 null</b>，其余一律 {@code null}，
         * 而不是空列表。空列表读起来是「这列没有取值」，null 读起来是「这里没有答案」——
         * 后者才是事实，而这两者会让模型走向相反的方向。
         */
        private List<String> values;

        /** 这一列为什么是这个结论。会随语义层注入模型上下文，所以它是一句人话，不是错误码。 */
        private String note;

        /** 判「能不能照着这个集合写查询条件」用这个，不要自己判 {@code values != null}。 */
        public boolean hasCompleteValueSet() {
            return outcome == Outcome.ENUMERATED && values != null;
        }
    }
}
