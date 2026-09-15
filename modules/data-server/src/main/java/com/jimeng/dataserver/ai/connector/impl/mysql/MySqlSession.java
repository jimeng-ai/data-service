package com.jimeng.dataserver.ai.connector.impl.mysql;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.model.CatalogEntry;
import com.jimeng.dataserver.ai.connector.model.CatalogView;
import com.jimeng.dataserver.ai.connector.model.FieldDetail;
import com.jimeng.dataserver.ai.connector.model.ObjectDetail;
import com.jimeng.dataserver.ai.connector.model.QueryResult;
import com.jimeng.dataserver.ai.connector.model.ReadOnlyVerdict;
import com.jimeng.dataserver.ai.connector.model.WritePlan;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.cap.DescribeCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.QueryOptions;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteCapable;
import com.jimeng.dataserver.ai.connector.spi.cap.WriteOptions;
import com.jimeng.dataserver.ai.connector.guard.WriteSqlGuard;
import com.jimeng.dataserver.ai.connector.model.WriteResult;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 一次 MySQL 会话。
 *
 * <p>它<b>不持有物理连接</b>：每个方法自己从池里借、用完还。理由是一次 Agent 调用可能在
 * 会话对象上停留很久（网关的 try-with-resources 包着整个 op），而占着客户库的一条连接
 * 等模型思考是不可接受的。
 */
@Slf4j
public class MySqlSession implements ConnectorSession, QueryCapable, DescribeCapable, WriteCapable {

    /**
     * 目录一次最多返回多少个对象。大库几百张表，全量塞进上下文放不下也没用。
     *
     * <p><b>上限本身不重要，「砍掉哪一批」才重要。</b>这个 LIMIT 是数据库执行的，
     * 被砍掉的行根本不会回到 Java——所以决定去留的只能是 SQL 里的 ORDER BY，
     * 见 {@link #CATALOG_LIST_SQL}。
     */
    private static final int CATALOG_MAX = 500;

    /**
     * 「行数未知」算第几档。见 {@link #importanceTier(Long)}。
     *
     * <p>3 = 当它是一张几百行的普通表：排在真正空的表和个位数行的表<b>前面</b>，
     * 排在上千行的实表<b>后面</b>。
     */
    private static final int UNKNOWN_ROWS_TIER = 3;

    /**
     * {@link CatalogView#ordering()}：这个连接器按什么排目录，一句人话，会进截断消息。
     *
     * <p>括号里那句「没声明外键的库这一项全是 0」是写给模型的，不是客套：少了它，模型会从
     * 「被截掉的表」推出「这些表没被别的表引用」，而在一个不声明外键的库里这个推论是凭空的。
     */
    static final String CATALOG_ORDERING =
            "按估算行数的数量级降序（行数未知的视图等按几百行量级插队），"
                    + "同档内先按被外键引用的次数降序（只数库里声明过的外键，没声明外键的库这一项全是 0），"
                    + "再按对象名升序";

    /**
     * 目录列表 SQL。<b>提成常量是为了能被单测钉住</b>：整个「按重要性截断」的修复，
     * 全部力气都在这段 ORDER BY 上，它一旦被谁顺手改回 {@code ORDER BY TABLE_NAME}，
     * 行为会<b>静默</b>退回老样子——大库里 t_ 开头的业务表整片消失，没有任何报错。
     *
     * <p>排序表达式与 {@link #importanceTier(Long)} 必须是<b>同一个函数</b>。
     * 这里用 {@code LENGTH(TABLE_ROWS)}（整数转字符串取十进制位数）而不是
     * {@code FLOOR(LOG10(TABLE_ROWS))+1}：后者走浮点，{@code LOG10(1000)} 在某些平台上
     * 会得到 2.9999999999999996 从而 FLOOR 成 2，SQL 侧和 Java 侧就会在 10 的整数次幂上分叉。
     *
     * <h3>同一数量级里，被外键引用得多的排前面——而这一步对大多数库是空操作</h3>
     * 第二排序键是「有几条<b>声明过的</b>外键指向这张表」（{@code information_schema.KEY_COLUMN_USAGE}）。
     * 被很多表引用的通常是主数据表（用户、商品、组织），截断时它比同量级的流水表更不该丢。
     *
     * <p><b>要如实说：多数客户库一个外键都不声明。</b>我们自己的 data-server 库就是 0 个——
     * 关系由应用层维护、DDL 里不写 {@code FOREIGN KEY} 是业务库的常态。对这样的库这一项全是 0，
     * 目录顺序与只按「数量级 + 表名」时<b>逐行一致</b>；它只帮得到真的声明了外键的库。
     * 设计 §9② 写的「按行数 / 被引用次数」在大多数客户那里实际只兑现了前一半，别把它读成两个信号都在起作用。
     *
     * <p>它排在数量级<b>之后</b>、不越档：一张声明了外键、只有几行的字典表不该压过没声明外键的千万行订单表，
     * 否则同一套业务在「写了外键」和「没写外键」的两个库里会拿到完全不同的截断结果。
     *
     * <p><b>确定性不受影响</b>：外键声明是 DDL，不像 {@code TABLE_ROWS} 那样随统计采样抖动，
     * 它变了就是结构真的变了（或账号的授权变了）。最后一个 tie-break 仍然是 {@code TABLE_NAME}。
     *
     * <h3>子查询里三处写法都是冲着「会静默出错」去的</h3>
     * <ul>
     *   <li><b>{@code TABLE_SCHEMA = ?}</b>：只写 {@code REFERENCED_TABLE_SCHEMA = ?} 语义上也对，
     *       但 MySQL 5.7 的 information_schema 只在 {@code TABLE_SCHEMA} 为常量时才只打开一个库的表定义，
     *       否则会把实例上所有库的表都打开一遍——目录查询有 15 秒超时，大实例上就是 conn_catalog 直接报超时。
     *       顺带把统计限定在本库内（别的库指过来的外键不算）。</li>
     *   <li><b>{@code COUNT(DISTINCT TABLE_NAME, CONSTRAINT_NAME)}</b>：KEY_COLUMN_USAGE 一列一行，
     *       两列的复合外键用 {@code COUNT(*)} 会被数成 2。要数的是「几条外键」，不是「几列」。</li>
     *   <li><b>两边都 {@code CONVERT ... COLLATE}，并按转换后的名字分组</b>：两个 information_schema 视图的名字列
     *       在不同版本 / {@code lower_case_table_names} 下<b>不保证</b>是同一种排序规则，直接等值比较有报
     *       「Illegal mix of collations」的风险，那会让整个目录查询失败。统一成 utf8mb4_general_ci 再比；
     *       按转换后的名字分组，保证 LEFT JOIN 不会把一张表连出两行、让它在目录里出现两次。</li>
     * </ul>
     */
    static final String CATALOG_LIST_SQL =
            "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, TABLE_ROWS, COALESCE(jm_ref.REF_CNT, 0) AS REF_N "
                    + "FROM information_schema.TABLES "
                    + "LEFT JOIN (SELECT CONVERT(REFERENCED_TABLE_NAME USING utf8mb4) COLLATE utf8mb4_general_ci AS REF_NAME, "
                    + "COUNT(DISTINCT TABLE_NAME, CONSTRAINT_NAME) AS REF_CNT "
                    + "FROM information_schema.KEY_COLUMN_USAGE "
                    + "WHERE TABLE_SCHEMA = ? AND REFERENCED_TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME IS NOT NULL "
                    + "GROUP BY REF_NAME) jm_ref "
                    + "ON jm_ref.REF_NAME = CONVERT(TABLE_NAME USING utf8mb4) COLLATE utf8mb4_general_ci "
                    + "WHERE TABLE_SCHEMA = ? "
                    + "ORDER BY CASE WHEN TABLE_ROWS IS NULL THEN " + UNKNOWN_ROWS_TIER
                    + " WHEN TABLE_ROWS < 1 THEN 0 ELSE LENGTH(TABLE_ROWS) END DESC, REF_N DESC, TABLE_NAME ASC "
                    + "LIMIT " + CATALOG_MAX;

    /** {@link #CATALOG_LIST_SQL} 里 {@code ?} 的个数，全部绑同一个库名。单测把两者钉在一起：少绑一个是运行期才炸的错。 */
    static final int CATALOG_LIST_PARAMS = 3;

    /**
     * 目录列表的<b>兜底</b> SQL：上一版线上跑的那一条，<b>逐字不改</b>——只按「数量级 → 表名」排，不碰 KEY_COLUMN_USAGE。
     *
     * <h3>为什么 conn_catalog 必须有兜底</h3>
     * {@link #CATALOG_LIST_SQL} 喂的是<b>每一条</b> MySQL 连接的 conn_catalog，而 {@link MySqlConnector} 明说自己也接
     * Doris / StarRocks。那两家的 information_schema 是「兼容」出来的：KEY_COLUMN_USAGE 可能缺列、缺视图，
     * {@code COUNT(DISTINCT a, b)} 与 {@code CONVERT ... COLLATE} 也不保证认；MySQL 5.7 的大库上这个子查询要把每张表的
     * 约束定义都打开一遍，开销随表数涨。没有兜底，catalog() 就整条抛错——
     * 模型每次都要调的 conn_catalog 对这条连接永久失效，而丢掉的只是一个「同档内的第二排序键」。
     * 为一个锦上添花的排序键赔上整个目录，账算反了。
     *
     * <p><b>什么时候走它，由 {@link #planCatalog} 按跨重启不变的事实决定</b>，不由「这一次新 SQL 跑没跑成」决定，理由见那里。
     * 唯一的例外是新 SQL <b>这一次超时</b>：只这一次拿它作答、不记住——热路径上答得出来比顺序每次一样要紧，代价也写在那里。
     *
     * <p>逐字保留旧 SQL 是有意的：它是已经在线上跑过的那一条，兜底路径上不该再引入一条没跑过的新写法。
     */
    static final String CATALOG_LIST_SQL_FALLBACK =
            "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, TABLE_ROWS "
                    + "FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? "
                    + "ORDER BY CASE WHEN TABLE_ROWS IS NULL THEN " + UNKNOWN_ROWS_TIER
                    + " WHEN TABLE_ROWS < 1 THEN 0 ELSE LENGTH(TABLE_ROWS) END DESC, TABLE_NAME ASC "
                    + "LIMIT " + CATALOG_MAX;

    /** {@link #CATALOG_LIST_SQL_FALLBACK} 里 {@code ?} 的个数。 */
    static final int CATALOG_LIST_FALLBACK_PARAMS = 1;

    /**
     * 走了兜底时交给 {@link CatalogView#ordering()} 的那一句。
     *
     * <p>必须和 {@link #CATALOG_ORDERING} 说成<b>两句不同的话</b>：这句会进截断消息。若兜底时仍报「同档内先按被外键引用的次数」，
     * 模型会从「被截掉的表」推出「它们没被引用」——而这一次根本没数过引用。
     */
    static final String CATALOG_ORDERING_FALLBACK =
            "按估算行数的数量级降序（行数未知的视图等按几百行量级插队），同档内按对象名升序"
                    + "（本次没有按被外键引用的次数排序）";

