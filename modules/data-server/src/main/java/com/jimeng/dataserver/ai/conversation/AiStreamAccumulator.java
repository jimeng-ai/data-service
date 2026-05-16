package com.jimeng.dataserver.ai.conversation;

import java.util.Map;

public interface AiStreamAccumulator {
    void accumulateEvent(String eventType, String data);
    Map<String, Object> buildResponseMap();
    boolean hasToolUse();
    int getInputTokens();
    int getOutputTokens();
    String getRequestId();
    String toJson();
}
