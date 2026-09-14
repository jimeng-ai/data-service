package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.common.core.tenant.TenantContext;
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
import com.jimeng.persistence.mapper.ConnectionMapper;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
     * 缓存键分隔符。取一个不可能出现在标识符里的控制字符（白名单只允许字母、数字、下划线和 $），
     * 否则 {@code ab} 拼 {@code c} 与 {@code a} 拼 {@code bc} 会得到同一个键——
     * 那种撞键不会报错，只会让一条关系悄悄拿到另一条的结论。
     */
    private static final String KEY_SEP = "\u0001";

    // ================================================================ 依赖与配置

    private final ConnectorGateway gateway;
    private final ConnectionMapper connectionMapper;

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

    // 上面那组是配置原值、下面这组是夹紧之后真正生效的值。两组都是包级可见，这是有意的：
    // 单测要把节流调快（不然一条用例光睡觉就要两秒），也要能钉住夹紧本身的行为。
    // 写成 private 再配一组 setXxxForTest，只是同一件事多一层壳。
    boolean enabled = true;
    int timeoutSeconds = 5;
    int sampleValues = 1000;
    int probesPerMinute = 30;
    long rowBudget = 1_000_000L;
    int maxCandidates = 200;

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
        return validate(connectorId, candidates, snapshot, null);
    }

    /**
     * 对一批候选做采样验证。
     *
     * <p><b>本方法不抛业务异常</b>：语义层是叠加的注解，用一次剖析失败去否决一次正常的推导是错的。
     * 出了什么事全部如实写进 {@code outcome} / {@code note} / 每条的 {@code reason}。
     *
     * @param connectorId 连接 id。档位与租户归属都由本方法自己查，<b>不信调用方转述</b>
     * @param candidates  S2 推出来的候选，顺序保留
     * @param snapshot    结构快照：表名 → 列名 → {@link FieldDetail}。
     *                    <b>它是标识符的唯一合法来源</b>：对不上快照的一律不探查，
     *                    而不是拼进 SQL 让客户的库去报错
     * @param progress    每决完一条的回调，可为 {@code null}；返回 {@code false} 即中止
     */
    public JoinValidationResult validate(Long connectorId, List<JoinCandidate> candidates,
                                         Map<String, Map<String, FieldDetail>> snapshot,
                                         ProbeProgress progress) {
        List<JoinCandidate> input = candidates == null ? List.<JoinCandidate>of() : candidates.stream()
                .filter(c -> c != null && c.getFromObject() != null && c.getFromColumn() != null
                        && c.getToObject() != null && c.getToColumn() != null)
                .toList();
        if (input.isEmpty()) {
            return done(OUT_NOTHING_TO_DO, "没有待验证的表关系。", List.of(), new RunState());
        }

        if (!enabled) {
            return blocked(input, OUT_DISABLED,
                    "采样验证已被配置关闭（connector.semantic.join-probe-enabled=false），本次没有做任何验证。",
                    "采样验证未启用，本条未经数据验证");
        }

        // ---- 租户上下文。网关那边同样会拒，但那句话是给模型看的通用文案，这里要说清楚是谁的责任 ----
        if (!TenantContext.isSet()) {
            log.error("采样验证跑在没有租户上下文的线程上 connectorId={}（后台任务漏了 TenantContext.set？）",
                    connectorId);
            return blocked(input, OUT_ABORTED,
                    "当前线程没有租户上下文，无法访问客户系统，本次没有做任何验证。",
                    "缺少租户上下文，本条未经数据验证");
        }

        Connection row = connectorId == null ? null : connectionMapper.selectById(connectorId);
        if (row == null || !TenantContext.get().equals(row.getTenantId())) {
            // 与网关同一条取舍：「不存在」与「不属于你」对外同形。
            log.warn("采样验证找不到连接，或它不属于当前租户 connectorId={}", connectorId);
            return blocked(input, OUT_ABORTED,
                    "连接不存在，或它不属于当前租户，本次没有做任何验证。",
                    "连接不可用，本条未经数据验证");
        }

        // ---- ★ 档位闸。必须在打出任何一条 SQL 之前 ----
        SemanticDataTier tier = SemanticDataTier.parse(row.getSemanticDataTier());
        if (!tier.allowsDerivedStats()) {
            return blocked(input, OUT_TIER_BLOCKED,
                    "这条连接的数据出库档位是「" + tier.label() + "」，未启用派生统计。"
                            + "包含率、基数、distinct 数都属于派生统计，所以本次【没有】做任何采样验证——"
                            + "这不代表这些表关系有问题，只代表没人验过它们。"
                            + "需要验证请由企业超管把档位调到「" + SemanticDataTier.DERIVED_STATS.label() + "」。",
                    "未启用派生统计（" + tier.label() + "），本条未做采样验证");
        }

        return run(connectorId, input, snapshot == null ? Map.of() : snapshot, progress);
    }

    // ================================================================ 主循环

    private JoinValidationResult run(Long connectorId, List<JoinCandidate> input,
                                     Map<String, Map<String, FieldDetail>> snapshot,
                                     ProbeProgress progress) {
        RunState run = new RunState();
        List<JoinVerdict> out = new ArrayList<>(input.size());
        Map<String, JoinVerdict> decidedByKey = new LinkedHashMap<>();
        String abortNote = null;
        int total = input.size();

        for (int i = 0; i < total; i++) {
            JoinCandidate c = input.get(i);

            if (abortNote != null) {
                out.add(notProbed(c, abortNote));
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
                out.add(notProbed(c, "本轮候选数超过上限 " + maxCandidates + " 条，这一条没轮到验证"));
                continue;
            }
            if (run.sampledRows >= rowBudget) {
                // 预算用尽不是失败：join score 在 10^6 行后就收敛了，再采没有收益。
                abortNote = "本轮采样预算（" + rowBudget + " 行）已用尽，这一条没轮到验证";
                out.add(notProbed(c, abortNote));
                continue;
            }
            if (Thread.currentThread().isInterrupted()) {
                abortNote = "本轮推导被中断，这一条没轮到验证";
                out.add(notProbed(c, abortNote));
                continue;
            }

            String bad = checkIdentifiers(c, snapshot);
            if (bad != null) {
                // 不探查，也不判错：快照对不上多半是结构刷新与推导错开了一次，不是关系本身有问题。
                out.add(notProbed(c, bad));
                continue;
            }

            JoinVerdict v = decide(connectorId, c, snapshot, run);
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

    /** 一条候选的完整判定：先包含率（决定性的那一步），必要时再补两次侧写定基数。 */
    private JoinVerdict decide(Long connectorId, JoinCandidate c,
                               Map<String, Map<String, FieldDetail>> snapshot, RunState run) {
        JoinVerdict.JoinVerdictBuilder b = JoinVerdict.builder()
                .fromObject(c.getFromObject()).fromColumn(c.getFromColumn())
                .toObject(c.getToObject()).toColumn(c.getToColumn())
                .autoJoinable(false);

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
        b.containment(containment);
        String measured = "采样 " + sn + " 个取值，命中 " + mn + "（包含率 " + pct(containment) + "）";

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
        if (!pace(run)) {
            run.fatal = "本轮推导被中断";
            throw ProbeFailure.notProbed("本轮推导被中断，这一条没验");
        }
        try {
            run.probeCount++;
            return gateway.executeAsPlatform(connectorId, Capability.QUERY,
                    ConnectorAuditService.OP_SEMANTIC_PROBE,
                    session -> {
                        if (!(session instanceof QueryCapable q)) {
                            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                                    "这条连接不支持查询能力，无法做采样验证");
                        }
                        // ★ 超时是本方法自己的，不是 connector.query.timeout-seconds 那 15 秒。
                        return q.query(sql, new QueryOptions(PROBE_MAX_ROWS, PROBE_MAX_BYTES, timeoutSeconds));
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
    private String checkIdentifiers(JoinCandidate c, Map<String, Map<String, FieldDetail>> snapshot) {
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

    private JoinValidationResult blocked(List<JoinCandidate> input, String outcome, String note, String reason) {
        List<JoinVerdict> out = new ArrayList<>(input.size());
        for (JoinCandidate c : input) {
            out.add(notProbed(c, reason));
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
     */
    private static JoinVerdict notProbed(JoinCandidate c, String reason) {
        return JoinVerdict.builder()
                .fromObject(c.getFromObject()).fromColumn(c.getFromColumn())
                .toObject(c.getToObject()).toColumn(c.getToColumn())
                .verified(ConnectorSemanticService.V_NONE)
                .autoJoinable(false)
                .probed(false)
                .basis("未经数据验证")
                .reason(reason)
                .build();
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
