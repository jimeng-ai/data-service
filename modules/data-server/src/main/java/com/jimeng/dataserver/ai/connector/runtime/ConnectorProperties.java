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
    private SchemaRefresh schemaRefresh = new SchemaRefresh();
    private Semantic semantic = new Semantic();
    private Overview overview = new Overview();

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

        /**
         * <b>平台自己发起</b>的调用（刷新结构快照、语义层采样探查）每租户每分钟的次数。
         *
         * <h4>★ 为什么它必须是一个独立的桶，而不是和上面那个共用</h4>
         * 上面那个额度是<b>客户的 Agent 在问数</b>时花的。语义层的采样验证一次要对客户的库打上百条
         * 探查——若两者共用一个计数器，一轮后台剖析就能把客户当分钟的问数额度吃干净，
         * 表现是「模型突然说被限流了」，而客户那一侧<b>一句话都没问</b>。
         * 一个由平台自己的后台任务造成、却只砸在用户脸上的限流，排查时没有任何线索指向真凶。
         *
         * <p>所以 {@code ConnectorGateway} 给管理面换了一段 key 前缀（{@code connector:rate:platform:}），
         * 两条路各记各的。<b>分桶本身才是要点，数值大小是次要的。</b>
         *
         * <p>{@code <= 0} 表示<b>沿用 {@link #perTenantPerMinute} 的数值</b>——注意不是「不限」：
         * 这里的默认值 0 只是「没单独配过，就按同样的宽松程度给一份自己的额度」。
         * 真要放开不限，把 {@code perTenantPerMinute} 配成 0（那时两条路都不限）。
         */
        private int perTenantPlatformPerMinute = 0;
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

    /**
     * 结构快照的定时刷新：{@code ConnectorHealthJob} 搭车做的低频 {@code ConnectorSchemaService.refresh()}
     * （Nacos {@code connector.schema-refresh.*}）。
     *
     * <h3>为什么要有它</h3>
     * 漂移处置（ObjectDiff → applyDrift → STALE）挂在 {@code refresh()} 上，而在此之前 {@code refresh()}
     * 只在超管手工点「刷新结构」时才跑。客户改了表、没人去点，过期的说明书就一直被当成事实注入——
     * {@code ConnectorOverviewService} 把那份快照塞进的是<b>每一个</b>请求的 system 上下文。
     *
     * <h3>★ 只刷新，绝不重新推导</h3>
     * 周期性重推要走 {@code replaceInferred}：物理删掉全部 INFERRED 行，连同平台花客户查询额度换来的
     * 每一条采样验证结论。设计文档 §12 把「周期性重新推断」明文列为不做（会覆盖人工确认过的口径）。
     *
     * <h3>★「间隔」按快照年龄算，不按定时器算</h3>
     * 本仓库 push main 即部署，一天可能重启好几次。{@code fixedDelay = 6h} 的定时器每次重启都从零计时，
     * 部署比 6 小时勤的时候它<b>一次都不会触发，而且不报错</b>。所以时钟用落库的
     * {@code connector_schema.synced_at}：快照比 {@link SchemaRefresh#intervalHours} 旧就算到期，最旧的先刷。
     * 手工点「刷新结构」同样盖新的 synced_at，所以不会紧跟着再被定时任务刷一遍。
     *
     * <p>「有没有到期的」这个检查本身每 10 分钟一次，由 {@code connector.schema-refresh.check-interval-ms}
     * 控制（{@code @Scheduled} 直接读占位符，<b>改它要重启</b>）。下面四个字段走配置绑定，Nacos 改完即生效。
     * 没有到期的连接时，一次检查只是<b>我们自己库上</b>的一条 GROUP BY，一个字节都不发到客户那边。
     *
     * <h3>容量账</h3>
     * 默认每 10 分钟最多 3 条 → 每 6 小时最多 108 条。连接数超过这个量级时，「最旧的先刷」保证公平，
     * 代价是快照的最长年龄会超过 6 小时；要追回来就调大 {@link SchemaRefresh#maxPerSweep}（先看下面那笔账）。
     *
     * <h3>平台侧速率账（与 S3 30/min、S4 20/min 合看）</h3>
     * 一次 refresh 是<b>一次</b> {@code executeAsPlatform}（1 + N 次往返装在同一个调用里），只吃平台桶
     * 1 个令牌，且一轮之内串行——对那笔「合计不超过 60/min」的账，增量是每分钟至多 maxPerSweep 个。
     * 它真正贵的不是令牌，是<b>每实例并发许可</b>：一次刷新全程占着那条连接的一个许可（默认共 2 个），
     * 这段时间里那条连接上的 Agent 查询少一个许可可用。这也是为什么它必须低频、有上限。
     */
    @Data
    public static class SchemaRefresh {
        /**
         * 默认开。关掉之后结构漂移只在有人手工点「刷新结构」时才会被发现。
         * 与 {@link Health#isEnabled()} 互相独立：ping 关了不影响这里，反之亦然。
         */
        private boolean enabled = true;

        /**
         * 快照多少小时没刷新就算到期。
         *
         * <p>6 小时是「过期说明书被当成事实注入多久」与「多频繁往客户生产库打 1 + N 次元数据查询」之间的折中：
         * 表结构变更是按天计的事，按分钟刷只是拿客户库的负载去换早几分钟的发现。
         *
         * <p>{@code <= 0} 等同于关掉，<b>不是</b>「每轮都到期」——拿不准的时候往少打客户库的方向落。
         */
        private int intervalHours = 6;

        /**
         * 每次检查最多<b>尝试</b>刷新几条（成功、失败、繁忙都算一次）。一轮之内串行，
         * 所以同一时刻全平台最多只有一条连接在被定时刷新。{@code <= 0} 等同于关掉。
         *
         * <p>按「尝试」而不是「成功」计数：按成功计的话，一串连不上的连接会把一轮检查变成
         * 把到期的连接挨个撞一遍。
         */
        private int maxPerSweep = 3;

        /**
         * 一轮的时间预算（分钟），同时是跨副本锁的租期。超了就不再开始下一条（正在跑的那条跑完为止）。
         *
         * <p>刻意不用 Redisson 看门狗的自动续租：一次卡死的刷新会让锁被续到进程退出，
         * 于是所有副本的定时刷新<b>一起静默停摆</b>——那正是本功能要消灭的状态。
         * 租期到了而那条还没跑完，最坏是另一个副本对同一条连接再刷一次，那是安全的
         * （撞车时会发生什么，见 {@code ConnectorHealthJob}）。
         */
        private int sweepTimeoutMinutes = 30;
    }

    /**
     * 语义层推导（{@code ConnectorSemanticDeriveService}）的参数。
     *
     * <p><b>注意这里没有 model 字段。</b>推导用哪个模型走
     * {@code @Value("${connector.semantic.infer-model:}")} 单独读，与仓库里唯一另一处同步 LLM 调用
     * （{@code SkillEvalService.graderModel}）保持同一个形状：留空即不下发 model，
     * 由 {@code GenericChatClient} 回落到 provider 的 {@code chat.model}。
     * 配一个 {@code ai_model} 表里没有的值<b>不会报错</b>——{@code ModelResolver} 未命中即静默回落全局
     * provider，所以推导时会把"本次请求的模型名"打进日志，否则改错配置的表现是"看起来生效了"。
     */
    @Data
    public static class Semantic {
        /**
         * 总开关。关掉之后接入不再触发推导，连接照常可用——语义层是叠加的注解，不是一道闸。
         * 与 {@code ConnectorProperties#enabled} 不同：那个关掉要明确报错（否则模型会自己编答案），
         * 这个关掉只是少一份说明书，不需要报错。
         */
        private boolean enabled = true;

        /**
         * 送进模型的结构摘要字符上限。
         *
         * <p>60000 不是拍的：设计文档写的"整个 schema 才 8.1KB"是量错了，拿本仓库自己的 DDL 重放，
         * 35 张表的摘要是 ~33KB / 8-9K token。60000 字符大约覆盖 60-70 张表，一次调用装得下。
         * <b>超了按整张表丢，绝不截半张表</b>——半张表会让模型对着看不见的列写字段含义，
         * 还会基于残缺的列集合给出表的用途判断。被丢掉的表名会进日志和 semantic_note。
         */
        private int maxDigestChars = 60000;

        /**
         * 推导那一次调用的 max_tokens。
         *
         * <p>输出量比直觉大：35 张表可能产出几百条 fields。给小了的表现不是报错，
         * 而是 JSON 在半路断掉——推导侧会尝试截到最后一个完整条目并<b>明说截断了</b>，
         * 但那毕竟是残缺的说明书，宁可这里给够。
         */
        private int maxTokens = 16000;
    }

    /**
     * 默认注入目录（{@code ConnectorOverviewService}）：把已授权连接器的表清单 + 已确认的口径
     * 直接放进 system 上下文，省掉模型开口前的那次 {@code conn_catalog} 往返。
     *
     * <p><b>它读的是我们自己的快照，不是客户的库。</b>注入发生在每一次请求上，接到客户库上
     * 等于客户每问一句话我们就 ping 一次他们的生产库。
     */
    @Data
    public static class Overview {
        /**
         * 默认开。关掉之后模型退回「先调 conn_catalog 再选表」的老路——多一次往返，
         * 但一切照常可用。这是个优化，不是一道闸，所以关掉不需要报错。
         */
        private boolean enabled = true;

        /**
         * 注入文本里<b>表清单与口径</b>那部分的字符预算（免责声明与「被截断了」的提示不计入，
         * 它们永远完整给出）。
         *
         * <p>4000 不是照抄设计文档的「概览约 1.2 KB」：那个数字是在一个 35 张表的库上量的，
         * 不是保证。4000 字符大约装得下 80-100 行「表名 + 注释 + 一句语义」，覆盖绝大多数库；
         * 超了就按行截断并<b>在文本里明说截了</b>——静默截断比不注入更糟，
         * 一份被悄悄砍掉一半的清单在模型看来和完整清单没有任何区别，它会对着列表里没有的表
         * 说「系统里没有这张表」。
         *
         * <p>配成 {@code <= 0} 等同于关掉（{@code enabled=false}）。
         */
        private int maxChars = 4000;
    }
}
