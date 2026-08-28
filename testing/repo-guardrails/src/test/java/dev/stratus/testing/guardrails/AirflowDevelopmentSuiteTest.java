// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.testing.guardrails;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Repository contract for the canonical Airflow development acceptance suite.
 *
 * <h2>Rationale</h2>
 *
 * <p>Increment 4 is proved by several deliberately focused harnesses: image acceptance,
 * lifecycle, Spark submission, each pipeline boundary, maintenance, retry/failure callbacks,
 * Deadline Alerts, and public-API orchestration. Individual historical transcripts do not prove
 * that the current repository can still execute the whole chain. One checked-in suite must name
 * every producer, retain phase timings, and own provider cleanup so a single successful run is a
 * reproducible developer-gate result rather than a hand-assembled claim. The canonical live suite
 * owns one lifecycle for every service and passes that running environment to its focused
 * harnesses. The deliberately destructive two-cycle lifecycle proof remains a separate image and
 * deployment qualification because restarting services is the behavior it tests.
 *
 * <h2>Proof boundary and maintenance</h2>
 *
 * <p>This test proves suite composition and safety offline; the suite's own transcript proves live
 * behavior. When an Airflow task or harness is added, first extend {@link #REQUIRED_TEST_SCRIPTS},
 * observe this test fail, then add the phase to the suite. Keep all executable test harnesses under
 * clearly named {@code scripts/tests} directories. The suite must finish with the complete offline
 * reactor and checked reverse-order shutdown, including a zero-container assertion.
 *
 * <p>This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-24
 * @version 1.0.0
 */
@Tag("unit")
final class AirflowDevelopmentSuiteTest {

    private static final Path SUITE_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-development-acceptance-suite.sh"));
    private static final Path SUITE_OVERLAY_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "compose.acceptance-suite.yaml"));
    private static final Path ACCEPTANCE_PROBE_MOUNTPOINT = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "dags", "acceptance-probes"));
    private static final Path COMPOSE_COMMON_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "lib",
            "airflow-compose-common.sh"));
    private static final Path LIFECYCLE_TEST_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-compose-lifecycle-test.sh"));

    private static final List<String> REQUIRED_TEST_SCRIPTS = List.of(
            "airflow-image-acceptance-test.sh",
            "airflow-pipeline-dag-parse-test.sh",
            "airflow-spark-submission-test.sh",
            "airflow-landing-to-bronze-live-test.sh",
            "airflow-bronze-to-silver-live-test.sh",
            "airflow-silver-to-gold-live-test.sh",
            "airflow-table-maintenance-live-test.sh",
            "airflow-retry-alert-live-test.sh",
            "airflow-deadline-alert-live-test.sh",
            "airflow-api-orchestration-live-test.sh");

    private static final List<String> REQUIRED_PROVIDER_LIFECYCLES = List.of(
            "ceph-compose-startup.sh",
            "ceph-compose-shutdown.sh",
            "openbao-compose-startup.sh",
            "openbao-compose-shutdown.sh",
            "polaris-compose-startup.sh",
            "polaris-compose-shutdown.sh",
            "spark-compose-startup.sh",
            "spark-compose-shutdown.sh");

    @Test
    void canonicalSuiteExecutesEveryAirflowDevelopmentTest() {
        assertTrue(Files.isRegularFile(SUITE_PATH),
                () -> "Missing canonical Airflow development suite: " + SUITE_PATH);
        String suite = Repo.read(SUITE_PATH);

        assertAll(
                () -> REQUIRED_TEST_SCRIPTS.forEach(script ->
                        assertTrue(suite.contains(script), script)),
                () -> assertTrue(suite.contains("mvnw")),
                () -> assertTrue(suite.contains("verify")),
                () -> assertTrue(suite.contains("java.io.tmpdir")),
                () -> assertTrue(suite.contains("phase_started")),
                () -> assertTrue(suite.contains("phase_completed")),
                () -> assertTrue(suite.contains("durationMs")));
    }

    @Test
    void canonicalSuiteOwnsCheckedProviderShutdownAndFinalContainerAssertion() {
        String suite = Repo.read(SUITE_PATH);

        assertAll(
                () -> REQUIRED_PROVIDER_LIFECYCLES.forEach(script ->
                        assertTrue(suite.contains(script), script)),
                () -> assertTrue(suite.contains("trap cleanup EXIT")),
                () -> assertTrue(suite.contains("remainingStratusContainers")),
                () -> assertTrue(suite.contains("development_acceptance_completed")));
    }

    @Test
    void canonicalSuiteStartsEachServiceOnceAndSharesAirflowWithFocusedHarnesses() {
        String suite = Repo.read(SUITE_PATH);
        String common = Repo.read(COMPOSE_COMMON_PATH);
        String overlay = Repo.read(SUITE_OVERLAY_PATH);

        assertAll(
                () -> assertTrue(Files.isRegularFile(LIFECYCLE_TEST_PATH),
                        "the separate two-cycle lifecycle qualification must remain available"),
                () -> assertFalse(suite.contains("airflow-compose-lifecycle-test.sh"),
                        "the suite-scoped live run must not restart Airflow for lifecycle proof"),
                () -> assertEquals(1, occurrences(suite, "airflow-compose-startup.sh"),
                        "the canonical suite must have one Airflow startup"),
                () -> assertEquals(1, occurrences(suite, "ceph-compose-startup.sh")),
                () -> assertEquals(1, occurrences(suite, "openbao-compose-startup.sh")),
                () -> assertEquals(1, occurrences(suite, "polaris-compose-startup.sh")),
                () -> assertEquals(1, occurrences(suite, "spark-compose-startup.sh")),
                () -> assertTrue(suite.contains("STRATUS_AIRFLOW_SUITE_SCOPED=true")),
                () -> assertTrue(suite.contains("compose.acceptance-suite.yaml")),
                () -> assertTrue(suite.contains("append_airflow_compose_overlay")),
                () -> assertTrue(suite.contains("stop_shared_services")),
                () -> assertTrue(common.contains("AIRFLOW_COMPOSE_ADDITIONAL_OVERLAYS")),
                () -> assertTrue(common.contains("suite_owns_airflow")),
                () -> assertTrue(Files.isDirectory(ACCEPTANCE_PROBE_MOUNTPOINT),
                        "the nested bind-mount target must exist inside the read-only DAG tree"),
                () -> assertTrue(overlay.contains(
                        "./scripts/tests/dags:/opt/airflow/dags/acceptance-probes:ro")),
                () -> assertFalse(overlay.contains("SCHEDULER_HEARTBEAT_SEC"),
                        "the shared scheduler must not race the retry probe's in-process runner"));
    }

    private static int occurrences(String text, String value) {
        return text.split(java.util.regex.Pattern.quote(value), -1).length - 1;
    }
}