    // ================================================================ 目录用哪条 SQL

    /**
     * 新 SQL 最多给多少个对象的库用。本库对象数（{@code catalog()} 里先算的那个 COUNT，含视图）<b>超过</b>它就直接走兜底，一次都不试新 SQL。
     *
     * <h3>为什么是 1000</h3>
     * <ul>
     *   <li><b>代价随表数涨</b>：5.7 一系的 information_schema 不是数据字典，读 TABLE_ROWS 与读 KEY_COLUMN_USAGE 都要把本库每张表的定义打开一遍，
     *       新 SQL 打开两遍（TABLES 一遍、外键子查询一遍），兜底 SQL 一遍。5.7 的 table_definition_cache 默认自动取 400 + table_open_cache/2
     *       （默认 1400）：1000 个对象以内，第二遍基本落在缓存里；再往上开始换进换出，15 秒超时就不再是理论风险。
     *       <b>「第二遍落在缓存里」这句对 5.7 靠不住，也从没在 5.7 上实测过</b>：按两遍各开一次表定义估算，一个兜底约 9 秒的 700 张表的 5.7 库，
     *       新 SQL 约 18 秒，已经越过 15 秒超时。所以 5.7 及更早改由 {@link #CATALOG_PRIMARY_MIN_MAJOR_VERSION} 按版本整体挡掉，这个阈值只再管 8.0 及以上。</li>
     *   <li><b>收益随表数跌</b>：REF_N 只决定同一数量级里谁先进刀口。对象过千时快照只留 200 个，不到五分之一，
     *       这个第二排序键能改变的只是一大片同量级表里挑哪几张，而绝大多数客户库一个外键都不声明，它本来就全是 0。</li>
     * </ul>
     * <b>跨过这条线是一次真的结构变化</b>（建了 / 删了表），顺序随之换一次；表数恰好在线上下来回摆的库会跟着来回换，这是按阈值判定躲不开的边界。
     */
    static final int CATALOG_PRIMARY_MAX_OBJECTS = 1000;

    /**
     * 新 SQL 只给主版本号不低于它的 MySQL 用（{@code VERSION()} 开头的那个数，见 {@link #majorVersion}）。
     *
     * <h3>为什么按版本挡，表数阈值为什么挡不住</h3>
     * 5.7 及更早没有事务型数据字典，information_schema 读 TABLE_ROWS、读 KEY_COLUMN_USAGE 都要把本库的表定义打开一遍，
     * 新 SQL 比兜底 SQL 多开一整遍，开销约翻倍。上一版线上跑的正是兜底 SQL：一个兜底约 9 秒的 700 张表的 5.7 库，
     * 换成新 SQL 约 18 秒（按量级估算；本地只有 8.0，没在 5.7 上实测），越过 15 秒的查询超时——conn_catalog 次次报 TIMEOUT、
     * 定时刷新次次失败，只在日志里看得见。而 700 &lt; {@link #CATALOG_PRIMARY_MAX_OBJECTS}，表数阈值根本不拦它。
     * 8.0 起 information_schema 是数据字典表上的系统视图（本地 8.0.46 实测 KEY_COLUMN_USAGE 的 TABLE_TYPE = {@code SYSTEM VIEW}），
     * 外键子查询不再逐张打开表定义，多出来的那一遍便宜。
     *
     * <p><b>版本号跨重启不变</b>：按它分支不会让顺序在两次部署之间来回翻；客户真升级到 8，顺序跟着换一次是对的。
     */
    static final int CATALOG_PRIMARY_MIN_MAJOR_VERSION = 8;

    /**
     * 读引擎事实的 SQL。{@code VERSION()} 与 {@code @@version_comment} 是所有兼容 MySQL 协议的引擎都模仿的两个值，
     * 而别家引擎几乎都在其中之一写上自己的名字（本地 MySQL 8.0.46 实测返回 {@code 8.0.46 / MySQL Community Server - GPL}）。
     * 不用 JDBC 握手里的版本号：Doris 在那里只报 {@code 5.7.99}，认不出来。
     */
    static final String ENGINE_FACTS_SQL = "SELECT VERSION() AS V, @@version_comment AS C";

    /**
     * 认出「这不是 MySQL」的标记，小写子串，在 {@code VERSION()} 与 {@code @@version_comment} 里找。
     *
     * <p>列进来的都是<b>自己实现 information_schema</b> 的引擎：Doris / SelectDB / StarRocks 没有外键这回事，REF_N 恒为 0，新 SQL 纯属多花；
     * TiDB / OceanBase / MariaDB / PolarDB-X（TDDL / DRDS）/ SingleStore（MemSQL）/ Vitess 的 information_schema 各有各的实现，
     * 新 SQL 在它们上面没验证过，也就说不准哪天会慢。Percona、云厂商的 MySQL（{@code Source distribution}）是 MySQL 本身，不在其中。
     *
     * <p><b>这份名单不是安全网</b>：漏掉的引擎会去试新 SQL，不认就报方言错误，由 {@link #catalogFallbackEligible} 那道保险兜住——
     * 同一个引擎对同一条 SQL 每次、每个进程报同一个错，结果照样跨重启一致。名单只省掉那一次必然失败的查询。
     */
    static final List<String> NON_MYSQL_ENGINE_MARKERS = List.of(
            "doris", "selectdb", "starrocks", "tidb", "oceanbase", "mariadb",
            "polardb-x", "polardbx", "tddl", "drds", "singlestore", "memsql", "vitess");

    /** 目录这一次用哪条 SQL，以及为什么。只用来分支和打日志，从不序列化。 */
    enum CatalogPlan {
        /** {@link #CATALOG_LIST_SQL}：带被外键引用次数。 */
        PRIMARY,
        /** 兜底：引擎认出不是 MySQL，或者连 {@link #ENGINE_FACTS_SQL} 都读不出来（方言不认）。 */
        FALLBACK_ENGINE,
        /** 兜底：MySQL 本家，但主版本号低于 {@link #CATALOG_PRIMARY_MIN_MAJOR_VERSION}（5.7 及更早），或读不出主版本号。 */
        FALLBACK_VERSION,
        /** 兜底：对象数超过 {@link #CATALOG_PRIMARY_MAX_OBJECTS}。 */
        FALLBACK_SIZE,
        /** 兜底：新 SQL 在这条连接上报过方言错误（保险丝，见 {@link #CATALOG_DIALECT_REJECTED}）。 */
        FALLBACK_DIALECT,
        /** 兜底：新 SQL <b>这一次</b>超时 / 被打断，只这一次拿兜底 SQL 作答，不记住（见 {@link #catalogTimeout}）。 */
        FALLBACK_TIMEOUT
    }

    /**
     * {@link #ENGINE_FACTS_SQL} 读回来的两个值。
     *
     * @param readable {@code false} = 读不出来（方言不认这条 SQL，或 {@code VERSION()} 为空）。读不出来按「不是 MySQL」处理：
     *                 真 MySQL 不可能答不上这两个值
     */
    record EngineFacts(String version, String comment, boolean readable) {
        static final EngineFacts UNREADABLE = new EngineFacts(null, null, false);

        static EngineFacts of(String version, String comment) {
            return version == null || version.isBlank() ? UNREADABLE : new EngineFacts(version, comment, true);
        }
    }

    /**
     * ★ 目录用哪条 SQL。<b>只看跨重启不变的事实，不看「这一次新 SQL 跑没跑成」。</b>
     * 唯一的例外是新 SQL 这一次超时：不归本方法判，由 {@link #catalog} 只这一次拿兜底 SQL 作答、不记住，见文末「超时」一节。
     *
     * <h3>为什么不能是「先试新 SQL、失败再兜底、记在进程里」</h3>
     * 两条 SQL 的顺序只差「同档内被声明外键引用的次数」这一个键，而目录顺序决定 {@code ConnectorSchemaService} 在 200 处砍掉哪些表。
     * 上一版把「兜底过」记在静态 Map 里：进程内稳定，可每次推 main 都重启、记忆清空。一个新 SQL <b>时而</b>超时的库，
     * 每次部署后第一次刷新都可能换一种顺序；刀口若落在 REF_N 不同的一档里，就是一对 REMOVED/ADDED、一批 STALE、一轮增量推导——
     * 全都不报错，而且每次部署重来一遍。所以选择必须是事实的函数，不能是运气的函数。
     *
     * <h3>规则（按顺序，先命中先用）</h3>
     * <ol>
     *   <li><b>引擎</b>：{@link #ENGINE_FACTS_SQL} 里出现 {@link #NON_MYSQL_ENGINE_MARKERS} 之一，或者读不出来 → {@link CatalogPlan#FALLBACK_ENGINE}。</li>
     *   <li><b>版本</b>：{@code VERSION()} 的主版本号低于 {@link #CATALOG_PRIMARY_MIN_MAJOR_VERSION}（5.7 及更早），或读不出主版本号
     *       → {@link CatalogPlan#FALLBACK_VERSION}。读不出按低版本算：证明不了是 8，就用上一版线上跑过的那条。</li>
     *   <li><b>规模</b>：本库对象数 &gt; {@link #CATALOG_PRIMARY_MAX_OBJECTS} → {@link CatalogPlan#FALLBACK_SIZE}。</li>
     *   <li>其余 → {@link CatalogPlan#PRIMARY}。</li>
     * </ol>
     * 这几样（引擎、版本号、版本注释、表数）在两次部署之间不会自己变；变了就是客户那边真的换了引擎、升了级、建删了表，顺序跟着换一次是对的。
     *
     * <h3>保险：新 SQL 报方言错误时仍然兜底</h3>
     * 规则认不出的引擎若不认这条 SQL，报的是方言错误。同一个引擎、同一条 SQL，每次、每个进程都报同一个错，
     * 于是每次都落到兜底，顺序照样跨重启一致。{@link #CATALOG_DIALECT_REJECTED} 在进程里记下它，只为省掉一次必然失败的查询和一条 WARN，<b>不改变结果</b>。
     *
     * <h3>★ 超时：只这一次拿兜底 SQL 作答，不记住（这一步不归本方法判，在 {@link #catalog} 里）</h3>
     * 上一轮让超时原样抛 TIMEOUT，理由是「超时是运气，拿它换 SQL 会让顺序来回翻」。账算反了：conn_catalog 是模型每次都要调的热路径，
     * 定时刷新也走它，一个新 SQL 次次超时的库在那条规则下就是目录永久不可用、刷新永久失败，而且只在日志里看得见。
     * 热路径上<b>答得出来</b>比<b>顺序每次一样</b>要紧。所以新 SQL 超时（{@link #catalogTimeout}）时，这一次改跑兜底 SQL 作答、打 WARN、
     * 交出兜底的那句排序说明；<b>不写进</b> {@link #CATALOG_DIALECT_REJECTED}，下一次照常先试新 SQL。兜底 SQL 也失败（包括它也超时）就照常抛兜底的那个错误。
     *
     * <p><b>残余风险，照实说</b>：在 MySQL 8 上，新 SQL 偶尔超时的那一次，交出去的顺序少了「同档内被声明外键引用的次数」这个键。
     * {@code ConnectorSchemaService} 在 200 处的刀口若正好落在 REF_N 不同的同档表之间，这一次刷新保留的 200 个对象会和平时不一样——
     * 一对 REMOVED/ADDED、挂在上面的语义一批 STALE、一轮增量推导；下一次不超时的刷新再换回来，又是一轮。全程不报错，只有那条 WARN 看得见。
     * 不声明外键的库（多数客户库）REF_N 全是 0，两条 SQL 顺序逐行一致，这个风险为零；5.7 已被版本规则挡在新 SQL 之外，走不到这里。
     * 另一个代价：超时之后再跑兜底，这一次调用最长要等新 SQL 的 15 秒，加上兜底本身的耗时（连接被池踢掉时还要加一次建连）。
     *
     * @param totalObjects 本库对象数（含视图）
     * @param engine       {@link #ENGINE_FACTS_SQL} 读回来的值；{@code null} 按读不出来处理
     */
    static CatalogPlan planCatalog(int totalObjects, EngineFacts engine) {
        if (engine == null || !engine.readable() || nonMySqlEngine(engine)) {
            return CatalogPlan.FALLBACK_ENGINE;
        }
        if (majorVersion(engine.version()) < CATALOG_PRIMARY_MIN_MAJOR_VERSION) {
            // 5.7 没有事务型数据字典，新 SQL 的开销约是兜底的两倍，大库上次次超时。版本号跨重启不变，按它分支不会让顺序来回翻。
            return CatalogPlan.FALLBACK_VERSION;
        }
        if (totalObjects > CATALOG_PRIMARY_MAX_OBJECTS) {
            return CatalogPlan.FALLBACK_SIZE;
        }
        return CatalogPlan.PRIMARY;
    }

