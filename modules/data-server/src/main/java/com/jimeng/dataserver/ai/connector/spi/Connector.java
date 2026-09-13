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
}
