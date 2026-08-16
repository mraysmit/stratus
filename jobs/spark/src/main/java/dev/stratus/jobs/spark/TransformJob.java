// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.analysis.TableAlreadyExistsException;
import org.apache.spark.sql.functions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Job 2 — transform: bronze rows become conformed silver rows, upserted on a
 * business key.
 *
 * <p>Silver is merged, not rebuilt (architecture §6.4.6). The merge condition
 * compares a monotonic sequence as well as the key, so a row only updates a row
 * it is newer than. Matching on the key alone lets a replay carrying an older
 * version of a record overwrite the newer state already conformed — the
 * architecture calls that the most common silent corruption in a change-data
 * pipeline, and it is silent precisely because both rows are valid and the
 * table ends up holding the wrong one.
 *
 * <p>{@code --sourceBatch} narrows the read to one bronze batch. Reading the
 * whole table instead would re-pick the newest row per key on every run, which
 * produces the right answer for the wrong reason and leaves the sequence
 * comparison unable to ever decide anything.
 *
 * <p>The promotion gate is consulted before the write when a quality run id is
 * supplied. A blocked gate exits non-zero and writes nothing, which is what the
 * orchestration layer in Increment 4 detects as a failed task.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class TransformJob {

    static final Set<String> ARGUMENTS = Set.of(
            "sourceTable", "targetTable", "businessKey", "sequenceColumn", "sourceBatch",
            "runId", "qualityRunId");

    private static final Logger LOGGER = LoggerFactory.getLogger(TransformJob.class);

    private TransformJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String sourceTable = arguments.require("sourceTable");
        String targetTable = arguments.require("targetTable");
        String[] businessKey = arguments.requireList("businessKey");
        String sequenceColumn = arguments.require("sequenceColumn");
        String sourceBatch = arguments.optional("sourceBatch").orElse(null);
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());

        try (var context = JobTelemetry.openContext(runId)) {
            SparkSession spark = SparkSession.builder()
                    .appName("stratus-transform-" + targetTable)
                    .getOrCreate();
            try {
                arguments.optional("qualityRunId").ifPresent(qualityRunId -> {
                    var decision = PromotionGate.evaluate(spark, qualityRunId, sourceTable);
                    LOGGER.info("{}", decision.describe());
                    if (decision.blocked()) {
                        // Non-zero exit rather than an exception: the orchestrator
                        // reads the status code, and a stack trace would bury the
                        // reason the promotion was refused.
                        spark.stop();
                        System.exit(JobExit.PROMOTION_BLOCKED);
                    }
                });

                long written = run(spark, sourceTable, targetTable, businessKey, sequenceColumn,
                        sourceBatch, runId, Clock.systemUTC());
                LOGGER.info("TRANSFORM COMPLETE table={} rows={} businessKey={} sequence={} "
                                + "batch={} runId={}", targetTable, written,
                        String.join(",", businessKey), sequenceColumn,
                        sourceBatch == null ? "all" : sourceBatch, runId);
            } finally {
                spark.stop();
            }
        }
    }

    public static long run(SparkSession spark, String sourceTable, String targetTable, String[] businessKey,
                    String sequenceColumn, String sourceBatch, String runId, Clock clock) {
        Dataset<Row> source = JobTelemetry.measure("TRANSFORM", "resolve_source", runId,
                targetTable, () -> spark.table(sourceTable));
        JobTelemetry.measure("TRANSFORM", "validate_columns", runId, targetTable, () -> {
            for (String column : businessKey) {
                requireColumn(source, sourceTable, column, "Business key column");
            }
            requireColumn(source, sourceTable, sequenceColumn, "Sequence column");
        });

        if (sourceBatch != null) {
            boolean present = JobTelemetry.measure("TRANSFORM", "validate_source_batch", runId,
                    targetTable, () -> !spark.table(sourceTable)
                            .filter(functions.col(IngestionJob.BATCH_COLUMN)
                                    .equalTo(functions.lit(sourceBatch)))
                            .limit(1).isEmpty());
            if (!present) {
                // Merging nothing succeeds and changes nothing, so a mistyped
                // batch id would otherwise be a green run that did no work.
                throw new IllegalArgumentException("Batch " + sourceBatch + " has no rows in "
                        + sourceTable + "; nothing would be merged");
            }
        }

        String batchQuery = JobTelemetry.measure("TRANSFORM", "plan_upsert", runId, targetTable,
                () -> batchQuery(sourceTable, source.columns(), businessKey, sequenceColumn, sourceBatch));

        JobTelemetry.measure("TRANSFORM", "write_upsert", runId, targetTable, () -> {
            if (spark.catalog().tableExists(targetTable)) {
                spark.sql(mergeStatement(targetTable, batchQuery, businessKey, sequenceColumn));
            } else {
                LOGGER.debug("TRANSFORM creating {}", targetTable);
                try {
                    ZoneWriteProperties.onCreate(spark.sql(batchQuery).writeTo(targetTable),
                            ZoneWriteProperties.silver()).create();
                } catch (TableAlreadyExistsException raced) {
                    // Another transform created it between the check and the
                    // create. Merging into what is now there is the right
                    // continuation; replacing it would discard that run's work.
                    LOGGER.debug("silver table appeared concurrently: {}", targetTable, raced);
                    spark.sql(mergeStatement(targetTable, batchQuery, businessKey, sequenceColumn));
                }
            }
        });

        JobTelemetry.measure("TRANSFORM", "align_properties", runId, targetTable,
                () -> ZoneWriteProperties.align(spark, targetTable, ZoneWriteProperties.silver()));
        JobTelemetry.measure("TRANSFORM", "emit_lineage", runId, targetTable,
                () -> LineageEvent.emit("TRANSFORM", sourceTable, targetTable, runId, clock));
        return JobTelemetry.measure("TRANSFORM", "verify_written_rows", runId, targetTable,
                () -> spark.table(targetTable).count());
    }

    /**
     * The conformed rows of the batch: one per business key, the highest by the
     * sequence column, without bronze's ingestion audit columns.
     *
     * <p>A batch that carries a key twice would otherwise match the same target
     * row twice and Spark refuses the whole merge; collapsing here means the
     * source is single-valued by construction.
     *
     * <p>The ordering is made total by a hash of the row's own contents, so two
     * rows sharing a key <em>and</em> a sequence value still have a defined
     * winner. Ordering by the sequence alone leaves that choice to whichever row
     * the engine happened to read first, and a pipeline whose output can change
     * between runs over the same input cannot be reasoned about — every quality
     * result downstream would be measuring a different table.
     *
     * <p>This is SQL rather than the DataFrame API for a reason found on the
     * cluster: a temporary view registered from a DataFrame plan cannot be used
     * as a MERGE source. Spark 4.1 fails to plan it — {@code No plan for
     * TableReference[...]} — while the same query written as a subquery plans
     * and runs. Stating the whole operation as one statement also puts it in the
     * transcript, which is what an operator reads when a row did not update.
     */
    static String batchQuery(String sourceTable, String[] sourceColumns, String[] businessKey,
                             String sequenceColumn, String sourceBatch) {
        String projection = Arrays.stream(sourceColumns)
                .filter(column -> !IngestionJob.AUDIT_COLUMNS.contains(column.toLowerCase(Locale.ROOT)))
                .map(TransformJob::quote)
                .collect(Collectors.joining(", "));
        String partition = Arrays.stream(businessKey).map(TransformJob::quote)
                .collect(Collectors.joining(", "));
        String wholeRow = Arrays.stream(sourceColumns).map(TransformJob::quote)
                .collect(Collectors.joining(", "));
        String filter = sourceBatch == null ? ""
                : " WHERE " + quote(IngestionJob.BATCH_COLUMN) + " = " + literal(sourceBatch);

        String query = "SELECT " + projection
                + " FROM (SELECT *, row_number() OVER ("
                + "PARTITION BY " + partition
                + " ORDER BY " + quote(sequenceColumn) + " DESC, hash(" + wholeRow + ") ASC"
                + ") AS __stratus_row FROM " + sourceTable + filter
                + ") WHERE __stratus_row = 1";
        LOGGER.debug("TRANSFORM SOURCE {}", query);
        return query;
    }

    /**
     * Builds the upsert, aliasing the target so the sequence comparison can name
     * both sides — the alias is what makes it sayable at all.
     *
     * <p>{@code t.seq IS NULL} is part of the condition on purpose: a comparison
     * against a null sequence is null, not true, so a silver row that arrived
     * without one could never be corrected.
     */
    static String mergeStatement(String targetTable, String batchQuery, String[] businessKey,
                                 String sequenceColumn) {
        String on = Arrays.stream(businessKey)
                .map(TransformJob::quote)
                .map(column -> "t." + column + " = s." + column)
                .collect(Collectors.joining(" AND "));
        String sequence = quote(sequenceColumn);
        String statement = "MERGE INTO " + targetTable + " AS t"
                + " USING (" + batchQuery + ") AS s"
                + " ON " + on
                + " WHEN MATCHED AND (s." + sequence + " > t." + sequence
                + " OR t." + sequence + " IS NULL) THEN UPDATE SET *"
                + " WHEN NOT MATCHED THEN INSERT *";
        LOGGER.debug("TRANSFORM MERGE {}", statement);
        return statement;
    }

    /**
     * Quotes a column name that has already been checked against the source's
     * own columns, and refuses one carrying the quoting character itself.
     */
    private static String quote(String column) {
        if (column.indexOf('`') >= 0) {
            throw new IllegalArgumentException("Column name may not contain a backtick: " + column);
        }
        return '`' + column + '`';
    }

    /**
     * Renders a batch id as a SQL string literal.
     *
     * <p>A batch id names a delivery, so it is a value the pipeline chose rather
     * than free text — but it still reaches this method from the command line,
     * and it is the only caller-supplied value in the statement. A quote or a
     * backslash in it would end the literal early and make the rest of the
     * argument part of the query, so both are refused rather than escaped:
     * neither belongs in the name of a delivery, and refusing says so.
     */
    static String literal(String batchId) {
        if (batchId.indexOf('\'') >= 0 || batchId.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "A batch id may not contain a quote or a backslash: " + batchId);
        }
        return "'" + batchId + "'";
    }

    private static void requireColumn(Dataset<Row> source, String sourceTable, String column,
                                      String role) {
        List<String> columns = Arrays.asList(source.columns());
        boolean present = columns.stream()
                .anyMatch(name -> name.toLowerCase(Locale.ROOT).equals(column.toLowerCase(Locale.ROOT)));
        if (!present) {
            throw new IllegalArgumentException(role + " '" + column + "' is not in " + sourceTable
                    + ": " + columns);
        }
    }
}
