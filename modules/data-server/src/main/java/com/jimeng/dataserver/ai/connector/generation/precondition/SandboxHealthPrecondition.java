package com.jimeng.dataserver.ai.connector.generation.precondition;

import com.jimeng.dataserver.ai.connector.generation.AgentPathPrecondition;
import com.jimeng.dataserver.ai.connector.generation.InterruptedBatchPolicy;
import com.jimeng.dataserver.ai.connector.generation.PreconditionVerdict;
import com.jimeng.dataserver.ai.connector.generation.SandboxHealthProbe;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** sandbox 实时健康与 semantic-layer run profile 能力。 */
@Component
@Order(6)
public class SandboxHealthPrecondition implements AgentPathPrecondition {

    private final SandboxHealthProbe probe;

    public SandboxHealthPrecondition(SandboxHealthProbe probe) {
        this.probe = probe;
    }

    @Override
    public PreconditionVerdict check(ConnectorView connector) {
        SandboxHealthProbe.ProbeResult result = probe.probe();
        return result.healthy()
                ? PreconditionVerdict.allowed()
                : PreconditionVerdict.rejected(false, result.reason());
    }

    @Override
    public InterruptedBatchPolicy onInterruptedBatch() {
        return InterruptedBatchPolicy.KEEP;
    }
}
