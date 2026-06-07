package com.jimeng.dataserver.ai.run;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 本实例在途生成的注册表（runId → 句柄）。单实例下「不在表里」即「不在跑」。 */
@Component
public class RunRegistry {

    private final ConcurrentHashMap<String, RunHandle> runs = new ConcurrentHashMap<>();

    public void register(RunHandle handle) {
        runs.put(handle.getRunId(), handle);
    }

    public RunHandle get(String runId) {
        return runId == null ? null : runs.get(runId);
    }

    /** 取出并移除（finalize 用）；返回 null 表示已被移除，调用方据此保证 finalize 幂等。 */
    public RunHandle remove(String runId) {
        return runId == null ? null : runs.remove(runId);
    }

    public boolean isLive(String runId) {
        return runId != null && runs.containsKey(runId);
    }

    public Set<String> liveRunIds() {
        return runs.keySet();
    }
}
