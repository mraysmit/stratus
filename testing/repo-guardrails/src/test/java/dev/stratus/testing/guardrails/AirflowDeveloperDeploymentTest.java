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
 * Offline repository contract for the P1-4.1-D1 Airflow developer deployment.
 *
 * <h2>Rationale</h2>
 *
 * <p>The developer harness spans Compose topology, environment defaults, lifecycle scripts,
 * generated credentials, health checks, and reset behavior. A syntactically valid Compose file can
 * still omit a required Airflow 3 service, expose a port broadly, embed a credential, skip a
 * migration, or make teardown depend on healthy configuration. This test keeps those operational
 * requirements explicit and reviewable without starting containers.
 *
 * <h2>Proof boundary</h2>
 *
 * <p>The assertions prove that the required files and source-level controls are present. They do
 * not prove image availability, PostgreSQL compatibility, successful migrations, Airflow health,
 * DAG scheduling, or idempotent lifecycle behavior. Those claims require the two-cycle live
 * developer test and the separately approved immutable image digest.
 *
 * <h2>Maintenance and promotion</h2>
 *
 * <p>When the Airflow image, PostgreSQL version, topology, endpoint, or script interface changes,
 * update the named constants and grouped contract markers first or in the same atomic change.
 * Observe the relevant assertion fail, update the harness and operator documentation, and run the
 * complete repository guardrail module before repeating the live lifecycle test. Do not remove a
 * marker merely because a new upstream image changes its defaults.
 *
 * <p>This Compose topology is developer evidence only. UAT must deploy the developer-approved
 * registry digest with UAT-managed configuration and prove migrations, scheduling, Spark
 * submission, secrets, logging, alerting, and recovery. Production must promote the exact
 * UAT-approved digest without rebuilding and satisfy the production database, identity, backup,
 * observability, rollback, security-review, and change-approval gates.
 *
 * <p>This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-18
 * @version 1.1.0
 */
@Tag("unit")
final class AirflowDeveloperDeploymentTest {

    private static final Path DEPLOYMENT_ROOT = Repo.root().resolve(
            Path.of("platform", "airflow", "developer"));
    private static final Path COMPOSE_PATH = Path.of("compose.yaml");
    private static final Path ENVIRONMENT_TEMPLATE_PATH = Path.of(".env.template");
    private static final Path README_PATH = Path.of("README.md");
    private static final Path DAG_PLACEHOLDER_PATH = Path.of("dags", ".gitkeep");
    private static final Path PLUGIN_PLACEHOLDER_PATH = Path.of("plugins", ".gitkeep");
    private static final Path COMMON_SCRIPT_PATH = Path.of(
            "scripts", "lib", "airflow-compose-common.sh");
    private static final Path STARTUP_SCRIPT_PATH = Path.of(
            "scripts", "lifecycle", "airflow-compose-startup.sh");
    private static final Path SHUTDOWN_SCRIPT_PATH = Path.of(
            "scripts", "lifecycle", "airflow-compose-shutdown.sh");
    private static final Path RESET_SCRIPT_PATH = Path.of(
            "scripts", "lifecycle", "airflow-compose-reset.sh");
    private static final Path HEALTH_TEST_PATH = Path.of(
            "scripts", "tests", "airflow-compose-verify-health.sh");
    private static final Path LIFECYCLE_TEST_PATH = Path.of(
            "scripts", "tests", "airflow-compose-lifecycle-test.sh");

    private static final String POSTGRES_VERSION = "17.10";
    private static final String EXPECTED_AIRFLOW_IMAGE = "stratus/airflow:dev";
    private static final String EXPECTED_POSTGRES_IMAGE = "postgres:" + POSTGRES_VERSION;
    private static final String EXPECTED_HEALTH_ENDPOINT = "/api/v2/monitor/health";

    private static final List<Path> REQUIRED_ARTIFACT_PATHS = List.of(
            COMPOSE_PATH,
            ENVIRONMENT_TEMPLATE_PATH,
            README_PATH,
            DAG_PLACEHOLDER_PATH,
            PLUGIN_PLACEHOLDER_PATH,
            COMMON_SCRIPT_PATH,
            STARTUP_SCRIPT_PATH,
            SHUTDOWN_SCRIPT_PATH,
            RESET_SCRIPT_PATH,
            HEALTH_TEST_PATH,
            LIFECYCLE_TEST_PATH);