    /**
     * {@code VERSION()} 开头的主版本号：{@code 5.7.44-log} → 5，{@code 8.0.46} → 8。开头不是数字、或数字长得不像版本号 → -1（读不出）。
     *
     * <p>只取开头连续的 ASCII 数字：各家发行版只在版本号<b>后面</b>加东西（{@code -log}、{@code -28}、{@code -txsql}），不动开头。
     */
    static int majorVersion(String version) {
        if (version == null) {
            return -1;
        }
        String v = version.trim();
        int end = 0;
        while (end < v.length() && v.charAt(end) >= '0' && v.charAt(end) <= '9') {
            end++;
        }
        // 超过 4 位的不是版本号，顺带防 parseInt 溢出。
        if (end == 0 || end > 4) {
            return -1;
        }
        return Integer.parseInt(v.substring(0, end));
    }

    /** 版本号或版本注释里带着别家引擎的名字。大小写不敏感。 */
    static boolean nonMySqlEngine(EngineFacts engine) {
        String text = ((engine.version() == null ? "" : engine.version()) + " "
                + (engine.comment() == null ? "" : engine.comment())).toLowerCase(Locale.ROOT);
        for (String marker : NON_MYSQL_ENGINE_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 新 SQL 在哪些连接上报过<b>方言错误</b>：{@code connection.id} → 当时那把 {@code poolKey}。
     *
     * <p>它只是 {@link #planCatalog} 那道保险丝的进程内记忆，<b>不参与决定顺序</b>：方言错误对同一个引擎是确定的，
     * 不记也会每次失败、每次落到兜底，得到同一个顺序。记下来只省掉每次一条必然失败的查询和一条 WARN。
     * 超时不写进来（{@link #catalogFallbackEligible} 不放行它），所以重启清空它不会让任何一个库换顺序。
     *
     * <p>值存 poolKey 而不是一个布尔：参数或凭据改过（比如把这条连接从 Doris 换到了 MySQL 8），poolKey 就变了，
     * 此时该重新试一次新 SQL。与 {@code CustomerDataSourceManager} 判断「池该不该重建」是同一把钥匙。
     * 放在静态字段上是因为会话对象每次调用都新建（见 {@link MySqlConnector#open}），存不住任何跨调用的东西。
     */
    private static final Map<Long, String> CATALOG_DIALECT_REJECTED = new ConcurrentHashMap<>();

    /** 单测之间清掉方言错误的记忆（也用来模拟一次重启）：静态状态不清，前一条用例会让后一条用例根本走不到新 SQL。 */
    static void resetCatalogFallbackForTest() {
        CATALOG_DIALECT_REJECTED.clear();
    }

    /**
     * {@link ObjectDetail#extra()} 里存唯一键（含主键）的键名。
     *
     * <p>与 {@code SemanticJoinValidator.DETAIL_UNIQUE_KEYS} <b>刻意同名</b>，由单测钉住。
     * 不互相 import 是有意的：连接器实现不该依赖语义层，语义层也不该依赖某一个连接器实现。
     */
    static final String EXTRA_UNIQUE_KEYS = "unique_keys";

    /**
     * 一张表的唯一索引（{@code NON_UNIQUE = 0}，主键也在其中，索引名固定叫 {@code PRIMARY}）。
     *
     * <p>不在 SQL 里 ORDER BY：一张表的索引列只有几行，顺序在 Java 侧定死（主键在前、再按索引名、列按
     * {@code SEQ_IN_INDEX}），理由同目录——information_schema 的排序规则随实例配置变。
     * 不查 {@code EXPRESSION} / {@code IS_VISIBLE}：那两列 5.7 没有，查了在 5.7 上就是整条失败。
     * 函数索引在 8.0 里表现为 {@code COLUMN_NAME IS NULL}，由 {@link #groupUniqueKeys} 整条丢掉。
     */
    static final String UNIQUE_KEYS_SQL =
            "SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME FROM information_schema.STATISTICS "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND NON_UNIQUE = 0";

    /** 只读探针用的表名。刻意取一个不可能与客户业务表重名的名字。 */
    private static final String PROBE_TABLE = "__jm_readonly_probe__";

    /** {@code information_schema} 里按名字取对象时的白名单：防止把标识符拼进 SQL。 */
    private static final Pattern IDENT_RE = Pattern.compile("^[A-Za-z0-9_$]{1,64}$");

    private final ConnectorInstance instance;
    private final DataSource dataSource;
    private final String database;
    private final ReadOnlySqlGuard sqlGuard;
    private final WriteSqlGuard writeGuard;

    /**
     * 预估影响行数的超时，<b>刻意比写操作本身的超时短</b>。
     *
     * <p>它产出的是给人看的参考信息，不是执行路径的一部分：估不出来只是审批页少一个数字，
     * 而一条卡住的 COUNT 会占着客户库的连接和我们的并发闸。宁可放弃，不要拖住。
     * 对抗审查实测过：高基数列上的聚合能跑到 24 秒，远超写操作默认的 15 秒。
     */
    private static final int ESTIMATE_TIMEOUT_SEC = 5;

    MySqlSession(ConnectorInstance instance, DataSource dataSource, String database,
                 ReadOnlySqlGuard sqlGuard, WriteSqlGuard writeGuard) {
        this.instance = instance;
        this.dataSource = dataSource;
        this.database = database;
        this.sqlGuard = sqlGuard;
        this.writeGuard = writeGuard;
    }

    /** 旧签名：只读场景（探测、测试）用，不需要写护栏。 */
    MySqlSession(ConnectorInstance instance, DataSource dataSource, String database, ReadOnlySqlGuard sqlGuard) {
        this(instance, dataSource, database, sqlGuard, new WriteSqlGuard());
    }

    // ================================================================ 探测三步

    @Override
    public void ping() {
        try (Connection c = borrow();
             Statement st = c.createStatement()) {
            st.setQueryTimeout(5);
            try (ResultSet rs = st.executeQuery("SELECT 1")) {
                rs.next();
            }
        } catch (SQLException e) {
            throw classify(e, "探活");
        }
    }

    /**
     * ★ 只读验证：<b>试一个无害的写，期望它失败</b>。
     *
     * <h3>为什么用 UPDATE 一张不存在的表，以及为什么「表不存在」是坏消息</h3>
     * MySQL 的权限检查发生在<b>对象存在性检查之前</b>（先看库级/表级权限，再看表在不在）。
     * 所以对一张不存在的表执行 {@code UPDATE}：
     * <ul>
     *   <li>账号<b>没有</b>该库的 UPDATE 权限 → {@code 1142} / {@code 1044}（access denied）
     *       → <b>确认只读</b> ✅</li>
     *   <li>账号<b>有</b>该库的 UPDATE 权限 → {@code 1146}（表不存在）
     *       → <b>说明它能写</b>，只是这张表碰巧不在 ❌</li>
     * </ul>
     * 这个反直觉的判据是本方法的全部要点。{@code WHERE 1 = 0} 是第二层保险：
     * 万一客户真的建了一张同名表且账号有权限，这条语句也不会改动任何行。
     *
     * <h3>按错误码判，不按消息文本判</h3>
     * 消息文本随 MySQL 版本、语言包、中间件（ProxySQL / MaxScale）而变，拿它做判据迟早失效，
     * <b>而失效的方向是「判成只读」——一个静默的安全降级</b>。错误码是稳定契约。
     *
     * <h3>★ 探针必须临时关掉连接的 readOnly，否则它什么都验不出来</h3>
     * 连接池对每条连接设了 {@code readOnly=true}（那是只读的第三道防线）。但 Connector/J 是
     * <b>在客户端就拦下</b>写语句的——{@code StatementImpl.executeUpdateInternal} 直接抛
     * {@code "Connection is read-only. Queries leading to data modification are not allowed."}，
     * {@code errorCode=0}，<b>语句根本没发到服务端</b>。于是我们永远拿不到 1142/1146，
     * 每一条连接都会被判成「无法判定」而拒绝保存。
     *
     * <p>这是一处很典型的「防护措施让检测失效」：我们自己的第二层防线挡住了对承重层的探测。
     * 所以这里显式 {@code setReadOnly(false)} 再跑探针、跑完恢复。安全性不受影响——
     * 真正决定成败的是<b>服务端</b>的权限检查，而 {@code WHERE 1 = 0} 保证即使权限放行也改不动任何行。
     */
    @Override
    public ReadOnlyVerdict verifyReadOnly() {
        String probe = "UPDATE `" + PROBE_TABLE + "` SET x = 1 WHERE 1 = 0";
        try (Connection c = borrow()) {
            try {
                c.setReadOnly(false);
            } catch (SQLException e) {
                // 关不掉就没法探。归 unknown 而不是 confirmed——「验不了」不是「验过了」。
                log.warn("只读探针无法关闭连接的 readOnly 标志 connectorId={}", instance.id(), e);
                return ReadOnlyVerdict.unknown("平台无法在这条连接上执行只读校验");
            }
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(10);
                st.executeUpdate(probe);
                // 竟然成功了：说明客户真有这张表，而且这个账号能写它。
                return ReadOnlyVerdict.writable("账号可以执行 UPDATE 语句");
            } finally {
                // 连接要还回池里，必须恢复原状，否则后续查询拿到的是一条可写连接。
                try {
                    c.setReadOnly(true);
                } catch (SQLException ignore) {
                    log.debug("恢复 readOnly 失败 connectorId={}", instance.id());
                }
            }
        } catch (SQLException e) {
            int code = e.getErrorCode();
            return switch (code) {
                // 1142 ER_TABLEACCESS_DENIED_ERROR / 1044 ER_DBACCESS_DENIED_ERROR
                case 1142, 1044 -> ReadOnlyVerdict.confirmed(
                        "数据库在表/库级别拒绝了写操作（错误码 " + code + "）");
                // 1290 ER_OPTION_PREVENTS_STATEMENT：整个服务端是 read_only 的（只读从库）
                case 1290 -> ReadOnlyVerdict.confirmed("数据库实例处于只读模式（错误码 1290）");
                // 1146 ER_NO_SUCH_TABLE：权限检查过了，说明这个账号在该库上有写权限
                case 1146 -> ReadOnlyVerdict.writable(
                        "账号具备该库的写权限（写操作没有被权限拦下，只是探针表不存在）");
                default -> {
                    // 任何判不出来的情况都归 unknown，绝不归 confirmed。
                    log.warn("只读探针返回了未预期的错误码 connectorId={} errorCode={} sqlState={}",
                            instance.id(), code, e.getSQLState(), e);
                    yield ReadOnlyVerdict.unknown("探测返回了无法判定的错误（错误码 " + code + "）");
                }
            };
        }
    }

    @Override
    public Set<Capability> probeCapabilities() {
        Set<Capability> caps = new LinkedHashSet<>();
        // 能连上就能查——ping 已经证明过了。
        caps.add(Capability.QUERY);
        caps.add(Capability.HEALTH);
        // 自描述要真的试一次：客户给的账号可能读不到 information_schema，
        // 这时 DESCRIBE 必须降级掉，否则界面上显示「支持自描述」而实际一用就报错——又一次静默降级。
        try (Connection c = borrow();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? LIMIT 1")) {
            ps.setQueryTimeout(10);
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
            caps.add(Capability.DESCRIBE);
        } catch (SQLException e) {
            log.warn("连接器自描述能力探测失败，将降级 connectorId={} errorCode={}",
                    instance.id(), e.getErrorCode(), e);
        }
        return caps;
    }

    // ================================================================ 能查

    @Override
    public QueryResult query(String statement, QueryOptions options) {
        // 第一件事就是过护栏。注意护栏是第二层防线——第一层是客户侧的只读账号，
        // 已在接入时由 verifyReadOnly() 验过。
        ReadOnlySqlGuard.Verdict verdict = sqlGuard.check(statement, options.maxRows());
        String sql = verdict.effectiveSql();

        long start = System.currentTimeMillis();
        try (Connection c = borrow()) {
            applyReadOnly(c);
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(options.timeoutSec());
                // 多取一行：用来区分「正好 maxRows 行」和「还有更多」。
                // 只靠 rows.size() == maxRows 判断会把恰好等于上限的完整结果误报成截断。
                st.setMaxRows(options.maxRows() + 1);
                // fetchSize 不设 Integer.MIN_VALUE（MySQL 的流式读）：那会独占连接直到读完，
                // 而我们有 maxRows 和 maxBytes 两道闸，结果集本来就不会大到需要流式。
                try (ResultSet rs = st.executeQuery(sql)) {
                    return readResultSet(rs, options, sql, start);
                }
            }
        } catch (SQLException e) {
            throw classify(e, "查询");
        }
    }

    private QueryResult readResultSet(ResultSet rs, QueryOptions options, String sql, long start) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();
        List<String> columns = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            // 用 label 而不是 name：`SELECT a AS 金额` 时模型该看到的是别名。
            columns.add(md.getColumnLabel(i));
        }

        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        String reason = null;
        long bytes = 0;

        while (rs.next()) {
            if (rows.size() >= options.maxRows()) {
                truncated = true;
                reason = "命中行数上限 " + options.maxRows();
                break;
            }
            List<Object> row = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                Object v = safeValue(rs, i);
                row.add(v);
                bytes += estimateBytes(v);
            }
            rows.add(row);
            if (bytes > options.maxBytes()) {
                truncated = true;
                reason = "命中结果大小上限 " + (options.maxBytes() / 1024) + "KB";
                break;
            }
        }
        return new QueryResult(columns, rows, truncated, reason, sql, System.currentTimeMillis() - start);
    }

