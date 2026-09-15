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
import com.jimeng.persistence.entity.ConnectorSchema;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从数据里<b>测</b>一张表是不是键值对表。
 *
 * <h3>为什么要测，而不是信模型</h3>
 * 键值对表一行是「某个指标的一个值」：{@code (stat_date, metric_code, metric_value)}。
 * 被当成明细表去 {@code SUM(metric_value)}，就是把 GMV 和转化率加在一起——所有聚合都错，且不报错。
 * 模型只看得到名字；名字晦涩（{@code t_idx_d / c1 / c2}）时它判不出来，而那正是最需要判出来的时候。
 *
 * <h3>★ 文档给的判据单独用不了，这里补了两道</h3>
 * 文档写的是「指标名列 distinct 小 + 指标值列为数值」。<b>几乎每张明细表都满足</b>：
 * {@code orders(status varchar, amount decimal)} 里 status 只有 5 种、amount 是数值。照字面实现，
 * 一半的明细表会被翻成键值对表，模型从此对「总销售额」先按 status 过滤——换一种方向的全错。
 * 所以本类的判定是分层的，验收取向与 S3 一致：<b>P 优先于 R</b>。
 * <ol>
 *   <li><b>结构预筛（第 1 档，不发语句）</b>：可疑的「度量列」最多一列（多列度量是宽表，不是键值对表），
 *       并且有一列能当指标名的短字符串列。度量列按<b>类型 + 是否有索引</b>判，不看列名。</li>
 *   <li><b>必要条件（一条语句）</b>：名列 2~200 种取值、平均每种至少 5 行、值列 95% 以上是数值、
 *       各名字的行数大体均匀（变异系数 ≤ 1）。失败 = {@link Outcome#NOT_KEY_VALUE}。</li>
 *   <li><b>单位混存（同一条语句）</b>：键值对表之所以不能跨指标求和，是因为不同指标<b>单位不同</b>。
 *       可测的代理是：同时存在「取值全是整数」的名字（计数）和「取值大多是小数」的名字（金额、比率），
 *       且各名字的平均量级相差 10 倍以上。{@code shop_sales(shop_code, amount)} 这类
 *       「一个度量 + 一个维度」的表在前两层上和键值对表一模一样，只有这一层分得开。</li>
 * </ol>
 *
 * <h3>结论怎么用：只能确认，不能否定</h3>
 * <ul>
 *   <li>模型判为键值对表、且名列没有歧义（候选只有一列）：满足必要条件即确认。</li>
 *   <li>要<b>推翻</b>模型的判断（它说明细表，我们说键值对表），或者名列有多个候选：必须三层全过。</li>
 *   <li>测不出键值对形态<b>从不</b>推翻模型说的「键值对表」：名列是本类按结构挑的，
 *       挑错了列测出来的「不是」说明不了任何事。这时只留痕，由调用方记下分歧。</li>
 * </ul>
 *
 * <h3>节奏、审计、档位</h3>
 * 读的全是聚合数（行数、distinct 数、均值量级），属于第 2 档派生统计，所以档位闸问的是
 * {@link SemanticDataTier#allowsDerivedStats()}，审计动作名沿用同为第 2 档的
 * {@link ConnectorAuditService#OP_SEMANTIC_PROBE}（动作名按敏感度分，理由见那个常量；
 * 真要单独起名，应当登记在 {@code ConnectorAuditService} 里，而不是在这里另起一个字符串）。
 * <b>一条语句一次 {@code executeAsPlatform}</b>，自我节流 {@link #statementsPerMinute}。
 * 它在验证阶段里与 S3（30/分钟）、S4（20/分钟）<b>先后</b>跑，平台桶（60/分钟）的峰值不变。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableShapeDetector {

    public enum Outcome {
        /** 测出来是键值对表。只有这一种会改 {@code table_shape}。 */
        KEY_VALUE,
        /** 必要条件不满足：这一对列不是「指标名 + 指标值」。 */
        NOT_KEY_VALUE,
        /** 形态像，但证据不够推翻模型 / 认定名列。 */
        INCONCLUSIVE,
        /** 发了语句但判不出来（样本太少、超时、没权限）。下一轮会重测。 */
        UNDECIDABLE
    }

    public static final String OUT_RAN = "RAN";
    public static final String OUT_TIER_BLOCKED = "TIER_BLOCKED";
    public static final String OUT_DISABLED = "DISABLED";
    public static final String OUT_ABORTED = "ABORTED";
    public static final String OUT_NOTHING_TO_DO = "NOTHING_TO_DO";

    private static final Pattern IDENT = Pattern.compile("^[A-Za-z0-9_$]{1,64}$");

    /** 值列是字符型时认它是不是数：可带正负号和小数，前后空白容忍。不认科学计数法——宁可少认。 */
    static final String NUMERIC_PATTERN = "^[[:space:]]*[-+]?[0-9]+([.][0-9]+)?[[:space:]]*$";

    static final int MAX_NAME_DISTINCT = 200;
    static final int MIN_ROWS = 30;
    static final int MIN_ROWS_PER_NAME = 5;
    static final double NUMERIC_RATIO = 0.95d;
    static final double MAX_COUNT_CV = 1.0d;
    static final double MIN_MAGNITUDE_SPREAD = 10.0d;

    /**
     * 排除主键 / 自增 / 唯一列之后还超过这么多列，就不当键值对表候选。
     * 这是成本闸不是判据：键值对表带上租户、审计、软删这些杂列也就十列上下。
     */
    static final int MAX_COLUMNS = 12;

    /** 指标名列的声明长度上限。与唯一键列同一个 191，超过的多半是名称或描述。 */
    private static final int NAME_MAX_DECLARED = 191;

    private static final Set<String> FRACTIONAL_TYPES = Set.of("decimal", "numeric", "dec", "float", "double", "real");
    private static final Set<String> INTEGER_TYPES = Set.of("tinyint", "smallint", "mediumint", "int", "integer", "bigint");
    private static final Set<String> TEXT_VALUE_TYPES = Set.of("char", "varchar", "text", "tinytext");

    /** {@code MySqlSession.describe} 把 COLUMN_KEY / EXTRA 拼进 extra 时用的词。 */
    private static final String EXTRA_PRIMARY = "主键";
    private static final String EXTRA_UNIQUE = "唯一";
    private static final String EXTRA_INDEXED = "有索引";
    private static final String EXTRA_AUTO_INCREMENT = "auto_increment";

    /*
     * ★ @Value 的内联默认必须等于字段初始值：Spring 里生效前者，单测里生效后者。
     *   TableShapeDetectorTest 用反射把两者钉在一起。挂在 connector.semantic.* 下但不进
     *   ConnectorProperties，理由同 SemanticValueProfiler。
     */

    /** 运维急停。与档位是两件事：档位是客户的选择，这个是平台自己的开关。 */
    @Value("${connector.semantic.shape-detect-enabled:true}")
    private boolean enabled = true;

    @Value("${connector.semantic.shape-statements-per-minute:20}")
    private int statementsPerMinute = 20;

    /**
     * 每张表只看前这么多行。<b>必须有这个 LIMIT</b>：分组统计不加上限就是全表扫描，
     * 打在客户的生产库上不可接受。样本是物理顺序的前 N 行，偏向老数据——
     * 对「每个周期都有全部指标」的键值对表，前 5 万行已经覆盖上千个周期。
     */
    @Value("${connector.semantic.shape-sample-rows:50000}")
    private int sampleRows = 50000;

    /** 与 S3 同一个 5 秒：平台自己发起的剖析，宁可放弃也不要占着客户库的连接。 */
    @Value("${connector.semantic.shape-timeout-seconds:5}")
    private int timeoutSeconds = 5;

    /** 一轮最多测几张表。超出的明说。 */
    @Value("${connector.semantic.shape-max-tables:200}")
    private int maxTables = 200;

    private final ConnectorGateway gateway;
    private final ConnectorService connectorService;

    /** 一张待测的表，和模型对它的判断（没有 / 认不出来时为 null）。 */
    public record Target(ConnectorSchema table, TableShape modelGuess) {
    }

    /** 每测完一张表回调一次。返回 false 即停。调用方靠它「测一张落一张」，中途停下不丢结论。 */
    @FunctionalInterface
    public interface Progress {
        boolean onDecided(ShapeVerdict verdict);
    }

    // ================================================================ 入口

    /**
     * 测一批表。<b>不抛异常</b>：档位不够、开关关了、连接不可用，全都写在返回值里。
     *
     * <p>前提同 S3 / S4：后台线程、真实租户上下文（{@code runAsSystem} 不算）。
     */
    public ShapeRun detect(Long connectorId, List<Target> targets, Progress progress) {
        List<Target> input = targets == null ? List.of() : targets.stream()
                .filter(t -> t != null && t.table() != null && t.table().getObjectName() != null)
                .toList();
        if (input.isEmpty()) {
            return ShapeRun.builder().outcome(OUT_NOTHING_TO_DO).note("没有待测量表形态的表").build();
        }
        if (!enabled) {
            return ShapeRun.builder().outcome(OUT_DISABLED)
                    .note("表形态测量已关闭（connector.semantic.shape-detect-enabled=false），表形态均为模型推测").build();
        }
        if (!TenantContext.isSet()) {
            log.error("表形态测量跑在没有租户上下文的线程上 connectorId={}", connectorId);
            return ShapeRun.builder().outcome(OUT_ABORTED).note("缺少租户上下文，没有测量表形态").build();
        }
        SemanticDataTier tier;
        try {
            tier = connectorService.dataTier(connectorId);
        } catch (RuntimeException e) {
            return ShapeRun.builder().outcome(OUT_ABORTED).note("连接不可用，没有测量表形态").build();
        }
        if (!tier.allowsDerivedStats()) {
            // ★ 没去测 ≠ 测过了不是。一句话写进阶段 note，行上保持 table_shape_source=MODEL——
            //   那个标签本身就在说「这是模型推测，未经数据测量」。
            return ShapeRun.builder().outcome(OUT_TIER_BLOCKED).tierAllowed(false)
                    .note("未测量表形态（" + tier.label() + "不允许派生统计），表形态均为模型推测、未经数据测量")
                    .build();
        }

        RunState run = new RunState();
        List<ShapeVerdict> verdicts = new ArrayList<>();
        int skippedViews = 0;
        int notMeasurable = 0;
        int modelKvUnmeasurable = 0;
        int beyondCap = 0;
        String abort = null;
        for (Target t : input) {
            if (abort != null) {
                break;
            }
            ConnectorSchema table = t.table();
            if (!isBaseTable(table.getObjectType())) {
                skippedViews++;
                continue;
            }
            Pair pair = pickPair(table.getObjectName(), parseFields(table));
            if (pair == null) {
                notMeasurable++;
                if (t.modelGuess() == TableShape.KEY_VALUE) {
                    modelKvUnmeasurable++;
                }
                continue;
            }
            if (run.measured >= maxTables) {
                beyondCap++;
                continue;
            }
            if (Thread.currentThread().isInterrupted()) {
                abort = "线程被中断";
                break;
            }
            ShapeVerdict v = measure(connectorId, t, pair, run);
            verdicts.add(v);
            if (run.fatal != null) {
                abort = run.fatal;
            }
            if (progress != null && !progress.onDecided(v)) {
                abort = "上层已中止本轮测量";
            }
        }

        String note = summarize(verdicts, run.statements, skippedViews, modelKvUnmeasurable, beyondCap, abort);
        return ShapeRun.builder()
                .outcome(abort == null ? OUT_RAN : OUT_ABORTED)
                .tierAllowed(true)
                .verdicts(List.copyOf(verdicts))
                .statements(run.statements)
                .skippedViews(skippedViews)
                .notMeasurable(notMeasurable)
                .modelKeyValueUnmeasurable(modelKvUnmeasurable)
                .note(note)
                .build();
    }

    // ================================================================ 结构预筛（不发语句）

    /**
     * 挑「指标名列 + 指标值列」。<b>只看类型、索引、主键这些元数据，不看列名</b>——
     * 按名字猜在客户的命名习惯之外立刻失效，而且失效方向随机。
     *
     * @return {@code null} = 结构上不可能是（或测不了）键值对表
     */
    static Pair pickPair(String objectName, List<FieldDetail> fields) {
        if (objectName == null || !IDENT.matcher(objectName).matches() || fields == null || fields.isEmpty()) {
            return null;
        }
        long pkColumns = fields.stream().filter(f -> hasExtra(f, EXTRA_PRIMARY)).count();
        List<FieldDetail> kept = new ArrayList<>();
        for (FieldDetail f : fields) {
            if (f.name() == null || !IDENT.matcher(f.name()).matches()) {
                continue;
            }
            // 自增 / 唯一 / 单列主键：每行一个值，既不是指标名也不是指标值。
            if (hasExtra(f, EXTRA_AUTO_INCREMENT) || hasExtra(f, EXTRA_UNIQUE)
                    || (pkColumns == 1 && hasExtra(f, EXTRA_PRIMARY))) {
                continue;
            }
            kept.add(f);
        }
        if (kept.size() < 2 || kept.size() > MAX_COLUMNS) {
            return null;
        }

        List<FieldDetail> measures = kept.stream().filter(TableShapeDetector::measureLike).toList();
        if (measures.size() > 1) {
            // 两列以上的度量：宽表（多指标周期表 / 明细表），指标是按列摊开的，不是按行。
            return null;
        }
        if (measures.size() == 1) {
            FieldDetail value = measures.get(0);
            List<FieldDetail> names = kept.stream().filter(f -> f != value && nameLike(f)).toList();
            if (names.isEmpty()) {
                // 指标名是整数外键（metric_id → 指标字典）的键值对表测不了：整数维度列与它长得一样，
                // 放进来会把「店铺 id + 销售额」这类表误判掉。漏掉比误判便宜。
                return null;
            }
            FieldDetail name = nearestBefore(kept, value, names);
            return new Pair(name.name(), value.name(), true, names.size() > 1);
        }

        // 没有数值型度量：只剩「值存成字符串」的 EAV 形态。只认恰好两列字符串的最简单形状。
        List<FieldDetail> strings = kept.stream()
                .filter(f -> TEXT_VALUE_TYPES.contains(baseType(f.type())) || "enum".equals(baseType(f.type())))
                .toList();
        if (strings.size() != 2) {
            return null;
        }
        FieldDetail name = strings.get(0);
        FieldDetail value = strings.get(1);
        if (!nameLike(name) || "enum".equals(baseType(value.type()))) {
            return null;
        }
        return new Pair(name.name(), value.name(), false, false);
    }

    /**
     * 像不像一列<b>度量</b>：小数类型一律算；整数类型要<b>没有索引</b>且不是 {@code tinyint(1)} 这类开关位。
     * 有索引的整数几乎总是键（实体 id、周期、维度外键），度量列很少建索引。
     */
    private static boolean measureLike(FieldDetail f) {
        String t = baseType(f.type());
        if (t == null) {
            return false;
        }
        if (FRACTIONAL_TYPES.contains(t)) {
            return true;
        }
        if (!INTEGER_TYPES.contains(t)) {
            return false;
        }
        if ("tinyint".equals(t) && Integer.valueOf(1).equals(declaredLength(f.type()))) {
            return false;
        }
        return !hasExtra(f, EXTRA_PRIMARY) && !hasExtra(f, EXTRA_UNIQUE) && !hasExtra(f, EXTRA_INDEXED);
    }

    /** 能当指标名的列：短字符串或 enum。长 varchar 多半是名称 / 描述。 */
    private static boolean nameLike(FieldDetail f) {
        String t = baseType(f.type());
        if ("enum".equals(t)) {
            return true;
        }
        if (!"char".equals(t) && !"varchar".equals(t)) {
            return false;
        }
        Integer len = declaredLength(f.type());
        return len == null || len <= NAME_MAX_DECLARED;
    }

    /** 名列取离值列最近的、排在它前面的那一列（{@code metric_code, metric_value} 是最常见的写法）；前面没有再往后找。 */
    private static FieldDetail nearestBefore(List<FieldDetail> ordered, FieldDetail value, List<FieldDetail> names) {
        int vi = ordered.indexOf(value);
        FieldDetail best = null;
        int bestDist = Integer.MAX_VALUE;
        for (FieldDetail n : names) {
            int ni = ordered.indexOf(n);
            // 前面的列距离按原值算，后面的列额外加上列数，保证「前面最近」优先于「后面最近」。
            int dist = ni < vi ? vi - ni : (ni - vi) + ordered.size();
            if (dist < bestDist) {
                bestDist = dist;
                best = n;
            }
        }
        return best;
    }

    // ================================================================ 测量（一张表一条语句）

    private ShapeVerdict measure(Long connectorId, Target t, Pair pair, RunState run) {
        String object = t.table().getObjectName();
        ShapeVerdict.ShapeVerdictBuilder b = ShapeVerdict.builder()
                .objectName(object).nameColumn(pair.nameColumn()).valueColumn(pair.valueColumn())
                .modelGuess(t.modelGuess());
        if (!pace(run)) {
            run.fatal = "线程被中断";
            return b.outcome(Outcome.UNDECIDABLE).probed(false).basis("本轮被中断，这张表没测").build();
        }
        run.measured++;
        run.statements++;
        QueryResult qr;
        try {
            String sql = measureSql(object, pair);
            qr = gateway.executeAsPlatform(connectorId, Capability.QUERY, ConnectorAuditService.OP_SEMANTIC_PROBE,
                    session -> {
                        if (!(session instanceof QueryCapable q)) {
                            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                                    "这条连接不支持查询能力，无法测量表形态");
                        }
                        return q.query(sql, new QueryOptions(1, 16 * 1024, timeoutSeconds));
                    });
        } catch (ConnectorException e) {
            return b.outcome(Outcome.UNDECIDABLE).probed(true).basis(failureBasis(e, run)).build();
        } catch (RuntimeException e) {
            // 原始异常只进日志：它可能带着完整 SQL 和主机名，而 basis 会进 detail_json、再进模型上下文。
            log.error("表形态测量出现未归类异常 connectorId={} object={}", connectorId, object, e);
            return b.outcome(Outcome.UNDECIDABLE).probed(true).basis("测量过程出错，判不出来").build();
        }
        return classify(b, Stats.of(qr), pair, t.modelGuess());
    }

    /**
     * 一条语句算完三层判据要的全部数字。只有聚合数离开客户库（第 2 档）。
     *
     * <p>内层 {@code LIMIT} 是刻意的：分组统计没有上限就是全表扫描。
     * 名列上加 {@code <> ''} 只因为名列一定是字符型——在数值列上这么写会把 0 整批剔掉。
     */
    String measureSql(String object, Pair pair) {
        String k = quote(pair.nameColumn());
        String v = quote(pair.valueColumn());
        String isNum;
        String num;
        if (pair.numericValue()) {
            isNum = "jm_s.jm_v IS NOT NULL";
            num = "jm_s.jm_v";
        } else {
            isNum = "jm_s.jm_v REGEXP '" + NUMERIC_PATTERN + "'";
            num = "CAST(jm_s.jm_v AS DECIMAL(38,10))";
        }
        return "SELECT COUNT(*) AS name_n, SUM(jm_g.jm_c) AS row_n, AVG(jm_g.jm_c) AS avg_c,"
                + " STDDEV_POP(jm_g.jm_c) AS sd_c, SUM(jm_g.jm_nn) AS nonnull_n, SUM(jm_g.jm_num) AS numeric_n,"
                + " SUM(CASE WHEN jm_g.jm_num > 0 AND jm_g.jm_int >= 0.99 * jm_g.jm_num THEN 1 ELSE 0 END) AS int_names,"
                + " SUM(CASE WHEN jm_g.jm_num > 0 AND jm_g.jm_int <= 0.5 * jm_g.jm_num THEN 1 ELSE 0 END) AS frac_names,"
                + " MIN(CASE WHEN jm_g.jm_mag > 0 THEN jm_g.jm_mag END) AS min_mag, MAX(jm_g.jm_mag) AS max_mag"
                + " FROM (SELECT COUNT(*) AS jm_c, COUNT(jm_s.jm_v) AS jm_nn,"
                + " SUM(CASE WHEN " + isNum + " THEN 1 ELSE 0 END) AS jm_num,"
                + " SUM(CASE WHEN " + isNum + " AND " + num + " = FLOOR(" + num + ") THEN 1 ELSE 0 END) AS jm_int,"
                + " AVG(CASE WHEN " + isNum + " THEN ABS(" + num + ") END) AS jm_mag"
                + " FROM (SELECT " + k + " AS jm_k, " + v + " AS jm_v FROM " + quote(object)
                + " WHERE " + k + " IS NOT NULL AND " + k + " <> '' LIMIT " + sampleRows + ") jm_s"
                + " GROUP BY jm_s.jm_k) jm_g";
    }

    /**
     * 三层判据。纯函数，便于直接测。
     *
     * <p>「只能确认、不能否定」落在这里：除 {@link Outcome#KEY_VALUE} 之外的结论都不改表形态，
     * 调用方只留痕。
     */
    static ShapeVerdict classify(ShapeVerdict.ShapeVerdictBuilder b, Stats s, Pair pair, TableShape modelGuess) {
        // modelGuess 在这里再设一次：「推没推翻模型」是从结论和它一起算出来的，两者必须出自同一处。
        b.probed(true).modelGuess(modelGuess).nameColumn(pair.nameColumn()).valueColumn(pair.valueColumn());
        if (s == null || s.rowN() == null || s.rowN() < MIN_ROWS) {
            long n = s == null || s.rowN() == null ? 0 : s.rowN();
            return b.outcome(Outcome.UNDECIDABLE)
                    .basis("采样只拿到 " + n + " 行（少于 " + MIN_ROWS + " 行），判不出来").build();
        }
        long rows = s.rowN();
        long names = s.nameN() == null ? 0 : s.nameN();
        String head = "采样 " + rows + " 行，" + pair.nameColumn() + " 有 " + names + " 种取值";

        if (names < 2) {
            return b.outcome(Outcome.NOT_KEY_VALUE).basis(head + "，只有一种取值，不是指标名列").build();
        }
        if (names > MAX_NAME_DISTINCT) {
            return b.outcome(Outcome.NOT_KEY_VALUE)
                    .basis(head + "，超过 " + MAX_NAME_DISTINCT + " 种，不像指标名列").build();
        }
        if (rows < names * MIN_ROWS_PER_NAME) {
            return b.outcome(Outcome.NOT_KEY_VALUE)
                    .basis(head + "，平均每种不到 " + MIN_ROWS_PER_NAME + " 行，名字几乎不重复").build();
        }
        long nonNull = s.nonNullN() == null ? 0 : s.nonNullN();
        long numeric = s.numericN() == null ? 0 : s.numericN();
        if (nonNull == 0 || numeric < NUMERIC_RATIO * nonNull) {
            return b.outcome(Outcome.NOT_KEY_VALUE)
                    .basis(head + "，" + pair.valueColumn() + " 的非空值里只有 " + pct(nonNull == 0 ? 0 : (double) numeric / nonNull)
                            + " 是数值，不是指标值列").build();
        }
        double cv = s.avgC() == null || s.avgC() <= 0 || s.sdC() == null ? Double.POSITIVE_INFINITY : s.sdC() / s.avgC();
        if (cv > MAX_COUNT_CV) {
            return b.outcome(Outcome.NOT_KEY_VALUE)
                    .basis(head + "，各取值的行数很不均匀（变异系数 " + fmt(cv) + "），更像业务维度而不是指标名").build();
        }

        long intNames = s.intNames() == null ? 0 : s.intNames();
        long fracNames = s.fracNames() == null ? 0 : s.fracNames();
        Double spread = s.minMag() == null || s.maxMag() == null || s.minMag() <= 0 ? null : s.maxMag() / s.minMag();
        boolean strong = intNames >= 1 && fracNames >= 1 && spread != null && spread >= MIN_MAGNITUDE_SPREAD;
        String units = "取值全为整数的 " + intNames + " 种、多为小数的 " + fracNames + " 种，各取值平均量级相差 "
                + (spread == null ? "未知" : fmt(spread) + " 倍");

        boolean modelSaysKv = modelGuess == TableShape.KEY_VALUE;
        boolean needStrong = !(modelSaysKv && !pair.ambiguousName());
        if (!needStrong || strong) {
            return b.outcome(Outcome.KEY_VALUE)
                    .basis(head + "，行数均匀，" + pair.valueColumn() + " 为数值；" + units
                            + "——判定为键值对表：指标名在 " + pair.nameColumn() + "，指标值在 " + pair.valueColumn())
                    .build();
        }
        return b.outcome(Outcome.INCONCLUSIVE)
                .basis(head + "，形态与键值对表一致，但" + units + "，没测到不同单位的指标混存在同一列的证据，"
                        + (modelSaysKv ? "而名列候选不止一列，不足以认定指标名在 " + pair.nameColumn()
                                       : "不足以推翻模型的判断"))
                .build();
    }

    // ================================================================ 小工具

    /**
     * 网关归一过的失败 → 一句依据。<b>没有任何一条会变成 NOT_KEY_VALUE</b>：
     * 查不到数和「不是键值对表」在返回值上必须分得开。
     */
    private static String failureBasis(ConnectorException e, RunState run) {
        ConnectorErrorCode code = e.getCode();
        switch (code) {
            case TIMEOUT:
                return "测量超时，判不出来";
            case FORBIDDEN:
                return "只读账号没有读这张表的权限，判不出来";
            case NOT_FOUND:
                return "客户库里找不到这张表或列，结构快照可能已过时";
            case GUARD_BLOCKED:
                // 语句是本类生成的，被自家护栏拦下说明生成器坏了；不影响客户，所以只有日志能暴露它。
                log.error("表形态测量 SQL 被只读护栏拦下，这是 TableShapeDetector 的 SQL 生成器出了问题: {}",
                        e.getSafeDetail());
                return "测量语句未能执行（平台内部原因），判不出来";
            case RATE_LIMITED:
            case UNREACHABLE:
            case AUTH_FAILED:
            case CONFIG_ERROR:
                run.fatal = "连接当前不可用或触发限流（" + code.title() + "）";
                return "连接当前不可用（" + code.title() + "），这张表没测成";
            default:
                return "测量失败，判不出来";
        }
    }

    private boolean pace(RunState run) {
        long wait = run.nextStatementAtMs - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                // 中断标志还回去：上层靠它决定要不要继续打客户的库。
                Thread.currentThread().interrupt();
                return false;
            }
        }
        run.nextStatementAtMs = System.currentTimeMillis() + 60_000L / Math.max(1, statementsPerMinute);
        return true;
    }

    private static String summarize(List<ShapeVerdict> verdicts, int statements, int skippedViews,
                                    int modelKvUnmeasurable, int beyondCap, String abort) {
        int kv = 0;
        int overrides = 0;
        int not = 0;
        int inconclusive = 0;
        int undecidable = 0;
        int modelKvUnconfirmed = 0;
        for (ShapeVerdict v : verdicts) {
            switch (v.getOutcome()) {
                case KEY_VALUE -> {
                    kv++;
                    if (v.overridesModel()) {
                        overrides++;
                    }
                }
                case NOT_KEY_VALUE -> not++;
                case INCONCLUSIVE -> inconclusive++;
                default -> undecidable++;
            }
            if (v.getModelGuess() == TableShape.KEY_VALUE && v.getOutcome() != Outcome.KEY_VALUE) {
                modelKvUnconfirmed++;
            }
        }
        StringBuilder b = new StringBuilder("表形态测量 ").append(verdicts.size()).append(" 张（语句 ")
                .append(statements).append(" 条）：键值对表 ").append(kv);
        if (overrides > 0) {
            b.append("（其中 ").append(overrides).append(" 张推翻了模型的判断）");
        }
        b.append("、不是 ").append(not).append("、证据不足 ").append(inconclusive).append("、判不出 ").append(undecidable);
        if (modelKvUnconfirmed > 0) {
            b.append("；模型判为键值对表但实测未确认 ").append(modelKvUnconfirmed).append(" 张（未改动模型判断）");
        }
        if (modelKvUnmeasurable > 0) {
            b.append("；模型判为键值对表、结构上测不了 ").append(modelKvUnmeasurable).append(" 张");
        }
        if (skippedViews > 0) {
            b.append("；跳过视图 ").append(skippedViews);
        }
        if (beyondCap > 0) {
            b.append("；超过单轮上限未测 ").append(beyondCap).append(" 张");
        }
        if (abort != null) {
            b.append("；中途停止：").append(abort);
        }
        return b.toString();
    }

    private static boolean isBaseTable(String objectType) {
        if (objectType == null || objectType.isBlank()) {
            return true;
        }
        String t = objectType.trim().toUpperCase(Locale.ROOT);
        return t.equals("TABLE") || t.equals("BASE TABLE");
    }

    @SuppressWarnings("unchecked")
    private static List<FieldDetail> parseFields(ConnectorSchema r) {
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
                out.add(new FieldDetail(String.valueOf(fm.get("name")),
                        fm.get("type") == null ? null : String.valueOf(fm.get("type")),
                        !Boolean.FALSE.equals(fm.get("nullable")),
                        fm.get("comment") == null ? null : String.valueOf(fm.get("comment")),
                        fm.get("extra") == null ? null : String.valueOf(fm.get("extra"))));
            }
        } catch (Exception e) {
            log.warn("解析结构快照失败，本次不测这张表的形态 objectName={}", r.getObjectName(), e);
        }
        return out;
    }

    private static boolean hasExtra(FieldDetail f, String token) {
        return f.extra() != null && f.extra().contains(token);
    }

    private static String baseType(String rawType) {
        return SemanticValueProfiler.baseType(rawType);
    }

    private static Integer declaredLength(String rawType) {
        return SemanticValueProfiler.declaredLength(rawType);
    }

    private static String quote(String ident) {
        return "`" + ident.replace("`", "``") + "`";
    }

    private static String pct(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100d);
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    // ================================================================ 形状

    /**
     * 选中的两列。
     *
     * @param numericValue  值列是不是数值类型（否则按字符串认数）
     * @param ambiguousName 名列候选是否不止一列——不止一列时，确认也要走最严的那一档
     */
    record Pair(String nameColumn, String valueColumn, boolean numericValue, boolean ambiguousName) {
    }

    /** 那条语句返回的一行数。取不到的是 null。 */
    record Stats(Long nameN, Long rowN, Double avgC, Double sdC, Long nonNullN, Long numericN,
                 Long intNames, Long fracNames, Double minMag, Double maxMag) {

        static Stats of(QueryResult qr) {
            if (qr == null || qr.rows() == null || qr.rows().isEmpty() || qr.rows().get(0) == null) {
                return null;
            }
            List<Object> row = qr.rows().get(0);
            List<String> cols = qr.columns();
            return new Stats(asLong(col(row, cols, "name_n", 0)), asLong(col(row, cols, "row_n", 1)),
                    asDouble(col(row, cols, "avg_c", 2)), asDouble(col(row, cols, "sd_c", 3)),
                    asLong(col(row, cols, "nonnull_n", 4)), asLong(col(row, cols, "numeric_n", 5)),
                    asLong(col(row, cols, "int_names", 6)), asLong(col(row, cols, "frac_names", 7)),
                    asDouble(col(row, cols, "min_mag", 8)), asDouble(col(row, cols, "max_mag", 9)));
        }

        /** 按列名取，取不到按下标兜底——理由同 {@code SemanticJoinValidator.column}。 */
        private static Object col(List<Object> row, List<String> cols, String label, int fallback) {
            int idx = cols == null ? -1 : cols.indexOf(label);
            if (idx < 0 || idx >= row.size()) {
                idx = fallback;
            }
            return idx >= 0 && idx < row.size() ? row.get(idx) : null;
        }

        /** 值可能是 Number，也可能是字符串（{@code MySqlSession.safeValue} 会把 BigDecimal 转成字符串）。 */
        private static Double asDouble(Object v) {
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v == null) {
                return null;
            }
            try {
                return Double.valueOf(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static Long asLong(Object v) {
            Double d = asDouble(v);
            return d == null ? null : Math.round(d);
        }
    }

    private static final class RunState {
        int measured;
        int statements;
        long nextStatementAtMs;
        String fatal;
    }

    /** 一张表的结论。不是 record：与 S3 的 JoinVerdict 同形，便于将来随管理台接口出网。 */
    @Data
    @Builder
    public static class ShapeVerdict {
        private String objectName;
        private Outcome outcome;
        private String nameColumn;
        private String valueColumn;
        /** 模型原来的判断；没有或认不出来时为 null。 */
        private TableShape modelGuess;
        /** 给人看的依据：用了哪两列、测到什么数。<b>不含任何业务取值</b>。 */
        private String basis;
        /** 真的发了语句。 */
        private boolean probed;

        /** 实测结论推翻了模型：测出键值对表，而模型没这么说。 */
        public boolean overridesModel() {
            return outcome == Outcome.KEY_VALUE && modelGuess != TableShape.KEY_VALUE;
        }

        /** 写进 OBJECT 行 {@code table_shape_measurement} 的片段。 */
        public Map<String, Object> measurementFragment() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("outcome", outcome == null ? null : outcome.name());
            m.put("name_column", nameColumn);
            m.put("value_column", valueColumn);
            m.put("basis", basis);
            return m;
        }
    }

    @Data
    @Builder
    public static class ShapeRun {
        /** {@code OUT_*}。{@link #OUT_TIER_BLOCKED} 不等于「测过了，都不是键值对表」。 */
        private String outcome;
        private boolean tierAllowed;
        @Builder.Default
        private List<ShapeVerdict> verdicts = List.of();
        private int statements;
        private int skippedViews;
        /** 结构上不可能是 / 测不了键值对表的表数（没发语句）。 */
        private int notMeasurable;
        /** 模型判为键值对表、但结构上测不了的表数。分歧要看得见。 */
        private int modelKeyValueUnmeasurable;
        private String note;

        public boolean ran() {
            return OUT_RAN.equals(outcome) || OUT_ABORTED.equals(outcome) && !verdicts.isEmpty();
        }
    }
}
