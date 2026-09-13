package com.jimeng.dataserver.ai.connector.impl.mysql;

import com.jimeng.dataserver.ai.connector.error.ConnectorErrorCode;
import com.jimeng.dataserver.ai.connector.error.ConnectorException;
import com.jimeng.dataserver.ai.connector.guard.ReadOnlySqlGuard;
import com.jimeng.dataserver.ai.connector.pool.CustomerDataSourceManager;
import com.jimeng.dataserver.ai.connector.pool.PoolSpec;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.spi.Capability;
import com.jimeng.dataserver.ai.connector.spi.Connector;
import com.jimeng.dataserver.ai.connector.spi.ConnectorInstance;
import com.jimeng.dataserver.ai.connector.spi.ConnectorSession;
import com.jimeng.dataserver.ai.connector.spi.ParamField;
import com.jimeng.dataserver.ai.connector.spi.ParamSpec;
import com.jimeng.dataserver.ai.connector.spi.ParamType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
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
                        "加密存储，保存后永不回显。留空表示沿用原密码"),
                ParamField.of("useSsl", "启用 SSL", ParamType.BOOL, false,
                        "公网直连建议开启。客户库没配证书时开启会连不上").withDefault("false"),
                ParamField.of("connectTimeoutSec", "连接超时（秒）", ParamType.INT, false,
                        "建立 TCP 连接的超时。查询本身的超时由平台统一控制，不在这里配")
                        .withDefault("10").withRange(1, 60)
        );
    }

    @Override
    public Set<Capability> declaredCapabilities() {
        // 不含 INVOKE：存储过程调用是写操作面，本期不开。
        return Set.of(Capability.QUERY, Capability.DESCRIBE, Capability.HEALTH);
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
        return new MySqlSession(instance, ds, database, sqlGuard);
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
