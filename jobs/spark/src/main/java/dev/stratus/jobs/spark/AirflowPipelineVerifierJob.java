// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live development verifier for the Airflow landing-to-bronze vertical slice.
 *
 * <p>The verifier runs after Airflow reports success. It independently proves that the isolated
 * bronze table contains the expected batch, that the quality task persisted one passing result
 * correlated to the same pipeline run, and that the table has an Iceberg snapshot. It then removes
 * both isolated artifacts. The target-name allow-list is deliberately narrow because cleanup is a
 * destructive operation and must never accept a normal data-product table.
 *
 * <p>This is verification code packaged with the jobs JAR so it exercises the same Spark, Polaris,
 * Ceph, TLS, credential and SLF4J telemetry boundary as the DAG tasks it checks.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-22
 * @version 1.0.0
 */
public final class AirflowPipelineVerifierJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(AirflowPipelineVerifierJob.class);
    private static final String QUALITY_RESULTS_TABLE =
            "stratus.platform.quality_check_results";
    private static final String BATCH_COLUMN = IngestionJob.BATCH_COLUMN;
    private static final Pattern ISOLATED_TARGET = Pattern.compile(
            "stratus\\.bronze\\.airflow_pipeline_probe_[a-z0-9_]+");
    private static final Set<String> ARGUMENTS = Set.of(
            "targetTable", "batchId", "pipelineRunId", "expectedRows", "runId", "cleanup");

    private AirflowPipelineVerifierJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String targetTable = requireIsolatedTarget(arguments.require("targetTable"));
        String batchId = arguments.require("batchId");
        String pipelineRunId = arguments.require("pipelineRunId");
        long expectedRows = parsePositiveLong(arguments.require("expectedRows"), "expectedRows");
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());
        boolean cleanup = parseBoolean(arguments.optional("cleanup").orElse("true"), "cleanup");

        try (var context = JobTelemetry.openContext(runId)) {
            SparkSession spark = SparkSession.builder()
                    .appName("stratus-airflow-pipeline-verifier")
                    .getOrCreate();
            try {
                verify(spark, targetTable, batchId, pipelineRunId, expectedRows, runId);
            } finally {
                if (cleanup) {
                    cleanup(spark, targetTable, pipelineRunId, runId);
                }
                spark.stop();
            }
        }
    }

    private static void verify(SparkSession spark, String targetTable, String batchId,
                               String pipelineRunId, long expectedRows, String runId) {
        long batchRows = JobTelemetry.measure("AIRFLOW_PIPELINE_VERIFY", "count_batch", runId,
                targetTable, () -> spark.table(targetTable)
                        .filter(functions.col(BATCH_COLUMN).equalTo(batchId)).count());
        if (batchRows != expectedRows) {
            throw new IllegalStateException("Expected " + expectedRows + " rows for batch "
                    + batchId + " but found " + batchRows);
        }

        long passingQualityRows = JobTelemetry.measure(
                "AIRFLOW_PIPELINE_VERIFY", "count_quality_results", runId, targetTable,
                () -> spark.table(QUALITY_RESULTS_TABLE)
                        .filter(functions.col("run_id").equalTo(pipelineRunId))
                        .filter(functions.col("pipeline_run_id").equalTo(pipelineRunId))
                        .filter(functions.col("dataset_namespace").equalTo("bronze"))
                        .filter(functions.col("dataset_name").equalTo(tableName(targetTable)))
                        .filter(functions.col("check_type").equalTo("row_count_min"))
                        .filter(functions.col("status").equalTo(QualityCheckJob.STATUS_PASSED))
                        .count());
        if (passingQualityRows != 1L) {
            throw new IllegalStateException("Expected one passing quality result for pipeline run "
                    + pipelineRunId + " but found " + passingQualityRows);
        }

        Row snapshot = JobTelemetry.measure("AIRFLOW_PIPELINE_VERIFY", "resolve_snapshot", runId,
                targetTable, () -> spark.sql("SELECT snapshot_id FROM " + targetTable
                        + ".snapshots ORDER BY committed_at DESC LIMIT 1").first());
        long snapshotId = snapshot.getLong(0);
        LOGGER.info("AIRFLOW PIPELINE VERIFIED table={} batchId={} pipelineRunId={} "
                        + "batchRows={} passingQualityRows={} snapshotId={} runId={}",
                targetTable, batchId, pipelineRunId, batchRows, passingQualityRows, snapshotId,
                runId);
    }

    private static void cleanup(SparkSession spark, String targetTable, String pipelineRunId,
                                String runId) {
        JobTelemetry.measure("AIRFLOW_PIPELINE_VERIFY", "cleanup_quality_result", runId,
                targetTable, () -> spark.sql("DELETE FROM " + QUALITY_RESULTS_TABLE
                        + " WHERE run_id = '" + sqlLiteral(pipelineRunId) + "'"));
        JobTelemetry.measure("AIRFLOW_PIPELINE_VERIFY", "cleanup_probe_table", runId,
                targetTable, () -> spark.sql("DROP TABLE IF EXISTS " + targetTable + " PURGE"));
        LOGGER.info("AIRFLOW PIPELINE CLEANUP COMPLETE table={} pipelineRunId={} runId={}",
                targetTable, pipelineRunId, runId);
    }

    static String requireIsolatedTarget(String targetTable) {
        if (!ISOLATED_TARGET.matcher(targetTable).matches()) {
            throw new IllegalArgumentException("Verifier cleanup accepts only isolated tables named "
                    + "stratus.bronze.airflow_pipeline_probe_<lowercase-run-token>, got: "
                    + targetTable);
        }
        return targetTable;
    }

    static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private static String tableName(String identifier) {
        return identifier.substring(identifier.lastIndexOf('.') + 1);
    }

    private static long parsePositiveLong(String value, String argument) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1L) {
                throw new NumberFormatException("must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + argument + " must be a positive integer: "
                    + value, exception);
        }
    }

    private static boolean parseBoolean(String value, String argument) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("--" + argument + " must be true or false: " + value);
    }
}