    /**
     * 把 JDBC 的值转成「能安全进模型上下文并被 JSON 序列化」的形状。
     *
     * <p>四类必须显式处理，否则要么序列化失败，要么把垃圾灌进上下文：
     * <ul>
     *   <li>{@code BigDecimal} → {@code toPlainString()}，避免科学计数法把金额写成 {@code 1.0E+8}</li>
     *   <li>时间类 → ISO 字符串。{@code java.sql.Timestamp} 的默认 toString 没有时区信息，
     *       而问数最常见的口径争议就是时区</li>
     *   <li>二进制 / BLOB → <b>只给占位</b>。把几百 KB 的二进制塞进上下文既烧 token 又毫无信息量</li>
     *   <li>超长文本 → 单值截断并标注，防止一个 TEXT 列撑爆整轮对话</li>
     * </ul>
     */
    private static final int SINGLE_VALUE_MAX = 8192;

    private Object safeValue(ResultSet rs, int idx) throws SQLException {
        Object v = rs.getObject(idx);
        if (v == null || rs.wasNull()) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC).toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (v instanceof byte[] b) {
            return "<binary " + b.length + " bytes>";
        }
        if (v instanceof java.sql.Blob blob) {
            try {
                return "<blob " + blob.length() + " bytes>";
            } catch (SQLException e) {
                return "<blob>";
            }
        }
        if (v instanceof java.sql.Clob clob) {
            try {
                long len = clob.length();
                String s = clob.getSubString(1, (int) Math.min(len, SINGLE_VALUE_MAX));
                return len > SINGLE_VALUE_MAX ? s + "…[单值已截断]" : s;
            } catch (SQLException e) {
                return "<clob>";
            }
        }
        if (v instanceof String s && s.length() > SINGLE_VALUE_MAX) {
            return s.substring(0, SINGLE_VALUE_MAX) + "…[单值已截断]";
        }
        return v;
    }

    private static long estimateBytes(Object v) {
        if (v == null) return 4;
        if (v instanceof String s) return s.getBytes(StandardCharsets.UTF_8).length;
        return 16;
    }

    // ================================================================ 能写

    /**
     * 执行一条写语句。
     *
     * <h3>★ 三道闸，缺一不可</h3>
     * <ol>
     *   <li><b>语句护栏</b>（{@link WriteSqlGuard}）——单条 DML，且 UPDATE/DELETE 必须带 WHERE。</li>
     *   <li><b>影响行数上限 + 回滚</b>——带了 WHERE 也可能命中远超预期的行（{@code WHERE 1=1} 同样合法）。
     *       这一层只能在<b>执行之后、提交之前</b>判定：事前估算不可靠，而不可靠的那次
     *       恰恰是要防的那次。</li>
     *   <li><b>客户侧账号权限</b>——平台放行了，数据库仍可能拒。这是唯一不由我们承重的一层，
     *       也是最可靠的一层。</li>
     * </ol>
     *
     * <h3>为什么必须显式关掉连接的 readOnly</h3>
     * 连接池对每条连接设了 {@code readOnly=true}（只读的纵深防御之一），而 Connector/J 是
     * <b>在客户端就拦下</b>写语句的——不关掉的话，写操作根本发不到服务端，
     * 报的还是一个 {@code errorCode=0} 的含糊错误。用完必须恢复，否则这条连接还回池里之后
     * 会带着「可写」状态被下一次查询拿到。
     */
    @Override
    public WritePlan plan(String statement) {
        // 只过护栏，不借连接。走到这里的语句将原样进审批队列，批准后由 execute() 再跑一次同一道护栏
        // ——两次结论必然一致（同一个 guard、同一段文本），所以入队的就是将来执行的。
        WriteSqlGuard.Verdict verdict = writeGuard.check(statement);
        return new WritePlan(verdict.effectiveSql(), verdict.operation(), verdict.targetTable());
    }

    /**
     * 预估影响行数：把 {@code UPDATE t ... WHERE w} / {@code DELETE FROM t WHERE w}
     * 改写成 {@code SELECT COUNT(*) FROM t WHERE w}，在客户库上真跑一次。
     *
     * <h3>三个刻意的取舍</h3>
     * <ul>
     *   <li><b>走 AST 重写，不拼字符串。</b>从已经解析好的语法树上取表与 WHERE 再重新序列化，
     *       不会因为 WHERE 里有子查询、注释、奇怪的引号而拼错——拼错的后果是拿一个
     *       <b>看起来合理但数错了</b>的行数去给人做审批依据。</li>
     *   <li><b>INSERT 不估。</b>{@code INSERT ... SELECT} 的行数由子查询决定，
     *       跑一遍子查询才知道，代价与副作用都不可控；{@code INSERT ... VALUES} 的行数
     *       在语句里一眼可见，不需要估。两者都返回 null。</li>
     *   <li><b>任何失败都返回 null，绝不抛。</b>估不出来只是少一条参考信息，
     *       不该把一条本可以进审批队列的写请求挡在门外。</li>
     * </ul>
     *
     * <p>这个数只是<b>提交时</b>的快照，执行发生在人点批准之后。真正的防线仍是执行时的
     * {@code maxAffectedRows} 整条回滚。
     */
    @Override
    public Integer estimateAffectedRows(String statement) {
        String countSql;
        try {
            net.sf.jsqlparser.statement.Statement st = CCJSqlParserUtil.parse(statement);
            net.sf.jsqlparser.schema.Table table;
            net.sf.jsqlparser.expression.Expression where;
            if (st instanceof net.sf.jsqlparser.statement.update.Update up) {
                table = up.getTable();
                where = up.getWhere();
            } else if (st instanceof net.sf.jsqlparser.statement.delete.Delete del) {
                table = del.getTable();
                where = del.getWhere();
            } else {
                return null;   // INSERT 及其它：见类注释
            }
            if (table == null || where == null) return null;
            countSql = "SELECT COUNT(*) FROM " + table + " WHERE " + where;
        } catch (Exception e) {
            log.debug("预估影响行数：语句无法改写成 COUNT，跳过");
            return null;
        }

        try (Connection c = borrow();
             java.sql.Statement st = c.createStatement()) {
            st.setQueryTimeout(ESTIMATE_TIMEOUT_SEC);
            try (ResultSet rs = st.executeQuery(countSql)) {
                return rs.next() ? rs.getInt(1) : null;
            }
        } catch (Exception e) {
            // 超时、权限、方言差异都落这里。原始异常只进日志：它可能带 SQL 片段与主机名。
            log.warn("预估影响行数失败，按未知处理", e);
            return null;
        }
    }

    @Override
    public WriteResult execute(String statement, WriteOptions options) {
        WriteSqlGuard.Verdict verdict = writeGuard.check(statement);
        String sql = verdict.effectiveSql();

        long start = System.currentTimeMillis();
        try (Connection c = borrow()) {
            boolean originalAutoCommit = c.getAutoCommit();
            try {
                c.setReadOnly(false);
                // 手动事务：拿到影响行数之后才决定 commit 还是 rollback。
                c.setAutoCommit(false);
                int affected;
                try (Statement st = c.createStatement()) {
                    st.setQueryTimeout(options.timeoutSec());
                    affected = st.executeUpdate(sql);
                }
                if (affected > options.maxAffectedRows()) {
                    c.rollback();
                    log.warn("写操作影响行数超限已回滚 connectorId={} table={} affected={} max={}",
                            instance.id(), verdict.targetTable(), affected, options.maxAffectedRows());
                    // 平台自己的行数闸：语句虽然发到了客户库，但事务已回滚，数据库本身并没有拒绝。
                    // 模型的下一步是收窄 WHERE 分批做，不是去要权限。
                    throw ConnectorException.of(ConnectorErrorCode.GUARD_BLOCKED,
                            "这条语句会影响 " + affected + " 行，超过平台单次上限 "
                                    + options.maxAffectedRows() + " 行。"
                                    + "已回滚，数据未被修改。请缩小 WHERE 的范围后分批执行");
                }
                c.commit();
                return new WriteResult(affected, sql, System.currentTimeMillis() - start);
            } catch (ConnectorException e) {
                throw e;
            } catch (SQLException e) {
                safeRollback(c);
                throw classify(e, "写入");
            } finally {
                // 还回池之前恢复原状：池里的连接是复用的，带着 autoCommit=false 或 readOnly=false
                // 回去，下一次查询就会拿到一条状态不对的连接——而且不报错。
                restore(c, originalAutoCommit);
            }
        } catch (SQLException e) {
            throw classify(e, "写入");
        }
    }

    private void safeRollback(Connection c) {
        try {
            c.rollback();
        } catch (SQLException e) {
            log.warn("写操作回滚失败 connectorId={}", instance.id(), e);
        }
    }

    private void restore(Connection c, boolean originalAutoCommit) {
        try {
            c.setAutoCommit(originalAutoCommit);
        } catch (SQLException e) {
            log.warn("恢复 autoCommit 失败 connectorId={}", instance.id(), e);
        }
        try {
            c.setReadOnly(true);
        } catch (SQLException e) {
            log.warn("恢复 readOnly 失败 connectorId={}", instance.id(), e);
        }
    }

    // ================================================================ 能自描述

    /**
     * 排序用的临时二元组：目录条目 + 它的重要性档位。
     *
     * <p>package-private 而不是 private：排序规则是这次修复的全部内容，
     * 得让单测能直接钉住它，而这个仓库里没有 testcontainers / H2，连不了真库。
     * 只在排序时存在，从不序列化。
     */
    record Ranked(CatalogEntry entry, int tier, int refs) {
        /** 被引用次数按 0 算——与「库里没声明外键」同形，也就是大多数客户库的真实情况。 */
        Ranked(CatalogEntry entry, int tier) {
            this(entry, tier, 0);
        }

        String name() {
            return entry.name();
        }
    }

    /**
     * 档位降序 → 同档内被声明外键引用的次数降序 → 名字升序。
     * 前两条理由见 {@link #importanceTier(Long)}，中间那条（以及它为什么对多数库是空操作）见 {@link #CATALOG_LIST_SQL}。
     *
     * <p>必须与 SQL 的 ORDER BY 是同一组键、同一个先后：SQL 那遍决定哪 {@link #CATALOG_MAX} 个能回来，
     * 这一遍决定交出去的顺序（也就是 {@code ConnectorSchemaService} 在 200 处下刀的顺序）。两边键不一致，
     * 同一个库在两道刀口上就会按两套规则取舍。
     */
    static final Comparator<Ranked> BY_IMPORTANCE =
            Comparator.<Ranked>comparingInt(Ranked::tier).reversed()
                    .thenComparing(Comparator.<Ranked>comparingInt(Ranked::refs).reversed())
                    .thenComparing(Ranked::name);

    /**
     * 目录的重要性档位：<b>估算行数的十进制位数</b>，越大越靠前。
     *
     * <h3>为什么是「数量级」而不是直接拿行数排</h3>
     * {@code TABLE_ROWS} 对 InnoDB 是<b>估算值</b>——来自索引统计的随机采样，
     * 同一张表两次查能差几倍，刚建或刚 ANALYZE 过的表还可能是 0。
     * 拿一个会抖的数做全序，排在第 200 名和第 201 名的两张表每次刷新都可能互换，
     * 而第 200 名正好是 {@code ConnectorSchemaService.MAX_OBJECTS} 的刀口——
     * 换位就意味着<b>保留下来的集合变了</b>，结构漂移检测会永远报一对 ADDED/REMOVED，
     * 挂在上面的语义跟着反复 STALE、反复恢复。一个永远在响的报警等于没有报警。
     * <p>取数量级之后，一张表要跨一档得让估算值动 <b>10 倍</b>——那已经不是采样噪声，是真的变了。
     *
     * <h3>为什么同档一定要用名字兜底</h3>
     * 同一个数量级里的表之间没有可信的高下之分，这时唯一重要的性质是<b>可重复</b>。
     * 表名在一个库里唯一、且不会自己变，是天然的确定性 tie-break。
     *
     * <h3>★ NULL 不是 0</h3>
     * VIEW 的 {@code TABLE_ROWS} <b>恒为 NULL</b>，某些引擎、统计信息不可用时也是 NULL。
     * 把 NULL 当 0，等于让客户 DBA 亲手建的 {@code v_xxx} 汇总视图——
     * 业务语义最浓、名字最像人话的那一批对象——整批沉到表尾被截掉。
     * 所以「不知道」单独放在 {@link #UNKNOWN_ROWS_TIER} 档，
     * 即「当它是一张几百行的普通表」：排在真正空的表前面，排在上千行的实表后面。
     * 这是个折中，但方向是对的——宁可让一个没人用的视图占掉一个名额，也不要整类对象消失。
     *
     * <p>用位数而不是 {@code log10}：SQL 侧走 {@code LENGTH(TABLE_ROWS)}，纯整数，没有浮点，
     * 两边算的是同一个函数。理由见 {@link #CATALOG_LIST_SQL}。
     *
     * @param approxRows {@code information_schema} 给的估算行数，{@code null} = 不知道
     */
    static int importanceTier(Long approxRows) {
        if (approxRows == null) {
            return UNKNOWN_ROWS_TIER;
        }
        if (approxRows < 1) {
            return 0;
        }
        return Long.toString(approxRows).length();
    }

    @Override
    public CatalogView catalog() {
        String countSql = "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?";
        try (Connection c = borrow()) {
            applyReadOnly(c);
            int total;
            try (PreparedStatement ps = c.prepareStatement(countSql)) {
                ps.setQueryTimeout(10);
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    total = rs.next() ? rs.getInt(1) : 0;
                }
            }

            // ★ 用哪条 SQL 只由跨重启不变的事实决定（引擎、版本、表数），规则与理由见 planCatalog。
            //   引擎事实读失败时：方言不认 → 按「不是 MySQL」走兜底；超时 / 连不上 / 权限 → 原样上抛，不替它挑 SQL。
            //   唯一的例外在下面：新 SQL 这一次超时，就这一次拿兜底 SQL 作答，不记住。
            CatalogPlan plan = planCatalog(total, engineFacts(c));
            String rejectedKey = catalogFallbackKey();
            if (plan == CatalogPlan.PRIMARY && rejectedKey != null
                    && rejectedKey.equals(CATALOG_DIALECT_REJECTED.get(instance.id()))) {
                // 本进程里新 SQL 已经在这条连接上报过方言错误：再试一次必然同样失败，直接兜底（结果与再试一次完全相同）。
                plan = CatalogPlan.FALLBACK_DIALECT;
            }

            List<Ranked> ranked = null;
            if (plan == CatalogPlan.PRIMARY) {
                try {
                    ranked = listCatalog(c, true);
                } catch (SQLException e) {
                    if (catalogTimeout(e)) {
                        // ★ 超时：只这一次拿兜底 SQL 作答，不记住（不写 CATALOG_DIALECT_REJECTED），下一次照常先试新 SQL。
                        //   热路径上答得出来比顺序每次一样要紧；代价是这一次交出的顺序少了 REF_N，残余风险见 planCatalog。
                        //   兜底也失败（包括它也超时）就照常抛兜底的那个错误，由外层 classify。
                        log.warn("目录列表的外键排序 SQL 超时，本次改用只按数量级与表名排序的兜底 SQL 作答（不记住，下次照常先试新 SQL） "
                                        + "connectorId={} errorCode={} sqlState={}",
                                instance.id(), e.getErrorCode(), e.getSQLState(), e);
                        ranked = listCatalogAfterTimeout(c);
                        plan = CatalogPlan.FALLBACK_TIMEOUT;
                    } else if (!catalogFallbackEligible(e)) {
                        // 认证 / 权限 / 连不上 / 连接数满：都不是「这条 SQL 不被支持」，换一条同样读 information_schema 的 SQL 救不了。
                        // 原样上抛，让它被看见。
                        throw e;
                    } else {
                        log.warn("目录列表的外键排序 SQL 被数据库以方言错误拒绝，改用只按数量级与表名排序的兜底 SQL "
                                        + "connectorId={} errorCode={} sqlState={}",
                                instance.id(), e.getErrorCode(), e.getSQLState(), e);
                    }
                }
                if (ranked == null) {
                    // 兜底自己也失败时照常抛（由外层 classify），而且不记：那说明问题不在外键子查询上。
                    ranked = listCatalog(c, false);
                    plan = CatalogPlan.FALLBACK_DIALECT;
                    if (rejectedKey != null) {
                        CATALOG_DIALECT_REJECTED.put(instance.id(), rejectedKey);
                    }
                }
            } else {
                log.debug("目录列表按规则直接用兜底 SQL connectorId={} plan={} total={}", instance.id(), plan, total);
                ranked = listCatalog(c, false);
            }
            String ordering = plan == CatalogPlan.PRIMARY ? CATALOG_ORDERING : CATALOG_ORDERING_FALLBACK;
            // SQL 已经按同一个表达式排过一遍了，这里再排不是白做，两件事：
            // 一、SQL 那遍决定的是【哪 CATALOG_MAX 个能回来】，这件事只有数据库能做，Java 救不回来；
            // 二、这一遍决定【回来的这些以什么顺序交出去】。SQL 的 TABLE_NAME tie-break 走
            //    information_schema 那一列的排序规则，受 lower_case_table_names 影响，可能大小写不敏感；
            //    换一台 MySQL 就换一种顺序，而快照顺序不稳定正是漂移检测报假警的来源。
            //    把最终顺序钉在 Java 这边，同一个库在哪台机器上都得到同一份目录。
            //    兜底路径上 refs 全是 0，BY_IMPORTANCE 与旧规则逐行一致（有单测钉着）。
            ranked.sort(BY_IMPORTANCE);
            List<CatalogEntry> entries = ranked.stream().map(Ranked::entry).toList();
            return new CatalogView("TABLE", entries, total > entries.size(), total, ordering);
        } catch (SQLException e) {
            throw classify(e, "列目录");
        }
    }

    /**
     * 跑一遍目录列表 SQL，读成排序用的二元组。
     *
     * @param withRefs true = {@link #CATALOG_LIST_SQL}（带被外键引用次数）；false = {@link #CATALOG_LIST_SQL_FALLBACK}
     *                 （结果集里没有 REF_N 这一列，refs 一律按 0）
     */
    private List<Ranked> listCatalog(Connection c, boolean withRefs) throws SQLException {
        String sql = withRefs ? CATALOG_LIST_SQL : CATALOG_LIST_SQL_FALLBACK;
        int params = withRefs ? CATALOG_LIST_PARAMS : CATALOG_LIST_FALLBACK_PARAMS;
        List<Ranked> ranked = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(15);
            // 所有 ? 都是库名：新 SQL 三个（外键子查询的引用方、被引用方都限在本库，TABLES 一个），兜底 SQL 一个。
            for (int i = 1; i <= params; i++) {
                ps.setString(i, database);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String comment = rs.getString("TABLE_COMMENT");
                    // MySQL 对视图的 TABLE_COMMENT 会返回字面量 "VIEW"，那不是注释，去掉免得误导模型。
                    if ("VIEW".equals(comment)) comment = null;
                    long raw = rs.getLong("TABLE_ROWS");
                    // ★ getLong 把 SQL NULL 读成 0。VIEW 的 TABLE_ROWS 恒为 NULL，
                    //   不做这一步区分，所有视图都会被当成空表排到最后然后被截掉。
                    Long rows = rs.wasNull() ? null : raw;
                    // TABLE_ROWS 对 InnoDB 是估算值，差几倍很常见。附上「约」字，
                    // 免得模型把它当成 COUNT(*) 的答案直接回给用户。
                    // 未知（null）和 0 都不写行数：前者没数可写，后者可能只是没统计过，
                    // 写「约 0 行」会让模型断言这张表是空的。
                    String withRows = (comment == null || comment.isBlank() ? "" : comment)
                            + (rows != null && rows > 0
                            ? "（约 " + rows + " 行，InnoDB 估算值，不可当作准确计数）" : "");
                    CatalogEntry entry = new CatalogEntry(rs.getString("TABLE_NAME"),
                            rs.getString("TABLE_TYPE"), withRows.isBlank() ? null : withRows);
                    // REF_N 在新 SQL 里已经 COALESCE 成 0，不会是 NULL；不声明外键的库每一行都是 0。
                    // 兜底 SQL 根本没有这一列，不能去读它（读一个不存在的列在 JDBC 里是 SQLException）。
                    ranked.add(withRefs
                            ? new Ranked(entry, importanceTier(rows), rs.getInt("REF_N"))
                            : new Ranked(entry, importanceTier(rows)));
                }
            }
        }
        return ranked;
    }

    /** 方言错误记忆的钥匙；实例没有 id（还没保存的连接）时为 {@code null}，此时不记。 */
    private String catalogFallbackKey() {
        return instance == null || instance.id() == null ? null : instance.poolKey();
    }

    /**
     * 新 SQL 超时之后，跑兜底 SQL 作答。
     *
     * <h3>★ 原连接可能已经死了，不能不看就接着用</h3>
     * 客户库的池是 HikariCP 5.0.1。它的 {@code ProxyConnection.checkException} 见到 {@link SQLTimeoutException} 就判这条连接坏了
     * （javap 实测：{@code instanceof SQLTimeoutException} → {@code PoolEntry.evict} → {@code delegate = CLOSED_CONNECTION}），
     * 而 Connector/J 8.0.29 在 setQueryTimeout 到点时抛的 {@code MySQLTimeoutException} 正是它的子类（同样 javap 实测）。
     * 于是 15 秒超时之后，原连接上的任何操作都报 08003「No operations allowed after connection closed」——
     * 在它上面重试兜底，这一次会被 classify 成 UNREACHABLE，修复形同虚设。
     *
     * <p>所以原连接已关（Hikari 的 {@code isClosed()} 只比对 delegate，不走网络）就另借一条；没关（比如 max_execution_time 的 3024，
     * 池不踢连接）就接着用它。<b>不无条件另借</b>：没被踢的连接还攥在手里，再借一条就是一次调用占两条，
     * 而客户池被 {@code PoolSpec} 夹在 1~8 条，并发满时第二次借要等满 connectionTimeout 才失败。被踢的那条已经同步移出池
     * （{@code HikariPool.closeConnection} 先 {@code connectionBag.remove}，{@code getTotalConnections} 数的就是这个 bag），不占名额。
     */
    private List<Ranked> listCatalogAfterTimeout(Connection c) throws SQLException {
        boolean closed;
        try {
            closed = c.isClosed();
        } catch (SQLException e) {
            // 连「关没关」都答不上，按已关处理：另借一条最多多一次建连，接着用一条坏连接则必然失败。
            closed = true;
        }
        if (!closed) {
            return listCatalog(c, false);
        }
        try (Connection fresh = borrow()) {
            applyReadOnly(fresh);
            return listCatalog(fresh, false);
        }
    }

    /**
     * 这个失败是不是<b>超时或被打断</b>：只有它能让新 SQL <b>这一次</b>改用兜底 SQL 作答（不记住），理由与残余风险见 {@link #planCatalog}。
     *
     * <p>清单与 {@link #catalogFallbackEligible} 里「超时与被打断」那一组一致：{@link SQLTimeoutException}（驱动 setQueryTimeout 到点）、
     * 3024 max_execution_time、1317 查询被中断（驱动超时靠 KILL QUERY 实现）、1205 锁等待超时、MariaDB 的 1969 max_statement_time、SQLState 70。
     * 认证 / 权限 / 连不上 / 连接数满<b>不在</b>其中：它们照常上抛，要让人看见。
     */
    static boolean catalogTimeout(SQLException e) {
        if (e == null) {
            return false;
        }
        if (e instanceof SQLTimeoutException) {
            return true;
        }
        switch (e.getErrorCode()) {
            case 3024:      // ER_QUERY_TIMEOUT（max_execution_time）
            case 1317:      // ER_QUERY_INTERRUPTED
            case 1205:      // ER_LOCK_WAIT_TIMEOUT
            case 1969:      // MariaDB ER_STATEMENT_TIMEOUT（max_statement_time）
                return true;
            default:
                break;
        }
        String state = e.getSQLState();
        return state != null && state.startsWith("70");
    }

    /**
     * 读引擎事实（{@link #ENGINE_FACTS_SQL}）。
     *
     * <p>失败分两种，走向相反：方言不认这条 SQL（{@link #catalogFallbackEligible} 放行的那类）→ {@link EngineFacts#UNREADABLE}，
     * 由 {@link #planCatalog} 按「不是 MySQL」走兜底——真 MySQL 不可能答不上这两个值，而同一个引擎每次答不上的方式都一样，结果确定；
     * 超时 / 连不上 / 权限 → 原样抛出。那不是引擎的事实，是这一次的运气，拿它挑 SQL 又会回到跨重启来回翻的老路。
     */
    private EngineFacts engineFacts(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(5);
            try (ResultSet rs = st.executeQuery(ENGINE_FACTS_SQL)) {
                if (!rs.next()) {
                    return EngineFacts.UNREADABLE;
                }
                return EngineFacts.of(rs.getString(1), rs.getString(2));
            }
        } catch (SQLException e) {
            if (!catalogFallbackEligible(e)) {
                throw e;
            }
            // debug 而不是 warn：对这种引擎每次 conn_catalog 都会走到这里，结论每次都一样。
            log.debug("读不出引擎版本信息（方言不认），目录按兜底 SQL 排序 connectorId={} errorCode={} sqlState={}",
                    instance.id(), e.getErrorCode(), e.getSQLState());
            return EngineFacts.UNREADABLE;
        }
    }

    /**
     * 这个失败是不是<b>方言错误</b>：同一个引擎对同一条 SQL 每次都会报的那种。只有它能让目录换兜底 SQL <b>并记住</b>
     * （超时只换这一次、不记，见 {@link #catalogTimeout}），也只有它能让 {@link #engineFacts} 判「读不出来」。
     *
     * <h3>★ 按错误码判，<b>不能</b>借 {@link #classify} 的分类判</h3>
     * {@code classify} 把 SQLState {@code 42xxx} 一律归成 FORBIDDEN，而「这个方言不认这条 SQL」恰恰大多落在 42 类里：
     * 本地 MySQL 8.0.46 实测，information_schema 里没有某个视图是 {@code 1109 (42S02)}，某列不存在是
     * {@code 1054 (42S22)}，语法不认是 {@code 1064 (42000)}。借 classify 判，Doris / StarRocks 上最典型的那几种失败
     * 全会被当成「权限不足」原样抛出——兜底形同虚设，conn_catalog 照样坏。
     *
     * <h3>为什么是「列出不算的」，而不是「列出算的」</h3>
     * 兼容 MySQL 协议的库各报各的码：Doris 的分析错误几乎全是 {@code 1105}，StarRocks 常报 {@code 1064}，
     * 还有 TiDB / OceanBase / 各家云代理。列一份「算方言错误」的白名单，漏掉一个码就是 conn_catalog 对那一类库整片失效；
     * 而「不算」的只有几类、码是稳定的：
     * <ul>
     *   <li><b>认证 / 权限</b>（1044 / 1045 / 1142 / 1143 / 1227 / 1698、SQLState 28）：要让人去改授权，
     *       换一条也读 information_schema 的 SQL 要么照样被拒，要么把这个故障藏起来；</li>
     *   <li><b>连不上</b>（SQLState 08）与<b>连接数满</b>（1040 / 1203）：暂时性的，兜底多半同样失败；</li>
     *   <li>★ <b>超时与被打断</b>（{@link SQLTimeoutException}、3024 max_execution_time、1317 查询被中断、1205 锁等待、
     *       MariaDB 的 1969 max_statement_time、SQLState 70）：时有时无，不能记成「这条连接只能兜底」——记在进程里，
     *       每次重启就重赌一遍顺序。它们不在这里放行，由 {@link #catalogTimeout} 另判：只这一次拿兜底 SQL 作答、不写进
     *       {@link #CATALOG_DIALECT_REJECTED}，理由与残余风险见 {@link #planCatalog}。</li>
     * </ul>
     * 其余（语法、缺视图缺列、排序规则不认、方言的通用错误码）一律算——兜底 SQL 也失败时照常抛出，不会把真故障吞掉。
     */
    static boolean catalogFallbackEligible(SQLException e) {
        if (e == null || e instanceof SQLTimeoutException) {
            return false;
        }
        switch (e.getErrorCode()) {
            case 1044:      // ER_DBACCESS_DENIED_ERROR
            case 1045:      // ER_ACCESS_DENIED_ERROR
            case 1142:      // ER_TABLEACCESS_DENIED_ERROR
            case 1143:      // ER_COLUMNACCESS_DENIED_ERROR
            case 1227:      // ER_SPECIFIC_ACCESS_DENIED_ERROR
            case 1698:      // ER_ACCESS_DENIED_NO_PASSWORD_ERROR
            case 1040:      // ER_CON_COUNT_ERROR
            case 1203:      // ER_TOO_MANY_USER_CONNECTIONS
            case 3024:      // ER_QUERY_TIMEOUT（max_execution_time）
            case 1317:      // ER_QUERY_INTERRUPTED（Connector/J 的 setQueryTimeout 就是靠 KILL QUERY 实现的）
            case 1205:      // ER_LOCK_WAIT_TIMEOUT
            case 1969:      // MariaDB ER_STATEMENT_TIMEOUT（max_statement_time）
                return false;
            default:
                break;
        }
        String state = e.getSQLState();
        return state == null || !(state.startsWith("28") || state.startsWith("08") || state.startsWith("70"));
    }

    // ================================================================ 对象是否存在（契约 K-1）

    /**
     * {@link #existingObjects} 的 SQL 前半段；后面接 {@code (?, ?, ...)}，一个名字一个占位符。
     * 第一个 {@code ?} 是库名：{@code TABLE_SCHEMA} 为常量时 5.7 才只打开一个库的元数据，理由同 {@link #CATALOG_LIST_SQL}。
     */
    static final String EXISTING_OBJECTS_SQL_PREFIX =
            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME IN (";

    /**
     * 一次最多核对多少个名字，超过就返回 {@code null}（不知道）并打 WARN。
     *
     * <p>它不是性能阀：快照本身只有 200 个对象，调用方问的名字远到不了这里。真正的硬边界是服务端预编译语句的 65535 个占位符——
     * 真撞上时整条查询失败，结果同样是「不知道」，但那样连一条说明原因的日志都没有。
     */
    static final int EXISTING_OBJECTS_MAX_NAMES = 10_000;

    /**
     * 这些名字里，哪些此刻在本库里真实存在（含视图）。<b>一条</b> {@code information_schema.TABLES} 查询，只查给定的这几个名字。
     *
     * <h3>返回值的三种形状，意思完全不同</h3>
     * <ul>
     *   <li>非空集合：这些存在，<b>其余给定的名字确实不存在</b>；名字按库里存的写法；</li>
     *   <li>空集合：一个都不存在（包括入参为空，以及名字本身就不可能是 MySQL 对象名）；</li>
     *   <li>{@code null}：<b>不知道</b>。任何失败都落这里——连不上、超时、权限、方言不认。<b>从不抛</b>：
     *       调用方在结构刷新的同一个会话里问它，拿「查不成」否决一次成功的拉取是错的；而返回空集合会被读成「全删光了」。</li>
     * </ul>
     *
     * <h3>为什么先把不可能存在的名字挑出去，而不是原样绑进去</h3>
     * {@code information_schema.TABLES.TABLE_NAME} 是 utf8mb3（本地 8.0.46 实测 {@code utf8mb3_bin}）。绑一个带 4 字节字符的参数，
     * MySQL 不是「查不到」，是<b>整条语句报错</b>（实测 ERROR 3988 Conversion from collation utf8mb4_0900_ai_ci into utf8mb3_bin impossible）——
     * 一个怪名字就能让其余每个名字都变成「不知道」。而 MySQL 的对象名只能用 BMP 字符、不含 U+0000、不以空格结尾、最长 64 个字符，
     * 过不了这几条的名字本来就不可能存在，挑出去并如实算作「不存在」，不是猜。
     *
     * <p>不按 {@link #IDENT_RE} 过滤：库里真有中文表名（本地实测 {@code 订单表} 照常查得到），它们在目录里、在快照里，
     * 按白名单滤掉就会被报成「不存在」——那正是调用方最怕的假删除。参数全部绑定，不拼进 SQL，不需要白名单挡注入。
     *
     * <p>大小写跟着服务端走：{@code lower_case_table_names=0} 时这一列是 {@code utf8mb3_bin}，{@code T_CUST} 与 {@code t_cust}
     * 是两张表（实测问 {@code T_CUST} 查不到 {@code t_cust}）；为 1 / 2 时比较不分大小写。这与客户库自己认不认得这个名字一致。
     */
    @Override
    public Set<String> existingObjects(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        Set<String> asked = new LinkedHashSet<>();
        for (String n : names) {
            if (couldBeMySqlObjectName(n)) {
                asked.add(n);
            }
        }
        if (asked.isEmpty()) {
            return Set.of();
        }
        if (asked.size() > EXISTING_OBJECTS_MAX_NAMES) {
            log.warn("核对对象是否存在：名字数 {} 超过单次上限 {}，按「不知道」返回 connectorId={}",
                    asked.size(), EXISTING_OBJECTS_MAX_NAMES, instance.id());
            return null;
        }
        try (Connection c = borrow()) {
            applyReadOnly(c);
            try (PreparedStatement ps = c.prepareStatement(existingObjectsSql(asked.size()))) {
                ps.setQueryTimeout(10);
                ps.setString(1, database);
                int i = 2;
                for (String n : asked) {
                    ps.setString(i++, n);
                }
                Set<String> found = new LinkedHashSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString("TABLE_NAME");
                        if (name != null) {
                            found.add(name);
                        }
                    }
                }
                return Collections.unmodifiableSet(found);
            }
        } catch (SQLException e) {
            log.warn("核对对象是否存在失败，按「不知道」返回 connectorId={} 名字数={} errorCode={} sqlState={}",
                    instance.id(), asked.size(), e.getErrorCode(), e.getSQLState(), e);
            return null;
        } catch (RuntimeException e) {
            // 驱动或连接池抛出的未受检异常同样不能漏进拉取。原始异常只进日志。
            log.warn("核对对象是否存在出现未归类异常，按「不知道」返回 connectorId={} 名字数={}",
                    instance.id(), asked.size(), e);
            return null;
        }
    }

    /** {@link #EXISTING_OBJECTS_SQL_PREFIX} 接上 {@code n} 个占位符。 */
    static String existingObjectsSql(int n) {
        return EXISTING_OBJECTS_SQL_PREFIX + String.join(", ", Collections.nCopies(n, "?")) + ")";
    }

    /**
     * 这个名字有没有可能是一个 MySQL 对象名：非空、最长 64 个字符、只用 BMP 字符、不含 U+0000、不以空格结尾（MySQL 标识符的硬规则）。
     * 过不了的一律不存在，理由见 {@link #existingObjects}。
     */
    static boolean couldBeMySqlObjectName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64 || name.endsWith(" ")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            // 代理对 = 4 字节字符（emoji 等）。只看 BMP 时 length() 就是字符数，所以上面的 64 不会被代理对算错。
            if (ch == ' ' || Character.isSurrogate(ch)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 看一张表的结构：列，加上这张表的唯一键。
     *
     * <h3>为什么要把唯一键（含主键、含多列唯一索引）一起带出来</h3>
     * {@code COLUMN_KEY} 只有一个词，而且对组合键会说错话：{@code (order_id, line_no)} 联合主键里，
     * 两列的 {@code COLUMN_KEY} <b>都是</b> {@code PRI}，于是 extra 里两列都写着「主键」——模型读到
     * 「order_id 主键」会以为它单独唯一，按它 join 就一行连出多行，金额被放大且不报错。
     * 设计 §6.2④「复合键遇到 NULL 会自己塌掉」是同一类问题的另一面：把组合键当单列用，判据就失真。
     * 所以这里多查一次 {@code information_schema.STATISTICS}，把完整唯一键放进 {@link ObjectDetail#extra()}
     * 的 {@link #EXTRA_UNIQUE_KEYS}，并在组合键成员列的 extra 上<b>追加</b>一句「组合键成员（…），单独这一列可能重复」
     * ——语义层推导（S2）喂给模型的摘要只带列级 extra，不带对象级 extra，不追加这一句它就看不到组合键。
     *
     * <h3>★ 这些新增信息不会让结构漂移检测报「全库都变了」</h3>
     * {@code ConnectorSchemaService.structureFingerprint} 只拼对象名、类型、以及每列的
     * {@code name:type:nullable:comment}，{@code ObjectDetail.extra} 与 {@code FieldDetail.extra} 都不在里面；
     * 语义层的列锚点 {@code ConnectorSemanticService.fieldAnchor} 是同一个形状。所以上线后第一次刷新，
     * 快照里多出唯一键，{@code content_hash} 一个都不变，挂在上面的语义一条都不会被标 STALE。有单测钉着。
     *
     * <h3>★ 追加的那句话里不能出现「主键」「唯一」这两个词</h3>
     * {@code SemanticValueProfiler} 用 {@code extra.contains("主键")} / {@code contains("唯一")} 判断一列是否天然高基数：
     * 数「主键」出现在几列上来区分单列主键和联合主键成员，见到「唯一」就跳过值域剖析。
     * 追加句要是写成「联合唯一键成员」，{@code (region, day)} 这类组合唯一键的成员列——值域剖析最想要的低基数维度——
     * 会被当成唯一列整批跳过，而且不报任何错。所以原有的「主键 / 唯一 / 有索引」原样保留，追加句只说「组合键」。
     */
    @Override
    public ObjectDetail describe(String object) {
        if (object == null || !IDENT_RE.matcher(object.trim()).matches()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "表名含有非法字符，只允许字母、数字、下划线和 $");
        }
        String name = object.trim();
        String colSql = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT, COLUMN_KEY, EXTRA, COLUMN_DEFAULT "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (Connection c = borrow()) {
            applyReadOnly(c);
            List<RawColumn> raw = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(colSql)) {
                ps.setQueryTimeout(15);
                ps.setString(1, database);
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        raw.add(new RawColumn(
                                rs.getString("COLUMN_NAME"),
                                rs.getString("COLUMN_TYPE"),
                                "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                                rs.getString("COLUMN_COMMENT"),
                                rs.getString("COLUMN_KEY"),
                                rs.getString("EXTRA"),
                                rs.getString("COLUMN_DEFAULT")));
                    }
                }
            }
            if (raw.isEmpty()) {
                throw ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                        "库中不存在名为「" + name + "」的表，或当前账号看不到它。请先用 conn_catalog 确认表名");
            }
            // 唯一键放在列之后查：表不存在时不必多打一条。
            List<UniqueKey> keys = uniqueKeys(c, name);
            List<FieldDetail> fields = new ArrayList<>(raw.size());
            for (RawColumn r : raw) {
                fields.add(new FieldDetail(r.name(), r.type(), r.nullable(), r.comment(), fieldExtra(r, keys)));
            }
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("database", database);
            extra.put("hint", "字段注释来自数据库本身，可能过时或为空。口径不明确时请向用户确认，不要自行假定。");
            if (keys != null) {
                // ★ 读不到就不放这个键，而不是放一个空列表：空列表的意思是「这张表确实没有唯一键」，
                //   语义层会据此认定目标列不属于任何组合键。「不知道」和「没有」必须分得开。
                extra.put(EXTRA_UNIQUE_KEYS, uniqueKeysExtra(keys));
            }
            return new ObjectDetail(name, "TABLE", null, fields, extra);
        } catch (SQLException e) {
            throw classify(e, "看结构");
        }
    }

    /** 列查询的一行原样。只在 {@link #describe} 里活一小会儿，从不序列化。 */
    private record RawColumn(String name, String type, boolean nullable, String comment,
                             String columnKey, String extra, String columnDefault) {}

    /** {@link #UNIQUE_KEYS_SQL} 的一行。 */
    record KeyPart(String index, int seq, String column) {}

    /**
     * 一个唯一键，列按索引内顺序。
     *
     * <p>{@code primary} 只看索引名是不是 {@code PRIMARY}。没有主键的表上 InnoDB 会拿第一个非空唯一索引当聚簇键，
     * {@code COLUMN_KEY} 也会把它显示成 {@code PRI}，但它在 STATISTICS 里仍叫自己的名字——这里如实记成非主键的唯一键。
     * 判「单列唯一 / 组合键成员」只看列，不看这个标记，所以两种写法得出同一个结论。
     */
    record UniqueKey(String name, boolean primary, List<String> columns) {}

    /**
     * 读一张表的唯一键。
     *
     * @return 主键在前、其余按索引名；{@code null} = <b>没读到</b>（不是「没有唯一键」）。
     *         读不到不让整次 describe 失败：唯一键是叠加信息，拿它去否决「看结构」本身是错的。
     */
    private List<UniqueKey> uniqueKeys(Connection c, String table) {
        try (PreparedStatement ps = c.prepareStatement(UNIQUE_KEYS_SQL)) {
            ps.setQueryTimeout(10);
            ps.setString(1, database);
            ps.setString(2, table);
            List<KeyPart> parts = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parts.add(new KeyPart(rs.getString("INDEX_NAME"), rs.getInt("SEQ_IN_INDEX"),
                            rs.getString("COLUMN_NAME")));
                }
            }
            return groupUniqueKeys(parts);
        } catch (SQLException e) {
            log.warn("读取唯一键失败，本表按「唯一键未知」处理 connectorId={} errorCode={} sqlState={}",
                    instance.id(), e.getErrorCode(), e.getSQLState(), e);
            return null;
        }
    }

    /**
     * STATISTICS 的行 → 唯一键列表。顺序在这里定死：主键在前、再按索引名；键内按 {@code SEQ_IN_INDEX}。
     *
     * <p>含函数键部分（8.0 的函数索引，{@code COLUMN_NAME} 为 NULL）的索引<b>整条丢掉</b>：
     * 它保证的是某个表达式唯一，写不成「这几列唯一」，硬记成少一列的键会凭空造出一个并不存在的唯一约束。
     */
    static List<UniqueKey> groupUniqueKeys(List<KeyPart> parts) {
        Map<String, List<KeyPart>> byIndex = new LinkedHashMap<>();
        for (KeyPart p : parts) {
            if (p == null || p.index() == null) {
                continue;
            }
            byIndex.computeIfAbsent(p.index(), k -> new ArrayList<>()).add(p);
        }
        List<UniqueKey> out = new ArrayList<>(byIndex.size());
        for (Map.Entry<String, List<KeyPart>> e : byIndex.entrySet()) {
            List<KeyPart> ps = new ArrayList<>(e.getValue());
            if (ps.stream().anyMatch(p -> p.column() == null)) {
                continue;
            }
            ps.sort(Comparator.comparingInt(KeyPart::seq));
            out.add(new UniqueKey(e.getKey(), "PRIMARY".equals(e.getKey()),
                    ps.stream().map(KeyPart::column).toList()));
        }
        out.sort(Comparator.comparing((UniqueKey k) -> !k.primary()).thenComparing(UniqueKey::name));
        return out;
    }

    /** 列级 extra：原有的「主键 / 唯一 / 有索引」、组合键成员说明、EXTRA、默认值，用 " / " 拼。 */
    private static String fieldExtra(RawColumn r, List<UniqueKey> keys) {
        List<String> extra = new ArrayList<>();
        String key = r.columnKey();
        if ("PRI".equals(key)) extra.add("主键");
        else if ("UNI".equals(key)) extra.add("唯一");
        else if ("MUL".equals(key)) extra.add("有索引");
        String member = compositeMemberNote(r.name(), keys);
        if (member != null) extra.add(member);
        if (r.extra() != null && !r.extra().isBlank()) extra.add(r.extra());
        if (r.columnDefault() != null) extra.add("默认 " + r.columnDefault());
        return extra.isEmpty() ? null : String.join(" / ", extra);
    }

    /**
     * 这一列若只是组合唯一键的一员（自己单独没有唯一键），给一句说明；否则 {@code null}。
     *
     * <p>一列同时属于好几个组合键时取<b>列数最少</b>的那个（同样少时主键在前）——那是最容易满足的完整条件，
     * 与 {@code SemanticJoinValidator} 判组合键时的取法一致，免得同一张表在两处说出两组不同的键。
     */
    static String compositeMemberNote(String column, List<UniqueKey> keys) {
        if (column == null || keys == null) {
            return null;
        }
        UniqueKey best = null;
        for (UniqueKey k : keys) {
            if (k.columns().stream().noneMatch(column::equalsIgnoreCase)) {
                continue;
            }
            if (k.columns().size() == 1) {
                // 自己就有唯一键：它单独就唯一，组合键成员的身份不构成风险。
                return null;
            }
            if (best == null || k.columns().size() < best.columns().size()) {
                best = k;
            }
        }
        if (best == null) {
            return null;
        }
        String cols = String.join(", ", best.columns());
        if (cols.contains("主键") || cols.contains("唯一")) {
            // 中文列名碰巧含这两个词时不列出列名：值域剖析按子串认这两个词，理由见 describe 的注释。
            return "组合键成员，单独这一列可能重复";
        }
        return "组合键成员（" + cols + "），单独这一列可能重复";
    }

    /** 放进 {@link ObjectDetail#extra()} 的形状。显式拼 Map：jackson 2.11 序列化不了 record。 */
    private static List<Map<String, Object>> uniqueKeysExtra(List<UniqueKey> keys) {
        List<Map<String, Object>> out = new ArrayList<>(keys.size());
        for (UniqueKey k : keys) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", k.name());
            m.put("primary", k.primary());
            m.put("columns", k.columns());
            out.add(m);
        }
        return out;
    }

    // ================================================================ 公共

    @Override
    public void close() {
        // 无状态：连接每次借还，这里没有需要释放的东西。
        // 保留空实现是为了让调用方统一用 try-with-resources，将来若改成持有连接不必改调用方。
    }

    private Connection borrow() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 双保险：JDBC 层的 {@code setReadOnly} 是「提示」（连接池已经设过一次），
     * {@code SET SESSION TRANSACTION READ ONLY} 是服务端<b>强制</b>。
     * 两者挡的东西不完全重合，而这一层本来就不承重，所以一道都不省。
     */
    private void applyReadOnly(Connection c) {
        try {
            c.setReadOnly(true);
        } catch (SQLException e) {
            log.debug("setReadOnly 失败，继续用会话级只读 connectorId={}", instance.id(), e);
        }
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(5);
            st.execute("SET SESSION TRANSACTION READ ONLY");
        } catch (SQLException e) {
            // 有些中间件（ProxySQL / 部分云 RDS 代理）不支持这条。不致命——承重层是客户侧的账号权限，
            // 这里只是纵深防御的一层，失败记日志继续。
            log.debug("SET SESSION TRANSACTION READ ONLY 失败 connectorId={} errorCode={}",
                    instance.id(), e.getErrorCode(), e);
        }
    }

    /**
     * {@code SQLException} → 统一错误语义。
     *
     * <p><b>{@code safeDetail} 一律是本方法里写死的常量句子。</b>
     * {@code e.getMessage()} 常带完整 SQL、主机名、有时带连接参数，而这个异常会被
     * 工具层转成返回值回灌模型、并落进 {@code ai_model_call_content}。原始异常只走日志。
     */
    ConnectorException classify(SQLException e, String action) {
        log.warn("MySQL 连接器{}失败 connectorId={} errorCode={} sqlState={}",
                action, instance.id(), e.getErrorCode(), e.getSQLState(), e);

        if (e instanceof SQLTimeoutException) {
            return ConnectorException.of(ConnectorErrorCode.TIMEOUT, null);
        }
        int code = e.getErrorCode();
        switch (code) {
            case 1205:      // ER_LOCK_WAIT_TIMEOUT
            case 3024:      // ER_QUERY_TIMEOUT（max_execution_time）
                return ConnectorException.of(ConnectorErrorCode.TIMEOUT, null);
            case 1044:      // ER_DBACCESS_DENIED_ERROR
            case 1142:      // ER_TABLEACCESS_DENIED_ERROR
            case 1143:      // ER_COLUMNACCESS_DENIED_ERROR
                return ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                        "这个数据库账号没有访问该对象的权限");
            case 1045:      // ER_ACCESS_DENIED_ERROR
                return ConnectorException.of(ConnectorErrorCode.AUTH_FAILED, null);
            case 1146:      // ER_NO_SUCH_TABLE
            case 1049:      // ER_BAD_DB_ERROR
                return ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                        "指定的表或库不存在。请先用 conn_catalog 确认对象名称");
            case 1054:      // ER_BAD_FIELD_ERROR
                return ConnectorException.of(ConnectorErrorCode.NOT_FOUND,
                        "查询里引用了不存在的列。请先用 conn_describe 确认字段名");
            case 1064:      // ER_PARSE_ERROR
                return ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                        "数据库拒绝了这条语句：语法错误。请检查后重写");
            case 1040:      // ER_CON_COUNT_ERROR
            case 1203:      // ER_TOO_MANY_USER_CONNECTIONS
                return ConnectorException.of(ConnectorErrorCode.RATE_LIMITED,
                        "数据库当前连接数已满，请稍后重试");
            default:
                break;
        }
        String state = e.getSQLState();
        if (state != null) {
            if (state.startsWith("28")) {
                return ConnectorException.of(ConnectorErrorCode.AUTH_FAILED, null);
            }
            if (state.startsWith("08")) {
                return ConnectorException.of(ConnectorErrorCode.UNREACHABLE, null);
            }
            if (state.startsWith("42")) {
                return ConnectorException.of(ConnectorErrorCode.FORBIDDEN,
                        "数据库拒绝了这次访问：对象不存在或权限不足");
            }
        }
        return ConnectorException.of(ConnectorErrorCode.UPSTREAM_ERROR,
                "数据库返回了错误（错误码 " + code + "）");
    }
}
