package com.jimeng.dataserver.ai.connector.spi;

import java.util.Set;

/**
 * 连接器 SPI —— 「加一种客户系统类型」的<b>唯一</b>扩展点。
 *
 * <p>实现类只需回答五个问题，其余（租户隔离、凭据加解密、Agent 授权、权限级别、限流配额、
 * 审计留痕、健康探测、统一错误语义、资源保护）一概由框架代劳：
 *
 * <ol>
 *   <li><b>我是什么类型</b> —— {@link #kind()}</li>
 *   <li><b>我需要哪些参数</b> —— {@link #paramSpec()}。它同时驱动前端表单渲染和后端校验，
 *       <b>所以前端不需要为新类型写任何代码</b></li>
 *   <li><b>怎么连上我、怎么断开、怎么探活</b> —— {@link #open} 返回的 {@link ConnectorSession}</li>
 *   <li><b>我支持哪些能力</b> —— {@link #declaredCapabilities()}，以及在 session 上实现对应的
 *       {@code *Capable} 接口</li>
 *   <li><b>怎么验证这把凭据确实是只读的</b> —— {@link ConnectorSession#verifyReadOnly()}：
 *       试一个无害的写操作，<b>期望它失败</b></li>
 * </ol>
 *
 * <h3>验收标准（技术架构 §15）</h3>
 * 新增一种类型应当<b>只</b>做三件事：写一个实现类、把它变成 Spring bean、加一个驱动依赖。
 * 不应该改：前端、权限模型、审计、限流、已有连接器实现、上层技能、工具定义、隧道。
 * <b>如果某次新增类型迫使你改了上面任何一项，那是抽象切错了的信号——回头改抽象，不要加特例分支。</b>
 *
 * <h3>注册</h3>
 * 标 {@code @Component} 即可，{@code ConnectorRegistry} 按 {@link #kind()} 自动收集。
 * 重名会让应用<b>启动失败</b>（不是静默覆盖）。
 */
public interface Connector {

    /**
     * 稳定的类型标识，如 {@code MYSQL} / {@code HTTP} / {@code ELASTICSEARCH}。
     *
     * <p>它会落进 {@code connection.kind} 列，改名等于让存量行全部失配，所以<b>一旦发布不可更改</b>。
     * 刻意用字符串而非枚举：枚举会让「加一种类型」多出一处必改的文件，与上面的验收标准冲突。
     */
    String kind();

    /** 给人看的类型名，如「MySQL / 兼容 MySQL 协议的库」。前端类型下拉直接用它。 */
    String displayName();

    /** 这种类型需要哪些参数。 */
    ParamSpec paramSpec();

    /**
     * 这种类型<b>理论上</b>支持哪些能力。
     *
     * <p>注意这只是声明。实际可用能力必须靠 {@link ConnectorSession#probeCapabilities()}
     * 探测后回填——同一种类型在不同客户那里能力可能不同（账号没有读元数据的权限，
     * DESCRIBE 就得降级成「要人工录入结构」）。
     */
    Set<Capability> declaredCapabilities();

    /**
     * 建立一次会话。调用方负责 {@code close()}（框架会用 try-with-resources 包住）。
     *
     * @throws com.jimeng.dataserver.ai.connector.error.ConnectorException 连不上 / 凭据错 / 配置错
     */
    ConnectorSession open(ConnectorInstance instance);

    /**
     * 生成一段「在你自己的数据库上执行这个，就能给平台开一个刚好够用的账号」的脚本。
     *
     * <p><b>为什么这段方言知识必须落在连接器里，而不是前端拼字符串：</b>
     * MySQL 是 {@code GRANT SELECT ON `db`.*}；PostgreSQL 要
     * {@code GRANT SELECT ON ALL TABLES IN SCHEMA}，还得再管一次 {@code DEFAULT PRIVILEGES}
     * （否则客户之后新建的表平台一律看不见，表现成「加了张表怎么查不到」）；Oracle 又是另一套。
     * 连标识符引用都不一样（MySQL 反引号、PG 双引号）。前端既写不对也转义不对，
     * 而它<b>每加一种类型就得改一次</b>——这正是 {@link #paramSpec()} 驱动表单要消灭的那类改动。
     *
     * <p>所以约定与 paramSpec 相同：<b>新增类型时顺手写自己那段，前端零改动</b>。
     *
     * <p>实现方必须自己兑现的一件事：{@link GrantRequest} 里的库名 / 表名 / 账号名<b>全是用户输入</b>，
     * 拼进 SQL 之前必须校验 + 按方言引用。<b>校验不过要直接抛，不要试图转义了事</b>——
     * 生成一段带分号的「库名」，客户复制执行就等于替别人执行了一条语句，
     * 而这段脚本天然会被信任（是平台给的）。
     *
     * @return {@code null} 表示这种类型不提供脚本（例如 HTTP 连接器，授权发生在对方系统里，
     *         没有「一段 SQL」可给）。调用方要把它翻译成「本类型暂不提供」，而不是当成失败。
     */
    /**
     * 这条连接<b>指向哪个外部目标</b>的稳定标识，用于「这个库已经接过了」这类提醒。
     *
     * <h3>为什么需要它</h3>
     * {@code connection} 的唯一键是 {@code (tenant_id, name)}——<b>只管名字不重，不管指向哪</b>。
     * 同一个库建两条连接是完全合法的（一条只读给 Agent、一条可写走审批：一条连接 = 一份凭据
     * + 一套权限 + 一个出库档位），但语义层是按 {@code connector_id} 切的，于是同一个库会被
     * <b>推导两遍</b>，而且两份口径<b>不互通</b>——在 A 上定的口径，Agent 用 B 查时一个字都看不到，
     * 直接按默认算。这不报错，只是悄悄算出不一样的数。
     *
     * <h3>为什么只提醒、不拦截</h3>
     * 按连接切语义层是<b>对的</b>：语义层是「用这套凭据能看到什么」的产物，换一套凭据可见范围就变了。
     * 所以这里不改唯一键、不拒绝创建，只在建连时说一句，让人知道自己在做什么。
     *
     * @return 稳定标识；{@code null} = 本类型不判断重复（默认）。宁可漏提示，不可误提示。
     */
    default String targetIdentity(ConnectorInstance instance) {
        return null;
    }

    default GrantScript grantScript(GrantRequest req) {
        return null;
    }
}