    private static final List<String> REQUIRED_COMPOSE_MARKERS = List.of(
            "name: stratus-airflow-local",
            "image: ${POSTGRES_IMAGE:",
            "AIRFLOW__CORE__EXECUTOR: LocalExecutor",
            "airflow-api-server:",
            "airflow-dag-processor:",
            "airflow-scheduler:",
            "airflow-triggerer:",
            "airflow-init:",
            "127.0.0.1",
            EXPECTED_HEALTH_ENDPOINT,
            "airflow db migrate",
            "postgres-data:",
            "airflow-logs:");

    private static final List<String> REQUIRED_STARTUP_MARKERS = List.of(
            "airflow db migrate",
            "compose up --detach",
            HEALTH_TEST_PATH.getFileName().toString());
    private static final List<String> REQUIRED_HEALTH_MARKERS = List.of(
            EXPECTED_HEALTH_ENDPOINT,
            "airflow jobs check --job-type SchedulerJob",
            "airflow db check");
    private static final List<String> REQUIRED_SECRET_GENERATION_MARKERS = List.of(
            "rand_hex",
            "rand_fernet_key",
            "chmod 600");

    @Test
    void deploymentArtifactsHaveStableLocations() {
        assertAll(REQUIRED_ARTIFACT_PATHS.stream()
                .<org.junit.jupiter.api.function.Executable>map(path -> () -> assertFile(path)));
    }

    @Test
    void composeDefinesTheApprovedLocalExecutorTopology() {
        String compose = read(COMPOSE_PATH);
        assertContainsAll(compose, REQUIRED_COMPOSE_MARKERS, "Compose topology");
        assertFalse(compose.matches("(?s).*(password|secret):\\s*[^$<{\\n][^\\n]*"),
                "Compose must interpolate secrets rather than embed literal values");
    }

    @Test
    void lifecycleIsIdempotentAndHealthChecked() {
        String startup = read(STARTUP_SCRIPT_PATH);
        String shutdown = read(SHUTDOWN_SCRIPT_PATH);
        String reset = read(RESET_SCRIPT_PATH);
        String health = read(HEALTH_TEST_PATH);
        String lifecycle = read(LIFECYCLE_TEST_PATH);

        assertAll(
                () -> assertContainsAll(startup, REQUIRED_STARTUP_MARKERS, "startup script"),
                () -> assertTrue(shutdown.contains("compose_teardown down --remove-orphans")),
                () -> assertTrue(reset.contains("down --volumes --remove-orphans")),
                () -> assertContainsAll(health, REQUIRED_HEALTH_MARKERS, "health test"),
                () -> assertTrue(lifecycle.contains("for cycle in 1 2")),
                () -> assertTrue(lifecycle.contains(STARTUP_SCRIPT_PATH.getFileName().toString())),
                () -> assertTrue(lifecycle.contains(SHUTDOWN_SCRIPT_PATH.getFileName().toString())));
    }

    @Test
    void environmentTemplateContainsNoSecretAndStartupGeneratesThem() {
        String template = read(ENVIRONMENT_TEMPLATE_PATH);
        String startup = read(STARTUP_SCRIPT_PATH);
        assertAll(
                () -> assertTrue(template.contains("AIRFLOW_IMAGE=" + EXPECTED_AIRFLOW_IMAGE)),
                () -> assertTrue(template.contains("POSTGRES_IMAGE=" + EXPECTED_POSTGRES_IMAGE)),
                () -> assertTrue(template.contains("AIRFLOW_DB_PASSWORD=")),
                () -> assertTrue(template.contains("AIRFLOW_FERNET_KEY=")),
                () -> assertTrue(template.contains("AIRFLOW_JWT_SECRET=")),
                () -> assertTrue(template.contains("AIRFLOW_API_SECRET_KEY=")),
                () -> assertContainsAll(
                        startup, REQUIRED_SECRET_GENERATION_MARKERS, "secret generation"));
    }

    private static void assertContainsAll(String content, List<String> markers, String contract) {
        List<String> missing = markers.stream().filter(marker -> !content.contains(marker)).toList();
        assertTrue(missing.isEmpty(), () -> contract + " is missing required markers: " + missing);
    }

    private static void assertFile(Path relative) {
        assertTrue(Files.isRegularFile(DEPLOYMENT_ROOT.resolve(relative)),
                () -> "Missing Airflow developer artifact: " + relative);
    }

    private static String read(Path relative) {
        assertFile(relative);
        return Repo.read(DEPLOYMENT_ROOT.resolve(relative));
    }
}
