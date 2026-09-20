package com.jimeng.dataserver.ai.connector.impl.mysql;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.guard.WriteSqlGuard;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.pool.PoolSpec;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.GrantRequest;
import com.jimeng.dataserver.ai.connector.spi.GrantScript;
import com.jimeng.dataserver.ai.connector.spi.ParamField;
import com.jimeng.dataserver.ai.connector.spi.ParamSpec;
import com.jimeng.dataserver.ai.connector.spi.ParamType;
import com.jimeng.dataserver.ai.connector.spi.WritePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MySQL 连接器。
 *
 * <h3>为什么类型标识叫 MYSQL 而不是更窄的名字</h3>
 * <b>Doris / StarRocks 用的就是 MySQL 协议</b>，做完 MySQL 几乎零成本支持——它们能直接复用本实现
 * （同一个驱动、同一套 {@code information_schema}、同一套错误码）。将来接它们时应当
 * <b>复用这个 kind</b> 或者以本类为基类，而不是复制一份改名字。真正需要新开 kind 的是
 * PostgreSQL / Oracle 这类协议与方言都不同的库。
 *
 * <h3>本类只做三件事</h3>
 * 声明参数、拼一条安全的 JDBC URL、把会话交给 {@link MySqlSession}。
 * 连接池的生命周期在 {@link CustomerDataSourceManager}，语句护栏在 {@link ReadOnlySqlGuard}，
 * 授权/限流/审计在网关——这里一件都不重复实现。
 */
@Component
@RequiredArgsConstructor
public class MySqlConnector implements Connector {

    public static final String KIND = "MYSQL";

    /**
     * ★ host 与 database 会被拼进 JDBC URL，所以必须先校验。
     *
     * <p>这不是洁癖：JDBC URL 的参数是 {@code ?k=v&k=v}，一个能往 host 或 database 里塞
     * {@code ?} 和 {@code &} 的人，就能追加 {@code ?allowLoadLocalInfile=true}，
     * <b>把下面那一整排安全参数全部关掉</b>。这是一条真实的提权路径，不是理论风险。
     */
    private static final Pattern HOST_RE = Pattern.compile("^[A-Za-z0-9._\\-]{1,255}$");
    private static final Pattern DB_RE = Pattern.compile("^[A-Za-z0-9_$\\-]{1,64}$");

    private final CustomerDataSourceManager dataSourceManager;
    private final ReadOnlySqlGuard sqlGuard;
    private final WriteSqlGuard writeGuard;
    private final ConnectorProperties properties;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String displayName() {
        return "MySQL / 兼容 MySQL 协议的库（含 Doris、StarRocks）";
    }

    @Override
    public ParamSpec paramSpec() {
        return ParamSpec.of(
                ParamField.of("host", "主机地址", ParamType.STRING, true,
                                "数据库的域名或 IP。平台从公网直连，请确保已对平台出口 IP 放行")
                        .withPattern(HOST_RE.pattern()),
                ParamField.of("port", "端口", ParamType.INT, false, "默认 3306")
                        .withDefault("3306").withRange(1, 65535),
                ParamField.of("database", "库名", ParamType.STRING, true,
                                "要访问的数据库名。一条连接只对应一个库；需要多个库请建多条连接")
                        .withPattern(DB_RE.pattern()),
                ParamField.of("username", "用户名", ParamType.STRING, true,
                        "★ 必须是只读账号（数据库侧只 GRANT SELECT）。保存时平台会实际验证它写不了，"
                                + "验不过会拒绝保存"),
                ParamField.secret("password", "密码", true,
                        "加密存储。已保存的密码可以在编辑里查看，每次查看都会记一条使用记录；"
                                + "不主动更换就不会改动它"),
                ParamField.of("useSsl", "启用 SSL", ParamType.BOOL, false,
                        "公网直连建议开启。客户库没配证书时开启会连不上").withDefault("false"),
                ParamField.of("connectTimeoutSec", "连接超时（秒）", ParamType.INT, false,
                        "建立 TCP 连接的超时。查询本身的超时由平台统一控制，不在这里配")
                        .withDefault("10").withRange(1, 60)
        );
    }

