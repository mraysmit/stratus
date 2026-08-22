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

/**
 * Offline contract for the immutable Airflow pipeline DAG layer introduced by {@code P1-4.3-V1}.
 *
 * <h2>Rationale</h2>
 *
 * <p>The DAG is executable control-plane policy, not a loose example. It decides which packaged
 * Spark class runs, which governed table it can change, how a landing object becomes a unique
 * batch, how long an absent object is sensed, how failures retry, and whether credentials are
 * resolved through protected Airflow connections or copied into source. A parseable DAG can still
 * be dangerously wrong when any one of those details drifts.
 *
 * <p>This first P1-4.3 slice therefore fixes the landing-to-bronze boundary before live execution:
 * one shared SparkSubmitOperator factory, one structured failure callback, one rescheduling S3
 * sensor, the real packaged {@code IngestionJob} and {@code QualityCheckJob} classes, an immutable
 * task chain, bounded retries, and no embedded endpoint or credential material. Runtime acceptance
 * must later prove the same files through Airflow's API and the real Spark/Polaris/Ceph stack.
 *
 * <h2>Maintenance</h2>
 *
 * <p>For a component or DAG version change, update these constants deliberately, observe the
 * focused test fail, then update the DAG and live harness together. UAT and production promotion
 * belong to the later hardening stage and must consume the same accepted DAG content by digest;
 * they must not fork class names, retry policy, table identifiers, or credential handling.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-22
 * @version 1.0.0
 */
@Tag("unit")
final class AirflowPipelineDagTest {

