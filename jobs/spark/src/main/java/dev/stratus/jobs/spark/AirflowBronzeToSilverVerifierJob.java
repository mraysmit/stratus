// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Independent live verifier for the Airflow bronze-to-silver development slice.
 *
 * <p>The accepted path proves that the exact bronze batch has approving evidence, silver contains
 * the expected conformed rows without bronze audit columns, two silver rules passed, and Iceberg
 * committed a snapshot. The blocked path proves the inverse write condition: recorded blocking
 * evidence exists and the isolated silver table was never created. This is deliberately stronger
 * than trusting Airflow's task state, because a failed task may still have made a partial write.
 *
 * <p>Cleanup accepts only generated probe names in the bronze and silver namespaces. Both tables
 * and their correlated quality records are removed in a {@code finally} block so a failed assertion
 * cannot leave verification data behind.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-23
 * @version 1.0.0
 */
public final class AirflowBronzeToSilverVerifierJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AirflowBronzeToSilverVerifierJob.class);
    private static final String QUALITY_RESULTS_TABLE = QualityCheckJob.RESULTS_TABLE;
    private static final String ACCEPTED = "accepted";
    private static final String BLOCKED = "blocked";
    private static final int EXPECTED_SILVER_QUALITY_CHECKS = 2;
    private static final Pattern ISOLATED_SOURCE = Pattern.compile(
            "stratus\\.bronze\\.airflow_pipeline_probe_[a-z0-9_]+");
    private static final Pattern ISOLATED_TARGET = Pattern.compile(
            "stratus\\.silver\\.airflow_pipeline_probe_[a-z0-9_]+");
    private static final Set<String> ARGUMENTS = Set.of(
            "sourceTable", "targetTable", "sourceBatch", "sourceQualityRunId",
            "pipelineRunId", "expectedRows", "expectedOutcome", "runId", "cleanup");

    private AirflowBronzeToSilverVerifierJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String sourceTable = requireIsolatedSource(arguments.require("sourceTable"));
        String targetTable = requireIsolatedTarget(arguments.require("targetTable"));
        String sourceBatch = arguments.require("sourceBatch");
        String sourceQualityRunId = arguments.require("sourceQualityRunId");
        String pipelineRunId = arguments.require("pipelineRunId");
        long expectedRows = parsePositiveLong(arguments.require("expectedRows"), "expectedRows");
        String expectedOutcome = requireOutcome(arguments.require("expectedOutcome"));
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());
        boolean cleanup = parseBoolean(arguments.optional("cleanup").orElse("true"), "cleanup");

        try (var context = JobTelemetry.openContext(runId)) {
            SparkSession spark = SparkSession.builder()
                    .appName("stratus-airflow-bronze-to-silver-verifier")
                    .getOrCreate();
            try {
                if (ACCEPTED.equals(expectedOutcome)) {
                    verifyAccepted(spark, sourceTable, targetTable, sourceBatch,
                            sourceQualityRunId, pipelineRunId, expectedRows, runId);
                } else {
                    verifyBlocked(spark, sourceTable, targetTable, sourceQualityRunId, runId);
                }
            } finally {
                if (cleanup) {
                    cleanup(spark, sourceTable, targetTable, sourceQualityRunId, pipelineRunId,
                            runId);
                }
                spark.stop();
            }
        }
    }

    private static void verifyAccepted(SparkSession spark, String sourceTable, String targetTable,
                                       String sourceBatch, String sourceQualityRunId,
                                       String pipelineRunId, long expectedRows, String runId) {
        long sourceRows = JobTelemetry.measure("AIRFLOW_BRONZE_TO_SILVER_VERIFY",
                "count_source_batch", runId, sourceTable, () -> spark.table(sourceTable)
                        .filter(functions.col(IngestionJob.BATCH_COLUMN).equalTo(sourceBatch))
                        .count());
        requireCount(expectedRows, sourceRows, "bronze rows for batch " + sourceBatch);

        PromotionDecision sourceDecision = JobTelemetry.measure(
                "AIRFLOW_BRONZE_TO_SILVER_VERIFY", "evaluate_source_gate", runId, sourceTable,
                () -> PromotionGate.evaluate(spark, sourceQualityRunId, sourceTable));
        if (sourceDecision.blocked()) {
            throw new IllegalStateException("Expected bronze promotion approval but observed: "
                    + sourceDecision.describe());
        }

        long targetRows = JobTelemetry.measure("AIRFLOW_BRONZE_TO_SILVER_VERIFY",
                "count_target", runId, targetTable, () -> spark.table(targetTable).count());
        requireCount(expectedRows, targetRows, "silver rows");

        Set<String> targetColumns = Arrays.stream(spark.table(targetTable).columns())
                .map(column -> column.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> leakedAuditColumns = IngestionJob.AUDIT_COLUMNS.stream()
                .filter(targetColumns::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!leakedAuditColumns.isEmpty()) {
            throw new IllegalStateException("Silver retained bronze audit columns: "
                    + leakedAuditColumns);
        }

        long passingSilverChecks = JobTelemetry.measure(
                "AIRFLOW_BRONZE_TO_SILVER_VERIFY", "count_silver_quality", runId, targetTable,
                () -> spark.table(QUALITY_RESULTS_TABLE)
                        .filter(functions.col("run_id").equalTo(pipelineRunId))
                        .filter(functions.col("pipeline_run_id").equalTo(pipelineRunId))
                        .filter(functions.col("dataset_namespace").equalTo("silver"))
                        .filter(functions.col("dataset_name").equalTo(tableName(targetTable)))
                        .filter(functions.col("status").equalTo(QualityCheckJob.STATUS_PASSED))
                        .count());
        requireCount(EXPECTED_SILVER_QUALITY_CHECKS, passingSilverChecks,
                "passing silver quality results");

        Row snapshot = JobTelemetry.measure("AIRFLOW_BRONZE_TO_SILVER_VERIFY",
                "resolve_target_snapshot", runId, targetTable,
                () -> spark.sql("SELECT snapshot_id FROM " + targetTable
                        + ".snapshots ORDER BY committed_at DESC LIMIT 1").first());
        long snapshotId = snapshot.getLong(0);
        LOGGER.info("AIRFLOW BRONZE TO SILVER VERIFIED sourceTable={} targetTable={} "
                        + "sourceBatch={} sourceQualityRunId={} pipelineRunId={} sourceRows={} "
                        + "targetRows={} passingSilverChecks={} snapshotId={} runId={}",
                sourceTable, targetTable, sourceBatch, sourceQualityRunId, pipelineRunId,
                sourceRows, targetRows, passingSilverChecks, snapshotId, runId);
    }

    private static void verifyBlocked(SparkSession spark, String sourceTable, String targetTable,
                                      String sourceQualityRunId, String runId) {
        PromotionDecision sourceDecision = JobTelemetry.measure(
                "AIRFLOW_BRONZE_TO_SILVER_VERIFY", "evaluate_blocked_gate", runId, sourceTable,
                () -> PromotionGate.evaluate(spark, sourceQualityRunId, sourceTable));
        if (!sourceDecision.blocked()) {
            throw new IllegalStateException("Expected bronze promotion to be blocked but observed: "
                    + sourceDecision.describe());
        }
        boolean targetExists = JobTelemetry.measure("AIRFLOW_BRONZE_TO_SILVER_VERIFY",
                "check_target_absent", runId, targetTable,
                () -> spark.catalog().tableExists(targetTable));
        if (targetExists) {
            throw new IllegalStateException("Blocked promotion created or retained silver table "
                    + targetTable);
        }
        LOGGER.info("AIRFLOW BRONZE TO SILVER BLOCK VERIFIED sourceTable={} targetTable={} "
                        + "sourceQualityRunId={} failingChecks={} runId={}",
                sourceTable, targetTable, sourceQualityRunId,
                String.join(",", sourceDecision.failingChecks()), runId);
    }

    private static void cleanup(SparkSession spark, String sourceTable, String targetTable,
                                String sourceQualityRunId, String pipelineRunId, String runId) {
        JobTelemetry.measure("AIRFLOW_BRONZE_TO_SILVER_VERIFY", "cleanup_quality_results", runId,
                targetTable, () -> spark.sql("DELETE FROM " + QUALITY_RESULTS_TABLE
                        + " WHERE run_id IN ('" + sqlLiteral(sourceQualityRunId) + "','"
                        + sqlLiteral(pipelineRunId) + "')"));
        JobTelemetry.measure("AIRFLOW_BRONZE_TO_SILVER_VERIFY", "cleanup_target", runId,
                targetTable, () -> spark.sql("DROP TABLE IF EXISTS " + targetTable + " PURGE"));
        JobTelemetry.measure("AIRFLOW_BRONZE_TO_SILVER_VERIFY", "cleanup_source", runId,
                sourceTable, () -> spark.sql("DROP TABLE IF EXISTS " + sourceTable + " PURGE"));
        LOGGER.info("AIRFLOW BRONZE TO SILVER CLEANUP COMPLETE sourceTable={} targetTable={} "
                        + "sourceQualityRunId={} pipelineRunId={} runId={}",
                sourceTable, targetTable, sourceQualityRunId, pipelineRunId, runId);
    }

    static String requireIsolatedSource(String sourceTable) {
        if (!ISOLATED_SOURCE.matcher(sourceTable).matches()) {
            throw new IllegalArgumentException("Verifier cleanup accepts only isolated sources named "
                    + "stratus.bronze.airflow_pipeline_probe_<lowercase-run-token>, got: "
                    + sourceTable);
        }
        return sourceTable;
    }

    static String requireIsolatedTarget(String targetTable) {
        if (!ISOLATED_TARGET.matcher(targetTable).matches()) {
            throw new IllegalArgumentException("Verifier cleanup accepts only isolated targets named "
                    + "stratus.silver.airflow_pipeline_probe_<lowercase-run-token>, got: "
                    + targetTable);
        }
        return targetTable;
    }

    static String requireOutcome(String outcome) {
        if (!ACCEPTED.equals(outcome) && !BLOCKED.equals(outcome)) {
            throw new IllegalArgumentException("expectedOutcome must be accepted or blocked, got: "
                    + outcome);
        }
        return outcome;
    }

    static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private static void requireCount(long expected, long actual, String subject) {
        if (actual != expected) {
            throw new IllegalStateException("Expected " + expected + " " + subject
                    + " but found " + actual);
        }
    }

    private static String tableName(String identifier) {
        return identifier.substring(identifier.lastIndexOf('.') + 1);
    }

    private static long parsePositiveLong(String value, String argument) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1L) {
                throw new IllegalArgumentException("--" + argument + " must be at least 1");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + argument + " must be an integer: " + value,
                    exception);
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
