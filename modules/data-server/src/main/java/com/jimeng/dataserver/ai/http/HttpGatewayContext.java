package com.jimeng.dataserver.ai.http;

import java.util.List;

/**
 * 一次网关调用的身份与授权。对话平面由执行器从线程上下文组装；沙箱平面由回调接口从运行凭证与 Redis 登记组装——
 * 请求里自带的任何身份字段一概不用（§4.1）。
 */
public record HttpGatewayContext(String tenantId, Long agentId, String runId, Plane plane, List<SkillGrant> grants) {

    /** 决定回包上限：工具 2 MB、脚本 50 MB。 */
    public enum Plane { TOOL, SCRIPT }

    public HttpGatewayContext {
        grants = grants == null ? List.of() : List.copyOf(grants);
    }
}
