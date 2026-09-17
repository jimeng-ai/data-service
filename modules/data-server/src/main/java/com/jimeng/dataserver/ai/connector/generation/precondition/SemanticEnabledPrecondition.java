package com.jimeng.dataserver.ai.connector.generation.precondition;

import com.jimeng.dataserver.ai.connector.generation.AgentPathPrecondition;
import com.jimeng.dataserver.ai.connector.generation.InterruptedBatchPolicy;
import com.jimeng.dataserver.ai.connector.generation.PreconditionVerdict;
import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 语义层总开关。关闭不算降级，由单次推导自己写「已关闭」。 */
@Component
@Order(1)
public class SemanticEnabledPrecondition implements AgentPathPrecondition {

    private final ConnectorProperties properties;

    public SemanticEnabledPrecondition(ConnectorProperties properties) {
        this.properties = properties;
    }

    @Override
    public PreconditionVerdict check(ConnectorView connector) {
        return properties.getSemantic().isEnabled()
                ? PreconditionVerdict.allowed()
                : PreconditionVerdict.rejected(true, null);
    }

    @Override
    public InterruptedBatchPolicy onInterruptedBatch() {
        return InterruptedBatchPolicy.LEAVE;
    }
}
