// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Defines the versioned metadata-policy contract before live maintenance is allowed to act. */
@Tag("unit")
final class TableMaintenanceJobTest {

    private static final String PROBE_TABLE =
            "stratus.bronze.airflow_maintenance_probe_policy_contract";

    @Test
    void runPolicySelectsCompactionFromObservedSmallFiles() {
        var policy = TableMaintenanceJob.requirePolicy("development-run-v1", PROBE_TABLE);
        var metrics = new TableMaintenanceJob.MaintenanceMetrics(3, 3, 10L, 3, 3, 0, 0);

        var plan = TableMaintenanceJob.plan(metrics, policy);

        assertEquals(List.of(MaintenanceJob.REWRITE_DATA_FILES), plan.operations());
        assertEquals("development-run-v1", plan.policyVersion());
        assertTrue(plan.rewriteDataFiles());
    }

    @Test
    void skipPolicyRecordsNoSelectedOperation() {
        var policy = TableMaintenanceJob.requirePolicy("development-skip-v1", PROBE_TABLE);
        var metrics = new TableMaintenanceJob.MaintenanceMetrics(3, 3, 10L, 3, 3, 0, 0);

        var plan = TableMaintenanceJob.plan(metrics, policy);

        assertEquals(List.of(), plan.operations());
        assertEquals(4, plan.smallFileThreshold());
        assertEquals(100, plan.retainedSnapshots());
    }

    @Test
    void expiryIsSelectedOnlyBeyondTheRetainedSnapshotCount() {
        var policy = TableMaintenanceJob.requirePolicy("bronze-default-v1",
                "stratus.bronze.customers");
        var within = new TableMaintenanceJob.MaintenanceMetrics(1, 0, 134217728L,
                policy.retainedSnapshots(), 1, 0, 0);
        var beyond = new TableMaintenanceJob.MaintenanceMetrics(1, 0, 134217728L,
                policy.retainedSnapshots() + 1L, 1, 0, 0);

        assertEquals(List.of(), TableMaintenanceJob.plan(within, policy).operations());
        assertEquals(List.of(MaintenanceJob.EXPIRE_SNAPSHOTS),
                TableMaintenanceJob.plan(beyond, policy).operations());
    }

    @Test
    void developmentPoliciesAreRestrictedToIsolatedProbeTables() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> TableMaintenanceJob.requirePolicy("development-run-v1",
                        "stratus.bronze.customers"));

        assertTrue(refused.getMessage().contains("airflow_maintenance_probe_"));
    }

    @Test
    void zonePoliciesCannotBeAppliedToAnotherZone() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> TableMaintenanceJob.requirePolicy("gold-default-v1",
                        "stratus.bronze.customers"));

        assertTrue(refused.getMessage().contains("stratus.gold"));
    }
}
