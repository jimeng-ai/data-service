package com.jimeng.dataserver.ai.connector.generation.precondition;

import com.jimeng.dataserver.ai.connector.generation.AgentPathPrecondition;
import com.jimeng.dataserver.ai.connector.generation.InterruptedBatchPolicy;
import com.jimeng.dataserver.ai.connector.generation.PreconditionVerdict;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** agent 生成独立开关。 */
@Component
@Order(3)
public class AgentSwitchPrecondition implements AgentPathPrecondition {

    private final ConnectorProperties properties;

    public AgentSwitchPrecondition(ConnectorProperties properties) {
        this.properties = properties;
    }

    @Override
    public PreconditionVerdict check(ConnectorView connector) {
        return properties.getSemantic().getAgent().isEnabled()
                ? PreconditionVerdict.allowed()
                : PreconditionVerdict.rejected(false, "agent 生成未开启，走单次推导");
    }

    @Override
    public InterruptedBatchPolicy onInterruptedBatch() {
        return InterruptedBatchPolicy.SUPERSEDE;
    }
}
