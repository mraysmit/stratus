// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small packaged job that proves the Airflow-to-standalone-Spark submission boundary.
 *
 * <p>This is deliberately more than a CLI or socket probe: the submitted driver creates a real
 * Spark application, schedules distributed range work, validates the result, and reports the
 * cluster application ID through the same SLF4J telemetry path as the platform batch jobs. It
 * creates and removes only a run-isolated probe table; pipeline behavior remains the
 * responsibility of the Increment 4 DAG verification slice.
 *
 * <p>This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-22
 * @version 1.0.0
 */
public final class SparkSubmissionProbeJob {

    static final Set<String> ARGUMENTS = Set.of("runId", "expectedCount");
    static final String COMPLETION_MARKER = "SPARK SUBMISSION PROBE COMPLETE";

    private static final Logger LOGGER = LoggerFactory.getLogger(SparkSubmissionProbeJob.class);

    private SparkSubmissionProbeJob() {
    }

    /** Runs the bounded distributed workload used by the Airflow integration acceptance test. */
    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());
        long expectedCount = parseExpectedCount(
                arguments.optional("expectedCount").orElse("1000"));

        try (var context = JobTelemetry.openContext(runId)) {
            SparkSession spark = SparkSession.builder()
                    .appName("stratus-airflow-submission-probe-" + runId)
                    .getOrCreate();
            try {
                long observedCount = JobTelemetry.measure(
                        "SUBMISSION_PROBE", "distributed_count", runId, "none",
                        () -> spark.range(expectedCount).repartition(2).count());
                if (observedCount != expectedCount) {
                    throw new IllegalStateException("Expected " + expectedCount
                            + " distributed records but observed " + observedCount);
                }
                long namespaceCount = JobTelemetry.measure(
                        "SUBMISSION_PROBE", "catalog_trust", runId, "stratus",
                        () -> spark.sql("SHOW NAMESPACES IN stratus").count());
                if (namespaceCount <= 0) {
                    throw new IllegalStateException("Polaris returned no Stratus namespaces");
                }
                long storageRowCount = JobTelemetry.measure(
                        "SUBMISSION_PROBE", "object_store_trust", runId,
                        "stratus.platform", () -> exerciseGovernedStorage(spark, runId));
                LOGGER.info("{} applicationId={} sparkVersion={} master={} expectedCount={} "
                                + "observedCount={} namespaceCount={} storageRowCount={} runId={}",
                        COMPLETION_MARKER,
                        spark.sparkContext().applicationId(), spark.version(),
                        spark.sparkContext().master(), expectedCount, observedCount,
                        namespaceCount, storageRowCount, runId);
            } finally {
                spark.stop();
            }
        }
    }

    static long parseExpectedCount(String rawCount) {
        final long count;
        try {
            count = Long.parseLong(rawCount);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("--expectedCount must be a positive integer", invalid);
        }
        if (count <= 0) {
            throw new IllegalArgumentException("--expectedCount must be greater than zero");
        }
        return count;
    }

    private static long exerciseGovernedStorage(SparkSession spark, String runId) {
        String suffix = runId.replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
        suffix = suffix.substring(Math.max(0, suffix.length() - 24));
        String tableName = "airflow_submission_probe_" + suffix;
        String probeTable = "stratus.platform." + tableName;
        try {
            spark.sql("CREATE TABLE stratus.platform." + tableName + " (id BIGINT) USING iceberg");
            spark.sql("INSERT INTO " + probeTable + " VALUES (1)");
            long rows = spark.table(probeTable).count();
            if (rows != 1) {
                throw new IllegalStateException("Expected one governed probe row but observed " + rows);
            }
            return rows;
        } finally {
            spark.sql("DROP TABLE IF EXISTS " + probeTable);
        }
    }
}
