// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a versioned maintenance policy to measurements from an Iceberg table's metadata.
 *
 * <p>The policy job decides; {@link MaintenanceJob} acts. Airflow supplies only a table and policy
 * version, so a DAG cannot quietly bypass the metadata thresholds by naming destructive procedures.
 * Every decision records its metadata table, observed value, threshold, action, and policy version.
 */
public final class TableMaintenanceJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(TableMaintenanceJob.class);
    private static final Set<String> ARGUMENTS = Set.of("targetTable", "policy", "runId");
    private static final Pattern IDENTIFIER_PART = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern DEVELOPMENT_PROBE = Pattern.compile(
            "stratus\\.bronze\\.airflow_maintenance_probe_[a-z0-9_]+");
    private static final DateTimeFormatter SQL_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Duration ORPHAN_MINIMUM_AGE = Duration.ofHours(24);

    private TableMaintenanceJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String targetTable = requireTable(arguments.require("targetTable"));
        MaintenancePolicy policy = requirePolicy(arguments.require("policy"), targetTable);
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());

        try (var context = JobTelemetry.openContext(runId)) {
            SparkSession spark = SparkSession.builder()
                    .appName("stratus-table-maintenance-" + targetTable)
                    .getOrCreate();
            try {
                run(spark, targetTable, policy, runId);
            } finally {
                spark.stop();
            }
        }
    }

    static MaintenancePlan run(SparkSession spark, String targetTable,
                               MaintenancePolicy policy, String runId) {
        MaintenanceMetrics before = JobTelemetry.measure("TABLE_MAINTENANCE", "inspect_before",
                runId, targetTable, () -> inspect(spark, targetTable, policy));
        logMetrics("before", targetTable, policy, before);
        MaintenancePlan selected = plan(before, policy);

        logDecision(targetTable, policy, MaintenanceJob.REWRITE_DATA_FILES,
                "files", before.smallFileCount(), policy.smallFileCountTrigger(),
                selected.rewriteDataFiles());
        logDecision(targetTable, policy, MaintenanceJob.EXPIRE_SNAPSHOTS,
                "snapshots", before.snapshotChainLength(), policy.retainedSnapshots() + 1L,
                selected.expireSnapshots());

        if (!selected.operations().isEmpty()) {
            String olderThan = SQL_TIMESTAMP.format(Instant.now().minus(policy.snapshotMinimumAge()));
            MaintenanceJob.runPolicySelected(spark, targetTable,
                    selected.operations().toArray(String[]::new), olderThan,
                    Integer.toString(policy.retainedSnapshots()), runId,
                    policy.smallFileCountTrigger(), policy.targetFileSizeBytes());
        }

        MaintenanceMetrics after = JobTelemetry.measure("TABLE_MAINTENANCE", "inspect_after",
                runId, targetTable, () -> inspect(spark, targetTable, policy));
        logMetrics("after", targetTable, policy, after);
        LOGGER.info("TABLE MAINTENANCE COMPLETE table={} policyVersion={} selectedOperations={} "
                        + "beforeFiles={} afterFiles={} beforeSnapshots={} afterSnapshots={} runId={}",
                targetTable, policy.version(), selected.operations().isEmpty()
                        ? "none" : String.join(",", selected.operations()),
                before.fileCount(), after.fileCount(), before.snapshotChainLength(),
                after.snapshotChainLength(), runId);
        return selected;
    }

    static MaintenanceMetrics inspect(SparkSession spark, String targetTable,
                                      MaintenancePolicy policy) {
        Row fileMetrics = spark.sql("SELECT COUNT(*) AS file_count, "
                + "SUM(CASE WHEN file_size_in_bytes < " + policy.targetFileSizeBytes()
                + " THEN 1 ELSE 0 END) AS small_file_count, "
                + "CAST(COALESCE(AVG(file_size_in_bytes), 0) AS BIGINT) AS average_file_size "
                + "FROM " + targetTable + ".files").first();
        long snapshots = scalarCount(spark, targetTable + ".snapshots");
        long manifests = scalarCount(spark, targetTable + ".manifests");
        long deleteFiles = scalarCount(spark, targetTable + ".delete_files");
        long orphanFiles = countOrphans(spark, targetTable, policy);
        return new MaintenanceMetrics(fileMetrics.getLong(0), fileMetrics.getLong(1),
                fileMetrics.getLong(2), snapshots, manifests, deleteFiles, orphanFiles);
    }

    static MaintenancePlan plan(MaintenanceMetrics metrics, MaintenancePolicy policy) {
        boolean rewrite = metrics.smallFileCount() >= policy.smallFileCountTrigger();
        boolean expire = metrics.snapshotChainLength() > policy.retainedSnapshots();
        var operations = new ArrayList<String>();
        if (rewrite) {
            operations.add(MaintenanceJob.REWRITE_DATA_FILES);
        }
        if (expire) {
            operations.add(MaintenanceJob.EXPIRE_SNAPSHOTS);
        }
        return new MaintenancePlan(policy.version(), List.copyOf(operations), rewrite, expire,
                policy.smallFileCountTrigger(), policy.retainedSnapshots());
    }

    static MaintenancePolicy requirePolicy(String version, String targetTable) {
        String[] identifier = requireIdentifier(targetTable);
        MaintenancePolicy policy = switch (version) {
            case "bronze-default-v1" -> new MaintenancePolicy(version, "bronze",
                    128L * 1024L * 1024L, 8, 10, Duration.ofDays(7));
            case "silver-default-v1" -> new MaintenancePolicy(version, "silver",
                    256L * 1024L * 1024L, 6, 10, Duration.ofDays(7));
            case "gold-default-v1" -> new MaintenancePolicy(version, "gold",
                    512L * 1024L * 1024L, 4, 20, Duration.ofDays(14));
            case "development-run-v1" -> developmentPolicy(version, targetTable, 2);
            case "development-skip-v1" -> developmentPolicy(version, targetTable, 4);
            default -> throw new IllegalArgumentException("Unknown maintenance policy: " + version);
        };
        if (!policy.namespace().equals(identifier[1])) {
            throw new IllegalArgumentException("Policy " + version + " accepts only stratus."
                    + policy.namespace() + " tables, got: " + targetTable);
        }
        return policy;
    }

    private static MaintenancePolicy developmentPolicy(String version, String targetTable,
                                                       int smallFileTrigger) {
        if (!DEVELOPMENT_PROBE.matcher(targetTable).matches()) {
            throw new IllegalArgumentException("Policy " + version + " accepts only isolated "
                    + "stratus.bronze.airflow_maintenance_probe_<lowercase-run-token> tables, got: "
                    + targetTable);
        }
        return new MaintenancePolicy(version, "bronze", 512L * 1024L * 1024L, smallFileTrigger,
                100, Duration.ofDays(7));
    }

    private static long scalarCount(SparkSession spark, String metadataTable) {
        return spark.sql("SELECT COUNT(*) FROM " + metadataTable).first().getLong(0);
    }

    private static long countOrphans(SparkSession spark, String targetTable,
                                     MaintenancePolicy policy) {
        String catalog = requireIdentifier(targetTable)[0];
        String location = MaintenanceJob.tableLocation(spark, targetTable)
                .replaceFirst("^s3://", "s3a://");
        String cutoff = SQL_TIMESTAMP.format(Instant.now().minus(ORPHAN_MINIMUM_AGE));
        String call = String.format("CALL %s.system.remove_orphan_files(table => '%s', "
                        + "older_than => TIMESTAMP '%s', location => '%s', dry_run => true, "
                        + "equal_schemes => map('s3', 's3a'))",
                catalog, targetTable, cutoff, location);
        LOGGER.debug("TABLE MAINTENANCE orphan scan policyVersion={} call={}",
                policy.version(), call);
        return spark.sql(call).count();
    }

    private static void logDecision(String table, MaintenancePolicy policy, String operation,
                                    String metadataTable, long observed, long threshold,
                                    boolean run) {
        LOGGER.info("TABLE MAINTENANCE action={} operation={} table={} metadataTable={} "
                        + "observed={} threshold={} policyVersion={}",
                run ? "RUN" : "SKIP", operation, table, metadataTable, observed, threshold,
                policy.version());
    }

    private static void logMetrics(String phase, String table, MaintenancePolicy policy,
                                   MaintenanceMetrics metrics) {
        LOGGER.info("TABLE MAINTENANCE METRICS phase={} table={} policyVersion={} fileCount={} "
                        + "smallFileCount={} averageFileSize={} snapshotChainLength={} "
                        + "manifestCount={} deleteFileCount={} orphanFileCount={}",
                phase, table, policy.version(), metrics.fileCount(), metrics.smallFileCount(),
                metrics.averageFileSize(), metrics.snapshotChainLength(), metrics.manifestCount(),
                metrics.deleteFileCount(), metrics.orphanFileCount());
    }

    private static String requireTable(String table) {
        requireIdentifier(table);
        return table;
    }

    private static String[] requireIdentifier(String table) {
        String[] parts = QualityCheckJob.splitIdentifier(table);
        for (String part : parts) {
            if (!IDENTIFIER_PART.matcher(part).matches()) {
                throw new IllegalArgumentException("Unsafe table identifier: " + table);
            }
        }
        if (!"stratus".equals(parts[0])) {
            throw new IllegalArgumentException("Maintenance accepts only the stratus catalog, got: "
                    + table);
        }
        return parts;
    }

    record MaintenancePolicy(String version, String namespace, long targetFileSizeBytes,
                             int smallFileCountTrigger, int retainedSnapshots,
                             Duration snapshotMinimumAge) {
    }

    record MaintenanceMetrics(long fileCount, long smallFileCount, long averageFileSize,
                              long snapshotChainLength, long manifestCount, long deleteFileCount,
                              long orphanFileCount) {
    }

    record MaintenancePlan(String policyVersion, List<String> operations,
                           boolean rewriteDataFiles, boolean expireSnapshots,
                           int smallFileThreshold, int retainedSnapshots) {
    }
}
