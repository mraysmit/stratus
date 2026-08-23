// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Seeds, independently verifies, and exactly cleans the Airflow maintenance probe table. */
public final class AirflowTableMaintenanceVerifierJob {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AirflowTableMaintenanceVerifierJob.class);
    private static final Pattern ISOLATED_TABLE = Pattern.compile(
            "stratus\\.bronze\\.airflow_maintenance_probe_[a-z0-9_]+");
    private static final Set<String> MODES =
            Set.of("seed", "verify-skip", "verify-run", "cleanup");
    private static final Set<String> ARGUMENTS =
            Set.of("mode", "targetTable", "expectedRows", "expectedFiles", "runId");

    private AirflowTableMaintenanceVerifierJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String mode = requireMode(arguments.require("mode"));
        String targetTable = requireIsolatedTable(arguments.require("targetTable"));
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());

        try (var context = JobTelemetry.openContext(runId)) {
            SparkSession spark = SparkSession.builder()
                    .appName("stratus-airflow-table-maintenance-verifier")
                    .getOrCreate();
            try {
                switch (mode) {
                    case "seed" -> seed(spark, targetTable, runId);
                    case "verify-skip", "verify-run" -> verify(spark, targetTable, mode,
                            nonNegative(arguments.require("expectedRows"), "expectedRows"),
                            nonNegative(arguments.require("expectedFiles"), "expectedFiles"), runId);
                    case "cleanup" -> cleanup(spark, targetTable, runId);
                    default -> throw new IllegalStateException("Unhandled verifier mode: " + mode);
                }
            } finally {
                spark.stop();
            }
        }
    }

    private static void seed(SparkSession spark, String targetTable, String runId) {
        JobTelemetry.measure("AIRFLOW_TABLE_MAINTENANCE_VERIFY", "reset_fixture", runId,
                targetTable, () -> spark.sql("DROP TABLE IF EXISTS " + targetTable + " PURGE"));
        JobTelemetry.measure("AIRFLOW_TABLE_MAINTENANCE_VERIFY", "create_fixture", runId,
                targetTable, () -> spark.sql("CREATE TABLE " + targetTable
                        + " (id BIGINT, category STRING) USING iceberg"));
        for (int id = 1; id <= 3; id++) {
            int rowId = id;
            JobTelemetry.measure("AIRFLOW_TABLE_MAINTENANCE_VERIFY", "append_fixture_" + id,
                    runId, targetTable, () -> spark.sql("INSERT INTO " + targetTable
                            + " VALUES (" + rowId + ", 'category_" + rowId + "')"));
        }
        long rows = rowCount(spark, targetTable);
        long files = fileCount(spark, targetTable);
        long snapshots = snapshotCount(spark, targetTable);
        requireCount(3, rows, "fixture rows");
        requireCount(3, files, "fixture data files");
        LOGGER.info("AIRFLOW TABLE MAINTENANCE FIXTURE READY table={} rows={} dataFiles={} "
                + "snapshots={} runId={}", targetTable, rows, files, snapshots, runId);
    }

    private static void verify(SparkSession spark, String targetTable, String mode,
                               long expectedRows, long expectedFiles, String runId) {
        long rows = JobTelemetry.measure("AIRFLOW_TABLE_MAINTENANCE_VERIFY", "count_rows",
                runId, targetTable, () -> rowCount(spark, targetTable));
        long files = JobTelemetry.measure("AIRFLOW_TABLE_MAINTENANCE_VERIFY", "count_files",
                runId, targetTable, () -> fileCount(spark, targetTable));
        long snapshots = JobTelemetry.measure("AIRFLOW_TABLE_MAINTENANCE_VERIFY",
                "count_snapshots", runId, targetTable, () -> snapshotCount(spark, targetTable));
        requireCount(expectedRows, rows, "table rows");
        requireCount(expectedFiles, files, "current data files");
        String outcome = "verify-run".equals(mode) ? "RUN" : "SKIP";
        LOGGER.info("AIRFLOW TABLE MAINTENANCE {} VERIFIED table={} rows={} dataFiles={} "
                + "snapshots={} runId={}", outcome, targetTable, rows, files, snapshots, runId);
    }

    private static void cleanup(SparkSession spark, String targetTable, String runId) {
        JobTelemetry.measure("AIRFLOW_TABLE_MAINTENANCE_VERIFY", "cleanup_fixture", runId,
                targetTable, () -> spark.sql("DROP TABLE IF EXISTS " + targetTable + " PURGE"));
        LOGGER.info("AIRFLOW TABLE MAINTENANCE CLEANUP COMPLETE table={} runId={}",
                targetTable, runId);
    }

    private static long rowCount(SparkSession spark, String targetTable) {
        return spark.table(targetTable).count();
    }

    private static long fileCount(SparkSession spark, String targetTable) {
        return spark.sql("SELECT COUNT(*) FROM " + targetTable + ".files").first().getLong(0);
    }

    private static long snapshotCount(SparkSession spark, String targetTable) {
        return spark.sql("SELECT COUNT(*) FROM " + targetTable + ".snapshots").first().getLong(0);
    }

    static String requireIsolatedTable(String table) {
        if (!ISOLATED_TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("Verifier accepts only isolated tables named "
                    + "stratus.bronze.airflow_maintenance_probe_<lowercase-run-token>, got: "
                    + table);
        }
        return table;
    }

    static String requireMode(String mode) {
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("mode must be one of " + MODES + ", got: " + mode);
        }
        return mode;
    }

    static long nonNegative(String value, String argument) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("--" + argument + " must not be negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + argument + " must be an integer: " + value,
                    exception);
        }
    }

    private static void requireCount(long expected, long actual, String subject) {
        if (actual != expected) {
            throw new IllegalStateException("Expected " + expected + " " + subject
                    + " but found " + actual);
        }
    }
}
