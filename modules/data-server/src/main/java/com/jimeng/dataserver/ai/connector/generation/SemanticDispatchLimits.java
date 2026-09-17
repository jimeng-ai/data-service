package com.jimeng.dataserver.ai.connector.generation;

import com.jimeng.dataserver.ai.connector.runtime.ConnectorProperties;
import lombok.extern.slf4j.Slf4j;

/** Immutable, clamped per-slice configuration. Its string form never contains credentials. */
@Slf4j
public final class SemanticDispatchLimits {

    private final String callbackBaseUrl;
    private final String llmBaseUrl;
    private final String llmAuthToken;
    private final String llmModel;
    private final String llmAuthScheme;
    private final int wallClockSeconds;
    private final int maxTurns;
    private final double maxBudgetUsd;
    private final int busyMaxRetries;
    private final int retryBackoffSeconds;

    private SemanticDispatchLimits(String callbackBaseUrl,
                                   String llmBaseUrl,
                                   String llmAuthToken,
                                   String llmModel,
                                   String llmAuthScheme,
                                   int wallClockSeconds,
                                   int maxTurns,
                                   double maxBudgetUsd,
                                   int busyMaxRetries,
                                   int retryBackoffSeconds) {
        this.callbackBaseUrl = callbackBaseUrl;
        this.llmBaseUrl = llmBaseUrl;
        this.llmAuthToken = llmAuthToken;
        this.llmModel = llmModel;
        this.llmAuthScheme = llmAuthScheme;
        this.wallClockSeconds = wallClockSeconds;
        this.maxTurns = maxTurns;
        this.maxBudgetUsd = maxBudgetUsd;
        this.busyMaxRetries = busyMaxRetries;
        this.retryBackoffSeconds = retryBackoffSeconds;
    }

    public static SemanticDispatchLimits from(ConnectorProperties.SemanticAgent properties, int tableCount) {
        if (properties == null || properties.getLlm() == null || tableCount < 1) {
            throw new IllegalArgumentException("semantic agent config and a non-empty slice are required");
        }
        int wall = clamp("slice-wall-clock-sec", properties.getSliceWallClockSec(), 120, 1200);
        int turnsPerTable = clamp("slice-max-turns-per-table",
                properties.getSliceMaxTurnsPerTable(), 2, 6);
        int maxTurns = Math.min(120, 20 + tableCount * turnsPerTable);
        double budget = properties.getSliceMaxBudgetUsd();
        if (!(budget > 0.0) || !Double.isFinite(budget)) {
            log.warn("connector.semantic.agent.slice-max-budget-usd={} 非法，按默认 50", budget);
            budget = 50.0;
        }
        int busyRetries = clamp("busy-max-retries", properties.getBusyMaxRetries(), 1, 20);
        int backoff = clamp("retry-backoff-sec", properties.getRetryBackoffSec(), 1, 300);
        ConnectorProperties.SemanticAgentLlm llm = properties.getLlm();
        return new SemanticDispatchLimits(properties.getCallbackBaseUrl(), llm.getBaseUrl(), llm.getAuthToken(),
                llm.getModel(), llm.getAuthScheme(), wall, maxTurns, budget, busyRetries, backoff);
    }

    private static int clamp(String name, int value, int min, int max) {
        int clamped = Math.max(min, Math.min(max, value));
        if (value != clamped) {
            log.warn("connector.semantic.agent.{}={} 越界，夹紧为 {}", name, value, clamped);
        }
        return clamped;
    }

    public String callbackBaseUrl() {
        return callbackBaseUrl;
    }

    public String llmBaseUrl() {
        return llmBaseUrl;
    }

    public String llmAuthToken() {
        return llmAuthToken;
    }

    public String llmModel() {
        return llmModel;
    }

    public String llmAuthScheme() {
        return llmAuthScheme;
    }

    public int wallClockSeconds() {
        return wallClockSeconds;
    }

    public int maxTurns() {
        return maxTurns;
    }

    public double maxBudgetUsd() {
        return maxBudgetUsd;
    }

    public int busyMaxRetries() {
        return busyMaxRetries;
    }

    public int retryBackoffSeconds() {
        return retryBackoffSeconds;
    }

    @Override
    public String toString() {
        return "SemanticDispatchLimits{" +
                "llmModel='" + llmModel + '\'' +
                ", wallClockSeconds=" + wallClockSeconds +
                ", maxTurns=" + maxTurns +
                ", maxBudgetUsd=" + maxBudgetUsd +
                ", busyMaxRetries=" + busyMaxRetries +
                ", retryBackoffSeconds=" + retryBackoffSeconds +
                '}';
    }
}
