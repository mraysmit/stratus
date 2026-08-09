// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

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

    private static final Logger LOGGER = Logger.getLogger(MaintenanceJob.class.getName());

    private MaintenanceJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv);
        String targetTable = arguments.require("targetTable");
        String[] operations = arguments.requireList("operations");

        SparkSession spark = SparkSession.builder()
                .appName("stratus-maintenance-" + targetTable)
                .getOrCreate();
        try {
            List<String> metrics = run(spark, targetTable, operations,
                    arguments.optional("olderThan").orElse(null),
                    arguments.optional("retainLast").orElse(null));
            metrics.forEach(LOGGER::info);
            LOGGER.info("MAINTENANCE COMPLETE table=" + targetTable
                    + " operations=" + String.join(",", operations));
        } finally {
            spark.stop();
        }
    }

    static List<String> run(SparkSession spark, String targetTable, String[] operations,
                            String olderThan, String retainLast) {
        String catalog = QualityCheckJob.splitIdentifier(targetTable)[0];
        var metrics = new ArrayList<String>();
        for (String operation : operations) {
            switch (operation) {
                case EXPIRE_SNAPSHOTS -> metrics.add(expireSnapshots(spark, catalog, targetTable,
                        olderThan, retainLast));
                case REWRITE_DATA_FILES -> metrics.add(rewriteDataFiles(spark, catalog, targetTable));
                case DELETE_ORPHAN_FILES -> metrics.add(deleteOrphanFiles(spark, catalog, targetTable,
                        olderThan));
                default -> throw new IllegalArgumentException(
                        "Unsupported maintenance operation: " + operation);
            }
        }
        return metrics;
    }

    private static String expireSnapshots(SparkSession spark, String catalog, String targetTable,
                                          String olderThan, String retainLast) {
        // retain_last defaults to 1 in Iceberg. Keeping at least two by
        // default leaves a snapshot to roll back to after a bad write, which
        // is the situation expiry is most likely to be run near.
        String call = String.format("CALL %s.system.expire_snapshots(table => '%s', retain_last => %s%s)",
                catalog, unqualified(targetTable), retainLast == null ? "2" : retainLast,
                olderThan == null ? "" : ", older_than => TIMESTAMP '" + olderThan + "'");
        Row result = spark.sql(call).first();
        return "MAINTENANCE expire_snapshots table=" + targetTable
                + " dataFilesDeleted=" + result.get(0)
                + " manifestFilesDeleted=" + result.get(2);
    }

    private static String rewriteDataFiles(SparkSession spark, String catalog, String targetTable) {
        Row result = spark.sql(String.format(
                "CALL %s.system.rewrite_data_files(table => '%s')",
                catalog, unqualified(targetTable))).first();
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
        List<Row> removed = spark.sql(String.format(
                "CALL %s.system.remove_orphan_files(table => '%s', older_than => TIMESTAMP '%s')",
                catalog, unqualified(targetTable), olderThan)).collectAsList();
        return "MAINTENANCE delete_orphan_files table=" + targetTable
                + " orphanFilesRemoved=" + removed.size();
    }

    /** Iceberg procedures take the identifier without its catalog prefix. */
    private static String unqualified(String targetTable) {
        String[] parts = QualityCheckJob.splitIdentifier(targetTable);
        return parts[1] + "." + parts[2];
    }
}
