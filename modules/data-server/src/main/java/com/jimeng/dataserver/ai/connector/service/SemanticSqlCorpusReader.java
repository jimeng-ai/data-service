package com.jimeng.dataserver.ai.connector.service;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.model.QueryResult;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorAuditService;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorGateway;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryOptions;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesisFromItem;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SubJoin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P4 / S1：<b>从客户自己写过的 SQL 里挖表关系</b>。
 *
 * <p>读的是客户团队早就写好的东西——{@code information_schema.VIEWS} 里的视图定义、
 * {@code information_schema.ROUTINES} 里的存储过程正文——把里面的等值 join 解析出来。
 * 一个视图把两张表 join 在一起，是<b>一个懂这个库的人</b>在说这两张表有关系；
 * 它不是推断，是证词。
 *
 * <h3>★ 这一档是第 1 档：纯 DDL / 元数据，一个客户数据值都不碰</h3>
 * 本类向客户库发出的全部请求都落在 {@code information_schema} 上，读回来的是
 * <b>视图与存储过程的定义文本</b>，没有一行业务记录参与运算，也没有任何聚合。
 * 按 {@link SemanticDataTier} 的分档，它属于 {@link SemanticDataTier#METADATA_ONLY}
 * 就能做的事，因此<b>不需要问档位、也不该问</b>——给它加一道
 * {@code allowsDerivedStats()} 判断只会让人误以为它读了数据。
 *
 * <p>而这恰恰是它值得存在的理由：在第 1 档上，表关系推断的精确率会<b>腰斩</b>
 *（设计里引的两组真实生产库数据：纯元数据的 Nexus 精确率 0.49，允许库内采样的 Tursio 1.00）。
 * 对那些坚持数据不出域的客户，采样验证（S3）这条路是关着的，
 * <b>本阶段是唯一还能把精确率捞回来一些的东西</b>——因为它拿到的不是推断，是人写下来的事实。
 * 附带的一个量级：设计引的 AT&T 实测里，<b>实际被用到的等值 join 有 25–27% 在 schema 里
 * 根本没有声明</b>；真实客户库的外键声明极其稀少（我们自己的参考库是 0 条），
 * 那 25% 的关系除了从人写的 SQL 里挖，没有别的地方能看到。
 *
 * <h3>★ 必须说清楚：这个阶段从来没有在真实数据上验证过</h3>
 * 我们自己的参考数据库里<b>一个视图都没有</b>，所以从写下它到现在，
 * 它的解析路径没有跑过任何一条真实的客户视图。设计把它排在 P4 就是因为这个。
 * 读到这里的人不要把它当成一个「已经验证过的能力」：
 * <ul>
 *   <li>{@link CorpusResult} 上的那一堆计数器不是装饰，它们是这个阶段<b>唯一</b>的现场证据——
 *       第一次接上一个有视图的客户库时，先看 {@code viewsSkippedUnparseable} 和
 *       {@code droppedUnresolvable}，再决定信不信它吐出来的关系。</li>
 *   <li>它产出的关系一律 {@code verified = NONE}。「人写的」不等于「现在还成立」——
 *       视图可能是三年前建的，两张表之间的口径早就变了。</li>
 * </ul>
 *
 * <h3>★ 视图正文绝不能原样流到模型面前</h3>
 * 视图体里会有 {@code WHERE tenant_id = 10086 AND name = '某某公司'} 这种东西——
 * 客户的真实 id、真实名称、真实阈值。本类只从语法树上取<b>结构</b>：
 * 两个表名、两个列名、以及这条关系是在<b>哪个对象</b>里看到的（对象名而已）。
 * 原始 SQL 只在解析的那几行里活着，既不落库、不进返回值，<b>也不进日志</b>——
 * 连 jsqlparser 的异常对象都不打，因为它的 message 会把整条语句抄进去。
 *
 * <h3>★ 解析必须失败得软、认得窄</h3>
 * 这一层的全部价值在于「它的关系是人写的」。一旦它开始猜，它就退化成了一个更差的
 * {@code ConnectorSemanticDeriveService}——同样在猜，却还顶着「来自视图定义」这个
 * 会让人更信它的出处。所以规则是宁可少挖：
 * <ul>
 *   <li>解析不了的对象<b>整个跳过并计数</b>，绝不半解析半猜。</li>
 *   <li>只认 {@code a.x = b.y} 这种两端都是列、且两端的限定符都能在 FROM 里
 *       解析到真实表的等值条件。</li>
 *   <li>UNION、CTE、FROM 里的子查询、任一端带函数/表达式、{@code USING (...)}、
 *       {@code OR} 分支、跨 schema 的表——一律不要，各自计数。</li>
 * </ul>
 * 每一条「不要」的理由都写在对应的方法上，它们不是保守，是各有各的出错方式。
 *
 * <h3>它不写库</h3>
 * 本类只负责<b>读出来、解析好、交出去</b>。写语义行是集成阶段的事：
 * 落成 {@link ConnectorSemanticService#SCOPE_JOIN} 行需要
 * {@code anchorHash}，而那要拿结构快照里的 {@code FieldDetail} 才算得出来，
 * 那份东西在本类手上没有、也不该有（它属于 {@code ConnectorSchemaService}）。
 * 本类给出的是关系本身，外加 {@link CorpusJoin#evidence()} /
 * {@link CorpusJoin#basis()} / {@link CorpusJoin#confidence()} 这三样
 * <b>只有本阶段知道、别处推不出来</b>的东西。
 *
 * @see SemanticDataTier 三档数据出库开关。本阶段稳稳落在第 1 档，任何档位都能跑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticSqlCorpusReader {

    /**
     * 审计动作名。
     *
     * <p><b>为什么写在这里而不是 {@link ConnectorAuditService} 里</b>：那个类由别的切片在改，
     * 而网关只校验 {@link ConnectorAuditService#PLATFORM_OP_PREFIX} 前缀、不校验白名单，
     * 所以常量放在使用方这里是安全的。等本切片被集成时，把它挪进
     * {@code ConnectorAuditService} 与 {@code OP_SCHEMA_REFRESH} 排在一起更好——
     * 那份清单的价值就在于「平台一共会打哪几种查询」能一眼看全。
     *
     * <p>名字要让客户的 DBA 分得出来：这是<b>读 DDL</b>，与
     * {@code platform.schema_refresh}（读表结构）、{@code platform.semantic_probe}
     *（真的 SELECT 业务数据做抽样）都不是一件事。
     */
    public static final String OP_SQL_CORPUS = ConnectorAuditService.PLATFORM_OP_PREFIX + "sql_corpus";

    /** 语料来源类型：视图。 */
    public static final String SRC_VIEW = "VIEW";

    /** 语料来源类型：存储过程 / 函数。 */
    public static final String SRC_ROUTINE = "ROUTINE";

    /**
     * {@code MySqlSession.SINGLE_VALUE_MAX} 截断长文本时贴的尾巴（那边是 private，这里只能照抄）。
     *
     * <p>视图正文是走 {@link QueryCapable#query} 读回来的，那条路对单个值有 8KB 的截断——
     * 那个上限是为了<b>保护模型上下文</b>定的，与本阶段无关，但我们照样受它约束。
     * 认出这个标记是为了把「被截断了」和「解析不了」<b>分开计数</b>：
     * 前者是我们自己的管道砍的，后者是视图本身太花哨，两者该采取的行动完全不同。
     *
     * <p>抄常量当然会分叉。分叉的后果是可接受的：标记一改，被截断的正文落进
     * {@code parse()} 照样解析失败（那条尾巴本身就是非法 SQL），只是从
     * {@code viewsSkippedTruncated} 换记到 {@code viewsSkippedUnparseable} 而已，
     * <b>不会产出错的关系</b>。
     *
     * <p>顺带说清楚这个 8KB 上限的真实代价：定义超过 8KB 的视图，几乎必然带着
     * 子查询、UNION 或成片的 CASE——而那些本来就会被下面的窄规则整个跳过。
     * 也就是说它砍掉的，大部分是我们本来也不要的那一批。真不是这样的话，
     * {@code viewsSkippedTruncated} 会显著大于 0，那时再去开一条专用读路不迟。
     */
    static final String TRUNCATION_MARK = "…[单值已截断]";

    /**
     * 只在<b>一个</b>视图/过程里出现过的关系的置信度。
     *
     * <p>刻度要和 {@code ConnectorSemanticDeriveService} 那边的 LLM 推断放在一起看才有意义：
     * 起点显著高于「名字像所以大概是外键」，因为这是人写的；但不给到 90 以上，
     * 因为视图里的等值条件<b>不一定是主外键关系</b>——{@code a.status = b.status}
     * 也是一个合法的等值 join，它只是把两张表按状态对齐，并不表示这两列互为引用。
     * 加上它从未经过数据验证（{@code verified = NONE}），70 是个诚实的位置。
     */
    static final int CONFIDENCE_SINGLE_SOURCE = 70;

    /**
     * 在两个及以上<b>不同</b>视图/过程里都出现过的关系的置信度。
     *
     * <p>多一处出现不只是「多一票」：它排除掉了「某个人为了一张报表临时把两张表凑在一起」
     * 这种一次性拼接。两个互不相干的对象都这么 join，说明这是这个库里的常规走法。
     */
    static final int CONFIDENCE_MULTI_SOURCE = 85;

    /** basis 文案里最多点名几个来源对象。再多就只报数——那句话是给模型看的，不是给人查账的。 */
    static final int BASIS_NAMED_SOURCES = 3;

    /**
     * 一次最多吐出多少条关系。
     *
     * <p>不是怕数量多，是怕一个病态的库（几百个互相 join 的报表视图）把下游的
     * {@code replaceInferred} 那一整个事务撑爆——那边是<b>先删后插</b>，
     * 插到一半失败会连旧的语义行一起回滚掉。上限在这里挡住，比在那边挡便宜得多。
     */
    static final int MAX_JOINS = 500;

    /**
     * 表达式/FROM 树的递归深度上限。
     *
     * <p>jsqlparser 把 {@code a AND b AND c ...} 建成<b>左深</b>的 AndExpression 链，
     * 一个上千个条件的 WHERE 会让下面的递归直接 StackOverflowError。
     * 那个错不会被普通的 {@code catch (Exception)} 接住，会把整条推导线打死——
     * 为了一个视图。所以宁可在 200 层上停手，把这个对象算作解析不了。
     */
    static final int RECURSION_MAX = 200;

    /** note 字段的落库上限（{@code connection.semantic_note} 是 varchar(512)，留点余量）。 */
    static final int NOTE_MAX = 500;

    private final ConnectorGateway gateway;

    /**
     * 总开关。视图/过程正文对一部分客户是敏感资产（里面写着业务规则），
     * 留一个能整段关掉的开关，比让客户为了关掉它去改别的东西强。
     */
    @Value("${connector.semantic.corpus.enabled:true}")
    boolean enabled = true;

    /** 一次最多读多少个视图。 */
    @Value("${connector.semantic.corpus.max-views:200}")
    int maxViews = 200;

    /** 一次最多读多少个存储过程 / 函数。给得比视图少，理由见 {@link #routinesSql}。 */
    @Value("${connector.semantic.corpus.max-routines:50}")
    int maxRoutines = 50;

    /** 要不要读存储过程。默认开；读不到正文是常态，不是故障。 */
    @Value("${connector.semantic.corpus.scan-routines:true}")
    boolean scanRoutines = true;

    /** 两条 information_schema 查询的语句超时。 */
    @Value("${connector.semantic.corpus.timeout-seconds:15}")
    int timeoutSeconds = 15;

    /** 两条查询各自的结果字节上限。200 个视图 × 每个至多 8KB ≈ 1.6MB，2MB 是留了余量的。 */
    @Value("${connector.semantic.corpus.max-bytes:2000000}")
    int maxBytes = 2_000_000;

    /**
     * 单个对象的解析超时（毫秒）。
     *
     * <p>jsqlparser 的 {@code parseStatement} 本来就把每次解析放在一个单线程池里跑并等超时，
     * 默认值是秒级的。客户写的 SQL 形状不可控，200 个对象 × 默认超时 = 分钟级的空等，
     * 而这条路上占着的是网关的<b>每实例并发闸</b>。压到 1 秒：能在 1 秒内解析完的语句，
     * 就是我们这条窄规则唯一认得的那一类。
     */
    @Value("${connector.semantic.corpus.parse-timeout-ms:1000}")
    int parseTimeoutMs = 1000;

    /**
     * 整批解析的总时间预算（毫秒）。超了就停手，剩下的对象计入
     * {@link CorpusResult#getSkippedNoBudget()}。
     *
     * <p>单个超时管不住总量：200 个各花 900ms 的对象加起来就是 3 分钟。
     * 语义层是<b>叠加的注解</b>，不值得为它把一条连接的并发闸占上几分钟。
     */
    @Value("${connector.semantic.corpus.parse-budget-ms:20000}")
    long parseBudgetMs = 20_000L;

    // ================================================================ 对外

    /**
     * 读一条连接的既有 SQL 语料，解析出等值 join。
     *
     * <h3>★ 它<b>从不抛异常</b></h3>
     * 失败一律落进 {@link CorpusResult#isOk()} = false 加一句 note。理由与
     * {@code ConnectorSemanticDeriveService} 那边一致：语义层是叠加的注解，不是连接可用的前提。
     * 客户的账号读不到视图定义（很常见），不该让整条语义层推导失败。
     *
     * <h3>调用方要负责的一件事：租户上下文</h3>
     * {@link ConnectorGateway#executeAsPlatform} 要求线程上有<b>真实的</b>租户，
     * {@code TenantContext.runAsSystem(...)} 不算数（它只开系统模式，
     * {@code CURRENT_TENANT} 仍是空的）。后台任务必须按那一行的 {@code tenant_id}
     * 自己 {@code TenantContext.set(...)}，并在 finally 里 {@code clear()}。
     *
     * @param connectorId 连接 id（管理面按 id 寻址）
     */
    public CorpusResult read(Long connectorId) {
        if (!enabled) {
            CorpusResult off = new CorpusResult();
            off.ok = false;
            off.note = "既有 SQL 语料挖掘已被配置关闭（connector.semantic.corpus.enabled=false）。";
            return off;
        }
        Corpus corpus;
        try {
            corpus = gateway.executeAsPlatform(connectorId, Capability.QUERY, OP_SQL_CORPUS, session -> {
                // 要的是 QUERY 而不是 DESCRIBE：我们确确实实在客户库上跑了一条 SELECT，
                // 尽管它读的是 information_schema。按【实际执行的东西】申能力，
                // 别因为「读的只是元数据」就去申一个更软的——那会让能力位失去它的意思。
                if (!(session instanceof QueryCapable q)) {
                    throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                            "这条连接不支持执行查询，无法读取既有 SQL 语料");
                }
                return readCorpus(q);
            });
        } catch (ConnectorException e) {
            CorpusResult bad = new CorpusResult();
            bad.ok = false;
            // getSafeDetail() 是 ConnectorException 的契约：写死的常量句子，不含 SQL / 主机名。
            bad.note = clip("读取既有 SQL 语料失败："
                    + (e.getSafeDetail() == null ? e.getCode().modelHint() : e.getSafeDetail()));
            return bad;
        } catch (RuntimeException e) {
            // 没归过类的失败。★ 原始异常只进日志：它可能是我们自己的 bug，也可能裹着一层
            // 还没被归一的底层异常，而 note 会被写进 connection.semantic_note 展示给客户看。
            log.error("读取既有 SQL 语料时出现未归类异常 connectorId={}", connectorId, e);
            CorpusResult bad = new CorpusResult();
            bad.ok = false;
            bad.note = "读取既有 SQL 语料时发生内部错误，已跳过这一步（详见服务端日志）。";
            return bad;
        }
        CorpusResult r = extract(corpus.entries());
        r.viewsTruncated = corpus.viewsTruncated();
        r.routinesTruncated = corpus.routinesTruncated();
        r.ok = corpus.viewsRead() || corpus.routinesRead();
        r.note = clip(buildNote(r, corpus));
        return r;
    }

    // ================================================================ 读（客户库那一次往返）

    /** 两条 information_schema 查询的产物。原始正文只在本记录里活到 {@link #extract} 结束。 */
    private record Corpus(List<CorpusEntry> entries, boolean viewsRead, boolean routinesRead,
                          boolean viewsTruncated, boolean routinesTruncated,
                          String viewsFailure, String routinesFailure) {}

    /** 一个客户自己写的对象：它的 schema、名字、和定义正文。<b>正文不出本类。</b> */
    record CorpusEntry(String kind, String schema, String name, String sql) {}

    private Corpus readCorpus(QueryCapable q) {
        List<CorpusEntry> entries = new ArrayList<>();
        boolean viewsRead = false;
        boolean routinesRead = false;
        boolean viewsTruncated = false;
        boolean routinesTruncated = false;
        String viewsFailure = null;
        String routinesFailure = null;

        // ★ 两条查询各自 try：视图读到了、过程读不到（只读账号的常态）时，
        //   已经拿到的视图不能因为第二条失败而一起丢掉。
        try {
            QueryResult qr = q.query(viewsSql(), options(maxViews + 1));
            Cursor c = new Cursor(qr);
            int n = qr.rowCount();
            if (n > maxViews) {
                // 多取一行来区分「正好 maxViews 个」和「还有更多」——与 MySqlSession.query
                // 里那一手同源。只靠 size() == maxViews 判断会把恰好等于上限的完整结果误报成截断。
                viewsTruncated = true;
                n = maxViews;
            }
            if (qr.truncated()) {
                // 字节上限先到了。同样是截断，也要说。
                viewsTruncated = true;
            }
            for (int i = 0; i < n; i++) {
                String name = c.str(i, "TABLE_NAME");
                if (name == null || name.isBlank()) {
                    continue;
                }
                entries.add(new CorpusEntry(SRC_VIEW, c.str(i, "TABLE_SCHEMA"), name, c.str(i, "VIEW_DEFINITION")));
            }
            viewsRead = true;
        } catch (ConnectorException e) {
            viewsFailure = e.getSafeDetail() == null ? e.getCode().modelHint() : e.getSafeDetail();
            log.warn("读取视图定义失败，本阶段降级继续 code={}", e.getCode());
        }

        if (scanRoutines) {
            try {
                QueryResult qr = q.query(routinesSql(), options(maxRoutines + 1));
                Cursor c = new Cursor(qr);
                int n = qr.rowCount();
                if (n > maxRoutines) {
                    routinesTruncated = true;
                    n = maxRoutines;
                }
                if (qr.truncated()) {
                    routinesTruncated = true;
                }
                for (int i = 0; i < n; i++) {
                    String name = c.str(i, "ROUTINE_NAME");
                    if (name == null || name.isBlank()) {
                        continue;
                    }
                    entries.add(new CorpusEntry(SRC_ROUTINE, c.str(i, "ROUTINE_SCHEMA"), name,
                            c.str(i, "ROUTINE_DEFINITION")));
                }
                routinesRead = true;
            } catch (ConnectorException e) {
                // ★ 这是预期内的正常结果，不是故障：information_schema.ROUTINES 在不少托管 MySQL 上
                //   对只读账号是直接拒的。归成 note，不归成失败。
                routinesFailure = e.getSafeDetail() == null ? e.getCode().modelHint() : e.getSafeDetail();
                log.info("读取存储过程正文失败（只读账号的常见结果，不是故障） code={}", e.getCode());
            }
        }
        return new Corpus(entries, viewsRead, routinesRead, viewsTruncated, routinesTruncated,
                viewsFailure, routinesFailure);
    }

    private QueryOptions options(int maxRows) {
        return new QueryOptions(maxRows, maxBytes, timeoutSeconds);
    }

    /**
     * 视图清单。
     *
     * <h3>为什么 ORDER BY 长这样</h3>
     * <ul>
     *   <li><b>定义为空的排最后。</b>{@code VIEW_DEFINITION} 为空<b>不表示这个视图没内容</b>，
     *       它表示当前账号<b>没有 SHOW VIEW 权限</b>——MySQL 让你看得见这一行、看不见正文。
     *       不把它们推到最后，一个「视图很多但一个都读不到」的库会用几百条空行把
     *       {@code maxViews} 的名额吃光，而真正能读的那几个正好排在名额外面。</li>
     *   <li><b>然后按定义长度升序。</b>这是这个阶段真正的「重要性」：短定义 = 我们这条窄规则
     *       读得懂的那一类。超长定义几乎必然带子查询/UNION，下面反正要整个跳过。
     *       所以被 LIMIT 砍掉的，正好是我们本来也用不上的那一批——这与
     *       {@code ConnectorSchemaService} 当年「按表名字母序截断、把 t_ 开头的业务表整片砍掉」
     *       那个坑是同一类问题，方向相反。</li>
     *   <li><b>最后按名字。</b>纯粹为了可重复：同一个库跑两次要得到同一份结果，
     *       否则下游的 diff 会永远在响。</li>
     * </ul>
     *
     * <p>{@code DATABASE()} 而不是把库名拼进去：{@link QueryCapable#query} 没有绑定参数，
     * 而把任何标识符拼进 SQL 都是在给自己开一个注入口子。这条连接本来就只连着一个库。
     */
    String viewsSql() {
        return "SELECT TABLE_SCHEMA, TABLE_NAME, VIEW_DEFINITION FROM information_schema.VIEWS "
                + "WHERE TABLE_SCHEMA = DATABASE() "
                + "ORDER BY CASE WHEN VIEW_DEFINITION IS NULL OR VIEW_DEFINITION = '' THEN 1 ELSE 0 END ASC, "
                + "CHAR_LENGTH(VIEW_DEFINITION) ASC, TABLE_NAME ASC "
                + "LIMIT " + (maxViews + 1);
    }

    /**
     * 存储过程 / 函数清单。
     *
     * <p><b>名额给得比视图少，是因为期望值本来就低。</b>{@code ROUTINE_DEFINITION} 要
     * {@code SHOW_ROUTINE}（8.0.20+）、或者你是 definer、或者 {@code SELECT ON mysql.proc}（5.7）
     * 才读得到，客户给的只读账号通常一个都不满足——读回来的是一列空值。
     * 就算读到了，过程体是 {@code BEGIN ... END} 的<b>过程式</b>语法，
     * jsqlparser 解析不了整段（见 {@link #plainSelectOf}），能捞到东西的只有
     * 「整个 body 就是一条 SELECT」的那种函数。
     *
     * <p>那为什么还要读：一是这一条查询本身很便宜；二是<b>「读不到」这件事本身要报给人看</b>——
     * 「这个库有 12 个存储过程，我们读不到正文」和「这个库没有存储过程」是完全不同的两句话，
     * 而不去读就只能说后面那句，还说错了。
     */
    String routinesSql() {
        return "SELECT ROUTINE_SCHEMA, ROUTINE_NAME, ROUTINE_TYPE, ROUTINE_DEFINITION "
                + "FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE() "
                + "ORDER BY CASE WHEN ROUTINE_DEFINITION IS NULL OR ROUTINE_DEFINITION = '' THEN 1 ELSE 0 END ASC, "
                + "CHAR_LENGTH(ROUTINE_DEFINITION) ASC, ROUTINE_NAME ASC "
                + "LIMIT " + (maxRoutines + 1);
    }

    /** 按列名（大小写不敏感）取值的小游标。连接器回的是 columns + rows 两个平行结构。 */
    private static final class Cursor {
        private final Map<String, Integer> index = new HashMap<>();
        private final List<List<Object>> rows;

        Cursor(QueryResult qr) {
            this.rows = qr.rows() == null ? List.of() : qr.rows();
            List<String> cols = qr.columns();
            if (cols != null) {
                for (int i = 0; i < cols.size(); i++) {
                    if (cols.get(i) != null) {
                        index.put(cols.get(i).toLowerCase(Locale.ROOT), i);
                    }
                }
            }
        }

        String str(int row, String column) {
            Integer i = index.get(column.toLowerCase(Locale.ROOT));
            if (i == null || row >= rows.size()) {
                return null;
            }
            List<Object> r = rows.get(row);
            if (r == null || i >= r.size()) {
                return null;
            }
            Object v = r.get(i);
            return v == null ? null : String.valueOf(v);
        }
    }

    // ================================================================ 解析（纯函数，可离线测）

    /**
     * 把一批对象定义解析成关系。<b>纯函数，不碰网络、不碰库</b>——这个阶段的全部风险都在这里，
     * 它必须能被单测直接喂 SQL 字符串。
     */
    CorpusResult extract(List<CorpusEntry> entries) {
        CorpusResult r = new CorpusResult();
        Map<String, CorpusJoin> byPair = new LinkedHashMap<>();
        long deadline = System.currentTimeMillis() + parseBudgetMs;

        for (CorpusEntry e : entries) {
            boolean view = SRC_VIEW.equals(e.kind());
            if (view) {
                r.viewsSeen++;
            } else {
                r.routinesSeen++;
            }

            String sql = e.sql();
            if (sql == null || sql.isBlank()) {
                // 空正文 = 权限不够，不是「这个视图是空的」。分开计数，note 里才说得出人话。
                if (view) {
                    r.viewDefinitionsDenied++;
                } else {
                    r.routineDefinitionsDenied++;
                }
                continue;
            }
            if (sql.contains(TRUNCATION_MARK)) {
                if (view) {
                    r.viewsSkippedTruncated++;
                } else {
                    r.routinesSkippedTruncated++;
                }
                continue;
            }
            if (System.currentTimeMillis() > deadline) {
                r.skippedNoBudget++;
                continue;
            }

            PlainSelect ps = plainSelectOf(sql, e.kind(), e.name());
            if (ps == null) {
                if (view) {
                    r.viewsSkippedUnparseable++;
                } else {
                    r.routinesSkippedUnparseable++;
                }
                continue;
            }
            if (view) {
                r.viewsParsed++;
            } else {
                r.routinesParsed++;
            }
            harvest(ps, e, byPair, r);
        }
        r.joins = new ArrayList<>(byPair.values());
        return r;
    }

    /**
     * 把一段定义收窄成「一条我们认得的 PlainSelect」，认不出来一律返回 null。
     *
     * <h3>五道关，每一道都有自己的出错方式</h3>
     * <ol>
     *   <li><b>解析失败</b>——存储过程的 {@code BEGIN ... END}、方言语法、被截断的正文都落这里。
     *       ★ 这里<b>刻意不去切分过程体</b>再逐条解析：自己写一个 SQL 切分器，
     *       第一个切错的地方就会产出一条「看起来像人写的」假关系，
     *       而本阶段的全部价值恰恰在于它的关系是人写的。宁可少挖。</li>
     *   <li><b>不是 SELECT / CREATE VIEW</b>——{@code information_schema.VIEWS} 给的是裸的
     *       SELECT，{@code SHOW CREATE VIEW} 给的是完整的 CREATE VIEW，两种都接。
     *       其它语句类型（包括解析成 {@code Block} 的过程体）一概不认。</li>
     *   <li><b>带 CTE（WITH）</b>——★ 这一条最要紧。CTE 的名字在 FROM 里长得和真表<b>一模一样</b>，
     *       解析出来会得到一条 {@code cte_a.x = cte_b.y} 的「关系」，两个「表」在客户库里
     *       根本不存在。那不是少挖，那是<b>凭空造了一条关系</b>，而且它会带着
     *       「来自视图 xxx 的定义」这个出处一起注入给模型。</li>
     *   <li><b>UNION / INTERSECT 等</b>（{@code SelectBody} 不是 {@code PlainSelect}）——
     *       各分支的 FROM 作用域是独立的，混在一起解析会把 A 分支的别名接到 B 分支的表上。</li>
     *   <li>FROM 里的子查询不在这里拦，见 {@link #collectFrom}：它们只是<b>进不了别名表</b>，
     *       于是引用它们的条件会自然地解析不出来、被逐条丢掉。那比整个视图丢掉更准。</li>
     * </ol>
     *
     * <p>★ 注意 catch 的是 {@code Throwable}：jsqlparser 是递归下降的，畸形输入能让它
     * StackOverflowError，而那个错误一路穿过普通的 {@code catch (Exception)}，
     * 会把整条推导线打死——为了一个视图。
     *
     * <p>★ 还要注意日志里<b>没有异常对象、也没有 sql</b>：jsqlparser 的异常 message 会把
     * 整条语句抄进去，而视图正文里可能带着客户的真实 id 和名称。只留类名，够定位了。
     */
    private PlainSelect plainSelectOf(String sql, String kind, String name) {
        Statement st;
        try {
            st = CCJSqlParserUtil.parse(sql, p -> p.withTimeOut(parseTimeoutMs));
        } catch (Throwable t) {
            log.debug("既有 SQL 语料：{} {} 的定义解析不了，已跳过（{}）", kind, name, t.getClass().getSimpleName());
            return null;
        }
        Select select;
        if (st instanceof CreateView cv) {
            select = cv.getSelect();
        } else if (st instanceof Select s) {
            select = s;
        } else {
            return null;
        }
        if (select == null) {
            return null;
        }
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            return null;
        }
        SelectBody body = select.getSelectBody();
        return body instanceof PlainSelect ps ? ps : null;
    }

    /** FROM 里解析得出的一张真表。{@link #AMBIGUOUS} 是「这个名字对应不止一张表」的哨兵。 */
    private record Resolved(String table, String schema) {}

    private static final Resolved AMBIGUOUS = new Resolved("", "");

    /**
     * 从一条 PlainSelect 里收关系。
     *
     * <p>两步：先把 FROM 树走一遍，建出「限定符 → 真表」的别名表并顺手收走所有 ON 条件；
     * 再把 ON 条件和 WHERE 一起过一遍等值提取。
     *
     * <h3>为什么 WHERE 也要挖</h3>
     * 老库里 {@code FROM a, b WHERE a.id = b.aid} 这种写法极常见，而 MySQL 在
     * {@code information_schema.VIEWS} 里<b>原样保留</b>它（不会帮你改写成 JOIN ON）。
     * 只挖 ON 会让这一整类库颗粒无收，而它们恰恰是外键声明最少、最需要这个阶段的那一类。
     *
     * <p>代价是 WHERE 里混着大量普通过滤条件（{@code a.status = 1}）。它们在下面会因为
     * 「右边不是列」被静默跳过，<b>且不计入任何 dropped 计数</b>——那是正常的过滤条件，
     * 不是一次失败，混进计数器只会把真正的信号淹掉。
     */
    private void harvest(PlainSelect ps, CorpusEntry src, Map<String, CorpusJoin> byPair, CorpusResult r) {
        Map<String, Resolved> scope = new HashMap<>();
        List<Expression> conditions = new ArrayList<>();
        if (!collectFrom(ps.getFromItem(), scope, conditions, r, 0)) {
            r.droppedTooDeep++;
            return;
        }
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                if (!collectJoin(j, scope, conditions, r, 0)) {
                    r.droppedTooDeep++;
                    return;
                }
            }
        }
        if (ps.getWhere() != null) {
            conditions.add(ps.getWhere());
        }
        for (Expression e : conditions) {
            walk(e, scope, src, byPair, r, 0);
        }
    }

    /**
     * 走 FROM 树，把能解析的表登记进别名表。
     *
     * <h3>★ {@code SubJoin} 这一支不能漏，漏了这个阶段就是 0 产出</h3>
     * MySQL 存进 {@code information_schema.VIEWS} 的<b>不是客户写的原文</b>，是规范化之后的形式：
     * 标识符全部加反引号并带上库名，而整个 FROM 被<b>裹进一层括号</b>——
     * {@code from (`d`.`orders` `o` join `d`.`users` `u` on((`o`.`uid` = `u`.`id`)))}。
     * jsqlparser 把括号里的那一整块解析成 {@link SubJoin}，于是
     * {@code PlainSelect.getJoins()} 是 <b>null</b>，join 全在 {@code SubJoin.getJoinList()} 里。
     * 只看 {@code getJoins()} 的实现在单测里（手写的漂亮 SQL）全绿，接上真库一条都挖不出来，
     * 而且不报错——只是「这个客户没有视图关系」。
     *
     * @return false 表示这棵树太深，整个对象放弃
     */
    private boolean collectFrom(FromItem fi, Map<String, Resolved> scope, List<Expression> out,
                                CorpusResult r, int depth) {
        if (fi == null) {
            return true;
        }
        if (depth > RECURSION_MAX) {
            return false;
        }
        if (fi instanceof Table t) {
            String key = fold(unquote(t.getAlias() == null ? t.getName() : t.getAlias().getName()));
            if (key.isEmpty()) {
                return true;
            }
            Resolved res = new Resolved(unquote(t.getName()), unquote(t.getSchemaName()));
            Resolved old = scope.get(key);
            if (old != null && !old.equals(res)) {
                // 同一个限定符指向两张不同的表：合法 SQL 里不该出现，但两个不同 schema 下的同名表
                // 都不写别名时会撞上。撞了就谁都不认——认错一个的后果是一条指向错表的关系。
                scope.put(key, AMBIGUOUS);
            } else if (old == null) {
                scope.put(key, res);
            }
            return true;
        }
        if (fi instanceof SubJoin sj) {
            if (!collectFrom(sj.getLeft(), scope, out, r, depth + 1)) {
                return false;
            }
            if (sj.getJoinList() != null) {
                for (Join j : sj.getJoinList()) {
                    if (!collectJoin(j, scope, out, r, depth + 1)) {
                        return false;
                    }
                }
            }
            return true;
        }
        if (fi instanceof ParenthesisFromItem pf) {
            return collectFrom(pf.getFromItem(), scope, out, r, depth + 1);
        }
        // 子查询、LATERAL、表函数、VALUES……：★ 刻意什么都不做。
        // 它们的别名进不了 scope，于是引用它们的等值条件会在 resolve() 那里解析不出来，
        // 逐条丢掉并计入 droppedUnresolvable。比整个视图丢掉更准，也比猜它们指向哪张表安全。
        return true;
    }

    private boolean collectJoin(Join j, Map<String, Resolved> scope, List<Expression> out,
                                CorpusResult r, int depth) {
        if (!collectFrom(j.getRightItem(), scope, out, r, depth + 1)) {
            return false;
        }
        if (j.getOnExpressions() != null) {
            out.addAll(j.getOnExpressions());
        }
        // ★ USING (col) 明确不支持。它等价于「左边那一坨里拥有这个列的那张表 . col = 右表 . col」，
        //   而「左边那一坨里的哪一张」只有拿到列清单才判得出来——本类手上没有列清单。
        //   猜一个（比如取最近的那张表）在两表时对、在三表时会悄悄指错表。计数让它可见。
        if (j.getUsingColumns() != null && !j.getUsingColumns().isEmpty()) {
            r.droppedUsing += j.getUsingColumns().size();
        }
        return true;
    }

    /**
     * 在一棵条件树上找等值 join。
     *
     * <p>只穿过 {@code AND} 和括号。★ {@code OR} 整支不进——{@code a.x = b.y OR a.z = b.w}
     * 里两条至多成立一条，把两条都当成关系是<b>凭空多造一条</b>；
     * 而判断哪条才是真的需要看数据，那是 S3 的事，不是这一层的事。
     */
    private void walk(Expression e, Map<String, Resolved> scope, CorpusEntry src,
                      Map<String, CorpusJoin> byPair, CorpusResult r, int depth) {
        if (e == null) {
            return;
        }
        if (depth > RECURSION_MAX) {
            // ★ 必须计数，不能静默返回。一条几百个条件的 WHERE 会让真正的关联条件
            //   躺在递归够不到的深处，于是这个视图「什么都没挖到」——
            //   而「没挖到」和「挖到 0 条」在结果上长得一模一样，没有计数器就永远没人发现。
            r.droppedTooDeep++;
            return;
        }
        if (e instanceof Parenthesis p) {
            walk(p.getExpression(), scope, src, byPair, r, depth + 1);
            return;
        }
        if (e instanceof AndExpression a) {
            walk(a.getLeftExpression(), scope, src, byPair, r, depth + 1);
            walk(a.getRightExpression(), scope, src, byPair, r, depth + 1);
            return;
        }
        if (e instanceof OrExpression) {
            r.droppedOrBranch++;
            return;
        }
        if (e instanceof EqualsTo eq) {
            consider(eq, scope, src, byPair, r);
        }
        // 其它（>, LIKE, IN, NOT, 函数调用……）：不是等值 join，静默略过。
    }

    /**
     * 判一条 {@code =} 是不是我们要的那种关系，是就记下来。
     *
     * <h3>四种「不是」，处置各不相同</h3>
     * <ul>
     *   <li><b>任一端不是裸列</b>（字面量、函数、CAST、算术）——静默略过，<b>不计数</b>。
     *       {@code a.status = 1} 是过滤条件，不是一次失败；
     *       {@code lower(a.x) = b.y} 也一样：函数两端的值域关系我们看不懂，不该猜。
     *       把它们计入 dropped 会让计数器被正常过滤条件淹掉，失去诊断价值。</li>
     *   <li><b>限定符解析不出来</b>——计入 {@code droppedUnresolvable}。这一项偏高说明
     *       视图里有子查询/派生表，或者我们的别名解析漏了一种形状，<b>是要去看的信号</b>。</li>
     *   <li><b>引用了别的 schema 的表</b>——计入 {@code droppedForeignSchema}。跨库 join
     *       在客户库里是真事，但那张表不在这条连接的目录里，写进语义层只会让模型去 join
     *       一张它看不见也查不了的表。</li>
     *   <li><b>两端是同一张表的同一列</b>——静默略过。{@code t.x = t.x} 这种（自连接的退化写法）
     *       不表达任何关系。</li>
     * </ul>
     */
    private void consider(EqualsTo eq, Map<String, Resolved> scope, CorpusEntry src,
                          Map<String, CorpusJoin> byPair, CorpusResult r) {
        if (!(eq.getLeftExpression() instanceof Column lc) || !(eq.getRightExpression() instanceof Column rc)) {
            return;
        }
        Resolved lt = resolve(lc, scope);
        Resolved rt = resolve(rc, scope);
        if (lt == null || rt == null) {
            r.droppedUnresolvable++;
            return;
        }
        String own = fold(src.schema());
        if (foreign(lt, own) || foreign(rt, own)) {
            r.droppedForeignSchema++;
            return;
        }
        String lcol = unquote(lc.getColumnName());
        String rcol = unquote(rc.getColumnName());
        if (lcol.isEmpty() || rcol.isEmpty()) {
            r.droppedUnresolvable++;
            return;
        }
        if (fold(lt.table()).equals(fold(rt.table())) && fold(lcol).equals(fold(rcol))) {
            return;
        }
        accumulate(lt.table(), lcol, rt.table(), rcol, src, byPair, r);
    }

    private static boolean foreign(Resolved t, String ownSchemaFolded) {
        String s = fold(t.schema());
        return !s.isEmpty() && !ownSchemaFolded.isEmpty() && !s.equals(ownSchemaFolded);
    }

    /**
     * 把一个列的限定符解析成真表。解析不出来返回 null。
     *
     * <p>三种形状都要认，因为 MySQL 规范化视图时会按有没有别名写成不同样子：
     * 有别名写 {@code `o`.`uid`}，没别名写 {@code `d`.`orders`.`uid`}。
     * 后者的限定符在 jsqlparser 里是一个「name=orders, schema=d」的 Table，
     * 而我们的别名表对没别名的表正是按表名登记的，对得上。
     *
     * <p>没有限定符（{@code WHERE id = b.id}）一律解析不出来：多表作用域里它是歧义的，
     * 单表作用域里根本不会有 join。猜「反正只有一张表」在两表视图里就会指错。
     */
    private static Resolved resolve(Column c, Map<String, Resolved> scope) {
        Table t = c.getTable();
        if (t == null) {
            return null;
        }
        String key = fold(unquote(t.getName()));
        if (key.isEmpty()) {
            return null;
        }
        Resolved res = scope.get(key);
        if (res == null || res == AMBIGUOUS) {
            return null;
        }
        String colSchema = fold(unquote(t.getSchemaName()));
        if (!colSchema.isEmpty() && !fold(res.schema()).isEmpty() && !colSchema.equals(fold(res.schema()))) {
            return null;
        }
        return res;
    }

    /**
     * 登记一条关系。
     *
     * <h3>为什么要定一个规范方向</h3>
     * {@code a.x = b.y} 和 {@code b.y = a.x} 是同一个事实。不定方向的话，同一条关系会在
     * 不同视图里存成两行互为镜像的记录，下游去重看不出它们是一回事，
     * 模型也会在 describe 里看到两句一样的话。
     *
     * <p>取字典序作规范方向，<b>不表示左边是子表</b>：第 1 档上我们没有任何依据判断
     * 谁引用谁（那要看数据分布，是 S3 的事）。下游要让 {@code conn_describe(右表)}
     * 也看得见这条关系，请用 {@link CorpusJoin#mirrored()} 自己挂一份，
     * 别去猜方向。
     */
    private void accumulate(String lo, String lc, String ro, String rc,
                            CorpusEntry src, Map<String, CorpusJoin> byPair, CorpusResult r) {
        String lk = fold(lo) + "." + fold(lc);
        String rk = fold(ro) + "." + fold(rc);
        boolean swap = lk.compareTo(rk) > 0;
        String key = swap ? rk + "=" + lk : lk + "=" + rk;
        CorpusJoin j = byPair.get(key);
        if (j == null) {
            if (byPair.size() >= MAX_JOINS) {
                r.droppedOverflow++;
                return;
            }
            j = new CorpusJoin();
            j.leftObject = swap ? ro : lo;
            j.leftColumn = swap ? rc : lc;
            j.rightObject = swap ? lo : ro;
            j.rightColumn = swap ? lc : rc;
            j.sources = new ArrayList<>();
            byPair.put(key, j);
        }
        j.addSource(src.kind(), src.name());
    }

    // ================================================================ note

    private String buildNote(CorpusResult r, Corpus corpus) {
        StringBuilder sb = new StringBuilder();
        if (corpus.viewsFailure() != null) {
            sb.append("读视图定义失败：").append(corpus.viewsFailure()).append("。");
        } else if (r.viewsSeen == 0) {
            // ★ 这句话要说全。「没有视图」听起来像「这个阶段没用」，
            //   而真相是「这个阶段在任何真实数据上都还没被验证过」，两件事都得让人知道。
            sb.append("这个库里没有视图，本阶段无事可做。（本阶段从未在真实客户库上验证过——"
                    + "我们自己的参考库同样是 0 个视图。）");
        } else if (r.viewDefinitionsDenied == r.viewsSeen) {
            sb.append("这个库有 ").append(r.viewsSeen)
                    .append(" 个视图，但当前只读账号读不到它们的定义（MySQL 需要 SHOW VIEW 权限）。"
                            + "这是只读账号的正常结果，不是故障；如需启用本阶段，请客户为该账号补 SHOW VIEW。");
        } else {
            sb.append("扫描视图 ").append(r.viewsSeen).append(" 个，解析成功 ").append(r.viewsParsed)
                    .append(" 个，挖出关系 ").append(r.joins == null ? 0 : r.joins.size()).append(" 条。");
            if (r.viewDefinitionsDenied > 0) {
                sb.append("其中 ").append(r.viewDefinitionsDenied).append(" 个因缺少 SHOW VIEW 权限读不到定义。");
            }
            if (r.viewsSkippedUnparseable > 0) {
                sb.append(r.viewsSkippedUnparseable).append(" 个因含子查询/UNION/CTE 等被整个跳过。");
            }
            if (r.viewsSkippedTruncated > 0) {
                sb.append(r.viewsSkippedTruncated).append(" 个定义过长被平台截断，无法解析。");
            }
            if (r.viewsTruncated) {
                sb.append("视图数超过上限 ").append(maxViews).append("，只扫了定义最短的那一批。");
            }
        }
        if (corpus.routinesFailure() != null) {
            sb.append("存储过程正文读不到（").append(corpus.routinesFailure())
                    .append("）——只读账号通常就是这个结果，不是故障。");
        } else if (r.routinesSeen > 0 && r.routineDefinitionsDenied == r.routinesSeen) {
            sb.append("这个库有 ").append(r.routinesSeen)
                    .append(" 个存储过程/函数，但当前账号读不到正文（需要 SHOW_ROUTINE 等额外权限），已跳过。");
        } else if (r.routinesParsed > 0) {
            sb.append("另从 ").append(r.routinesParsed).append(" 个存储过程/函数的正文里也解析到了内容。");
        }
        if (r.droppedTooDeep > 0) {
            sb.append("有 ").append(r.droppedTooDeep).append(" 处条件因嵌套过深未解析（不等于那里没有关系）。");
        }
        if (r.skippedNoBudget > 0) {
            sb.append("另有 ").append(r.skippedNoBudget).append(" 个对象因解析超出总时间预算未处理。");
        }
        if (r.joins != null && !r.joins.isEmpty()) {
            sb.append("这些关系是客户自己写下来的，但均未经过数据验证（verified=NONE）——"
                    + "视图可能是几年前建的，口径未必还成立。");
        }
        return sb.toString();
    }

    // ================================================================ 小工具

    /** 去掉标识符外面的引号。MySQL 规范化视图定义时会给<b>每一个</b>标识符加反引号。 */
    static String unquote(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        if (v.length() >= 2) {
            char a = v.charAt(0);
            char b = v.charAt(v.length() - 1);
            if ((a == '`' && b == '`') || (a == '"' && b == '"') || (a == '[' && b == ']')) {
                return v.substring(1, v.length() - 1).trim();
            }
        }
        return v;
    }

    /** 比对用的折叠形式。SQL 标识符的大小写规则各库不同，比对一律折小写。 */
    static String fold(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String clip(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= NOTE_MAX ? s : s.substring(0, NOTE_MAX - 1) + "…";
    }

    // ================================================================ 返回形状

    /**
     * 一条从客户自己写的 SQL 里挖出来的等值关系。
     *
     * <p><b>刻意不是 record</b>：本仓库实际生效的 jackson-databind 是 2.11.1，record 的序列化
     * 支持是 2.12+ 才有的，任何可能跨 HTTP 出去的 DTO 在这里都必须是 Lombok 类
     *（同 {@code ConnectorSchemaService.ObjectDiff} 上那段注释）。
     *
     * <h3>★ 表名/列名的大小写是「视图正文里的写法」</h3>
     * 它不一定与 {@code information_schema} 里的写法一致（MySQL 的
     * {@code lower_case_table_names} 决定了这件事）。下游拿它去结构快照里匹配时
     * <b>必须做大小写不敏感的匹配</b>，否则会有一部分对不上——而对不上的表现只是
     * 「少了几条关系」，不报错、没人会发现。
     */
    @Data
    public static class CorpusJoin {
        private String leftObject;
        private String leftColumn;
        private String rightObject;
        private String rightColumn;
        /** 在哪些客户自己写的对象里见过这条关系。<b>只有对象名，永远没有 SQL 正文。</b> */
        private List<CorpusSource> sources;

        void addSource(String kind, String name) {
            for (CorpusSource s : sources) {
                if (s.getKind().equals(kind) && fold(s.getName()).equals(fold(name))) {
                    return;
                }
            }
            sources.add(new CorpusSource(kind, name));
        }

        /**
         * 依据来源标签，恒为 {@link ConnectorSemanticService#EV_COMMENT}。
         *
         * <h3>为什么是 COMMENT 而不是 NAME / GUESS / DATA</h3>
         * 那四个值的分界线是<b>「有没有外部依据」</b>。一条写在视图里的 join 是
         * <b>客户自己写在库里的一句话</b>——和列注释是同一类东西（人写的、在客户库里、
         * 不是我们推的），所以归 COMMENT。
         * <ul>
         *   <li>不是 {@code NAME}：那是「名字像所以猜它是外键」，而这里根本没有猜的成分。</li>
         *   <li>不是 {@code GUESS}：贴上它会被 {@code ConnectorSemanticDeriveService} 那条
         *       「GUESS 一律丢弃」的规则整批扔掉——把这个阶段唯一的产出全丢了。</li>
         *   <li>不是 {@code DATA}：★ 这一条最要紧。{@code DATA} 的意思是
         *       「在数据里查得到」，而本阶段<b>一个数据值都没读过</b>。
         *       贴 DATA 等于对下游谎报了一次数据验证，
         *       而下游（S3 采样验证）正是靠这个标签决定还要不要去核。</li>
         * </ul>
         * 常量本身没有一个「来自 DDL」的值，我们也不去 {@code ConnectorSemanticService}
         * 里加一个：多一个取值就多一处所有读 evidence 的代码都要跟着改的地方，
         * 而 COMMENT 已经如实表达了「人写在客户库里的依据」这件事。
         */
        public String evidence() {
            return ConnectorSemanticService.EV_COMMENT;
        }

        /** 见 {@link SemanticSqlCorpusReader#CONFIDENCE_SINGLE_SOURCE}。 */
        public int confidence() {
            return sources.size() >= 2 ? CONFIDENCE_MULTI_SOURCE : CONFIDENCE_SINGLE_SOURCE;
        }

        /**
         * 写进 {@code detail_json.basis} 的那句话：<b>这条关系是从哪来的</b>。
         *
         * <p>★ 这句话会原样注入到模型面前，所以它必须<b>具体且真</b>。
         * 「来自视图 v_order_detail 的定义」能让模型（和事后翻记录的人）
         * 直接回到客户库里那个对象去核；一句笼统的「来自客户的 SQL」就只是一个
         * 让人更信它、却没法核的修饰词——那比不写还糟。
         *
         * <p>只放对象<b>名字</b>，绝不放定义正文：视图体里会有客户的真实 id、名称、阈值。
         */
        public String basis() {
            StringBuilder sb = new StringBuilder("来自");
            int named = Math.min(sources.size(), BASIS_NAMED_SOURCES);
            for (int i = 0; i < named; i++) {
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(sources.get(i).label());
            }
            sb.append(" 的定义");
            if (sources.size() > named) {
                sb.append("（共 ").append(sources.size()).append(" 处）");
            }
            return sb.toString();
        }

        /**
         * 左右互换的一份拷贝。
         *
         * <p>存在的理由：本类给出的方向是<b>字典序</b>，不含「谁引用谁」的信息。
         * 语义层的 JOIN 行是挂在左侧表上的，只挂一份的话
         * {@code conn_describe(右表)} 就看不见这条关系。下游要两头都挂时用这个方法，
         * 别自己手写交换——手写交换漏换一个列名，会造出一条
         * {@code a.x → b.x} 的假关系，而它看起来完全正常。
         */
        public CorpusJoin mirrored() {
            CorpusJoin m = new CorpusJoin();
            m.leftObject = rightObject;
            m.leftColumn = rightColumn;
            m.rightObject = leftObject;
            m.rightColumn = leftColumn;
            m.sources = new ArrayList<>(sources);
            return m;
        }
    }

    /** 一条关系是在客户的哪个对象里被看到的。<b>只有类型和名字。</b> */
    @Data
    public static class CorpusSource {
        /** {@link SemanticSqlCorpusReader#SRC_VIEW} / {@link SemanticSqlCorpusReader#SRC_ROUTINE} */
        private String kind;
        private String name;

        public CorpusSource() {
        }

        CorpusSource(String kind, String name) {
            this.kind = kind;
            this.name = name;
        }

        /** 给 basis 用的人话标签，例如「视图 v_order_detail」。 */
        public String label() {
            return (SRC_ROUTINE.equals(kind) ? "存储过程 " : "视图 ") + name;
        }
    }

    /**
     * 一次语料扫描的全部产出。
     *
     * <p>★ 这些计数器<b>不是装饰</b>。本阶段从未在真实客户库上验证过，
     * 它们是第一次接上有视图的客户时唯一的现场证据：
     * {@code viewsSkippedUnparseable} 高说明窄规则太窄（或者客户的视图都很花哨），
     * {@code droppedUnresolvable} 高说明别名解析漏了一种形状，
     * {@code viewDefinitionsDenied} 高说明是权限问题、跟解析一点关系没有。
     * 只报一个「挖到 N 条」会把这三种完全不同的情况混成同一个数字。
     */
    @Data
    public static class CorpusResult {
        /** 至少读到了一类语料。false 表示这次扫描本身没跑成，原因在 {@link #note}。 */
        private boolean ok;
        /** 给人看的一句话，会写进 {@code connection.semantic_note}（varchar 512，已截断）。 */
        private String note;
        /** 挖出来的关系。按第一次出现的顺序，可重复。 */
        private List<CorpusJoin> joins = new ArrayList<>();

        private int viewsSeen;
        private int viewsParsed;
        /** 解析不了（子查询/UNION/CTE/方言/畸形）而整个跳过的视图数。 */
        private int viewsSkippedUnparseable;
        /** 定义超过平台单值上限被截断、因而无法解析的视图数。见 {@link SemanticSqlCorpusReader#TRUNCATION_MARK}。 */
        private int viewsSkippedTruncated;
        /** 看得见这一行、读不到定义的视图数 = <b>缺 SHOW VIEW 权限</b>，不是「视图是空的」。 */
        private int viewDefinitionsDenied;
        /** 视图数超过 {@code max-views}，只扫了定义最短的那一批。 */
        private boolean viewsTruncated;

        private int routinesSeen;
        private int routinesParsed;
        private int routinesSkippedUnparseable;
        private int routinesSkippedTruncated;
        /** 读不到正文的存储过程数。<b>只读账号的常态</b>，不是故障。 */
        private int routineDefinitionsDenied;
        private boolean routinesTruncated;

        /** 两端都是列、但限定符解析不到真表而丢掉的等值条件数。偏高是要去看的信号。 */
        private int droppedUnresolvable;
        /** 引用了别的 schema、不在本连接目录里而丢掉的条数。 */
        private int droppedForeignSchema;
        /** 因为落在 {@code OR} 分支里而整支没进的条数。 */
        private int droppedOrBranch;
        /** {@code JOIN ... USING (col)} 的列数：本类明确不支持，见 {@code collectJoin}。 */
        private int droppedUsing;
        /** 撞上 {@link SemanticSqlCorpusReader#MAX_JOINS} 上限被丢掉的条数。 */
        private int droppedOverflow;
        /**
         * 因为表达式/FROM 树太深（{@link SemanticSqlCorpusReader#RECURSION_MAX}）而停手的次数。
         *
         * <p>它<b>不是</b>「这个视图没有关系」，是「我们没走到那么深」。两者在 joins 里长得一样，
         * 只有这个计数器分得开。偏高说明客户的视图里有几百个条件的 WHERE，
         * 那时该考虑的是把遍历改成显式栈，而不是把深度上限往上调。
         */
        private int droppedTooDeep;
        /** 撞上解析总时间预算、根本没处理的对象数。 */
        private int skippedNoBudget;
    }
}
