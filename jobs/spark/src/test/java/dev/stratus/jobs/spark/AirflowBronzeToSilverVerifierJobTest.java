// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit tests for the verifier's destructive cleanup and expected-outcome boundaries. */
@Tag("unit")
final class AirflowBronzeToSilverVerifierJobTest {

    private static final String TOKEN = "20260823t120000z";
    private static final String ISOLATED_SOURCE =
            "stratus.bronze.airflow_pipeline_probe_" + TOKEN;
    private static final String ISOLATED_TARGET =
            "stratus.silver.airflow_pipeline_probe_" + TOKEN;

    @Test
    void cleanupAcceptsOnlyGeneratedProbeTablesInTheirExactZones() {
        assertEquals(ISOLATED_SOURCE,
                AirflowBronzeToSilverVerifierJob.requireIsolatedSource(ISOLATED_SOURCE));
        assertEquals(ISOLATED_TARGET,
                AirflowBronzeToSilverVerifierJob.requireIsolatedTarget(ISOLATED_TARGET));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowBronzeToSilverVerifierJob.requireIsolatedSource(
                        "stratus.bronze.customers"));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowBronzeToSilverVerifierJob.requireIsolatedSource(ISOLATED_TARGET));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowBronzeToSilverVerifierJob.requireIsolatedTarget(ISOLATED_SOURCE));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowBronzeToSilverVerifierJob.requireIsolatedTarget(
                        "stratus.silver.customers"));
    }

    @Test
    void expectedOutcomeIsClosedToTheTwoVerifiedBehaviours() {
        assertEquals("accepted", AirflowBronzeToSilverVerifierJob.requireOutcome("accepted"));
        assertEquals("blocked", AirflowBronzeToSilverVerifierJob.requireOutcome("blocked"));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowBronzeToSilverVerifierJob.requireOutcome("skipped"));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowBronzeToSilverVerifierJob.requireOutcome("ACCEPTED"));
    }

    @Test
    void cleanupEscapesQualityRunIdsBeforeEmbeddingSqlLiterals() {
        assertEquals("manual__O''Brien",
                AirflowBronzeToSilverVerifierJob.sqlLiteral("manual__O'Brien"));
    }
}
