// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guards the portfolio-wide implementation-stage contract.
 *
 * <h2>Rationale and proof boundary</h2>
 *
 * <p>Stratus must first prove the complete functional system in the development environment.
 * Production deployment hardening and operational readiness are deliberately later work. If a
 * component plan makes registry publication, high availability, production PKI, managed secrets,
 * disaster recovery, or formal production acceptance a prerequisite for a developer task, the
 * plan can stop engineering before the integrated development system has been demonstrated.
 *
 * <p>These assertions prove that every active component plan states the same sequencing rule, that
 * a separate production-hardening plan owns later work, and that the active Airflow dependency
 * chain permits local development proof. They do not prove that either implementation stage has
 * passed its technical gates.
 *
 * <h2>Maintenance</h2>
 *
 * <p>Add every new component implementation plan to the applicable list. Change the canonical
 * markers only as an atomic portfolio-wide planning decision. A production concern may block
 * development only when it establishes a fundamental functional or architectural incompatibility;
 * document that exception explicitly rather than weakening this contract.
 *
 * <p>This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-22
 * @version 1.0.0
 */
@Tag("unit")
final class ImplementationStageContractTest {

    private static final String CURRENT_STAGE_MARKER =
            "**Current stage:** Development implementation and functional acceptance.";
    private static final String LATER_STAGE_MARKER =
            "**Later stage:** Production deployment hardening and readiness.";

    private static final Path PHASE_ONE_PLAN = implementation("stratus_implementation_plan_phase1.md");
    private static final Path PHASE_TWO_PLAN = implementation("stratus_implementation_plan_phase2.md");
    private static final Path PHASE_THREE_PLAN = implementation("stratus_implementation_plan_phase3.md");
    private static final Path PRODUCTION_HARDENING_PLAN =
            implementation("stratus_production_deployment_hardening.md");
    private static final Path TASK_AUDIT = implementation("task_track_audit.md");
    private static final Path PHASE_ONE_READINESS = Path.of(
            "docs", "operations", "stratus_phase1_operational_readiness.md");
    private static final Path AIRFLOW_PLAN = implementation("airflow_orchestration.md");

    private static final List<Path> ACTIVE_COMPONENT_PLANS = List.of(
            implementation("ceph_storage.md"),
            implementation("iceberg_polaris_catalog.md"),
            implementation("spark_compute.md"),
            AIRFLOW_PLAN,
            implementation("trino_query.md"),
            implementation("atlas_ranger_governance.md"),
            implementation("freeipa_keycloak_identity.md"),
            implementation("kafka_event_backbone.md"),
            implementation("kafka_connect_debezium_cdc.md"),
            implementation("flink_streaming_compute.md"),
            implementation("flink_streaming_iceberg.md"),
            implementation("atlas_streaming_lineage.md"));

    @Test
    void controllingPlansStateDevelopmentFirstAndDeferProductionHardening() {
        List<Path> controllingDocuments = List.of(
                PHASE_ONE_PLAN,
                PHASE_TWO_PLAN,
                PHASE_THREE_PLAN,
                TASK_AUDIT,
                PHASE_ONE_READINESS,
                PRODUCTION_HARDENING_PLAN);

        assertAll(controllingDocuments.stream()
                .map(document -> () -> assertContainsStageMarkers(document)));
    }

    @Test
    void everyActiveComponentPlanUsesTheCanonicalStageContract() {
        assertAll(ACTIVE_COMPONENT_PLANS.stream()
                .map(document -> () -> assertContainsStageMarkers(document)));
    }

    @Test
    void airflowDevelopmentCanCompleteBeforeProductionArtifactPublication() {
        String airflow = read(AIRFLOW_PLAN);
        String developmentTask = tableRow(airflow, "P1-4.1-D1");
        String productionTask = tableRow(airflow, "P1-4.1-P1");

        assertAll(
                () -> assertTrue(developmentTask.contains("P1-4.1-S2")),
                () -> assertTrue(developmentTask.contains("local development image")),
                () -> assertFalse(developmentTask.contains("Published `P1-4.1-S2` image digest")),
                () -> assertTrue(productionTask.contains("P1-4.1-S2")),
                () -> assertTrue(productionTask.contains("P1-0.1")),
                () -> assertFalse(productionTask.contains("| `P1-4.1-S1` |")));
    }

    @Test
    void productionHardeningHasAnExplicitDeferredEntryGate() {
        String hardening = read(PRODUCTION_HARDENING_PLAN);
        assertAll(
                () -> assertTrue(hardening.contains("all applicable development gates are accepted")),
                () -> assertTrue(hardening.contains("development-system acceptance record")),
                () -> assertTrue(hardening.contains("must not block development implementation")));
    }

    @Test
    void laterDevelopmentPhasesDependOnDevelopmentAcceptanceNotProductionReadiness() {
        String phaseTwo = read(PHASE_TWO_PLAN);
        String phaseThree = read(PHASE_THREE_PLAN);

        assertAll(
                () -> assertTrue(phaseTwo.contains(
                        "Phase 2 development implementation may begin when the Phase 1 development-system contracts")),
                () -> assertTrue(phaseTwo.contains(
                        "wait for production deployment hardening or operational-readiness signoff.")),
                () -> assertTrue(phaseThree.contains("## 2. Phase 3 Development Entry Criteria")),
                () -> assertTrue(phaseThree.contains(
                        "Phase 1 operational acceptance and Phase 2 production readiness are prerequisites for production")),
                () -> assertTrue(phaseThree.contains(
                        "not for Phase 3 development")));
    }

    private static void assertContainsStageMarkers(Path document) {
        String content = read(document);
        assertAll(
                () -> assertTrue(content.contains(CURRENT_STAGE_MARKER),
                        () -> document + " is missing the canonical current-stage marker"),
                () -> assertTrue(content.contains(LATER_STAGE_MARKER),
                        () -> document + " is missing the canonical later-stage marker"));
    }

    private static String tableRow(String markdown, String taskId) {
        String prefix = "| `" + taskId + "` |";
        return markdown.lines()
                .filter(line -> line.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing task row " + taskId));
    }

    private static String read(Path relative) {
        return Repo.read(Repo.root().resolve(relative));
    }

    private static Path implementation(String fileName) {
        return Path.of("docs", "implementation", fileName);
    }
}
