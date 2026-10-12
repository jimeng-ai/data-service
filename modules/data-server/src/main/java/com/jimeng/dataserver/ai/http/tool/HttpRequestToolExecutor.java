package com.jimeng.dataserver.ai.http.tool;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.http.HttpGateway;
import com.jimeng.dataserver.ai.http.HttpGatewayContext;
import com.jimeng.dataserver.ai.http.HttpGatewayProperties;
import com.jimeng.dataserver.ai.http.SkillGrant;
import com.jimeng.dataserver.ai.skill.service.ActivatedSkills;
import com.jimeng.dataserver.ai.skill.service.SkillToolExecutor;
import com.jimeng.dataserver.ai.skill.source.AiSkillToolPackage;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 对话平面的 http_request（设计文档 §4.1 第一行）。「本次可用的 skill」= 本轮已激活、且有 HTTP 声明的企业 skill；
 * 没激活的 skill 里声明的 key，这里拿不到。网关从不抛异常，所以这里也不抛：错误以 gateway_error 交给模型。
 */
@Component
@RequiredArgsConstructor
public class HttpRequestToolExecutor implements SkillToolExecutor {

    private final HttpGateway gateway;
    private final HttpGatewayProperties props;

    @Override
    public boolean supports(String toolName) {
        return HttpRequestToolDefinition.NAME.equals(toolName);
    }

    @Override
    public Object execute(String toolName, Map<String, Object> input) {
        List<SkillGrant> grants = ActivatedSkills.current().stream()
                .filter(p -> p instanceof AiSkillToolPackage a && a.getManifest().hasHttp())
                .map(p -> ((AiSkillToolPackage) p).toGrant())
                .toList();
        if (grants.isEmpty()) {
            return HttpToolResults.rejected(403, "no_http_skill", "当前没有激活带外部接口声明的技能：先激活对应的技能再调用");
        }
        HttpToolInput in;
        try {
            in = HttpToolInput.parse(input, props.getToolRequestMaxBytes());
        } catch (IllegalArgumentException e) {
            return HttpToolResults.rejected(400, "bad_request", e.getMessage());
        }
        AgentRuntimeView agent = AgentContext.get();
        HttpGatewayContext ctx = new HttpGatewayContext(TenantContext.get(), agent == null ? null : agent.getAgentId(),
                MDC.get("connectionId"), HttpGatewayContext.Plane.TOOL, grants);
        return HttpToolResults.toModelPayload(gateway.executeTool(ctx, in.toRequest(), in.asText()), false, props.getToolTextMaxChars());
    }
}
