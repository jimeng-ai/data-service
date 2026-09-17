package com.jimeng.dataserver.ai.connector.generation.precondition;

import com.jimeng.dataserver.ai.connector.generation.AgentPathPrecondition;
import com.jimeng.dataserver.ai.connector.generation.InterruptedBatchPolicy;
import com.jimeng.dataserver.ai.connector.generation.PreconditionVerdict;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** agent 专用 LLM 四项与回调地址配置。 */
@Component
@Order(5)
public class AgentConfigPrecondition implements AgentPathPrecondition {

    private static final List<String> FORBIDDEN_MODEL_PARTS = List.of("claude", "opus", "sonnet", "haiku");

    private final ConnectorProperties properties;

    public AgentConfigPrecondition(ConnectorProperties properties) {
        this.properties = properties;
    }

    @Override
    public PreconditionVerdict check(ConnectorView connector) {
        ConnectorProperties.SemanticAgent agent = properties.getSemantic().getAgent();
        ConnectorProperties.SemanticAgentLlm llm = agent == null ? null : agent.getLlm();
        boolean configured = agent != null
                && notBlank(agent.getCallbackBaseUrl())
                && llm != null
                && notBlank(llm.getBaseUrl())
                && notBlank(llm.getAuthToken())
                && notBlank(llm.getModel())
                && notBlank(llm.getAuthScheme())
                && allowedModel(llm.getModel());
        return configured
                ? PreconditionVerdict.allowed()
                : PreconditionVerdict.rejected(false, "agent 专用模型或回调地址未配置");
    }

    @Override
    public InterruptedBatchPolicy onInterruptedBatch() {
        return InterruptedBatchPolicy.SUPERSEDE;
    }

    private static boolean allowedModel(String model) {
        String lower = model.toLowerCase(Locale.ROOT);
        return FORBIDDEN_MODEL_PARTS.stream().noneMatch(lower::contains);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
