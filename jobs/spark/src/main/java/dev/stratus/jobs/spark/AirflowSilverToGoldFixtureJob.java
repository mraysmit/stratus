// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the two deterministic silver fixtures and their exact cleanup in one Spark application.
 *
 * <p>The accepted fixture contains three unique customer keys. The blocked fixture contains a
 * duplicate key so the production DAG's own uniqueness rule records the failure; the harness must
 * never manufacture a quality result outside the DAG. All mutable identifiers are constrained to
 * generated acceptance-probe names before any SQL is evaluated.
 */
public final class AirflowSilverToGoldFixtureJob {

    static final List<Customer> ACCEPTED_CUSTOMERS = List.of(
            new Customer(1001, "Ada Lovelace", "ada@example.test", "GB",
                    "2026-08-22T12:00:00Z"),
            new Customer(1002, "Grace Hopper", "grace@example.test", "US",
                    "2026-08-22T12:01:00Z"),
            new Customer(1003, "Edsger Dijkstra", "edsger@example.test", "NL",
                    "2026-08-22T12:02:00Z"));
    static final List<Customer> BLOCKED_CUSTOMERS = List.of(
            new Customer(1001, "Ada Lovelace", "ada@example.test", "GB",
                    "2026-08-22T12:00:00Z"),
            new Customer(1001, "Duplicate Ada", "duplicate@example.test", "US",
                    "2026-08-22T12:01:00Z"),
            new Customer(1003, "Edsger Dijkstra", "edsger@example.test", "NL",
                    "2026-08-22T12:02:00Z"));

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AirflowSilverToGoldFixtureJob.class);
    private static final Set<String> MODES = Set.of("prepare", "cleanup");
    private static final Set<String> ARGUMENTS = Set.of(
            "mode", "acceptedSourceTable", "acceptedTargetTable", "blockedSourceTable",
            "blockedTargetTable", "cleanupRunIds", "runId");
    private static final StructType SILVER_SCHEMA = new StructType()
            .add("customer_id", DataTypes.IntegerType, false)
            .add("customer_name", DataTypes.StringType, false)
            .add("email", DataTypes.StringType, false)
            .add("country", DataTypes.StringType, false)
            .add("updated_at", DataTypes.TimestampType, false);

    private AirflowSilverToGoldFixtureJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String mode = requireMode(arguments.require("mode"));
        String acceptedSource = requireSilver(arguments.require("acceptedSourceTable"));
        String acceptedTarget = requireGold(arguments.require("acceptedTargetTable"));
        String blockedSource = requireSilver(arguments.require("blockedSourceTable"));
        String blockedTarget = requireGold(arguments.require("blockedTargetTable"));
        String[] cleanupIds = cleanupRunIds(arguments.require("cleanupRunIds"));
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());

        try (var context = JobTelemetry.openContext(runId)) {
            SparkSession spark = SparkSession.builder()
                    .appName("stratus-airflow-silver-to-gold-fixture")
                    .getOrCreate();
            try {
                if ("prepare".equals(mode)) {
                    try {
                        prepare(spark, acceptedSource, acceptedTarget, blockedSource, blockedTarget,
                                runId);
                    } catch (RuntimeException failure) {
                        try {
                            cleanup(spark, acceptedSource, acceptedTarget, blockedSource,
                                    blockedTarget, cleanupIds, runId);
                        } catch (RuntimeException cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                        }
                        throw failure;
                    }
                } else {
                    cleanup(spark, acceptedSource, acceptedTarget, blockedSource, blockedTarget,
                            cleanupIds, runId);
                }
            } finally {
                spark.stop();
            }
        }
    }

    private static void prepare(
            SparkSession spark, String acceptedSource, String acceptedTarget,
            String blockedSource, String blockedTarget, String runId) {
        dropProbeTables(spark, acceptedSource, acceptedTarget, blockedSource, blockedTarget, runId);
        writeFixture(spark, acceptedSource, ACCEPTED_CUSTOMERS, runId, "write_accepted_silver");
        writeFixture(spark, blockedSource, BLOCKED_CUSTOMERS, runId, "write_blocked_silver");
        LOGGER.info("AIRFLOW SILVER TO GOLD FIXTURES READY acceptedSource={} blockedSource={} "
                        + "acceptedRows={} blockedRows={} blockedDistinctCustomerIds={} runId={}",
                acceptedSource, blockedSource, ACCEPTED_CUSTOMERS.size(), BLOCKED_CUSTOMERS.size(),
                BLOCKED_CUSTOMERS.stream().map(Customer::customerId).distinct().count(), runId);
    }

    private static void writeFixture(
            SparkSession spark, String table, List<Customer> customers, String runId, String phase) {
        var rows = customers.stream()
                .map(customer -> RowFactory.create(
                        customer.customerId(), customer.customerName(), customer.email(),
                        customer.country(), Timestamp.from(Instant.parse(customer.updatedAt()))))
                .toList();
        JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_FIXTURE", phase, runId, table,
                () -> {
                    try {
                        spark.createDataFrame(rows, SILVER_SCHEMA).writeTo(table)
                                .using("iceberg").create();
                    } catch (Exception failure) {
                        throw new IllegalStateException("Could not create silver fixture " + table,
                                failure);
                    }
                });
    }

    private static void cleanup(
            SparkSession spark, String acceptedSource, String acceptedTarget,
            String blockedSource, String blockedTarget, String[] cleanupIds, String runId) {
        String runIdList = Arrays.stream(cleanupIds)
                .map(value -> "'" + AirflowSilverToGoldVerifierJob.sqlLiteral(value) + "'")
                .collect(Collectors.joining(","));
        JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_FIXTURE", "cleanup_quality_results", runId,
                acceptedTarget, () -> spark.sql("DELETE FROM " + QualityCheckJob.RESULTS_TABLE
                        + " WHERE run_id IN (" + runIdList + ")"));
        dropProbeTables(spark, acceptedSource, acceptedTarget, blockedSource, blockedTarget, runId);
        LOGGER.info("AIRFLOW SILVER TO GOLD FIXTURES CLEANUP COMPLETE acceptedSource={} "
                        + "acceptedTarget={} blockedSource={} blockedTarget={} cleanupRunIds={} "
                        + "runId={}",
                acceptedSource, acceptedTarget, blockedSource, blockedTarget,
                String.join(",", cleanupIds), runId);
    }

    private static void dropProbeTables(
            SparkSession spark, String acceptedSource, String acceptedTarget,
            String blockedSource, String blockedTarget, String runId) {
        for (String table : List.of(acceptedTarget, blockedTarget, acceptedSource, blockedSource)) {
            JobTelemetry.measure("AIRFLOW_SILVER_TO_GOLD_FIXTURE", "drop_probe", runId, table,
                    () -> spark.sql("DROP TABLE IF EXISTS " + table + " PURGE"));
        }
    }

    static String requireMode(String mode) {
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("mode must be prepare or cleanup: " + mode);
        }
        return mode;
    }

    static String requireSilver(String table) {
        return AirflowSilverToGoldVerifierJob.requireIsolatedSilver(table);
    }

    static String requireGold(String table) {
        return AirflowSilverToGoldVerifierJob.requireIsolatedGold(table);
    }

    static String[] cleanupRunIds(String value) {
        String[] runIds = Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .filter(runId -> !runId.isEmpty())
                .toArray(String[]::new);
        if (runIds.length == 0) {
            throw new IllegalArgumentException("cleanupRunIds must name at least one run");
        }
        return runIds;
    }

    record Customer(int customerId, String customerName, String email, String country,
                    String updatedAt) {
    }
}
