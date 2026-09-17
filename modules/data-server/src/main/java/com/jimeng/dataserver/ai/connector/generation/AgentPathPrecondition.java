package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.connector.service.ConnectorView;

/** agent 生成路径的廉价前置条件。 */
public interface AgentPathPrecondition {

    PreconditionVerdict check(ConnectorView connector);

    /** 本条不通过且已有中断批次时，门面应采取的处置。 */
    InterruptedBatchPolicy onInterruptedBatch();
}
