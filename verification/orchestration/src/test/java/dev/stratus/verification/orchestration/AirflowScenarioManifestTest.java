// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

final class AirflowScenarioManifestTest {

    @Test
    void decodesOrderedSchedulerScenariosWithExactTaskStatesAndSparkBudgets() {
        String encoded = encode("""
                [
                  {
                    "name": "accepted-landing",
                    "dagId": "stratus_landing_to_bronze",
                    "runId": "acceptance-run",
                    "expectedRunState": "success",
                    "configuration": {"bronze_table": "stratus.bronze.probe"},
                    "expectedTaskStates": {
                      "wait_for_source_file": "success",
                      "run_ingestion": "success",
                      "run_bronze_quality": "success"
                    },
                    "expectedSparkApplications": 2
                  },
                  {
                    "name": "blocked-silver",
                    "dagId": "stratus_silver_to_gold",
                    "runId": "acceptance-run",
                    "expectedRunState": "failed",
                    "configuration": {"silver_table": "stratus.silver.blocked_probe"},
                    "expectedTaskStates": {
                      "run_silver_quality_for_gold": "success",
                      "evaluate_silver_promotion": "failed",
                      "run_gold_materialisation": "upstream_failed",
                      "run_gold_quality": "upstream_failed"
                    },
                    "expectedSparkApplications": 1
                  }
                ]
                """);

        var scenarios = AirflowScenarioManifest.decode(encoded);

        assertEquals(2, scenarios.size());
        assertEquals("accepted-landing", scenarios.getFirst().name());
        assertEquals(2, scenarios.getFirst().expectedSparkApplications());
        assertEquals("upstream_failed",
                scenarios.getLast().expectedTaskStates().get("run_gold_materialisation"));
        assertEquals(3, scenarios.stream()
                .mapToInt(AirflowScenario::expectedSparkApplications).sum());
    }

    @Test
    void rejectsDuplicateScenarioNames() {
        String encoded = encode("""
                [
                  {
                    "name": "duplicate",
                    "dagId": "dag-a",
                    "runId": "run-a",
                    "expectedRunState": "success",
                    "configuration": {},
                    "expectedTaskStates": {"task": "success"},
                    "expectedSparkApplications": 0
                  },
                  {
                    "name": "duplicate",
                    "dagId": "dag-b",
                    "runId": "run-b",
                    "expectedRunState": "success",
                    "configuration": {},
                    "expectedTaskStates": {"task": "success"},
                    "expectedSparkApplications": 0
                  }
                ]
                """);

        assertThrows(IllegalArgumentException.class,
                () -> AirflowScenarioManifest.decode(encoded));
    }

    @Test
    void rejectsAScenarioWithoutExactExpectedTaskStates() {
        String encoded = encode("""
                [{
                  "name": "incomplete",
                  "dagId": "dag",
                  "runId": "run",
                  "expectedRunState": "success",
                  "configuration": {},
                  "expectedTaskStates": {},
                  "expectedSparkApplications": 0
                }]
                """);

        assertThrows(IllegalArgumentException.class,
                () -> AirflowScenarioManifest.decode(encoded));
    }

    @Test
    void rejectsNegativeSparkApplicationBudgets() {
        String encoded = encode("""
                [{
                  "name": "invalid-budget",
                  "dagId": "dag",
                  "runId": "run",
                  "expectedRunState": "failed",
                  "configuration": {},
                  "expectedTaskStates": {"task": "failed"},
                  "expectedSparkApplications": -1
                }]
                """);

        assertThrows(IllegalArgumentException.class,
                () -> AirflowScenarioManifest.decode(encoded));
    }

    private static String encode(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
