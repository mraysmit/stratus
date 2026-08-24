// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live Airflow 3 orchestration acceptance proof.
 *
 * <p>The shell harness owns real S3/Iceberg fixtures and independently verifies table side
 * effects. This class owns the Airflow protocol assertions: authentication, service health, DAG
 * registration, API-triggered execution, bounded polling, and exact terminal task states. The
 * blocked scenario is intentionally expected to fail at the embedded promotion gate; accepting
 * any downstream task state other than {@code upstream_failed} would hide an unauthorized write.
 */
@Tag("orchestration-integration")
final class AirflowOrchestrationVerificationTest {

    private static final Logger LOG =
            LoggerFactory.getLogger(AirflowOrchestrationVerificationTest.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> REQUIRED_DAGS = List.of(
            "stratus_landing_to_bronze",
            "stratus_bronze_to_silver",
            "stratus_silver_to_gold",
            "stratus_table_maintenance");

    @Test
    void provesHealthyPositiveAndFailClosedOrchestration() throws Exception {
        Map<String, String> environment = runtimeSettings();
        var config = AirflowVerifierConfig.from(environment);
        Scenario positive = scenario(environment, "POSITIVE");
        Scenario blocked = scenario(environment, "BLOCKED");
        long suiteStarted = System.nanoTime();

        LOG.info("event=airflow_orchestration_verification_started positiveDagId={} "
                        + "positiveRunId={} blockedDagId={} blockedRunId={} timeoutMs={} pollMs={}",
                positive.dagId(), positive.runId(), blocked.dagId(), blocked.runId(),
                config.runTimeout().toMillis(), config.pollInterval().toMillis());

        try (var airflow = AirflowApiClient.connect(config)) {
            long phaseStarted = System.nanoTime();
            var health = airflow.health();
            assertEquals("healthy", health.metadatabaseStatus(),
                    "Airflow metadata database must be healthy");
            assertEquals("healthy", health.schedulerStatus(),
                    "Airflow scheduler must be healthy");
            LOG.info("event=airflow_orchestration_phase_completed phase=health status=SUCCESS "
                    + "elapsedMs={}", elapsedMs(phaseStarted));

            phaseStarted = System.nanoTime();
            var dags = airflow.dags();
            for (String required : REQUIRED_DAGS) {
                AirflowDag dag = dags.get(required);
                assertNotNull(dag, "Required DAG is not registered: " + required);
                assertFalse(dag.paused(), "Required DAG is paused: " + required);
            }
            LOG.info("event=airflow_orchestration_phase_completed phase=dag_registry "
                    + "status=SUCCESS dagCount={} requiredDagCount={} elapsedMs={}",
                    dags.size(), REQUIRED_DAGS.size(), elapsedMs(phaseStarted));

            verifyScenario(airflow, config, positive);
            verifyScenario(airflow, config, blocked);
        }

        LOG.info("event=airflow_orchestration_verification_completed status=SUCCESS "
                        + "positiveRunId={} blockedRunId={} elapsedMs={}",
                positive.runId(), blocked.runId(), elapsedMs(suiteStarted));
    }

    private static void verifyScenario(
            AirflowApiClient airflow, AirflowVerifierConfig config, Scenario scenario) {
        long scenarioStarted = System.nanoTime();
        AirflowDagRun accepted = airflow.triggerDag(
                scenario.dagId(), scenario.runId(), scenario.configuration());
        assertEquals(scenario.runId(), accepted.runId(),
                "Airflow must echo the caller-owned correlation run ID");

        AirflowDagRun terminal = airflow.awaitTerminalRun(
                scenario.dagId(), scenario.runId(),
                config.pollInterval(), config.runTimeout());
        assertEquals(scenario.expectedRunState(), terminal.state(),
                "Unexpected DAG outcome for scenario " + scenario.name());

        Map<String, AirflowTaskInstance> tasks = airflow.taskInstances(
                scenario.dagId(), scenario.runId());
        for (Map.Entry<String, String> expected : scenario.expectedTaskStates().entrySet()) {
            AirflowTaskInstance task = tasks.get(expected.getKey());
            assertNotNull(task, "Expected task is absent: " + expected.getKey());
            assertEquals(expected.getValue(), task.state(),
                    "Unexpected state for task " + expected.getKey());
            LOG.info("event=airflow_task_outcome scenario={} dagId={} runId={} taskId={} "
                            + "state={} tryNumber={} airflowDurationMs={}",
                    scenario.name(), scenario.dagId(), scenario.runId(), task.taskId(),
                    task.state(), task.tryNumber(), task.duration().toMillis());
        }
        LOG.info("event=airflow_orchestration_scenario_completed scenario={} dagId={} runId={} "
                        + "state={} observedTasks={} airflowDurationMs={} elapsedMs={}",
                scenario.name(), scenario.dagId(), scenario.runId(), terminal.state(), tasks.size(),
                terminal.duration().toMillis(), elapsedMs(scenarioStarted));
    }

    private static Scenario scenario(Map<String, String> environment, String prefix) throws Exception {
        String name = prefix.toLowerCase();
        String dagId = required(environment, "STRATUS_AIRFLOW_" + prefix + "_DAG_ID");
        String runId = required(environment, "STRATUS_AIRFLOW_" + prefix + "_RUN_ID");
        String runState = required(environment,
                "STRATUS_AIRFLOW_" + prefix + "_EXPECTED_RUN_STATE");
        Map<String, Object> configuration = decode(environment,
                "STRATUS_AIRFLOW_" + prefix + "_CONF_BASE64", new TypeReference<>() { });
        Map<String, String> taskStates = decode(environment,
                "STRATUS_AIRFLOW_" + prefix + "_TASK_STATES_BASE64", new TypeReference<>() { });
        return new Scenario(name, dagId, runId, runState, configuration, taskStates);
    }

    private static <T> T decode(
            Map<String, String> environment, String name, TypeReference<T> type) throws Exception {
        String encoded = required(environment, name);
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(name + " must contain canonical Base64", failure);
        }
        return JSON.readValue(new String(decoded, StandardCharsets.UTF_8), type);
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    /**
     * Git Bash launches the Windows Maven wrapper through cmd.exe, which does not reliably inherit
     * newly exported variables. Exact-name system properties therefore override inherited
     * environment values; the checked-in harness uses that path and never puts a secret there.
     */
    private static Map<String, String> runtimeSettings() {
        var settings = new HashMap<>(System.getenv());
        for (String name : List.of(
                "STRATUS_AIRFLOW_BASE_URL",
                "STRATUS_AIRFLOW_ALLOW_HTTP",
                "STRATUS_AIRFLOW_ANONYMOUS_ADMIN",
                "STRATUS_AIRFLOW_USERNAME",
                "STRATUS_AIRFLOW_PASSWORD",
                "STRATUS_AIRFLOW_POLL_INTERVAL_MS",
                "STRATUS_AIRFLOW_RUN_TIMEOUT_MS",
                "STRATUS_AIRFLOW_POSITIVE_DAG_ID",
                "STRATUS_AIRFLOW_POSITIVE_RUN_ID",
                "STRATUS_AIRFLOW_POSITIVE_EXPECTED_RUN_STATE",
                "STRATUS_AIRFLOW_POSITIVE_CONF_BASE64",
                "STRATUS_AIRFLOW_POSITIVE_TASK_STATES_BASE64",
                "STRATUS_AIRFLOW_BLOCKED_DAG_ID",
                "STRATUS_AIRFLOW_BLOCKED_RUN_ID",
                "STRATUS_AIRFLOW_BLOCKED_EXPECTED_RUN_STATE",
                "STRATUS_AIRFLOW_BLOCKED_CONF_BASE64",
                "STRATUS_AIRFLOW_BLOCKED_TASK_STATES_BASE64")) {
            String override = System.getProperty(name);
            if (override != null) {
                settings.put(name, override);
            }
        }
        return Map.copyOf(settings);
    }

    private static long elapsedMs(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private record Scenario(
            String name,
            String dagId,
            String runId,
            String expectedRunState,
            Map<String, Object> configuration,
            Map<String, String> expectedTaskStates) {
    }
}
