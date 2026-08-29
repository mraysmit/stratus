// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Locks the bounded fixture lifecycle used by focused silver-to-gold acceptance. */
final class AirflowSilverToGoldFixtureJobTest {

    private static final String ACCEPTED_SILVER =
            "stratus.silver.airflow_pipeline_probe_run_accepted";
    private static final String BLOCKED_SILVER =
            "stratus.silver.airflow_pipeline_probe_run_blocked";
    private static final String ACCEPTED_GOLD =
            "stratus.gold.airflow_pipeline_probe_run_accepted";
    private static final String BLOCKED_GOLD =
            "stratus.gold.airflow_pipeline_probe_run_blocked";

    @Test
    void modesAreClosedToPrepareAndCleanup() {
        assertEquals("prepare", AirflowSilverToGoldFixtureJob.requireMode("prepare"));
        assertEquals("cleanup", AirflowSilverToGoldFixtureJob.requireMode("cleanup"));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowSilverToGoldFixtureJob.requireMode("verify"));
    }

    @Test
    void everyMutableTableMustUseTheIsolatedProbePrefixInItsExactZone() {
        assertEquals(ACCEPTED_SILVER,
                AirflowSilverToGoldFixtureJob.requireSilver(ACCEPTED_SILVER));
        assertEquals(ACCEPTED_GOLD,
                AirflowSilverToGoldFixtureJob.requireGold(ACCEPTED_GOLD));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowSilverToGoldFixtureJob.requireSilver("stratus.silver.customers"));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowSilverToGoldFixtureJob.requireGold("stratus.gold.customer_summary"));
    }

    @Test
    void blockedFixtureContainsDuplicateCustomerKeysWhileAcceptedFixtureDoesNot() {
        assertEquals(3, AirflowSilverToGoldFixtureJob.ACCEPTED_CUSTOMERS.size());
        assertEquals(3, AirflowSilverToGoldFixtureJob.BLOCKED_CUSTOMERS.size());
        assertEquals(3, AirflowSilverToGoldFixtureJob.ACCEPTED_CUSTOMERS.stream()
                .map(AirflowSilverToGoldFixtureJob.Customer::customerId).distinct().count());
        assertEquals(2, AirflowSilverToGoldFixtureJob.BLOCKED_CUSTOMERS.stream()
                .map(AirflowSilverToGoldFixtureJob.Customer::customerId).distinct().count());
    }

    @Test
    void cleanupRequiresAtLeastOneExplicitQualityRunId() {
        assertEquals(2, AirflowSilverToGoldFixtureJob.cleanupRunIds("run-a,run-b").length);
        var missing = assertThrows(IllegalArgumentException.class,
                () -> AirflowSilverToGoldFixtureJob.cleanupRunIds(" , "));
        assertTrue(missing.getMessage().contains("cleanupRunIds"));
    }
}