    private static final Path DAG_ROOT = Repo.root().resolve(
            Path.of("platform", "airflow", "developer", "dags"));
    private static final Path COMMON_DAG_PATH = Path.of("stratus_common.py");
    private static final Path ALERTS_DAG_PATH = Path.of("stratus_alerts.py");
    private static final Path LANDING_DAG_PATH = Path.of("stratus_landing_to_bronze.py");
    private static final Path DAG_PARSE_TEST_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-pipeline-dag-parse-test.sh"));
    private static final Path LANDING_LIVE_TEST_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-landing-to-bronze-live-test.sh"));
    private static final Path AIRFLOW_SPARK_COMMON_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "lib",
            "airflow-spark-common.sh"));
    private static final Path AIRFLOW_SPARK_OVERLAY_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "compose.spark.yaml"));

    private static final String SPARK_CONNECTION_ID = "spark_default";
    private static final String LANDING_CONNECTION_ID = "stratus_landing";
    private static final String LANDING_DAG_ID = "stratus_landing_to_bronze";
    private static final String INGESTION_CLASS = "dev.stratus.jobs.spark.IngestionJob";
    private static final String QUALITY_CLASS = "dev.stratus.jobs.spark.QualityCheckJob";
    private static final String BRONZE_TABLE = "stratus.bronze.customers";
    private static final String LANDING_BUCKET_VARIABLE = "stratus_landing_bucket";

    @Test
    void firstPipelineSliceHasStableImmutableLocations() {
        assertAll(
                () -> assertFile(COMMON_DAG_PATH),
                () -> assertFile(ALERTS_DAG_PATH),
                () -> assertFile(LANDING_DAG_PATH),
                () -> assertTrue(Files.isRegularFile(DAG_PARSE_TEST_PATH),
                        "The Airflow-owned DAG parser must be a checked-in test script"),
                () -> assertTrue(Files.isRegularFile(LANDING_LIVE_TEST_PATH),
                        "The live landing-to-bronze proof must be a checked-in test script"));
    }

    @Test
    void commonFactoryUsesProtectedConnectionAndMountedRuntime() {
        String common = read(COMMON_DAG_PATH);
        assertAll(
                () -> assertTrue(common.contains("SparkSubmitOperator")),
                () -> assertTrue(common.contains("conn_id=SPARK_CONNECTION_ID")),
                () -> assertTrue(common.contains("SPARK_CONNECTION_ID = \""
                        + SPARK_CONNECTION_ID + "\"")),
                () -> assertTrue(common.contains("/opt/stratus/jobs/stratus-spark-jobs.jar")),
                () -> assertTrue(common.contains("spark.driver.extraClassPath")),
                () -> assertTrue(common.contains("spark.eventLog.dir")),
                () -> assertFalse(common.contains("STRATUS_POLARIS_CLIENT_SECRET")),
                () -> assertFalse(common.contains("CEPH_RGW_SECRET_KEY")),
                () -> assertFalse(common.contains("spark://")));
    }

    @Test
    void landingDagIsBoundedObservableAndUsesThePackagedJobs() {
        String dag = read(LANDING_DAG_PATH);
        assertAll(
                () -> assertTrue(dag.contains("dag_id=\"" + LANDING_DAG_ID + "\"")),
                () -> assertTrue(dag.contains("retries\": 2")),
                () -> assertTrue(dag.contains("retry_delay\": timedelta(minutes=5)")),
                () -> assertTrue(dag.contains("max_active_runs=1")),
                () -> assertTrue(dag.contains("mode=\"reschedule\"")),
                () -> assertTrue(dag.contains("aws_conn_id=LANDING_CONNECTION_ID")),
                () -> assertTrue(dag.contains(LANDING_BUCKET_VARIABLE)),
                () -> assertTrue(dag.contains(INGESTION_CLASS)),
                () -> assertTrue(dag.contains(QUALITY_CLASS)),
                () -> assertTrue(dag.contains(BRONZE_TABLE)),
                () -> assertTrue(dag.contains("dag_run.conf.get")),
                () -> assertTrue(dag.contains("landing_object_key")),
                () -> assertTrue(dag.contains("bronze_table")),
                () -> assertTrue(dag.contains("pipeline_run_id")),
                () -> assertTrue(dag.contains("--batchId")),
                () -> assertTrue(dag.contains("wait_for_source_file >> run_ingestion "
                        + ">> run_bronze_quality")),
                () -> assertTrue(dag.contains("on_failure_callback=stratus_failure_alert")));
    }

    @Test
    void failureCallbackCarriesTheRequiredDiagnosticContext() {
        String alerts = read(ALERTS_DAG_PATH);
        assertAll(
                () -> assertTrue(alerts.contains("LOGGER.error")),
                () -> assertTrue(alerts.contains("event=airflow_task_failed")),
                () -> assertTrue(alerts.contains("dag_id")),
                () -> assertTrue(alerts.contains("task_id")),
                () -> assertTrue(alerts.contains("run_id")),
                () -> assertTrue(alerts.contains("logical_date")),
                () -> assertTrue(alerts.contains("try_number")),
                () -> assertTrue(alerts.contains("log_url")));
    }

    @Test
    void checkedInParseTestUsesTheLifecycleAndRecordsTiming() {
        String script = Repo.read(DAG_PARSE_TEST_PATH);
        assertAll(
                () -> assertTrue(script.contains("airflow-compose-startup.sh")),
                () -> assertTrue(script.contains("airflow-compose-verify-health.sh")),
                () -> assertTrue(script.contains("airflow-compose-shutdown.sh")),
                () -> assertTrue(script.contains("airflow dags list-import-errors")),
                () -> assertTrue(script.contains("airflow dags list")),
                () -> assertTrue(script.contains(LANDING_DAG_ID)),
                () -> assertTrue(script.contains("suiteRunId=")),
                () -> assertTrue(script.contains("elapsedMs=")));
    }

    @Test
    void livePipelineTestUsesProtectedIdentitiesAndProvesCleanupAndTiming() {
        String script = Repo.read(LANDING_LIVE_TEST_PATH);
        String common = Repo.read(AIRFLOW_SPARK_COMMON_PATH);
        String overlay = Repo.read(AIRFLOW_SPARK_OVERLAY_PATH);
        assertAll(
                () -> assertTrue(common.contains("fetch_airflow_storage_identity")),
                () -> assertTrue(common.contains("svc-airflow")),
                () -> assertTrue(overlay.contains("AIRFLOW_LANDING_RGW_ACCESS_KEY")),
                () -> assertTrue(overlay.contains("AIRFLOW_LANDING_RGW_SECRET_KEY")),
                () -> assertTrue(overlay.contains("stratus-ca.crt:ro")),
                () -> assertTrue(script.contains("airflow-compose-startup.sh")),
                () -> assertTrue(script.contains("airflow-compose-shutdown.sh")),
                () -> assertTrue(script.contains("airflow connections add "
                        + LANDING_CONNECTION_ID)),
                () -> assertTrue(script.contains("readonly LANDING_BUCKET_VARIABLE=\""
                        + LANDING_BUCKET_VARIABLE + "\"")),
                () -> assertTrue(script.contains(
                        "airflow variables set \"$LANDING_BUCKET_VARIABLE\"")),
                () -> assertTrue(script.contains("readonly DAG_ID=\"" + LANDING_DAG_ID + "\"")),
                () -> assertTrue(script.contains("airflow dags test \"$DAG_ID\"")),
                () -> assertTrue(script.contains("dev.stratus.jobs.spark.AirflowPipelineVerifierJob")),
                () -> assertTrue(script.contains("event=airflow_pipeline_phase_completed")),
                () -> assertTrue(script.contains("elapsedMs=")),
                () -> assertTrue(script.contains("assert_not_logged")),
                () -> assertTrue(script.contains("cleanup")));
    }

    private static void assertFile(Path relative) {
        assertTrue(Files.isRegularFile(DAG_ROOT.resolve(relative)),
                () -> "Missing Airflow DAG artifact: " + relative);
    }

    private static String read(Path relative) {
        Path file = DAG_ROOT.resolve(relative);
        assertTrue(Files.isRegularFile(file), () -> "Missing Airflow DAG artifact: " + relative);
        return Repo.read(file);
    }
}
