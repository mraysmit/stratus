// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Locks the independent verifier and exact-cleanup boundary for Airflow maintenance probes. */
@Tag("unit")
final class AirflowTableMaintenanceVerifierJobTest {

    @Test
    void cleanupAcceptsOnlyGeneratedMaintenanceProbeTables() {
        assertEquals("stratus.bronze.airflow_maintenance_probe_run_1",
                AirflowTableMaintenanceVerifierJob.requireIsolatedTable(
                        "stratus.bronze.airflow_maintenance_probe_run_1"));

        var production = assertThrows(IllegalArgumentException.class,
                () -> AirflowTableMaintenanceVerifierJob.requireIsolatedTable(
                        "stratus.bronze.customers"));
        assertTrue(production.getMessage().contains("airflow_maintenance_probe_"));
    }

    @Test
    void verifierModesAreExplicitAndClosed() {
        assertEquals("seed", AirflowTableMaintenanceVerifierJob.requireMode("seed"));
        assertEquals("verify-skip",
                AirflowTableMaintenanceVerifierJob.requireMode("verify-skip"));
        assertEquals("verify-run",
                AirflowTableMaintenanceVerifierJob.requireMode("verify-run"));
        assertEquals("cleanup", AirflowTableMaintenanceVerifierJob.requireMode("cleanup"));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowTableMaintenanceVerifierJob.requireMode("verify"));
    }

    @Test
    void numericExpectationsRejectNegativeOrMalformedValues() {
        assertEquals(3L, AirflowTableMaintenanceVerifierJob.nonNegative("3", "expectedFiles"));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowTableMaintenanceVerifierJob.nonNegative("-1", "expectedFiles"));
        assertThrows(IllegalArgumentException.class,
                () -> AirflowTableMaintenanceVerifierJob.nonNegative("three", "expectedFiles"));
    }
}
