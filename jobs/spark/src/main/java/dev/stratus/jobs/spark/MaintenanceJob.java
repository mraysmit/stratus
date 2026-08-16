// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Job 5 — table maintenance: expires snapshots, rewrites data files, and
 * removes orphan files through Iceberg's Spark procedures.
 *
 * <p>Orphan removal is the one destructive operation here, and it is refused
 * unless an explicit retention age is supplied. Iceberg's own default would
 * delete files older than three days; a job that inherits that silently can
 * remove files a concurrent write has staged but not yet committed. The
 * decision of what is safe to delete is proven separately by `P1-2.5-D1`,
 * whose detector never deletes at all.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class MaintenanceJob {

    public static final String EXPIRE_SNAPSHOTS = "expire_snapshots";
    public static final String REWRITE_DATA_FILES = "rewrite_data_files";
    public static final String DELETE_ORPHAN_FILES = "delete_orphan_files";

    static final Set<String> ARGUMENTS = Set.of("targetTable", "operations", "olderThan", "retainLast");

    private static final Logger LOGGER = LoggerFactory.getLogger(MaintenanceJob.class);

    private MaintenanceJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String targetTable = arguments.require("targetTable");
        String[] operations = arguments.requireList("operations");
        String runId = "maintenance-" + UUID.randomUUID();

        try (var context = JobTelemetry.openContext(runId)) {
            SparkSession spark = SparkSession.builder()
                    .appName("stratus-maintenance-" + targetTable)
                    .getOrCreate();
            try {
                List<String> metrics = run(spark, targetTable, operations,
                        arguments.optional("olderThan").orElse(null),
                        arguments.optional("retainLast").orElse(null), runId);
                metrics.forEach(LOGGER::info);
                LOGGER.info("MAINTENANCE COMPLETE table={} operations={}",
                        targetTable, String.join(",", operations));
            } finally {
                spark.stop();
            }
        }
    }

    public static List<String> run(SparkSession spark, String targetTable, String[] operations,
                            String olderThan, String retainLast) {
        return run(spark, targetTable, operations, olderThan, retainLast, "maintenance");
    }

    private static List<String> run(SparkSession spark, String targetTable, String[] operations,
                                    String olderThan, String retainLast, String runId) {
        String catalog = QualityCheckJob.splitIdentifier(targetTable)[0];
        var metrics = new ArrayList<String>();
        LOGGER.debug("MAINTENANCE planned table={} operations={} olderThan={} retainLast={}",
                targetTable, String.join(",", operations),
                olderThan == null ? "unset" : olderThan,
                retainLast == null ? "default 2" : retainLast);
        for (String operation : operations) {
            String metric = JobTelemetry.measure("MAINTENANCE", operation, runId, targetTable,
                    () -> switch (operation) {
                case EXPIRE_SNAPSHOTS -> expireSnapshots(spark, catalog, targetTable,
                        olderThan, retainLast);
                case REWRITE_DATA_FILES -> rewriteDataFiles(spark, catalog, targetTable);
                case DELETE_ORPHAN_FILES -> deleteOrphanFiles(spark, catalog, targetTable, olderThan);
                default -> throw new IllegalArgumentException(
                        "Unsupported maintenance operation: " + operation);
            });
            metrics.add(metric);
        }
        return metrics;
    }

    private static String expireSnapshots(SparkSession spark, String catalog, String targetTable,
                                          String olderThan, String retainLast) {
        // retain_last defaults to 1 in Iceberg. Keeping at least two by
        // default leaves a snapshot to roll back to after a bad write, which
        // is the situation expiry is most likely to be run near.
        String call = String.format("CALL %s.system.expire_snapshots(table => '%s', retain_last => %s%s)",
                catalog, targetTable, retainLast == null ? "2" : retainLast,
                olderThan == null ? "" : ", older_than => TIMESTAMP '" + olderThan + "'");
        LOGGER.debug("MAINTENANCE call {}", call);
        Row result = spark.sql(call).first();
        return "MAINTENANCE expire_snapshots table=" + targetTable
                + " dataFilesDeleted=" + result.get(0)
                + " manifestFilesDeleted=" + result.get(2);
    }

    private static String rewriteDataFiles(SparkSession spark, String catalog, String targetTable) {
        String call = String.format("CALL %s.system.rewrite_data_files(table => '%s')",
                catalog, targetTable);
        LOGGER.debug("MAINTENANCE call {}", call);
        Row result = spark.sql(call).first();
        return "MAINTENANCE rewrite_data_files table=" + targetTable
                + " rewrittenDataFiles=" + result.get(0)
                + " addedDataFiles=" + result.get(1);
    }

    private static String deleteOrphanFiles(SparkSession spark, String catalog, String targetTable,
                                            String olderThan) {
        if (olderThan == null) {
            throw new IllegalArgumentException(
                    "delete_orphan_files requires an explicit --olderThan timestamp; inheriting the "
                            + "default retention can delete files a concurrent write has staged");
        }
        // Three things this procedure needs that the others do not, each found
        // by running it against the live platform:
        //
        // - The fully qualified identifier. Orphan removal reads the table's
        //   metadata tables by name, and a two-part name is resolved with its
        //   first part taken for a catalog: "The catalog `bronze` not found".
        // - A location whose scheme Hadoop can list. Iceberg's S3FileIO writes
        //   paths as s3://, and the cluster registers only s3a, so listing the
        //   table's own location fails with "No FileSystem for scheme s3".
        // - equal_schemes, so a file listed as s3a:// is recognised as the same
        //   file the metadata records as s3://. Without it every live file
        //   looks like an orphan, which is the most destructive possible
        //   misreading.
        String location = tableLocation(spark, targetTable).replaceFirst("^s3://", "s3a://");
        String call = String.format(
                "CALL %s.system.remove_orphan_files(table => '%s', older_than => TIMESTAMP '%s', "
                        + "location => '%s', equal_schemes => map('s3', 's3a'))",
                catalog, targetTable, olderThan, location);
        LOGGER.debug("MAINTENANCE call {}", call);
        List<Row> removed = spark.sql(call).collectAsList();
        return "MAINTENANCE delete_orphan_files table=" + targetTable
                + " orphanFilesRemoved=" + removed.size();
    }

    /**
     * The table's own storage location, read from its newest metadata log
     * entry.
     *
     * <p>The location is not among the properties {@code SHOW TBLPROPERTIES}
     * returns, and the metadata log always has an entry, so this holds for a
     * table that has never been written to as well as one that has.
     */
    static String tableLocation(SparkSession spark, String targetTable) {
        Row newest = spark.sql("SELECT file FROM " + targetTable
                + ".metadata_log_entries ORDER BY timestamp DESC LIMIT 1").first();
        return metadataParent(newest.getString(0));
    }

    /** The table root holding a {@code <root>/metadata/<file>.json} entry. */
    static String metadataParent(String metadataFile) {
        int metadata = metadataFile.lastIndexOf("/metadata/");
        if (metadata < 0) {
            throw new IllegalStateException(
                    "Cannot derive the table location from " + metadataFile);
        }
        return metadataFile.substring(0, metadata);
    }

}
