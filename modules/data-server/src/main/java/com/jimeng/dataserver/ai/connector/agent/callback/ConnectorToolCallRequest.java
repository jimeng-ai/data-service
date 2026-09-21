package com.jimeng.dataserver.ai.connector.agent.callback;

import lombok.Data;

import java.util.Map;

/**
 * 连接器回调的请求体。
 *
 * <p>★ <b>刻意只有这一个字段</b>：任何身份维度（tenantId / agentId / userId / runId / connectorId）
 * 都不准出现在请求体里。沙箱侧跑的是模型写的代码，只要请求体里有一个身份字段，
 * 它就可能被填上别的值；而一旦服务端读了它，"token 绑定身份"这件事就作废了。
 * 身份只从 {@link ConnectorAgentTokens#requirePrincipal} 与 {@code TenantContext} 取。
 */
@Data
public class ConnectorToolCallRequest {

    /** 工具参数原样透传给 {@code ConnectorToolExecutor}，不在这一层解释。 */
    private Map<String, Object> input;
}
