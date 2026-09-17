package com.jimeng.dataserver.ai.connector.generation.precondition;

import com.jimeng.dataserver.ai.connector.generation.AgentPathPrecondition;
import com.jimeng.dataserver.ai.connector.generation.InterruptedBatchPolicy;
import com.jimeng.dataserver.ai.connector.generation.PreconditionVerdict;
import com.jimeng.dataserver.ai.connector.service.ConnectorView;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 连接实例的实测能力必须包含 DESCRIBE。 */
@Component
@Order(2)
public class DescribeCapabilityPrecondition implements AgentPathPrecondition {

    @Override
    public PreconditionVerdict check(ConnectorView connector) {
        boolean describe = connector != null && connector.getCapabilities() != null
                && connector.getCapabilities().contains("DESCRIBE");
        return describe ? PreconditionVerdict.allowed() : PreconditionVerdict.rejected(true, null);
    }

    @Override
    public InterruptedBatchPolicy onInterruptedBatch() {
        return InterruptedBatchPolicy.SUPERSEDE;
    }
}
