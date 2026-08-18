// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Offline contract for the P1-4.1-D1 Airflow developer deployment. */
@Tag("unit")
final class AirflowDeveloperDeploymentTest {

    private static final Path DEPLOYMENT = Repo.root().resolve(
            Path.of("platform", "airflow", "developer"));

    @Test
    void deploymentArtifactsHaveStableLocations() {
        assertAll(
                () -> assertFile("compose.yaml"),
                () -> assertFile(".env.template"),
                () -> assertFile("README.md"),
                () -> assertFile(Path.of("dags", ".gitkeep")),
                () -> assertFile(Path.of("plugins", ".gitkeep")),
                () -> assertFile(Path.of("scripts", "lib", "airflow-compose-common.sh")),
                () -> assertFile(Path.of("scripts", "lifecycle", "airflow-compose-startup.sh")),
                () -> assertFile(Path.of("scripts", "lifecycle", "airflow-compose-shutdown.sh")),
                () -> assertFile(Path.of("scripts", "lifecycle", "airflow-compose-reset.sh")),
                () -> assertFile(Path.of("scripts", "tests", "airflow-compose-verify-health.sh")),
                () -> assertFile(Path.of("scripts", "tests", "airflow-compose-lifecycle-test.sh")));
    }

    @Test
    void composeDefinesTheApprovedLocalExecutorTopology() {
        String compose = read("compose.yaml");
        assertAll(
                () -> assertTrue(compose.contains("name: stratus-airflow-local")),
                () -> assertTrue(compose.contains("image: ${POSTGRES_IMAGE:")),
                () -> assertTrue(compose.contains("AIRFLOW__CORE__EXECUTOR: LocalExecutor")),
                () -> assertTrue(compose.contains("airflow-api-server:")),
                () -> assertTrue(compose.contains("airflow-dag-processor:")),
                () -> assertTrue(compose.contains("airflow-scheduler:")),
                () -> assertTrue(compose.contains("airflow-triggerer:")),
                () -> assertTrue(compose.contains("airflow-init:")),
                () -> assertTrue(compose.contains("127.0.0.1")),
                () -> assertTrue(compose.contains("/api/v2/monitor/health")),
                () -> assertTrue(compose.contains("airflow db migrate")),
                () -> assertTrue(compose.contains("postgres-data:")),
                () -> assertTrue(compose.contains("airflow-logs:")),
                () -> assertFalse(compose.matches("(?s).*(password|secret):\\s*[^$<{\\n][^\\n]*"),
                        "Compose must interpolate secrets rather than embed literal values"));
    }

    @Test
    void lifecycleIsIdempotentAndHealthChecked() {
        String startup = read(Path.of("scripts", "lifecycle", "airflow-compose-startup.sh"));
        String shutdown = read(Path.of("scripts", "lifecycle", "airflow-compose-shutdown.sh"));
        String reset = read(Path.of("scripts", "lifecycle", "airflow-compose-reset.sh"));
        String health = read(Path.of("scripts", "tests", "airflow-compose-verify-health.sh"));
        String lifecycle = read(Path.of("scripts", "tests", "airflow-compose-lifecycle-test.sh"));
        assertAll(
                () -> assertTrue(startup.contains("airflow db migrate")),
                () -> assertTrue(startup.contains("compose up --detach")),
                () -> assertTrue(startup.contains("airflow-compose-verify-health.sh")),
                () -> assertTrue(shutdown.contains("compose_teardown down --remove-orphans")),
                () -> assertTrue(reset.contains("down --volumes --remove-orphans")),
                () -> assertTrue(health.contains("/api/v2/monitor/health")),
                () -> assertTrue(health.contains("airflow jobs check --job-type SchedulerJob")),
                () -> assertTrue(health.contains("airflow db check")),
                () -> assertTrue(lifecycle.contains("for cycle in 1 2")),
                () -> assertTrue(lifecycle.contains("airflow-compose-startup.sh")),
                () -> assertTrue(lifecycle.contains("airflow-compose-shutdown.sh")));
    }

    @Test
    void environmentTemplateContainsNoSecretAndStartupGeneratesThem() {
        String template = read(".env.template");
        String startup = read(Path.of("scripts", "lifecycle", "airflow-compose-startup.sh"));
        assertAll(
                () -> assertTrue(template.contains("AIRFLOW_IMAGE=stratus/airflow:dev")),
                () -> assertTrue(template.contains("POSTGRES_IMAGE=postgres:17.10")),
                () -> assertTrue(template.contains("AIRFLOW_DB_PASSWORD=")),
                () -> assertTrue(template.contains("AIRFLOW_FERNET_KEY=")),
                () -> assertTrue(template.contains("AIRFLOW_JWT_SECRET=")),
                () -> assertTrue(template.contains("AIRFLOW_API_SECRET_KEY=")),
                () -> assertTrue(startup.contains("rand_hex")),
                () -> assertTrue(startup.contains("rand_fernet_key")),
                () -> assertTrue(startup.contains("chmod 600")));
    }

    private static void assertFile(String relative) {
        assertFile(Path.of(relative));
    }

    private static void assertFile(Path relative) {
        assertTrue(Files.isRegularFile(DEPLOYMENT.resolve(relative)),
                () -> "Missing Airflow developer artifact: " + relative);
    }

    private static String read(String relative) {
        return read(Path.of(relative));
    }

    private static String read(Path relative) {
        assertFile(relative);
        return Repo.read(DEPLOYMENT.resolve(relative));
    }
}
