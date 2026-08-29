// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

import java.util.Map;
import java.util.Set;

/** One caller-correlated DAG run and its complete observable acceptance contract. */
public record AirflowScenario(
        String name,
        String dagId,
        String runId,
        String expectedRunState,
        Map<String, Object> configuration,
        Map<String, String> expectedTaskStates,
        int expectedSparkApplications) {

    private static final Set<String> TERMINAL_RUN_STATES = Set.of("success", "failed");

    public AirflowScenario {
        name = required(name, "name");
        dagId = required(dagId, "dagId");
        runId = required(runId, "runId");
        expectedRunState = required(expectedRunState, "expectedRunState");
        if (!TERMINAL_RUN_STATES.contains(expectedRunState)) {
            throw new IllegalArgumentException(
                    "expectedRunState must be success or failed: " + expectedRunState);
        }
        if (configuration == null) {
            throw new IllegalArgumentException("configuration is required");
        }
        configuration = Map.copyOf(configuration);
        if (expectedTaskStates == null || expectedTaskStates.isEmpty()) {
            throw new IllegalArgumentException("expectedTaskStates must name every expected task");
        }
        expectedTaskStates.forEach((taskId, state) -> {
            required(taskId, "expectedTaskStates task ID");
            required(state, "expectedTaskStates state");
        });
        expectedTaskStates = Map.copyOf(expectedTaskStates);
        if (expectedSparkApplications < 0) {
            throw new IllegalArgumentException("expectedSparkApplications must not be negative");
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
