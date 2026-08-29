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
 * one shared SparkSubmitOperator factory, one lightweight catalog-gate factory, one structured
 * failure callback, one rescheduling S3
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
    private static final Path BRONZE_TO_SILVER_DAG_PATH =
            Path.of("stratus_bronze_to_silver.py");
    private static final Path SILVER_TO_GOLD_DAG_PATH =
            Path.of("stratus_silver_to_gold.py");
    private static final Path TABLE_MAINTENANCE_DAG_PATH =
            Path.of("stratus_table_maintenance.py");
    private static final Path API_CONTRACT_PROBE_DAG_PATH =
            Path.of("stratus_api_contract_probe.py");
    private static final Path DAG_PARSE_TEST_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-pipeline-dag-parse-test.sh"));
    private static final Path LANDING_LIVE_TEST_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-landing-to-bronze-live-test.sh"));
    private static final Path BRONZE_TO_SILVER_LIVE_TEST_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-bronze-to-silver-live-test.sh"));
    private static final Path SILVER_TO_GOLD_LIVE_TEST_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-silver-to-gold-live-test.sh"));
    private static final Path TABLE_MAINTENANCE_LIVE_TEST_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-table-maintenance-live-test.sh"));
    private static final Path API_ORCHESTRATION_LIVE_TEST_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-api-orchestration-live-test.sh"));
    private static final Path RETRY_ALERT_PROBE_DAG_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests", "dags",
            "stratus_retry_alert_probe.py"));
    private static final Path RETRY_ALERT_OVERLAY_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "compose.retry-alert.yaml"));
    private static final Path RETRY_ALERT_LIVE_TEST_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-retry-alert-live-test.sh"));
    private static final Path DEADLINE_ALERT_PROBE_DAG_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests", "dags",
            "deadline-alert", "stratus_deadline_alert_probe.py"));
    private static final Path DEADLINE_ALERT_OVERLAY_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "compose.deadline-alert.yaml"));
    private static final Path DEADLINE_ALERT_LIVE_TEST_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "tests",
            "airflow-deadline-alert-live-test.sh"));
    private static final Path AIRFLOW_SPARK_COMMON_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "scripts", "lib",
            "airflow-spark-common.sh"));
    private static final Path AIRFLOW_SPARK_OVERLAY_PATH = Repo.root().resolve(Path.of(
            "platform", "airflow", "developer", "compose.spark.yaml"));

    private static final String SPARK_CONNECTION_ID = "spark_default";
    private static final String RETRY_ALERT_PROBE_DAG_ID = "stratus_retry_alert_probe";
    private static final String DEADLINE_ALERT_PROBE_DAG_ID = "stratus_deadline_alert_probe";
    private static final String HADOOP_AWS_JAR = "/opt/stratus/runtime/hadoop-aws.jar";
    private static final String AWS_SDK_BUNDLE_JAR = "/opt/stratus/runtime/aws-sdk-bundle.jar";
    private static final String S3_ACCELERATOR_JAR =
            "/opt/stratus/runtime/analyticsaccelerator-s3.jar";
    private static final String AWS_BUNDLE_LOGGING_BRIDGE_JAR =
            "/opt/stratus/runtime/log4j-slf4j-impl.jar";
    private static final String AIRFLOW_SPARK_EVENT_LOG_DIRECTORY =
            "/opt/airflow/logs/spark-events";
    private static final String LANDING_CONNECTION_ID = "stratus_landing";
    private static final String LANDING_DAG_ID = "stratus_landing_to_bronze";
    private static final String BRONZE_TO_SILVER_DAG_ID = "stratus_bronze_to_silver";
    private static final String SILVER_TO_GOLD_DAG_ID = "stratus_silver_to_gold";
    private static final String TABLE_MAINTENANCE_DAG_ID = "stratus_table_maintenance";
    private static final String API_CONTRACT_PROBE_DAG_ID = "stratus_api_contract_probe";
    private static final String INGESTION_CLASS = "dev.stratus.jobs.spark.IngestionJob";
    private static final String QUALITY_CLASS = "dev.stratus.jobs.spark.QualityCheckJob";
    private static final String PROMOTION_GATE_CLASS =
            "dev.stratus.jobs.spark.CatalogPromotionGateJob";
    private static final String TRANSFORM_CLASS = "dev.stratus.jobs.spark.TransformJob";
    private static final String MATERIALISATION_CLASS =
            "dev.stratus.jobs.spark.MaterialisationJob";
    private static final String TABLE_MAINTENANCE_CLASS =
            "dev.stratus.jobs.spark.TableMaintenanceJob";
    private static final String BRONZE_TO_SILVER_VERIFIER_CLASS =
            "dev.stratus.jobs.spark.AirflowBronzeToSilverVerifierJob";
    private static final String CATALOG_TABLE_STATE_CLASS =
            "dev.stratus.jobs.spark.CatalogTableStateJob";
    private static final String SILVER_TO_GOLD_FIXTURE_CLASS =
            "dev.stratus.jobs.spark.AirflowSilverToGoldFixtureJob";
    private static final String SILVER_TO_GOLD_CATALOG_VERIFIER_CLASS =
            "dev.stratus.jobs.spark.CatalogSilverToGoldVerifierJob";
    private static final String TABLE_MAINTENANCE_VERIFIER_CLASS =
            "dev.stratus.jobs.spark.AirflowTableMaintenanceVerifierJob";
    private static final String BRONZE_TABLE = "stratus.bronze.customers";
    private static final String SILVER_TABLE = "stratus.silver.customers";
    private static final String GOLD_TABLE = "stratus.gold.customer_summary";
    private static final String LANDING_BUCKET_VARIABLE = "stratus_landing_bucket";
    private static final String BRONZE_TO_SILVER_RETRIES_ENV =
            "STRATUS_BRONZE_TO_SILVER_RETRIES";
    private static final String SILVER_TO_GOLD_RETRIES_ENV =
            "STRATUS_SILVER_TO_GOLD_RETRIES";

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
    void secondPipelineSliceHasStableImmutableLocations() {
        assertAll(
                () -> assertFile(BRONZE_TO_SILVER_DAG_PATH),
                () -> assertTrue(Files.isRegularFile(BRONZE_TO_SILVER_LIVE_TEST_PATH),
                        "The accepted and blocked bronze-to-silver proof must be checked in under "
                                + "the Airflow test-scripts folder"));
    }

    @Test
    void thirdPipelineSliceHasStableImmutableLocations() {
        assertAll(
                () -> assertFile(SILVER_TO_GOLD_DAG_PATH),
                () -> assertTrue(Files.isRegularFile(SILVER_TO_GOLD_LIVE_TEST_PATH),
                        "The accepted and blocked silver-to-gold proof must be checked in under "
                                + "the Airflow test-scripts folder"));
    }

    @Test
    void maintenanceSliceHasStableImmutableLocations() {
        assertAll(
                () -> assertFile(TABLE_MAINTENANCE_DAG_PATH),
                () -> assertTrue(Files.isRegularFile(TABLE_MAINTENANCE_LIVE_TEST_PATH),
                        "The run/skip maintenance proof must be checked in under the Airflow "
                                + "test-scripts folder"));
    }

    @Test
    void commonFactoryUsesProtectedConnectionAndMountedRuntime() {
        String common = read(COMMON_DAG_PATH);
        assertAll(
                () -> assertTrue(common.contains("SparkSubmitOperator")),
                () -> assertTrue(common.contains("BashOperator")),
                () -> assertTrue(common.contains("catalog_promotion_gate_task")),
                () -> assertTrue(common.contains(PROMOTION_GATE_CLASS)),
                () -> assertTrue(common.contains("--catalogProperties")),
                () -> assertTrue(common.contains("/opt/spark/jars/*")),
                () -> assertTrue(common.contains("conn_id=SPARK_CONNECTION_ID")),
                () -> assertTrue(common.contains("SPARK_CONNECTION_ID = \""
                        + SPARK_CONNECTION_ID + "\"")),
                () -> assertTrue(common.contains("/opt/stratus/jobs/stratus-spark-jobs.jar")),
                () -> assertTrue(common.contains(HADOOP_AWS_JAR),
                        "direct s3a landing reads require Hadoop's S3A implementation"),
                () -> assertTrue(common.contains(AWS_SDK_BUNDLE_JAR),
                        "Hadoop S3A requires its locked, unrelocated AWS SDK bundle"),
                () -> assertTrue(common.contains(S3_ACCELERATOR_JAR),
                        "Hadoop 3.4.3 S3A links its pinned analytics accelerator at startup"),
                () -> assertTrue(common.contains(AWS_BUNDLE_LOGGING_BRIDGE_JAR),
                        "the AWS bundle's legacy SLF4J contract must route into Log4j2"),
                () -> assertTrue(common.contains("spark.driver.extraClassPath")),
                () -> assertTrue(common.contains("spark.eventLog.dir")),
                () -> assertTrue(common.contains("verbose=False"),
                        "routine DAG submissions must not emit spark-submit's verbose configuration dump"),
                () -> assertTrue(common.contains("STRATUS_DISABLE_DAG_SCHEDULES")),
                () -> assertTrue(common.contains("test_isolated_schedule")),
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
                () -> assertFalse(dag.contains("conf.get(\\\"landing_object_key\\\","),
                        "Jinja eagerly evaluates get() defaults, so an API run with no ds would retry"),
                () -> assertTrue(dag.contains("conf.get(\\\"landing_object_key\\\") or"),
                        "The configured object must short-circuit the scheduled-run fallback"),
                () -> assertTrue(dag.contains("bronze_table")),
                () -> assertTrue(dag.contains("pipeline_run_id")),
                () -> assertTrue(dag.contains("--batchId")),
                () -> assertTrue(dag.contains("wait_for_source_file >> run_ingestion "
                        + ">> run_bronze_quality")),
                () -> assertTrue(dag.contains("on_failure_callback=stratus_failure_alert")));
    }

    @Test
    void bronzeToSilverDagGatesBeforeWritingAndRecordsSilverQuality() {
        String dag = read(BRONZE_TO_SILVER_DAG_PATH);
        assertAll(
                () -> assertTrue(dag.contains("dag_id=\"" + BRONZE_TO_SILVER_DAG_ID + "\"")),
                () -> assertTrue(dag.contains("retries\": configured_retries()")),
                () -> assertTrue(dag.contains("retry_delay\": timedelta(minutes=5)")),
                () -> assertTrue(dag.contains("DEFAULT_RETRIES = 2")),
                () -> assertTrue(dag.contains(BRONZE_TO_SILVER_RETRIES_ENV)),
                () -> assertTrue(dag.contains("max_active_runs=1")),
                () -> assertTrue(dag.contains(TRANSFORM_CLASS)),
                () -> assertTrue(dag.contains(QUALITY_CLASS)),
                () -> assertTrue(dag.contains("catalog_promotion_gate_task")),
                () -> assertTrue(dag.contains(BRONZE_TABLE)),
                () -> assertTrue(dag.contains(SILVER_TABLE)),
                () -> assertTrue(dag.contains("bronze_table")),
                () -> assertTrue(dag.contains("silver_table")),
                () -> assertTrue(dag.contains("source_batch")),
                () -> assertTrue(dag.contains("quality_run_id")),
                () -> assertTrue(dag.contains("pipeline_run_id")),
                () -> assertTrue(dag.contains("--qualityRunId"),
                        "TransformJob must enforce the recorded bronze verdict before writing"),
                () -> assertTrue(dag.contains("task_id=\"evaluate_bronze_promotion\""),
                        "Airflow must expose the bronze promotion decision as its own task"),
                () -> assertTrue(dag.contains("run_id=QUALITY_RUN_ID")),
                () -> assertTrue(dag.contains("target_table=SOURCE_TABLE")),
                () -> assertTrue(dag.contains("--sourceBatch"),
                        "Each run must transform only the correlated bronze delivery"),
                () -> assertTrue(dag.contains("--businessKey")),
                () -> assertTrue(dag.contains("--sequenceColumn")),
                () -> assertTrue(dag.contains("evaluate_bronze_promotion "
                        + ">> run_silver_transform >> run_silver_quality")),
                () -> assertTrue(dag.contains("on_failure_callback=stratus_failure_alert")));
    }

    @Test
    void silverToGoldDagGatesBeforeWritingAndRecordsGoldQuality() {
        String dag = read(SILVER_TO_GOLD_DAG_PATH);
        assertAll(
                () -> assertTrue(dag.contains("dag_id=\"" + SILVER_TO_GOLD_DAG_ID + "\"")),
                () -> assertTrue(dag.contains("retries\": configured_retries()")),
                () -> assertTrue(dag.contains("retry_delay\": timedelta(minutes=5)")),
                () -> assertTrue(dag.contains("DEFAULT_RETRIES = 2")),
                () -> assertTrue(dag.contains(SILVER_TO_GOLD_RETRIES_ENV)),
                () -> assertTrue(dag.contains("max_active_runs=1")),
                () -> assertTrue(dag.contains(QUALITY_CLASS)),
                () -> assertTrue(dag.contains("catalog_promotion_gate_task")),
                () -> assertTrue(dag.contains(MATERIALISATION_CLASS)),
                () -> assertTrue(dag.contains(SILVER_TABLE)),
                () -> assertTrue(dag.contains(GOLD_TABLE)),
                () -> assertTrue(dag.contains("silver_table")),
                () -> assertTrue(dag.contains("gold_table")),
                () -> assertTrue(dag.contains("quality_run_id")),
                () -> assertTrue(dag.contains("pipeline_run_id")),
                () -> assertTrue(dag.contains("--qualityRunId"),
                        "MaterialisationJob must enforce silver quality before writing gold"),
                () -> assertTrue(dag.contains("task_id=\"evaluate_silver_promotion\""),
                        "Airflow must expose the silver promotion decision as its own task"),
                () -> assertTrue(dag.contains("run_id=QUALITY_RUN_ID")),
                () -> assertTrue(dag.contains("target_table=SOURCE_TABLE")),
                () -> assertTrue(dag.contains("--sourceTables")),
                () -> assertTrue(dag.contains("--sql")),
                () -> assertTrue(dag.contains("customer_count")),
                () -> assertTrue(dag.contains("run_silver_quality >> evaluate_silver_promotion "
                        + ">> run_gold_materialisation >> run_gold_quality")),
                () -> assertTrue(dag.contains("on_failure_callback=stratus_failure_alert")));
    }

    @Test
    void maintenanceDagDelegatesMetadataPolicyWithoutNamingOperations() {
        String dag = read(TABLE_MAINTENANCE_DAG_PATH);
        assertAll(
                () -> assertTrue(dag.contains("dag_id=\"" + TABLE_MAINTENANCE_DAG_ID + "\"")),
                () -> assertTrue(dag.contains("retries\": 1")),
                () -> assertTrue(dag.contains("retry_delay\": timedelta(minutes=10)")),
                () -> assertTrue(dag.contains("max_active_runs=1")),
                () -> assertTrue(dag.contains(TABLE_MAINTENANCE_CLASS)),
                () -> assertTrue(dag.contains("target_table")),
                () -> assertTrue(dag.contains("policy")),
                () -> assertTrue(dag.contains("--targetTable")),
                () -> assertTrue(dag.contains("--policy")),
                () -> assertTrue(dag.contains("--runId")),
                () -> assertFalse(dag.contains("--operations"),
                        "The DAG must not bypass metadata policy by naming procedures"),
                () -> assertTrue(dag.contains("on_failure_callback=stratus_failure_alert")));
    }

    @Test
    void failureCallbackCarriesTheRequiredDiagnosticContext() {
        String alerts = read(ALERTS_DAG_PATH);
        assertAll(
                () -> assertTrue(alerts.contains("LOGGER.error")),
                () -> assertTrue(alerts.contains("event=airflow_task_failed")),
                () -> assertTrue(alerts.contains("event=airflow_task_retry")),
                () -> assertTrue(alerts.contains("dag_id")),
                () -> assertTrue(alerts.contains("task_id")),
                () -> assertTrue(alerts.contains("run_id")),
                () -> assertTrue(alerts.contains("logical_date")),
                () -> assertTrue(alerts.contains("try_number")),
                () -> assertTrue(alerts.contains("log_url")),
                () -> assertTrue(alerts.contains("duration_ms")),
                () -> assertTrue(alerts.contains("start_date"),
                        "duration must fall back to elapsed wall time during callbacks"),
                () -> assertTrue(alerts.contains("exception_class")),
                () -> assertFalse(alerts.contains("exception_message"),
                        "structured alerts must not copy arbitrary exception text"));
    }

    @Test
    void retryAlertProofHasStableTestOnlyLocations() {
        assertAll(
                () -> assertTrue(Files.isRegularFile(RETRY_ALERT_PROBE_DAG_PATH),
                        "The controlled probe DAG must live below scripts/tests/dags"),
                () -> assertTrue(Files.isRegularFile(RETRY_ALERT_OVERLAY_PATH),
                        "The probe DAG must be mounted only by a checked-in test overlay"),
                () -> assertTrue(Files.isRegularFile(RETRY_ALERT_LIVE_TEST_PATH),
                        "The retry/alert proof must be a checked-in test script"),
                () -> assertFalse(Files.isRegularFile(
                        DAG_ROOT.resolve(RETRY_ALERT_PROBE_DAG_PATH.getFileName())),
                        "The development-only probe must not become a fifth platform DAG"));
    }

    @Test
    void retryAlertProbeUsesAirflowAttemptStateAndBoundedRetries() {
        String probe = Repo.read(RETRY_ALERT_PROBE_DAG_PATH);
        String overlay = Repo.read(RETRY_ALERT_OVERLAY_PATH);
        assertAll(
                () -> assertTrue(probe.contains("DAG_ID = \""
                        + RETRY_ALERT_PROBE_DAG_ID + "\"")),
                () -> assertTrue(probe.contains("dag_id=DAG_ID")),
                () -> assertTrue(probe.contains(
                        "from airflow.sdk.bases.operator import BaseOperator")),
                () -> assertFalse(probe.contains("airflow.models.baseoperator"),
                        "Airflow 3 probes must use the supported public SDK import"),
                () -> assertTrue(probe.contains("retries\": 1")),
                () -> assertTrue(probe.contains("retry_delay\": timedelta(seconds=1)")),
                () -> assertTrue(probe.contains("task_instance.try_number")),
                () -> assertTrue(probe.contains("mode == TRANSIENT_MODE")),
                () -> assertTrue(probe.contains("mode == PERMANENT_MODE")),
                () -> assertTrue(probe.contains("event=airflow_retry_alert_probe_attempt")),
                () -> assertTrue(probe.contains("event=airflow_retry_alert_probe_succeeded")),
                () -> assertTrue(probe.contains("on_retry_callback=stratus_retry_alert")),
                () -> assertTrue(probe.contains("on_failure_callback=stratus_failure_alert")),
                () -> assertTrue(overlay.contains(
                        "./scripts/tests/dags:/opt/airflow/dags:ro")),
                () -> assertTrue(overlay.contains(
                        "./dags:/opt/airflow/platform-dags:ro")),
                () -> assertTrue(overlay.contains(
                        "PYTHONPATH: /opt/airflow/platform-dags")),
                () -> assertFalse(overlay.contains(
                        "/opt/airflow/dags/stratus_retry_alert_probe.py:ro"),
                        "a file cannot be mounted below the read-only platform DAG mount"));
    }

    @Test
    void liveRetryAlertTestProvesRecoveryAndTerminalFailureObservability() {
        String script = Repo.read(RETRY_ALERT_LIVE_TEST_PATH);
        assertAll(
                () -> assertTrue(script.contains("compose.retry-alert.yaml")),
                () -> assertTrue(script.contains("airflow-compose-startup.sh")),
                () -> assertTrue(script.contains("airflow-compose-shutdown.sh")),
                () -> assertTrue(script.contains("readonly DAG_ID=\""
                        + RETRY_ALERT_PROBE_DAG_ID + "\"")),
                () -> assertTrue(script.contains("airflow dags trigger \"$DAG_ID\"")),
                () -> assertTrue(script.contains("airflow dags list-runs \"$DAG_ID\"")),
                () -> assertTrue(script.contains("capture_run_logs")),
                () -> assertFalse(script.contains("airflow dags test \"$DAG_ID\""),
                        "the in-process DAG runner races a live Airflow scheduler"),
                () -> assertTrue(script.contains(
                        "trigger_probe \"$transient_run_id\" \"$transient_correlation\" transient success")),
                () -> assertTrue(script.contains(
                        "trigger_probe \"$permanent_run_id\" \"$permanent_correlation\" permanent failed")),
                () -> assertTrue(script.contains("tryNumber=1")),
                () -> assertTrue(script.contains("tryNumber=2")),
                () -> assertTrue(script.contains("event=airflow_task_retry")),
                () -> assertTrue(script.contains("event=airflow_task_failed")),
                () -> assertTrue(script.contains("exception_class=AirflowException")),
                () -> assertTrue(script.contains("duration_ms=[0-9]")),
                () -> assertTrue(script.contains("alert_count")),
                () -> assertTrue(script.contains("assert_not_logged")),
                () -> assertTrue(script.contains("event=airflow_retry_alert_phase_completed")),
                () -> assertTrue(script.contains("elapsedMs=")),
                () -> assertTrue(script.contains("cleanup")));
    }

    @Test
    void deadlineAlertProofHasStableTestOnlyLocations() {
        assertAll(
                () -> assertTrue(Files.isRegularFile(DEADLINE_ALERT_PROBE_DAG_PATH),
                        "The controlled Deadline Alert DAG must live below scripts/tests/dags"),
                () -> assertTrue(Files.isRegularFile(DEADLINE_ALERT_OVERLAY_PATH),
                        "The Deadline Alert DAG must be mounted only by a checked-in test overlay"),
                () -> assertTrue(Files.isRegularFile(DEADLINE_ALERT_LIVE_TEST_PATH),
                        "The Deadline Alert proof must be a checked-in test script"),
                () -> assertFalse(Files.isRegularFile(
                        DAG_ROOT.resolve(DEADLINE_ALERT_PROBE_DAG_PATH.getFileName())),
                        "The development-only Deadline Alert probe must not become a platform DAG"));
    }

    @Test
    void deadlineAlertProbeUsesTheAirflowDeadlineModelAndAsyncCallback() {
        String probe = Repo.read(DEADLINE_ALERT_PROBE_DAG_PATH);
        String overlay = Repo.read(DEADLINE_ALERT_OVERLAY_PATH);
        String alerts = read(ALERTS_DAG_PATH);
        assertAll(
                () -> assertTrue(probe.contains(
                        "DAG_ID = os.environ.get(\"STRATUS_DEADLINE_PROBE_DAG_ID\", \""
                                + DEADLINE_ALERT_PROBE_DAG_ID + "\")"),
                        "shared test-DAG mounts must parse without the Deadline-specific overlay"),
                () -> assertTrue(probe.contains("dag_id=DAG_ID")),
                () -> assertTrue(probe.contains("DeadlineAlert(")),
                () -> assertTrue(probe.contains(
                        "reference=DeadlineReference.DAGRUN_QUEUED_AT")),
                () -> assertTrue(probe.contains("interval=DEADLINE_INTERVAL")),
                () -> assertTrue(probe.contains("timedelta(seconds=12)")),
                () -> assertTrue(probe.contains("MISSED_SLEEP_SECONDS = 35")),
                () -> assertTrue(probe.contains("AsyncCallback(")),
                () -> assertTrue(probe.contains(
                        "\"stratus_alerts.stratus_deadline_alert\"")),
                () -> assertTrue(probe.contains("expected_interval_ms")),
                () -> assertTrue(probe.contains("deadline_name")),
                () -> assertTrue(probe.contains("time.sleep")),
                () -> assertTrue(probe.contains("dag_run.conf.get")),
                () -> assertTrue(alerts.contains("async def stratus_deadline_alert")),
                () -> assertTrue(alerts.contains("event=airflow_deadline_missed")),
                () -> assertTrue(alerts.contains("correlation_id")),
                () -> assertTrue(alerts.contains("deadline_time")),
                () -> assertTrue(alerts.contains("expected_interval_ms")),
                () -> assertTrue(alerts.contains("observed_elapsed_ms")),
                () -> assertTrue(alerts.contains("breach_ms")),
                () -> assertTrue(overlay.contains(
                        "./scripts/tests/dags/deadline-alert:/opt/airflow/dags:ro")),
                () -> assertTrue(overlay.contains(
                        "./dags:/opt/airflow/platform-dags:ro")),
                () -> assertTrue(overlay.contains(
                        "PYTHONPATH: /opt/airflow/platform-dags")),
                () -> assertTrue(overlay.contains(
                        "STRATUS_DEADLINE_PROBE_DAG_ID: ${STRATUS_DEADLINE_PROBE_DAG_ID:?")),
                () -> assertTrue(overlay.contains(
                        "AIRFLOW__SCHEDULER__SCHEDULER_HEARTBEAT_SEC: 2")));
    }

    @Test
    void liveDeadlineAlertTestProvesOnTimeAndMissedOutcomes() {
        String script = Repo.read(DEADLINE_ALERT_LIVE_TEST_PATH);
        assertAll(
                () -> assertTrue(script.contains("compose.deadline-alert.yaml")),
                () -> assertTrue(script.contains("airflow-compose-startup.sh")),
                () -> assertTrue(script.contains("airflow-compose-shutdown.sh")),
                () -> assertTrue(script.contains("readonly DAG_ID_PREFIX=\""
                        + DEADLINE_ALERT_PROBE_DAG_ID + "\"")),
                () -> assertTrue(script.contains(
                        "DAG_ID=\"${STRATUS_DEADLINE_PROBE_DAG_ID:-${DAG_ID_PREFIX}_")),
                () -> assertTrue(script.contains("export STRATUS_DEADLINE_PROBE_DAG_ID=\"$DAG_ID\"")),
                () -> assertTrue(script.contains("airflow dags trigger \"$DAG_ID\"")),
                () -> assertTrue(script.contains(
                        "trigger_probe \"$on_time_run_id\" \"$on_time_correlation\" on-time 1")),
                () -> assertTrue(script.contains(
                        "trigger_probe \"$missed_run_id\" \"$missed_correlation\" missed 35")),
                () -> assertTrue(script.contains("SCHEDULER_HEARTBEAT_SECONDS=2")),
                () -> assertTrue(script.contains("wait_for_run_state")),
                () -> assertTrue(script.contains("compose logs --no-color airflow-triggerer")),
                () -> assertTrue(script.contains("event=airflow_deadline_missed")),
                () -> assertTrue(script.contains("deadline_alert_count")),
                () -> assertTrue(script.contains("expected_interval_ms=12000")),
                () -> assertTrue(script.contains("observed_elapsed_ms=[0-9]")),
                () -> assertTrue(script.contains("breach_ms=[0-9]")),
                () -> assertTrue(script.contains("assert_not_logged")),
                () -> assertTrue(script.contains("event=airflow_deadline_alert_phase_completed")),
                () -> assertTrue(script.contains("elapsedMs=")),
                () -> assertTrue(script.contains("capture_failure_diagnostics"),
                        "live failures must retain scheduler, triggerer, and task diagnostics"),
                () -> assertTrue(script.contains("airflow dags delete \"$DAG_ID\" -y"),
                        "each run must remove its isolated test-only DAG metadata"),
                () -> assertTrue(script.contains("cleanup")));
    }

    @Test
    void checkedInParseTestUsesTheLifecycleAndRecordsTiming() {
        String script = Repo.read(DAG_PARSE_TEST_PATH);
        assertAll(
                () -> assertTrue(script.contains("airflow-compose-startup.sh")),
                () -> assertTrue(script.contains("airflow-compose-verify-health.sh"),
                        "suite-scoped reuse must verify the shared deployment before parsing"),
                () -> assertTrue(script.contains("airflow-compose-shutdown.sh")),
                () -> assertTrue(script.contains("airflow dags list-import-errors")),
                () -> assertTrue(script.contains("airflow dags list")),
                () -> assertTrue(script.contains(LANDING_DAG_ID)),
                () -> assertTrue(script.contains(BRONZE_TO_SILVER_DAG_ID)),
                () -> assertTrue(script.contains(SILVER_TO_GOLD_DAG_ID)),
                () -> assertTrue(script.contains(TABLE_MAINTENANCE_DAG_ID)),
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
                () -> assertTrue(common.contains("verify_protected_connections")),
                () -> assertTrue(overlay.contains("AIRFLOW_LANDING_RGW_ACCESS_KEY")),
                () -> assertTrue(overlay.contains("AIRFLOW_LANDING_RGW_SECRET_KEY")),
                () -> assertTrue(overlay.contains("AIRFLOW_CONN_SPARK_DEFAULT")),
                () -> assertTrue(overlay.contains("AIRFLOW_CONN_STRATUS_LANDING")),
                () -> assertTrue(overlay.contains("AIRFLOW_VAR_STRATUS_LANDING_BUCKET")),
                () -> assertTrue(overlay.contains("STRATUS_DISABLE_DAG_SCHEDULES")),
                () -> assertTrue(overlay.contains("STRATUS_LOG_LEVEL: ${STRATUS_LOG_LEVEL:-INFO}")),
                () -> assertTrue(overlay.contains("stratus-ca.crt:ro")),
                () -> assertTrue(overlay.contains("hadoop-aws.jar:ro")),
                () -> assertTrue(overlay.contains("aws-sdk-bundle.jar:ro")),
                () -> assertTrue(overlay.contains("analyticsaccelerator-s3.jar:ro")),
                () -> assertTrue(overlay.contains("log4j-slf4j-impl.jar:ro")),
                () -> assertTrue(script.contains("airflow-compose-startup.sh")),
                () -> assertTrue(script.contains("airflow-compose-shutdown.sh")),
                () -> assertTrue(script.contains("verify_protected_connections")),
                () -> assertFalse(script.contains("airflow connections add"),
                        "focused tests must not write environment-backed credentials to metadata"),
                () -> assertTrue(script.contains("readonly LANDING_BUCKET_VARIABLE=\""
                        + LANDING_BUCKET_VARIABLE + "\"")),
                () -> assertTrue(script.contains("readonly DAG_ID=\"" + LANDING_DAG_ID + "\"")),
                () -> assertFalse(script.contains("airflow dags test"),
                        "Canonical live data paths must execute through the real scheduler"),
                () -> assertTrue(script.contains("STRATUS_AIRFLOW_SCENARIOS_BASE64")),
                () -> assertTrue(script.contains(
                        "\\\"wait_for_source_file\\\":\\\"success\\\"")),
                () -> assertTrue(script.contains(
                        "\\\"run_ingestion\\\":\\\"success\\\"")),
                () -> assertTrue(script.contains(
                        "\\\"run_bronze_quality\\\":\\\"success\\\"")),
                () -> assertTrue(script.contains("\\\"expectedSparkApplications\\\":2")),
                () -> assertTrue(script.contains("dev.stratus.jobs.spark.AirflowPipelineVerifierJob")),
                () -> assertTrue(script.contains(
                        "mkdir -p " + AIRFLOW_SPARK_EVENT_LOG_DIRECTORY),
                        "the standalone verifier must create its inherited Spark event-log directory"),
                () -> assertTrue(script.contains(
                        "--conf spark.eventLog.dir=file://" + AIRFLOW_SPARK_EVENT_LOG_DIRECTORY),
                        "the standalone verifier must use Airflow's writable Spark event-log directory"),
                () -> assertTrue(script.contains("event=airflow_pipeline_phase_completed")),
                () -> assertTrue(script.contains("elapsedMs=")),
                () -> assertTrue(script.contains("assert_not_logged")),
                () -> assertTrue(script.contains(
                        "assert_not_logged \"$AIRFLOW_SPARK_RGW_ACCESS_KEY\"")),
                () -> assertTrue(script.contains(
                        "assert_not_logged \"$AIRFLOW_LANDING_RGW_ACCESS_KEY\"")),
                () -> assertTrue(script.contains("cleanup")));
    }

    @Test
    void liveBronzeToSilverTestProvesAcceptedAndBlockedOutcomesWithCleanupAndTiming() {
        String script = Repo.read(BRONZE_TO_SILVER_LIVE_TEST_PATH);
        String overlay = Repo.read(AIRFLOW_SPARK_OVERLAY_PATH);
        assertAll(
                () -> assertTrue(script.contains("airflow-compose-startup.sh")),
                () -> assertTrue(script.contains("airflow-compose-shutdown.sh")),
                () -> assertTrue(script.contains("readonly DAG_ID=\""
                        + BRONZE_TO_SILVER_DAG_ID + "\"")),
                () -> assertTrue(script.contains("airflow dags test \"$DAG_ID\"")),
                () -> assertTrue(script.contains(BRONZE_TO_SILVER_VERIFIER_CLASS)),
                () -> assertTrue(script.contains("--expectedOutcome")),
                () -> assertTrue(script.contains("run_verifier accepted")),
                () -> assertFalse(script.contains("run_verifier blocked"),
                        "The blocked path must not start a redundant Spark verifier"),
                () -> assertFalse(script.contains("stage_bronze \"blocked\""),
                        "Fail-closed orchestration does not require a second ingestion run"),
                () -> assertTrue(script.contains(CATALOG_TABLE_STATE_CLASS)),
                () -> assertTrue(script.contains("--expectedState absent")),
                () -> assertTrue(script.contains("export " + BRONZE_TO_SILVER_RETRIES_ENV
                        + "=0"),
                        "The expected-failure proof must not idle through normal retry delays"),
                () -> assertTrue(overlay.contains(BRONZE_TO_SILVER_RETRIES_ENV)),
                () -> assertTrue(script.contains("AIRFLOW BRONZE TO SILVER VERIFIED")),
                () -> assertTrue(script.contains("CATALOG TABLE STATE VERIFIED")),
                () -> assertTrue(script.contains("event=airflow_bronze_to_silver_phase_completed")),
                () -> assertTrue(script.contains("elapsedMs=")),
                () -> assertTrue(script.contains("assert_not_logged")),
                () -> assertTrue(script.contains(
                        "assert_not_logged \"$AIRFLOW_SPARK_RGW_ACCESS_KEY\"")),
                () -> assertTrue(script.contains("cleanup")));
    }

    @Test
    void liveSilverToGoldTestUsesBoundaryFixturesRealSchedulingAndBoundedSparkWork() {
        String script = Repo.read(SILVER_TO_GOLD_LIVE_TEST_PATH);
        String overlay = Repo.read(AIRFLOW_SPARK_OVERLAY_PATH);
        assertAll(
                () -> assertTrue(script.contains("airflow-compose-startup.sh")),
                () -> assertTrue(script.contains("airflow-compose-shutdown.sh")),
                () -> assertTrue(script.contains("readonly DAG_ID=\""
                        + SILVER_TO_GOLD_DAG_ID + "\"")),
                () -> assertFalse(script.contains("airflow dags test"),
                        "Canonical live data paths must execute through the real scheduler"),
                () -> assertFalse(script.contains(LANDING_DAG_ID),
                        "Focused silver acceptance must not rebuild landing fixtures"),
                () -> assertFalse(script.contains(BRONZE_TO_SILVER_DAG_ID),
                        "Focused silver acceptance must not rebuild bronze fixtures"),
                () -> assertFalse(script.contains(QUALITY_CLASS),
                        "The blocked result must come from the DAG's own uniqueness rule"),
                () -> assertTrue(script.contains(SILVER_TO_GOLD_FIXTURE_CLASS)),
                () -> assertTrue(script.contains(SILVER_TO_GOLD_CATALOG_VERIFIER_CLASS)),
                () -> assertTrue(script.contains("run_fixture prepare")),
                () -> assertTrue(script.contains("run_fixture cleanup")),
                () -> assertTrue(script.contains("STRATUS_AIRFLOW_SCENARIOS_BASE64")),
                () -> assertTrue(script.contains(
                        "\\\"run_silver_quality_for_gold\\\":\\\"success\\\"")),
                () -> assertTrue(script.contains(
                        "\\\"evaluate_silver_promotion\\\":\\\"failed\\\"")),
                () -> assertTrue(script.contains(
                        "\\\"run_gold_materialisation\\\":\\\"upstream_failed\\\"")),
                () -> assertTrue(script.contains("\\\"expectedSparkApplications\\\":3")),
                () -> assertTrue(script.contains("\\\"expectedSparkApplications\\\":1")),
                () -> assertTrue(script.contains("spark_application_count"),
                        "The application budget must be measured from the Spark master"),
                () -> assertTrue(script.contains("observedSparkApplications"),
                        "The measured application count must be emitted as evidence"),
                () -> assertTrue(script.contains("-ne \"$EXPECTED_SPARK_APPLICATIONS\""),
                        "A budget mismatch must fail the focused suite"),
                () -> assertTrue(script.contains("export " + SILVER_TO_GOLD_RETRIES_ENV + "=0")),
                () -> assertTrue(overlay.contains(SILVER_TO_GOLD_RETRIES_ENV)),
                () -> assertTrue(script.contains("CATALOG SILVER TO GOLD VERIFIED")),
                () -> assertTrue(script.contains("CATALOG SILVER TO GOLD BLOCK VERIFIED")),
                () -> assertTrue(script.contains("event=airflow_silver_to_gold_phase_completed")),
                () -> assertTrue(script.contains("elapsedMs=")),
                () -> assertTrue(script.contains("assert_not_logged")),
                () -> assertTrue(script.contains("cleanup")));
    }

    @Test
    void liveMaintenanceTestProvesRunAndSkipWithIndependentCleanupAndTiming() {
        String script = Repo.read(TABLE_MAINTENANCE_LIVE_TEST_PATH);
        assertAll(
                () -> assertTrue(script.contains("airflow-compose-startup.sh")),
                () -> assertTrue(script.contains("airflow-compose-shutdown.sh")),
                () -> assertTrue(script.contains("readonly DAG_ID=\""
                        + TABLE_MAINTENANCE_DAG_ID + "\"")),
                () -> assertTrue(script.contains("airflow dags test \"$DAG_ID\"")),
                () -> assertTrue(script.contains(TABLE_MAINTENANCE_VERIFIER_CLASS)),
                () -> assertTrue(script.contains("development-skip-v1")),
                () -> assertTrue(script.contains("development-run-v1")),
                () -> assertTrue(script.contains("run_verifier seed")),
                () -> assertTrue(script.contains("run_verifier verify-skip")),
                () -> assertTrue(script.contains("run_verifier verify-run")),
                () -> assertTrue(script.contains("run_verifier cleanup")),
                () -> assertTrue(script.contains("TABLE MAINTENANCE action=SKIP")),
                () -> assertTrue(script.contains("TABLE MAINTENANCE action=RUN")),
                () -> assertTrue(script.contains("AIRFLOW TABLE MAINTENANCE SKIP VERIFIED")),
                () -> assertTrue(script.contains("AIRFLOW TABLE MAINTENANCE RUN VERIFIED")),
                () -> assertTrue(script.contains("AIRFLOW TABLE MAINTENANCE CLEANUP COMPLETE")),
                () -> assertTrue(script.contains("event=airflow_table_maintenance_phase_completed")),
                () -> assertTrue(script.contains("elapsedMs=")),
                () -> assertTrue(script.contains("assert_not_logged")),
                () -> assertTrue(script.contains("cleanup")));
    }

    @Test
    void apiOrchestrationLiveTestRetriesShutdownAndReportsTheObservedContainerCount() {
        String script = Repo.read(API_ORCHESTRATION_LIVE_TEST_PATH);
        String probe = read(API_CONTRACT_PROBE_DAG_PATH);
        assertAll(
                () -> assertTrue(probe.contains(
                        "dag_id=\"" + API_CONTRACT_PROBE_DAG_ID + "\"")),
                () -> assertTrue(probe.contains("EmptyOperator")),
                () -> assertTrue(script.contains(API_CONTRACT_PROBE_DAG_ID)),
                () -> assertTrue(script.contains(CATALOG_TABLE_STATE_CLASS)),
                () -> assertTrue(script.contains("--expectedState absent")),
                () -> assertFalse(script.contains("spark-submit"),
                        "the API state-contract suite must not start Spark applications"),
                () -> assertFalse(script.contains("airflow-pipeline-s3-fixture.py"),
                        "the API state-contract suite does not need data fixtures"),
                () -> assertTrue(script.contains("shutdown_harness"),
                        "provider teardown must retry a transient lifecycle-script failure"),
                () -> assertTrue(script.contains("shutdown_harness airflow")),
                () -> assertTrue(script.contains("shutdown_harness spark")),
                () -> assertTrue(script.contains("shutdown_harness polaris")),
                () -> assertTrue(script.contains("shutdown_harness openbao")),
                () -> assertTrue(script.contains("shutdown_harness ceph")),
                () -> assertTrue(script.contains("remaining_count=")),
                () -> assertTrue(script.contains(
                        "remainingStratusContainers=$remaining_count")),
                () -> assertFalse(script.contains(
                        "remainingStratusContainers=0\""),
                        "cleanup evidence must report the observed count, not a constant"));
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
