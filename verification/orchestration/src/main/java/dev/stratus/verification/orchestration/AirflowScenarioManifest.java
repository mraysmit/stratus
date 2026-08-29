// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;

/** Decodes and validates the ordered scenarios consumed by live Airflow acceptance. */
public final class AirflowScenarioManifest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AirflowScenarioManifest() {
    }

    public static List<AirflowScenario> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("The Airflow scenario manifest is required");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "The Airflow scenario manifest must contain canonical Base64", failure);
        }

        List<AirflowScenario> scenarios;
        try {
            scenarios = JSON.readValue(new String(decoded, StandardCharsets.UTF_8),
                    new TypeReference<>() { });
        } catch (IOException failure) {
            throw new IllegalArgumentException("The Airflow scenario manifest is invalid", failure);
        }
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("The Airflow scenario manifest must not be empty");
        }

        var names = new HashSet<String>();
        var dagRuns = new HashSet<String>();
        for (AirflowScenario scenario : scenarios) {
            if (!names.add(scenario.name())) {
                throw new IllegalArgumentException(
                        "Duplicate Airflow scenario name: " + scenario.name());
            }
            String dagRun = scenario.dagId() + "\u0000" + scenario.runId();
            if (!dagRuns.add(dagRun)) {
                throw new IllegalArgumentException("Duplicate Airflow DAG run: "
                        + scenario.dagId() + "/" + scenario.runId());
            }
        }
        return List.copyOf(scenarios);
    }
}
