package com.jimeng.dataserver.ai.connector.agent.callback;

import com.jimeng.common.core.tenant.TenantContext;
import com.jimeng.dataserver.ai.agent.dto.AgentRuntimeView;
import com.jimeng.dataserver.ai.agent.runtime.AgentContext;
import com.jimeng.dataserver.ai.connector.tool.ConnectorToolExecutor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 对话 agent 的连接器工具<b>宿主回调面</b>。
 *
 * <h3>为什么真正的执行留在宿主</h3>
 * 带附件的会话整轮路由到沙箱平面，而沙箱平面一个 {@code conn_*} 工具都没有——Agent 查不了数据库，
 * 而且<b>不报错</b>，模型只会拿历史数据讲或者自己编。修法是沙箱侧只放工具代理，执行仍在这里，
 * 经本控制器调既有的 {@link ConnectorToolExecutor}。于是凭据解密、{@code ReadOnlySqlGuard} /
 * {@code WriteSqlGuard}、租户隔离、审计<b>一行都不用动</b>，也不会分叉出第二套护栏。
 *
 * <h3>★ 路径段就是工具名，就是白名单</h3>
 * 八个方法各占一个固定 path，刻意<b>不</b>写成 {@code /{toolName}} 再转发：
 * 有了 {@code @PathVariable} 就得在方法体里再维护一份白名单，而那份白名单和路由必然会分叉。
 * 这样写，Spring 的路由表本身就是白名单，多一个工具就必须多一个方法。
 *
 * <p>返回裸 Map，{@code GlobalResponseHandler} 统一包 {@code {success,respCode,respMsg,data}} 信封。
 */
@RestController
@RequestMapping("/data/internal/connector-agent")
@RequiredArgsConstructor
public class ConnectorAgentCallbackController {

    private final ConnectorToolExecutor connectorToolExecutor;

    @PostMapping("/conn_list")
    public Object connList(HttpServletRequest request,
                           @RequestBody(required = false) ConnectorToolCallRequest body) {
        return invoke(request, ConnectorToolExecutor.TOOL_LIST, body);
    }

    @PostMapping("/conn_catalog")
    public Object connCatalog(HttpServletRequest request,
                              @RequestBody(required = false) ConnectorToolCallRequest body) {
        return invoke(request, ConnectorToolExecutor.TOOL_CATALOG, body);
    }

    @PostMapping("/conn_describe")
    public Object connDescribe(HttpServletRequest request,
                               @RequestBody(required = false) ConnectorToolCallRequest body) {
        return invoke(request, ConnectorToolExecutor.TOOL_DESCRIBE, body);
    }

    @PostMapping("/conn_query")
    public Object connQuery(HttpServletRequest request,
                            @RequestBody(required = false) ConnectorToolCallRequest body) {
        return invoke(request, ConnectorToolExecutor.TOOL_QUERY, body);
    }

    @PostMapping("/conn_invoke")
    public Object connInvoke(HttpServletRequest request,
                             @RequestBody(required = false) ConnectorToolCallRequest body) {
        return invoke(request, ConnectorToolExecutor.TOOL_INVOKE, body);
    }

    @PostMapping("/conn_execute")
    public Object connExecute(HttpServletRequest request,
                              @RequestBody(required = false) ConnectorToolCallRequest body) {
        return invoke(request, ConnectorToolExecutor.TOOL_EXECUTE, body);
    }

    @PostMapping("/conn_define_metric")
    public Object connDefineMetric(HttpServletRequest request,
                                   @RequestBody(required = false) ConnectorToolCallRequest body) {
        return invoke(request, ConnectorToolExecutor.TOOL_DEFINE_METRIC, body);
    }

    @PostMapping("/conn_annotate")
    public Object connAnnotate(HttpServletRequest request,
                               @RequestBody(required = false) ConnectorToolCallRequest body) {
        return invoke(request, ConnectorToolExecutor.TOOL_ANNOTATE, body);
    }

    /**
     * 按 token 绑定的那个 Agent 的身份执行，写法与 {@code PendingWriteService.executeAsSubmittingAgent} 一致。
     *
     * <p>{@code ConnectorGateway} 的授权按 {@link AgentContext} 判（查 {@code agent_connection}，为空即拒），
     * 而回调进来的是一条干净的 HTTP 请求线程，上面没有 AgentContext，直接调网关会被 fail-closed 拒掉。
     *
     * <p><b>★ 绝不能改用 {@code executeAsPlatform}</b>：那条路跳过 agent 授权，是给平台后台任务
     * （刷结构快照、语义层采样）用的。用它接回调，等于让沙箱得到一条「不经 agent_connection 授权
     * 就能碰客户生产库」的通道。
     *
     * <p><b>★ tenantId 取 {@code TenantContext}，不取请求体</b>：请求体来自沙箱里模型写的代码。
     * TenantContext 由 {@code TenantContextFilter} 按网关注入的 {@code X-Tenant-Id} 设置，
     * 而那个头又已在过滤器里与 token 的 {@code tenant_id} 交叉核对过。
     *
     * <p><b>★ finally 必须恢复 previous</b>：这是请求线程池的线程，留下 ThreadLocal 会污染下一个请求，
     * 表现为<b>跨租户静默越权、没有任何报错</b>。
     */
    private Object invoke(HttpServletRequest request, String toolName, ConnectorToolCallRequest body) {
        ConnectorAgentPrincipal principal = ConnectorAgentTokens.requirePrincipal(request);

        AgentRuntimeView previous = AgentContext.get();
        AgentContext.set(AgentRuntimeView.builder()
                .agentId(principal.agentId())
                .tenantId(TenantContext.required())
                .build());
        try {
            return connectorToolExecutor.execute(toolName, body == null ? Map.of() : body.getInput());
        } finally {
            if (previous == null) {
                AgentContext.clear();
            } else {
                AgentContext.set(previous);
            }
        }
    }
}
