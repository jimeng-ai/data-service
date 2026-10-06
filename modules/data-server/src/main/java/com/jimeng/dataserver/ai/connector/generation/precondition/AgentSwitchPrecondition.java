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
        // silent：开关关着时走单次推导是配置出来的常态，不是降级，管理台的说明里不写这句内部话。
        // 原因照写，trigger 会打进日志——「agent 生成一直没开」只能从那里看出来（2026-10 就是这么发现的）。
        return properties.getSemantic().getAgent().isEnabled()
                ? PreconditionVerdict.allowed()
                : PreconditionVerdict.rejected(true, "agent 生成未开启");
    }

    @Override
    public InterruptedBatchPolicy onInterruptedBatch() {
        return InterruptedBatchPolicy.SUPERSEDE;
    }
}
