// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit contract for the destructive-boundary validation in the live Airflow verifier. */
@Tag("unit")
final class AirflowPipelineVerifierJobTest {

    private static final String ISOLATED_TABLE =
            "stratus.bronze.airflow_pipeline_probe_20260822t120000z";

    @Test
    void acceptsOnlyTheIsolatedBronzeProbeNamespace() {
        assertEquals(ISOLATED_TABLE,
                AirflowPipelineVerifierJob.requireIsolatedTarget(ISOLATED_TABLE));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowPipelineVerifierJob.requireIsolatedTarget(
                        "stratus.bronze.customers"));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowPipelineVerifierJob.requireIsolatedTarget(
                        "stratus.silver.airflow_pipeline_probe_20260822t120000z"));
    }

    @Test
    void escapesPipelineRunIdsBeforeUsingThemAsSqlLiterals() {
        assertEquals("manual__O''Brien",
                AirflowPipelineVerifierJob.sqlLiteral("manual__O'Brien"));
    }
}
