// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Unit tests for the silver-to-gold verifier's exact cleanup and outcome boundaries. */
@Tag("unit")
final class AirflowSilverToGoldVerifierJobTest {

    private static final String TOKEN = "20260823t120000z";
    private static final String BRONZE = "stratus.bronze.airflow_pipeline_probe_" + TOKEN;
    private static final String SILVER = "stratus.silver.airflow_pipeline_probe_" + TOKEN;
    private static final String GOLD = "stratus.gold.airflow_pipeline_probe_" + TOKEN;

    @Test
    void cleanupAcceptsOnlyGeneratedProbeTablesInTheirExactZones() {
        assertEquals(BRONZE, AirflowSilverToGoldVerifierJob.requireIsolatedBronze(BRONZE));
        assertEquals(SILVER, AirflowSilverToGoldVerifierJob.requireIsolatedSilver(SILVER));
        assertEquals(GOLD, AirflowSilverToGoldVerifierJob.requireIsolatedGold(GOLD));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowSilverToGoldVerifierJob.requireIsolatedBronze(SILVER));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowSilverToGoldVerifierJob.requireIsolatedSilver(GOLD));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowSilverToGoldVerifierJob.requireIsolatedGold(
                        "stratus.gold.customer_summary"));
    }

    @Test
    void expectedOutcomeIsClosedToTheTwoVerifiedBehaviours() {
        assertEquals("accepted", AirflowSilverToGoldVerifierJob.requireOutcome("accepted"));
        assertEquals("blocked", AirflowSilverToGoldVerifierJob.requireOutcome("blocked"));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowSilverToGoldVerifierJob.requireOutcome("ACCEPTED"));
    }

    @Test
    void cleanupEscapesEveryRunIdBeforeEmbeddingSqlLiterals() {
        assertEquals("manual__O''Brien",
                AirflowSilverToGoldVerifierJob.sqlLiteral("manual__O'Brien"));
    }
}
