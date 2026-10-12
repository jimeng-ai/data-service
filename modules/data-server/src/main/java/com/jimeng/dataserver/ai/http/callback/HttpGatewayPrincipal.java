package com.jimeng.dataserver.ai.http.callback;

/** 过滤器验签后的可信身份。 */
public record HttpGatewayPrincipal(String tenantId, Long userId, Long agentId, String runId) {}
