// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Independent verifier for the Airflow silver-to-gold development slice.
 *
 * <p>The accepted outcome proves silver approval, the exact gold grouping schema and counts, two
 * passing gold checks, and a committed Iceberg snapshot. The blocked outcome proves persisted
 * blocking evidence and absence of the isolated gold target. Cleanup is limited to generated
 * bronze, silver, and gold probes and explicitly correlated quality run IDs.
 */
public final class AirflowSilverToGoldVerifierJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AirflowSilverToGoldVerifierJob.class);
    private static final String QUALITY_RESULTS_TABLE = QualityCheckJob.RESULTS_TABLE;
    private static final String ACCEPTED = "accepted";
    private static final String BLOCKED = "blocked";
    private static final int EXPECTED_GOLD_QUALITY_CHECKS = 2;
    private static final Pattern ISOLATED_BRONZE = Pattern.compile(
            "stratus\\.bronze\\.airflow_pipeline_probe_[a-z0-9_]+");
    private static final Pattern ISOLATED_SILVER = Pattern.compile(
            "stratus\\.silver\\.airflow_pipeline_probe_[a-z0-9_]+");
    private static final Pattern ISOLATED_GOLD = Pattern.compile(
            "stratus\\.gold\\.airflow_pipeline_probe_[a-z0-9_]+");
    private static final Set<String> ARGUMENTS = Set.of(
            "bronzeTable", "sourceTable", "targetTable", "sourceQualityRunId",
            "pipelineRunId", "cleanupRunIds", "expectedSourceRows", "expectedGroups",
            "expectedTotal", "expectedOutcome", "runId", "cleanup");

    private AirflowSilverToGoldVerifierJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String bronzeTable = requireIsolatedBronze(arguments.require("bronzeTable"));
        String sourceTable = requireIsolatedSilver(arguments.require("sourceTable"));
        String targetTable = requireIsolatedGold(arguments.require("targetTable"));
        String sourceQualityRunId = arguments.require("sourceQualityRunId");
        String pipelineRunId = arguments.require("pipelineRunId");
        String[] cleanupRunIds = arguments.requireList("cleanupRunIds");
        long expectedSourceRows = positive(arguments.require("expectedSourceRows"),
                "expectedSourceRows");
        long expectedGroups = positive(arguments.require("expectedGroups"), "expectedGroups");
        long expectedTotal = positive(arguments.require("expectedTotal"), "expectedTotal");
        String expectedOutcome = requireOutcome(arguments.require("expectedOutcome"));
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());
        boolean cleanup = parseBoolean(arguments.optional("cleanup").orElse("true"), "cleanup");

        try (var context = JobTelemetry.openContext(runId)) {
            SparkSession spark = SparkSession.builder()
                    .appName("stratus-airflow-silver-to-gold-verifier")
                    .getOrCreate();
            try {
                if (ACCEPTED.equals(expectedOutcome)) {
                    verifyAccepted(spark, sourceTable, targetTable, sourceQualityRunId,
                            pipelineRunId, expectedSourceRows, expectedGroups, expectedTotal, runId);
                } else {
                    verifyBlocked(spark, sourceTable, targetTable, sourceQualityRunId, runId);
                }
            } finally {
                if (cleanup) {
                    cleanup(spark, bronzeTable, sourceTable, targetTable, cleanupRunIds, runId);
                }
                spark.stop();
            }
        }
    }

    private static void verifyAccepted(SparkSession spark, String sourceTable, String targetTable,
                                       String sourceQualityRunId, String pipelineRunId,
                                       long expectedSourceRows, long expectedGroups,
                                       long expectedTotal, String runId) {
        long sourceRows = JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY",
                "count_source", runId, sourceTable, () -> spark.table(sourceTable).count());
        requireCount(expectedSourceRows, sourceRows, "silver rows");

        PromotionDecision decision = JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY",
                "evaluate_source_gate", runId, sourceTable,
                () -> PromotionGate.evaluate(spark, sourceQualityRunId, sourceTable));
        if (decision.blocked()) {
            throw new IllegalStateException("Expected silver promotion approval but observed: "
                    + decision.describe());
        }

        long groups = JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY",
                "count_target", runId, targetTable, () -> spark.table(targetTable).count());
        requireCount(expectedGroups, groups, "gold country groups");

        Set<String> columns = Arrays.stream(spark.table(targetTable).columns())
                .map(column -> column.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (!columns.equals(Set.of("country", "customer_count"))) {
            throw new IllegalStateException("Unexpected gold summary columns: " + columns);
        }

        Row totalRow = JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY",
                "sum_customer_count", runId, targetTable,
                () -> spark.sql("SELECT SUM(customer_count) FROM " + targetTable).first());
        long observedTotal = totalRow.getLong(0);
        requireCount(expectedTotal, observedTotal, "gold customer total");

        long passingChecks = JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY",
                "count_gold_quality", runId, targetTable,
                () -> spark.table(QUALITY_RESULTS_TABLE)
                        .filter(functions.col("run_id").equalTo(pipelineRunId))
                        .filter(functions.col("pipeline_run_id").equalTo(pipelineRunId))
                        .filter(functions.col("dataset_namespace").equalTo("gold"))
                        .filter(functions.col("dataset_name").equalTo(tableName(targetTable)))
                        .filter(functions.col("status").equalTo(QualityCheckJob.STATUS_PASSED))
                        .count());
        requireCount(EXPECTED_GOLD_QUALITY_CHECKS, passingChecks,
                "passing gold quality results");

        Row snapshot = JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY",
                "resolve_target_snapshot", runId, targetTable,
                () -> spark.sql("SELECT snapshot_id FROM " + targetTable
                        + ".snapshots ORDER BY committed_at DESC LIMIT 1").first());
        LOGGER.info("AIRFLOW SILVER TO GOLD VERIFIED sourceTable={} targetTable={} "
                        + "sourceQualityRunId={} pipelineRunId={} sourceRows={} groups={} "
                        + "customerTotal={} passingGoldChecks={} snapshotId={} runId={}",
                sourceTable, targetTable, sourceQualityRunId, pipelineRunId, sourceRows,
                groups, observedTotal, passingChecks, snapshot.getLong(0), runId);
    }

    private static void verifyBlocked(SparkSession spark, String sourceTable, String targetTable,
                                      String sourceQualityRunId, String runId) {
        PromotionDecision decision = JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY",
                "evaluate_blocked_gate", runId, sourceTable,
                () -> PromotionGate.evaluate(spark, sourceQualityRunId, sourceTable));
        if (!decision.blocked()) {
            throw new IllegalStateException("Expected silver promotion to be blocked but observed: "
                    + decision.describe());
        }
        boolean targetExists = JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY",
                "check_target_absent", runId, targetTable,
                () -> spark.catalog().tableExists(targetTable));
        if (targetExists) {
            throw new IllegalStateException("Blocked promotion created or retained gold table "
                    + targetTable);
        }
        LOGGER.info("AIRFLOW SILVER TO GOLD BLOCK VERIFIED sourceTable={} targetTable={} "
                        + "sourceQualityRunId={} failingChecks={} runId={}",
                sourceTable, targetTable, sourceQualityRunId,
                String.join(",", decision.failingChecks()), runId);
    }

    private static void cleanup(SparkSession spark, String bronzeTable, String sourceTable,
                                String targetTable, String[] cleanupRunIds, String runId) {
        String runIdList = Arrays.stream(cleanupRunIds)
                .map(value -> "'" + sqlLiteral(value) + "'")
                .collect(Collectors.joining(","));
        JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY", "cleanup_quality_results", runId,
                targetTable, () -> spark.sql("DELETE FROM " + QUALITY_RESULTS_TABLE
                        + " WHERE run_id IN (" + runIdList + ")"));
        JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY", "cleanup_target", runId,
                targetTable, () -> spark.sql("DROP TABLE IF EXISTS " + targetTable + " PURGE"));
        JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY", "cleanup_source", runId,
                sourceTable, () -> spark.sql("DROP TABLE IF EXISTS " + sourceTable + " PURGE"));
        JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_VERIFY", "cleanup_bronze", runId,
                bronzeTable, () -> spark.sql("DROP TABLE IF EXISTS " + bronzeTable + " PURGE"));
        LOGGER.info("AIRFLOW SILVER TO GOLD CLEANUP COMPLETE bronzeTable={} sourceTable={} "
                        + "targetTable={} cleanupRunIds={} runId={}",
                bronzeTable, sourceTable, targetTable, String.join(",", cleanupRunIds), runId);
    }

    static String requireIsolatedBronze(String table) {
        return requirePattern(table, ISOLATED_BRONZE, "bronze");
    }

    static String requireIsolatedSilver(String table) {
        return requirePattern(table, ISOLATED_SILVER, "silver");
    }

    static String requireIsolatedGold(String table) {
        return requirePattern(table, ISOLATED_GOLD, "gold");
    }

    private static String requirePattern(String table, Pattern pattern, String zone) {
        if (!pattern.matcher(table).matches()) {
            throw new IllegalArgumentException("Verifier cleanup accepts only isolated " + zone
                    + " tables named stratus." + zone
                    + ".airflow_pipeline_probe_<lowercase-run-token>, got: " + table);
        }
        return table;
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

    private static String tableName(String identifier) {
        return identifier.substring(identifier.lastIndexOf('.') + 1);
    }

    private static void requireCount(long expected, long actual, String subject) {
        if (actual != expected) {
            throw new IllegalStateException("Expected " + expected + " " + subject
                    + " but found " + actual);
        }
    }

    private static long positive(String value, String argument) {
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
