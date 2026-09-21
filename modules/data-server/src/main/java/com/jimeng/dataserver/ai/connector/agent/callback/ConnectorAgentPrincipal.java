package com.jimeng.dataserver.ai.connector.agent.callback;

/**
 * 已通过 {@link ConnectorAgentScopeFilter} 校验的对话 agent 回调身份。
 *
 * <p>控制器不得从请求体或客户端头重新读取这四项；只使用这个主体，否则请求体里的同名字段
 * 就能把 token 已绑定的 agent 和运行替换掉。
 *
 * <h3>★ 为什么这里没有「被授权的连接 id 集合」</h3>
 * 把授权连接写进 token 就是<b>一份快照</b>：超管在对话进行中撤销了某条连接的授权，
 * 这枚 token 在整个 TTL 内还会拿着旧快照继续读写，要等到下一轮才生效。
 * 所以连接归属一律由 {@code ConnectorGateway} 按 {@code agent_connection} <b>实时查</b>，
 * token 只回答「你是哪个 agent」，不回答「你能碰哪些连接」。
 */
public record ConnectorAgentPrincipal(
        String tenantId,
        Long userId,
        Long agentId,
        String runId) {
}
