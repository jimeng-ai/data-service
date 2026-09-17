package com.jimeng.dataserver.ai.connector.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticReasonCodeTest {

    @Test
    @DisplayName("批次原因码覆盖设计全集且 name 可直接落 varchar(32)")
    void generationCodes() {
        Set<String> expected = Set.of(
                "HEARTBEAT_LOST", "SHUTDOWN", "INTERNAL_ERROR", "CONNECTION_DISABLED",
                "AGENT_DISABLED", "TOKEN_CAP", "CLI_BUDGET", "GAVE_UP_RATIO", "SNAPSHOT_CHURN",
                "SANDBOX_REJECTED", "SANDBOX_AUTH", "SANDBOX_UNAVAILABLE", "SANDBOX_BUSY",
                "NO_CALLBACK", "NO_PROGRESS", "CLAIM_BUSY", "MODEL_MISMATCH", "NO_OUTPUT",
                "EMPTY_REPLACE", "SNAPSHOT_NOT_APPLICABLE", "SNAPSHOT_REFRESH_FAILED",
                "CONNECTION_DELETED", "SUPERSEDED");

        Set<String> actual = Arrays.stream(GenerationReasonCode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertEquals(expected, actual);
        assertTrue(actual.stream().allMatch(code -> code.length() <= 32));
    }

    @Test
    @DisplayName("表级原因码覆盖设计全集且 name 可直接落 varchar(32)")
    void tableCodes() {
        Set<String> expected = Set.of("STRUCTURE_CHANGED", "STRUCTURE_UNSTABLE", "SUBMIT_LIMIT",
                "DISPATCH_LIMIT", "AGENT_SKIPPED", "NOT_DESCRIBED", "NAME_TOO_LONG");

        Set<String> actual = Arrays.stream(TableReasonCode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertEquals(expected, actual);
        assertTrue(actual.stream().allMatch(code -> code.length() <= 32));
    }
}
