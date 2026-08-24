// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Repository contract for the Increment 4 developer-gate evidence package.
 *
 * <h2>Rationale</h2>
 *
 * <p>Functional Airflow evidence is not, by itself, a gate record. D1 must map every promised
 * behavior to a producing task and durable result, while D2 must expose every material developer
 * shortcut with its later replacement and stop condition. Without that mapping, a local setting
 * can silently be mistaken for an accepted deployment property.
 *
 * <h2>Proof boundary and maintenance</h2>
 *
 * <p>This test proves the repository record is complete and internally consistent; it does not
 * provide owner acceptance. Keep {@code REQUIRED_*} constants synchronized with the normative
 * Increment 4 gate. When evidence changes, update the gate record, implementation plan, and status
 * documents atomically. The repository maintainer acting for the development-stage owners must
 * explicitly direct acceptance of {@code P1-4.G-D} before its task row or D1-D2 checkboxes are
 * marked accepted.
 *
 * <p>This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-24
 * @version 1.0.0
 */
@Tag("unit")
final class AirflowDeveloperGateTest {

    private static final Path GATE_RECORD_PATH = Repo.root().resolve(
            Path.of("platform", "airflow", "developer-gate-20260824.md"));
    private static final Path INCREMENT_PLAN_PATH = Repo.root().resolve(
            Path.of("docs", "implementation", "airflow_orchestration.md"));
    private static final Path PHASE_PLAN_PATH = Repo.root().resolve(
            Path.of("docs", "implementation", "stratus_implementation_plan_phase1.md"));

    private static final String GATE_ID = "P1-4.G-D";
    private static final String GATE_RECORD_LINK =
            "../../platform/airflow/developer-gate-20260824.md";
    private static final String ACCEPTED_STATUS = "Status: Accepted";
    private static final String CANONICAL_SUITE_RUN_ID =
            "airflow-development-acceptance-20260824T103411Z";
    private static final List<String> REQUIRED_PRODUCING_TASKS = List.of(
            "P1-4.1-S2", "P1-4.1-D1", "P1-4.2-D1", "P1-4.3-V1");
    private static final List<String> REQUIRED_LIVE_EVIDENCE = List.of(
            "airflow-spark-20260822T090250Z",
            "airflow-pipeline-20260823T071231Z",
            "airflow-bronze-to-silver-20260823T084502Z",
            "airflow-silver-to-gold-20260823T093453Z",
            "airflow-table-maintenance-20260823T113447Z",
            "airflow-retry-alert-20260824T040947Z",
            "airflow-deadline-alert-20260824T051734Z",
            "airflow-api-orchestration-20260824T073836Z");
    private static final List<String> REQUIRED_DEVELOPER_CONDITIONS = List.of(
            "Local PostgreSQL metadata volume",
            "Local Airflow task logs and Spark event logs",
            "Disposable bootstrap and service credentials",
            "Locally generated CA material",
            "Reduced single-host availability",
            "Loopback HTTP and SimpleAuth",
            "Workstation-built mutable development artifacts",
            "Structured log callback without an external alert sink");
    private static final List<String> REQUIRED_REPLACEMENT_TASKS = List.of(
            "P1-0.1", "P1-4.1-P1", "P1-4.2-P1", "P1-4.5-R1", "P1-4.4-V1", "P1-7.4");

    @Test
    void acceptedGateRecordMapsCanonicalD1EvidenceAndD2DeveloperConditions() {
        assertTrue(Files.isRegularFile(GATE_RECORD_PATH),
                () -> "Missing Increment 4 developer-gate record: " + GATE_RECORD_PATH);
        String record = Repo.read(GATE_RECORD_PATH);

        assertAll(
                () -> assertTrue(record.contains("`" + GATE_ID + "`")),
                () -> assertTrue(record.contains(ACCEPTED_STATUS)),
                () -> assertTrue(record.contains("## D1 evidence matrix")),
                () -> assertTrue(record.contains("## D2 development-state promotion manifest")),
                () -> assertTrue(record.contains("294 offline tests")),
                () -> assertTrue(record.contains(CANONICAL_SUITE_RUN_ID)),
                () -> assertTrue(record.contains("remainingStratusContainers=0")),
                () -> REQUIRED_PRODUCING_TASKS.forEach(task ->
                        assertTrue(record.contains("`" + task + "`"), task)),
                () -> REQUIRED_LIVE_EVIDENCE.forEach(run ->
                        assertTrue(record.contains("`" + run + "`"), run)),
                () -> REQUIRED_DEVELOPER_CONDITIONS.forEach(condition ->
                        assertTrue(record.contains(condition), condition)),
                () -> REQUIRED_REPLACEMENT_TASKS.forEach(task ->
                        assertTrue(record.contains("`" + task + "`"), task)),
                () -> assertFalse(record.contains("Ready for owner acceptance"),
                        "An accepted gate must not retain a contradictory ready status"));
    }

    @Test
    void implementationPlansRecordTheExplicitDevelopmentGateAcceptance() {
        String incrementPlan = Repo.read(INCREMENT_PLAN_PATH);
        String phasePlan = Repo.read(PHASE_PLAN_PATH);

        assertAll(
                () -> assertTrue(incrementPlan.contains("### Developer-to-production promotion controls")),
                () -> assertTrue(incrementPlan.contains("### Gate traceability rule")),
                () -> assertTrue(incrementPlan.contains(GATE_RECORD_LINK)),
                () -> assertTrue(incrementPlan.contains("- [x] **D1**")),
                () -> assertTrue(incrementPlan.contains("- [x] **D2**")),
                () -> assertTrue(incrementPlan.contains("`" + GATE_ID + "`")),
                () -> assertTrue(incrementPlan.contains("`" + GATE_ID
                        + "` developer gate is accepted")),
                () -> assertTrue(phasePlan.contains("`" + GATE_ID + "`")),
                () -> assertTrue(phasePlan.contains("Development accepted 2026-08-24")));
    }
}
