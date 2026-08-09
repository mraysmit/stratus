// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Logger;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.expressions.Window;
import org.apache.spark.sql.functions;

/**
 * Job 2 — transform: a bronze table becomes a deduplicated silver table.
 *
 * <p>Deduplication keeps one row per business key. Which one is not arbitrary:
 * rows are ordered by an ordering column when the caller names one, so a
 * re-run over the same input produces the same silver table. Without that,
 * two runs of the same job can disagree and neither is wrong, which makes
 * every downstream quality check unreproducible.
 *
 * <p>The promotion gate is consulted before the write when a quality run id is
 * supplied. A blocked gate exits non-zero and writes nothing, which is what
 * the orchestration layer in Increment 4 detects as a failed task.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class TransformJob {

    private static final Logger LOGGER = Logger.getLogger(TransformJob.class.getName());

    private TransformJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv);
        String sourceTable = arguments.require("sourceTable");
        String targetTable = arguments.require("targetTable");
        String[] businessKey = arguments.requireList("businessKey");
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());

        SparkSession spark = SparkSession.builder()
                .appName("stratus-transform-" + targetTable)
                .getOrCreate();
        try {
            arguments.optional("qualityRunId").ifPresent(qualityRunId -> {
                var decision = PromotionGate.evaluate(spark, qualityRunId, sourceTable);
                LOGGER.info(decision.describe());
                if (decision.blocked()) {
                    // Non-zero exit rather than an exception: the orchestrator
                    // reads the status code, and a stack trace would bury the
                    // reason the promotion was refused.
                    spark.stop();
                    System.exit(2);
                }
            });

            long written = run(spark, sourceTable, targetTable, businessKey,
                    arguments.optional("orderBy").orElse(null), runId, Clock.systemUTC());
            LOGGER.info(String.format("TRANSFORM COMPLETE table=%s rows=%d businessKey=%s runId=%s",
                    targetTable, written, String.join(",", businessKey), runId));
        } finally {
            spark.stop();
        }
    }

    static long run(SparkSession spark, String sourceTable, String targetTable,
                    String[] businessKey, String orderBy, String runId, Clock clock) {
        Dataset<Row> source = spark.table(sourceTable);
        for (String column : businessKey) {
            if (Arrays.stream(source.columns()).noneMatch(column::equals)) {
                throw new IllegalArgumentException("Business key column '" + column
                        + "' is not in " + sourceTable + ": " + Arrays.toString(source.columns()));
            }
        }

        Column[] partition = Arrays.stream(businessKey).map(functions::col).toArray(Column[]::new);
        // A deterministic tie-break: without an explicit ordering column the
        // rows are ordered by the whole business key, so the choice is stable
        // across runs even though it is arbitrary between duplicates.
        Column ordering = orderBy == null ? partition[0] : functions.col(orderBy).desc();

        Dataset<Row> deduplicated = source
                .withColumn("__stratus_row", functions.row_number()
                        .over(Window.partitionBy(partition).orderBy(ordering)))
                .filter(functions.col("__stratus_row").equalTo(1))
                .drop("__stratus_row");

        deduplicated.writeTo(targetTable).createOrReplace();
        LineageEvent.emit("TRANSFORM", sourceTable, targetTable, runId, clock);
        return spark.table(targetTable).count();
    }
}
