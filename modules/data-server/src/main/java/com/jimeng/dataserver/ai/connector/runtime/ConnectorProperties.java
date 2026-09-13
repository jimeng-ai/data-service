package com.jimeng.dataserver.ai.connector.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 连接器框架的运行期护栏参数（Nacos {@code data-server.yml} 的 {@code connector.*}）。
 *
 * <h3>为什么每一项都有默认值——这条是硬纪律，不是风格偏好</h3>
 * 本仓库 <b>push main = 部署到生产那台机器</b>。一个没有默认值的必填配置项，会让生产在下一次
 * 部署时<b>直接启动不来</b>，而不是"某个功能不可用"。{@code skill.enabled} 就是现成的例子：
 * 它没有默认值，配缺即启动失败。所以这里每个字段都在声明处给了值，Nacos 里一行都不配也能起。
 *
 * <p><b>但这条纪律只适用于护栏参数，不适用于安全密钥。</b>
 * {@code CONNECTION_CREDENTIAL_KEY} 之类继续 fail-closed：缺了就拒绝读写凭据，绝不回落明文。
 * 区别在于前者配错只是"限得松一点"，后者配错是"悄悄不加密"。
 *
 * <h3>这些数字是事故防线，不是可调优项</h3>
 * 我们是在<b>客户的生产库</b>上跑模型自己写的语句。超时、行数上限、字节上限、并发闸由框架强制下发
 * （{@code QueryOptions}），连接器实现方拿不到绕过它们的口子。
 */
@Data
@Component
@ConfigurationProperties(prefix = "connector")
public class ConnectorProperties {

    /**
     * 总开关。关掉之后 {@code ConnectorGateway.execute} 会<b>明确报错</b>而不是静默返回空结果——
     * 静默失败时模型不会说"我没这个能力"，它会自己编一个答案。
     */
    private boolean enabled = true;

    private Query query = new Query();
    private Pool pool = new Pool();
    private Limit limit = new Limit();
    private Invoke invoke = new Invoke();
    private Write write = new Write();
    private Health health = new Health();

    /** 「能查」的护栏。 */
    @Data
    public static class Query {
        /** Agent 没写 LIMIT 时平台替它加的行数。 */
        private int defaultLimit = 1000;
        /** Agent 写了更大的 LIMIT 也按这个截断，并如实告诉它"被截断了"。 */
        private int maxLimit = 10000;
        /** 语句超时。客户的库不该因为一条模型写的慢查询被拖垮。 */
        private int timeoutSeconds = 15;
        /** 结果字节上限（256K）。行数没超但单行很宽时靠它兜底。 */
        private int maxResultBytes = 262144;
    }

    /**
     * 客户库连接池参数。
     *
     * <p><b>别照抄主库的 Nacos 配置。</b>主库那份写的是 {@code max-lifetime: 30000}（30 <b>秒</b>），
     * 那是 HikariCP 允许的下限、属于明显配错，正常量级是 30 分钟。
     *
     * <p>还有一条更要命的：持有这些池的组件<b>绝不能</b>把 {@code DataSource} 注册成 Spring bean。
     * {@code DataSourceAutoConfiguration} 是 {@code @ConditionalOnMissingBean(DataSource)}，
     * 注册第一个就会让主库 DataSource 干脆不被创建、{@code SqlSessionFactory} 静默绑到客户库上——
     * 而且<b>启动会成功</b>。
     */
    @Data
    public static class Pool {
        /** 每个客户实例的最大连接数。刻意很小：这是别人的生产库，不是我们的。 */
        private int maxSize = 3;
        private long connectionTimeoutMs = 10000;
        private int maxLifetimeMinutes = 30;
        /** 多久没人用就把整个池关掉——连接器是低频访问，长期占着客户库的连接没有道理。 */
        private int idleEvictMinutes = 10;
    }

    /** 限流。两级：每实例并发（保护客户的库）+ 每租户速率（防滥用与烧钱）。 */
    @Data
    public static class Limit {
        /** 单个连接实例的并发闸。进程内信号量，见 {@code ConnectorGateway} 里关于多副本的说明。 */
        private int perInstanceConcurrency = 2;
        /** 每租户每分钟的调用次数，走 Redis 做成分布式的。&lt;= 0 表示不限。 */
        private int perTenantPerMinute = 60;
    }

    /**
     * 「能写」的护栏。
     *
     * <p>默认值刻意小。这是在客户的生产库上执行模型写的 DML——
     * 单次能改的行数越少，一次写错的爆炸半径越小。
     */
    @Data
    public static class Write {
        /**
         * 单条语句的影响行数上限。<b>超了整条回滚</b>。
         *
         * <p>200 是个刻意保守的值：正常的业务操作（改一个订单状态、补一批工单备注）
         * 都在这个量级以内；真要批量处理上万行，应该由人写脚本在数据库侧做，
         * 而不是让模型一条语句推过去。
         */
        private int maxAffectedRows = 200;
        /** 写语句的超时。比查询短——写操作持有锁，拖久了会阻塞客户的业务。 */
        private int timeoutSeconds = 10;
        /** 待审批项的有效期。过期未处理即作废，避免队列里堆着一堆没人记得的陈年请求。 */
        private int approvalTtlHours = 24;
    }

    /** 「能调用」的护栏。 */
    @Data
    public static class Invoke {
        private int timeoutSeconds = 20;
        private int maxResponseBytes = 262144;
    }

    /**
     * 定时健康探测。
     *
     * <p><b>它只做 ping，不做接入探测那三步。</b>三步里的只读校验会往客户库发一条 UPDATE
     * （靠权限拒绝来证明账号只读），那是录入时验一次的动作——每 5 分钟往客户的生产库发一次
     * 写尝试，无论多无害都不可接受，客户的 DBA 看到审计日志会先来找我们。
     *
     * <p>间隔由 {@code connector.health.interval-ms} 控制（{@code @Scheduled} 直接读占位符，
     * 所以改它要重启才生效）。
     */
    @Data
    public static class Health {
        /** 关掉之后界面上的健康态就只在「新建 / 编辑 / 点测试连接」时更新。 */
        private boolean enabled = true;
        /** 单次 ping 的超时由各连接器实现自己控制，这里限的是整轮扫描的总时长上限。 */
        private int sweepTimeoutSeconds = 120;
    }
}
