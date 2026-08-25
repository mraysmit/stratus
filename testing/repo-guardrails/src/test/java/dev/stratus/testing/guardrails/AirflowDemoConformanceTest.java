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
 * Repository contract for audience-facing Airflow demonstrations.
 *
 * <h2>Rationale</h2>
 *
 * <p>The live acceptance harnesses prove Airflow behavior thoroughly, but a raw test transcript is
 * not a useful product demonstration. A Stratus demo must explain Airflow's control-plane role,
 * execute a previously accepted proof rather than reimplementing it, identify the expected UI,
 * data and telemetry outcomes, and offer safe automatic cleanup. This keeps demonstrations honest:
 * every statement shown to an audience remains backed by the same executable behavior that closes
 * the developer gate.
 *
 * <h2>Maintenance</h2>
 *
 * <p>Keep demo entry points below {@code platform/airflow/developer/demos/scripts}; test harnesses
 * remain below {@code scripts/tests}. When a demo changes, first update this contract and observe a
 * failing focused test. Then update its walkthrough, expected-result record and script together.
 * Demo scripts may compose accepted harnesses and checked lifecycle scripts, but must not copy DAG
 * or Spark business logic. Default execution cleans up; {@code --keep-running} is an explicit UI
 * inspection choice with a documented one-command shutdown.
 *
 * <p>This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-24
 * @version 1.0.0
 */
@Tag("unit")
final class AirflowDemoConformanceTest {

    private static final Path DEMO_ROOT = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "demos"));
    private static final Path SCRIPT_ROOT = DEMO_ROOT.resolve("scripts");
    private static final Path EXPECTED_ROOT = DEMO_ROOT.resolve("expected-results");
    private static final Path README_PATH = DEMO_ROOT.resolve("README.md");
    private static final Path DEVELOPER_README_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "README.md"));

    private static final List<String> DEMO_SCRIPTS = List.of(
            "airflow-customer-pipeline-demo.sh",
            "airflow-quality-gate-demo.sh",
            "airflow-api-maintenance-demo.sh");
    private static final List<String> EXPECTED_RESULT_FILES = List.of(
            "customer-pipeline.md",
            "quality-gate.md",
            "api-maintenance.md");
    private static final List<String> CHECKED_PROVIDER_LIFECYCLES = List.of(
            "ceph-compose-startup.sh",
            "ceph-compose-shutdown.sh",
            "openbao-compose-startup.sh",
            "openbao-compose-shutdown.sh",
            "polaris-compose-startup.sh",
            "polaris-compose-shutdown.sh",
            "spark-compose-startup.sh",
            "spark-compose-shutdown.sh",
            "airflow-compose-startup.sh",
            "airflow-compose-shutdown.sh");

    @Test
    void demosHaveASeparateDiscoverableAndDocumentedLayout() {
        assertAll(
                () -> assertTrue(Files.isRegularFile(README_PATH), "Missing Airflow demo guide"),
                () -> DEMO_SCRIPTS.forEach(name -> assertTrue(
                        Files.isRegularFile(SCRIPT_ROOT.resolve(name)), name)),
                () -> EXPECTED_RESULT_FILES.forEach(name -> assertTrue(
                        Files.isRegularFile(EXPECTED_ROOT.resolve(name)), name)),
                () -> assertTrue(Files.isRegularFile(
                        DEMO_ROOT.resolve(Path.of("fixtures", "customers.csv"))),
                        "The audience-visible customer fixture is missing"),
                () -> assertTrue(Files.isRegularFile(
                        SCRIPT_ROOT.resolve("airflow-demo-common.sh"))),
                () -> assertTrue(Files.isRegularFile(
                        SCRIPT_ROOT.resolve("airflow-demo-shutdown.sh"))));
    }

    @Test
    void demoGuideExplainsAirflowRoleCommandsOutcomesAndInspectionLifecycle() {
        String guide = Repo.read(README_PATH);
        assertAll(
                () -> assertTrue(guide.contains("Airflow is the orchestration and control plane")),
                () -> assertTrue(guide.contains("Spark performs the data processing")),
                () -> DEMO_SCRIPTS.forEach(name -> assertTrue(guide.contains(name), name)),
                () -> assertTrue(guide.contains("http://127.0.0.1:8088")),
                () -> assertTrue(guide.contains("--keep-running")),
                () -> assertTrue(guide.contains("airflow-demo-shutdown.sh")),
                () -> assertTrue(guide.contains("Estimated duration")),
                () -> assertTrue(guide.contains("Expected result")),
                () -> assertTrue(guide.contains("suiteRunId")),
                () -> assertTrue(guide.contains("elapsedMs")));
    }

    @Test
    void demoEntriesReuseAcceptedHarnessesAndDeclareObservableOutcomes() {
        String customer = Repo.read(SCRIPT_ROOT.resolve(DEMO_SCRIPTS.get(0)));
        String quality = Repo.read(SCRIPT_ROOT.resolve(DEMO_SCRIPTS.get(1)));
        String api = Repo.read(SCRIPT_ROOT.resolve(DEMO_SCRIPTS.get(2)));

        assertAll(
                () -> assertTrue(customer.contains("airflow-silver-to-gold-live-test.sh")),
                () -> assertTrue(customer.contains("AIRFLOW SILVER TO GOLD VERIFIED")),
                () -> assertTrue(quality.contains("airflow-bronze-to-silver-live-test.sh")),
                () -> assertTrue(quality.contains("AIRFLOW BRONZE TO SILVER BLOCK VERIFIED")),
                () -> assertTrue(api.contains("airflow-api-orchestration-live-test.sh")),
                () -> assertTrue(api.contains(
                        "event=airflow_orchestration_verification_completed status=SUCCESS")),
                () -> DEMO_SCRIPTS.forEach(name -> {
                    String script = Repo.read(SCRIPT_ROOT.resolve(name));
                    assertTrue(script.contains("airflow-demo-common.sh"), name);
                    assertTrue(script.contains("EXPECTED_RESULT_FILE"), name);
                    assertFalse(script.contains("spark-submit"),
                            name + " must not duplicate Spark implementation logic");
                }));
    }

    @Test
    void sharedDemoLifecycleDefaultsToCleanupAndMakesInspectionExplicit() {
        String common = Repo.read(SCRIPT_ROOT.resolve("airflow-demo-common.sh"));
        String shutdown = Repo.read(SCRIPT_ROOT.resolve("airflow-demo-shutdown.sh"));

        assertAll(
                () -> CHECKED_PROVIDER_LIFECYCLES.forEach(script ->
                        assertTrue(common.contains(script) || shutdown.contains(script), script)),
                () -> assertTrue(common.contains("--keep-running")),
                () -> assertTrue(common.contains("trap cleanup EXIT")),
                () -> assertTrue(common.contains("remainingStratusContainers")),
                () -> assertTrue(common.contains("required_markers")),
                () -> assertTrue(common.contains("expectedResult=")),
                () -> assertTrue(shutdown.contains("remainingStratusContainers")),
                () -> assertFalse(common.contains("docker compose down")),
                () -> assertFalse(shutdown.contains("docker compose down")));
    }

    @Test
    void developerReadmeAdvertisesTheAcceptedDemoLayerWithoutStaleGateClaims() {
        String readme = Repo.read(DEVELOPER_README_PATH);
        assertAll(
                () -> assertTrue(readme.contains("demos/README.md")),
                () -> assertTrue(readme.contains("airflow-development-acceptance-suite.sh")),
                () -> assertFalse(readme.contains("developer gate remains a separate")),
                () -> assertFalse(readme.contains("remaining live pipeline work")));
    }
}