    @Override
    public Set<Capability> declaredCapabilities() {
        // 不含 INVOKE：存储过程调用另算，本期不开。
        //
        // 含 WRITE 只表示【这种类型支持写】，不表示某条连接真的能写——
        // 实例级的 WRITE 由「写策略 ∧ 类型支持 ∧ 账号确实能写」三者的交集决定，
        // 在 ConnectorProbeService 里回填（见 Capability.WRITE 的注释）。
        return Set.of(Capability.QUERY, Capability.DESCRIBE, Capability.HEALTH, Capability.WRITE);
    }

    @Override
    public ConnectorSession open(ConnectorInstance instance) {
        String host = required(instance.str("host"), "主机地址");
        String database = required(instance.str("database"), "库名");
        String username = required(instance.str("username"), "用户名");
        String password = instance.credential();
        if (password == null || password.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "这条连接没有保存密码，请在管理台重新填写");
        }
        if (!HOST_RE.matcher(host).matches()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "主机地址含有非法字符，只允许字母、数字、点、短横线和下划线");
        }
        if (!DB_RE.matcher(database).matches()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    "库名含有非法字符，只允许字母、数字、下划线、$ 和短横线");
        }
        int port = instance.intVal("port", 3306);
        if (port < 1 || port > 65535) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "端口必须在 1-65535 之间");
        }

        String jdbcUrl = buildJdbcUrl(host, port, database,
                instance.boolVal("useSsl", false), instance.intVal("connectTimeoutSec", 10));

        var pool = properties.getPool();
        PoolSpec spec = new PoolSpec(pool.getMaxSize(), (int) pool.getConnectionTimeoutMs(),
                pool.getMaxLifetimeMinutes(), pool.getIdleEvictMinutes());
        DataSource ds = dataSourceManager.acquire(instance, jdbcUrl, username, password, spec);
        return new MySqlSession(instance, ds, database, sqlGuard, writeGuard);
    }

    // ==================================================== 授权脚本

    /**
     * ★ 占位符，<b>绝不生成真实密码</b>。
     *
     * <p>两个理由，都不是洁癖：一是我们本来就不该知道客户的密码（平台只需要它被填进密码框，
     * 之后是 AES-GCM 密文）；二是这段脚本会被复制来复制去，贴进工单、贴进聊天窗口、
     * 贴进某个共享文档——里面带着真密码的那一刻，密码就已经泄了，而没有人会意识到。
     */
    private static final String PASSWORD_PLACEHOLDER = "请替换成一个强密码";

    /** 账号名白名单。MySQL 8 的用户名上限是 32 字符，超了根本建不出来，不如在这里就说清楚。 */
    private static final Pattern GRANT_USER_RE = Pattern.compile("^[A-Za-z0-9_$\\-]{1,32}$");

    /** host 段：{@code %} 通配、IP、网段前缀（{@code 192.168.%}）、域名，冒号留给 IPv6。 */
    private static final Pattern GRANT_HOST_RE = Pattern.compile("^[A-Za-z0-9._:%\\-]{1,255}$");

    @Override
    public GrantScript grantScript(GrantRequest req) {
        if (req == null) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, "生成授权脚本的参数不能为空");
        }
        // 库名与表名复用 DB_RE：同一份白名单管两件事（拼 JDBC URL、拼 GRANT）。
        // 好处是「能存进连接的库名」与「脚本敢生成的库名」永远是同一个集合，
        // 不会出现脚本生成得出来、连接却存不进去的错配。
        String db = ident(req.database(), "库名", DB_RE);
        String user = ident(req.username(), "账号名", GRANT_USER_RE);
        String host = ident(req.host(), "登录地址", GRANT_HOST_RE);
        List<String> tables = req.tables() == null ? List.of() : req.tables();

        WritePolicy policy = req.writePolicy() == null ? WritePolicy.FORBIDDEN : req.writePolicy();
        // 两档写策略授同一套 DML：REQUIRE_APPROVAL 与 AUTO 的差别在平台侧（要不要人点确认），
        // 数据库侧表达不了，也不该表达——把审批做成「数据库不给权限」，审批通过后照样执行不了。
        String privileges = policy.allowsWrite() ? "SELECT, INSERT, UPDATE, DELETE" : "SELECT";
        String account = "'" + user + "'@'" + host + "'";

        StringBuilder sql = new StringBuilder();
        sql.append("-- 请用有授权权限的数据库账号（如 root）整段执行\n");
        sql.append("-- 平台侧写策略：").append(policy.label()).append("，对应权限：").append(privileges).append("\n");
        sql.append("CREATE USER ").append(account)
                .append(" IDENTIFIED BY '").append(PASSWORD_PLACEHOLDER).append("';\n");
        if (tables.isEmpty()) {
            sql.append("GRANT ").append(privileges).append(" ON `").append(db).append("`.* TO ")
                    .append(account).append(";\n");
        } else {
            for (String raw : tables) {
                String t = ident(raw, "表名", DB_RE);
                sql.append("GRANT ").append(privileges).append(" ON `").append(db).append("`.`").append(t)
                        .append("` TO ").append(account).append(";\n");
            }
        }
        // FLUSH PRIVILEGES 对 CREATE USER / GRANT 其实不是必需的（只有直接改 mysql.* 授权表才要刷），
        // 留着是因为 DBA 普遍按「授权完刷一下」的习惯核对脚本，少一行反而要被问一轮；执行它没有副作用。
        sql.append("FLUSH PRIVILEGES;\n");

        return GrantScript.builder()
                .sql(sql.toString())
                .notes(grantNotes(db, account, host, tables, policy))
                .build();
    }

    /**
     * 校验并返回一个标识符。<b>校验不过直接抛，不做转义。</b>
     *
     * <p>这里是本类第二处「用户输入拼进将被执行的文本」的地方（第一处是 JDBC URL），
     * 而这一处更阴险：脚本是<b>平台给的</b>，客户 IT 天然信任它、往 root 会话里整段粘贴。
     * 一个叫 {@code shop`; DROP DATABASE x; --} 的「库名」，到那时执行的就不是我们生成的语句了。
     *
     * <p>不转义的理由：转义要对方言、对版本、对字符集都判断正确才安全，而白名单只要判断一次。
     * 合法的库名里本来也不会有分号和反引号——被挡住的输入，十有八九本来就该被挡住。
     */
    private static String ident(String raw, String label, Pattern allowed) {
        if (raw == null || raw.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, label + "不能为空");
        }
        String v = raw.trim();
        if (!allowed.matcher(v).matches()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR,
                    label + "含有非法字符，已拒绝生成脚本。" + label
                            + "只允许字母、数字、下划线、$ 和短横线（登录地址另可用点、冒号和 %）；"
                            + "带引号、反引号、分号、空格的输入一律不接受");
        }
        return v;
    }

    /** 脚本表达不了的东西都在这儿。每条一句话，按「执行前最需要知道」排序。 */
    private static List<String> grantNotes(String db, String account, String host,
                                           List<String> tables, WritePolicy policy) {
        List<String> notes = new ArrayList<>();
        notes.add("把脚本里的「" + PASSWORD_PLACEHOLDER + "」换成一个随机生成的强口令，"
                + "且不要与其它系统复用——替换后的真实密码只填进平台的密码框，不要写回这段脚本、更不要贴进聊天或工单。");
        if ("%".equals(host)) {
            notes.add("账号写成了 " + account + "，其中 @'%' 表示允许从任意地址登录；"
                    + "如果能拿到平台的出口 IP，请把它收紧成具体 IP（如 '...'@'203.0.113.10'），这是成本最低的一道防线。");
        } else {
            notes.add("账号已限定只能从 " + host + " 登录；"
                    + "将来平台出口 IP 变更时这个账号会突然连不上（表现为凭据/网络类报错），届时改这一处即可。");
        }
        notes.add("脚本只授了 `" + db + "` 这一个库"
                + (tables.isEmpty() ? "" : "中列出的那几张表") + "的权限，不要图省事改成 *.*——"
                + "多授的权限平台一行都用不上，真出了事却要算在这个账号头上。");
        if (!tables.isEmpty()) {
            // 这条是实测踩出来的：information_schema 只返回账号有权限的对象，
            // 所以漏授一张表，平台侧的表现是「这张表不存在」而不是「没权限」，排查会绕很远。
            notes.add("逐表授权后平台只看得见这 " + tables.size() + " 张表，"
                    + "漏授的表在平台侧表现为「这张表不存在」而不是权限报错，所以请一次把 Agent 要用的表列全。");
        }
        if (policy.allowsWrite()) {
            notes.add("这是一个能写的账号：平台会在连接上如实标注写策略「" + policy.label()
                    + "」，界面和审计里都看得到，不会伪装成只读。");
            notes.add("写操作仍受平台侧两道闸约束——单次影响行数上限、UPDATE/DELETE 必须带 WHERE"
                    + (policy == WritePolicy.REQUIRE_APPROVAL ? "，且每一条都要超管点确认后才真正执行。" : "。")
                    + "如果只是想让平台查数，请把写策略调回只读后重新生成脚本：数据库侧的只读授权才是承重层。");
        }
        notes.add("MySQL 8.0 起 GRANT 不再能隐式建账号，所以必须先 CREATE USER；"
                + "5.7 的默认 sql_mode 也带 NO_AUTO_CREATE_USER，这段脚本两个版本都能直接跑。");
        notes.add("MySQL 8.0 的默认认证插件是 caching_sha2_password，而平台连库时刻意关掉了"
                + "「向服务端索要 RSA 公钥」（防中间人拿到明文密码）——"
                + "所以请在连接里勾选 SSL，或把建号语句改成 IDENTIFIED WITH mysql_native_password BY '...'，否则会认证失败。");
        notes.add("如果这个账号已存在，CREATE USER 会报 ERROR 1396；"
                + "此时改用 ALTER USER " + account + " IDENTIFIED BY '...' 重设密码，或换一个账号名，不要直接跳过这一行。");
        return notes;
    }

    /**
     * 拼 JDBC URL。<b>下面每一个参数都是安全项，不接受调用方覆盖</b>——它们写死在这里，
     * 而用户可配的只有 host / port / database / useSsl / connectTimeout。
     */
    static String buildJdbcUrl(String host, int port, String database, boolean useSsl, int connectTimeoutSec) {
        int connectMs = Math.max(1, connectTimeoutSec) * 1000;
        // socketTimeout 取连接超时的若干倍并留足余量：它是「整个 socket 读」的硬上限，
        // 设得比 statement timeout 还短会让正常的慢查询被误报成网络故障。
        int socketMs = Math.max(connectMs, 60_000);
        List<String> params = List.of(
                // 堆叠语句的最后一道门。ReadOnlySqlGuard 在解析层已经拦过一次，
                // 这里是驱动层兜底——两层都要，因为解析器认不出所有方言。
                "allowMultiQueries=false",
                // LOAD DATA LOCAL INFILE：恶意/被攻陷的 MySQL 服务端可以反过来让【客户端】
                // 读本地文件并上传。我们是客户端，关掉它保护的是平台自己的文件系统。
                "allowLoadLocalInfile=false",
                "allowUrlInLocalInfile=false",
                // Connector/J 的历史 RCE 面：服务端返回的 BLOB 被自动反序列化成 Java 对象。
                "autoDeserialize=false",
                // 关掉「向服务端索要 RSA 公钥」——中间人可借此拿到明文密码。
                "allowPublicKeyRetrieval=false",
                // 0000-00-00 这类 MySQL 特有的零值日期在严格模式外很常见，不转会直接抛异常，
                // 表现为「某张表一查就报错」。
                "zeroDateTimeBehavior=CONVERT_TO_NULL",
                // 让 TINYINT(1) 保持数字而不是被驱动转成 boolean——客户用它存状态码的情况很多，
                // 转成 true/false 会让模型看到的值与客户在自己系统里看到的对不上。
                "tinyInt1isBit=false",
                "connectTimeout=" + connectMs,
                "socketTimeout=" + socketMs,
                "useSSL=" + useSsl,
                "characterEncoding=utf8",
                "useUnicode=true",
                // 服务端时区未知时按 UTC 解释，避免驱动抛 "The server time zone value ... is unrecognized"。
                // 时区口径本身是语义层要解决的问题（业务方得说清「上个月」按哪个时区算），不在这里猜。
                "serverTimezone=UTC"
        );
        return "jdbc:mysql://" + host + ":" + port + "/" + database + "?" + String.join("&", params);
    }

    private static String required(String v, String label) {
        if (v == null || v.isBlank()) {
            throw ConnectorException.of(ConnectorErrorCode.CONFIG_ERROR, label + "不能为空");
        }
        return v.trim();
    }
}
