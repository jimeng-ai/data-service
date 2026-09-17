package com.jimeng.dataserver.ai.connector.generation.precondition;

import com.jimeng.dataserver.ai.agent.exec.config.AgentSandboxProperties;
import com.jimeng.dataserver.ai.connector.generation.AgentPathPrecondition;
import com.jimeng.dataserver.ai.connector.generation.InterruptedBatchPolicy;
import com.jimeng.dataserver.ai.connector.generation.PreconditionVerdict;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** sandbox 地址与内部鉴权 token 必须都配置。 */
@Component
@Order(4)
public class SandboxConfiguredPrecondition implements AgentPathPrecondition {

    private final AgentSandboxProperties sandbox;

    public SandboxConfiguredPrecondition(AgentSandboxProperties sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public PreconditionVerdict check(ConnectorView connector) {
        boolean configured = notBlank(sandbox.getBaseUrl()) && notBlank(sandbox.getServiceToken());
        return configured
                ? PreconditionVerdict.allowed()
                : PreconditionVerdict.rejected(false, "sandbox 未配置");
    }

    @Override
    public InterruptedBatchPolicy onInterruptedBatch() {
        return InterruptedBatchPolicy.SUPERSEDE;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
