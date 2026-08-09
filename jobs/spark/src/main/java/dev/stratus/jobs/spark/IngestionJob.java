// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.time.Clock;
import java.util.UUID;
import java.util.logging.Logger;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.DataTypes;

/**
 * Job 1 — ingestion: a raw file in the landing zone becomes a bronze Iceberg
 * table.
 *
 * <p>Normalisation is deliberately minimal: strings are trimmed and empty
 * strings become null. Bronze is the record of what arrived, so anything
 * beyond making the data addressable belongs in the transform job, where it
 * can be reviewed against the business key.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class IngestionJob {

    private static final Logger LOGGER = Logger.getLogger(IngestionJob.class.getName());

    private IngestionJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv);
        String sourceFile = arguments.require("sourceFile");
        String targetTable = arguments.require("targetTable");
        String sourceSystem = arguments.require("sourceSystem");
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());

        SparkSession spark = SparkSession.builder()
                .appName("stratus-ingestion-" + targetTable)
                .getOrCreate();
        try {
            long written = run(spark, sourceFile, targetTable, sourceSystem, runId, Clock.systemUTC());
            LOGGER.info(String.format(
                    "INGESTION COMPLETE table=%s rows=%d source=%s runId=%s",
                    targetTable, written, sourceFile, runId));
        } finally {
            spark.stop();
        }
    }

    static long run(SparkSession spark, String sourceFile, String targetTable,
                    String sourceSystem, String runId, Clock clock) {
        Dataset<Row> source = read(spark, sourceFile);
        Dataset<Row> normalised = normalise(source);

        // createOrReplace rather than append: re-running ingestion for the
        // same source file must converge on the same table rather than
        // doubling its rows, and bronze has no business key yet to dedupe on.
        normalised.writeTo(targetTable).createOrReplace();

        // Recorded on the table so lineage survives the job that wrote it;
        // Increment 6 reads these back when registering the table in Atlas.
        spark.sql(String.format(
                "ALTER TABLE %s SET TBLPROPERTIES ("
                        + "'stratus.source-system' = '%s', "
                        + "'stratus.source-file' = '%s', "
                        + "'stratus.ingestion-run-id' = '%s')",
                targetTable, sourceSystem, sourceFile, runId));

        LineageEvent.emit("INGESTION", "external:" + sourceSystem + "/" + sourceFile,
                targetTable, runId, clock);
        return spark.table(targetTable).count();
    }

    /**
     * Reads the landing file by its extension. The format is not guessed from
     * the content: a CSV read as JSON yields one null column per row and a
     * table that looks empty rather than a job that failed.
     */
    private static Dataset<Row> read(SparkSession spark, String sourceFile) {
        String lower = sourceFile.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return spark.read().option("header", "true").option("inferSchema", "true").csv(sourceFile);
        }
        if (lower.endsWith(".json") || lower.endsWith(".ndjson")) {
            return spark.read().json(sourceFile);
        }
        throw new IllegalArgumentException(
                "Unsupported landing file extension, expected .csv, .json or .ndjson: " + sourceFile);
    }

    private static Dataset<Row> normalise(Dataset<Row> source) {
        Dataset<Row> result = source;
        for (var field : source.schema().fields()) {
            if (!DataTypes.StringType.equals(field.dataType())) {
                continue;
            }
            Column trimmed = functions.trim(functions.col(field.name()));
            // An empty string and a missing value mean the same thing in a
            // landing extract, and only one of them is testable downstream.
            result = result.withColumn(field.name(),
                    functions.when(trimmed.equalTo(""), functions.lit(null).cast(DataTypes.StringType))
                            .otherwise(trimmed));
        }
        return result;
    }
}
