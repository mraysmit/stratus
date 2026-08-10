// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.DataFrameReader;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.analysis.TableAlreadyExistsException;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

/**
 * Job 1 — ingestion: a raw file in the landing zone becomes rows in a bronze
 * Iceberg table.
 *
 * <p>Bronze accumulates. Each landing file is a batch, identified by
 * {@code --batchId}, and a batch is appended to whatever is already there. This
 * is the zone's contract (architecture §6.4.6): bronze is the record of what
 * arrived, so a second day's file must not be able to erase the first day's.
 *
 * <p>Re-running a batch is refused by default. {@code --onExistingBatch
 * replace} is the deliberate replay path — it rewrites that batch alone, by an
 * explicit predicate on the batch id. A re-run that silently converged would be
 * indistinguishable from a job that wrote nothing at all, which is the harder
 * failure to notice of the two.
 *
 * <p>Normalisation is deliberately minimal: strings are trimmed and empty
 * strings become null. Anything beyond making the data addressable belongs in
 * the transform job, where it can be reviewed against the business key.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class IngestionJob {

    /** The batch a row arrived in. Bronze is partitioned on it. */
    public static final String BATCH_COLUMN = "stratus_batch_id";

    /** When the batch was ingested, from the job's clock. */
    public static final String INGESTED_AT_COLUMN = "stratus_ingested_at";

    /** The landing object the row was read from. */
    public static final String SOURCE_FILE_COLUMN = "stratus_source_file";

    /**
     * The columns ingestion adds, lower-cased for comparison. They are bronze's
     * record of how a row arrived; the transform job drops them, because a
     * silver row is rewritten by whichever batch last corrected it.
     */
    public static final Set<String> AUDIT_COLUMNS =
            Set.of(BATCH_COLUMN, INGESTED_AT_COLUMN, SOURCE_FILE_COLUMN);

    /** Refuse a batch id the table already holds. The default. */
    public static final String ON_EXISTING_FAIL = "fail";

    /** Rewrite the batch the table already holds, and only that batch. */
    public static final String ON_EXISTING_REPLACE = "replace";

    static final Set<String> ARGUMENTS = Set.of(
            "sourceFile", "targetTable", "sourceSystem", "batchId", "onExistingBatch",
            "schema", "runId");

    private static final Logger LOGGER = Logger.getLogger(IngestionJob.class.getName());

    private IngestionJob() {
    }

    public static void main(String... argv) {
        JobLogging.configureFromEnvironment();
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String sourceFile = arguments.require("sourceFile");
        String targetTable = arguments.require("targetTable");
        String sourceSystem = arguments.require("sourceSystem");
        String batchId = arguments.require("batchId");
        String onExistingBatch = arguments.optional("onExistingBatch").orElse(ON_EXISTING_FAIL);
        String schema = arguments.optional("schema").orElse(null);
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());

        // Validated before the session starts: a misspelled mode should cost a
        // second, not a cluster allocation (code_style_rules 4.3).
        if (!ON_EXISTING_FAIL.equals(onExistingBatch) && !ON_EXISTING_REPLACE.equals(onExistingBatch)) {
            throw new IllegalArgumentException("--onExistingBatch must be "
                    + ON_EXISTING_FAIL + " or " + ON_EXISTING_REPLACE + ", got: " + onExistingBatch);
        }

        SparkSession spark = SparkSession.builder()
                .appName("stratus-ingestion-" + targetTable)
                .getOrCreate();
        try {
            long written = run(spark, sourceFile, targetTable, sourceSystem, batchId,
                    onExistingBatch, schema, runId, Clock.systemUTC());
            LOGGER.info(String.format(
                    "INGESTION COMPLETE table=%s batchId=%s rowsInBatch=%d source=%s runId=%s",
                    targetTable, batchId, written, sourceFile, runId));
        } catch (SchemaDrift.Refusal refusal) {
            // Its own status code: an orchestrator retries a failed job and
            // escalates a refused one, and drift will still be there on retry.
            LOGGER.severe("INGESTION REFUSED " + refusal.getMessage());
            spark.stop();
            System.exit(JobExit.SCHEMA_DRIFT);
        } finally {
            spark.stop();
        }
    }

    static long run(SparkSession spark, String sourceFile, String targetTable, String sourceSystem,
                    String batchId, String onExistingBatch, String schema, String runId, Clock clock) {
        Dataset<Row> batch = stamp(normalise(read(spark, sourceFile, schema)),
                batchId, sourceFile, clock);

        if (!spark.catalog().tableExists(targetTable)) {
            create(spark, batch, targetTable, batchId);
        } else {
            append(spark, batch, targetTable, batchId, onExistingBatch);
        }

        ZoneWriteProperties.align(spark, targetTable, ZoneWriteProperties.bronze());
        // Recorded on the table so lineage survives the job that wrote it;
        // Increment 6 reads these back when registering the table in Atlas.
        // The batch id is deliberately absent — it belongs to a row, not to a
        // table that holds many batches.
        spark.sql(String.format(
                "ALTER TABLE %s SET TBLPROPERTIES ("
                        + "'stratus.source-system' = '%s', "
                        + "'stratus.last-source-file' = '%s', "
                        + "'stratus.last-ingestion-run-id' = '%s')",
                targetTable, sourceSystem, sourceFile, runId));

        LineageEvent.emit("INGESTION", "external:" + sourceSystem + "/" + sourceFile,
                targetTable, runId, clock);
        return spark.table(targetTable)
                .filter(functions.col(BATCH_COLUMN).equalTo(functions.lit(batchId))).count();
    }

    /**
     * Creates bronze partitioned by batch id.
     *
     * <p>Identity partitioning on the batch is what makes a replay cheap and
     * safe: the delete predicate resolves to whole partitions, so it is a
     * metadata operation that cannot touch another batch's files. It is also an
     * unbounded partition key, which is the wrong shape for a table taking
     * thousands of batches — production bronze would partition by ingest day
     * and identify batches within it.
     */
    private static void create(SparkSession spark, Dataset<Row> batch, String targetTable,
                               String batchId) {
        LOGGER.log(Level.FINE, () -> "INGESTION creating " + targetTable
                + " partitioned by " + BATCH_COLUMN);
        try {
            ZoneWriteProperties.onCreate(batch.writeTo(targetTable), ZoneWriteProperties.bronze())
                    .partitionedBy(functions.col(BATCH_COLUMN))
                    .create();
        } catch (TableAlreadyExistsException raced) {
            // Another ingestion created it between the check and the create.
            // The table now has the shape this batch needs, so continuing with
            // the append is the correct outcome, not a failure.
            LOGGER.log(Level.FINE, "bronze table appeared concurrently: " + targetTable, raced);
            append(spark, batch, targetTable, batchId, ON_EXISTING_FAIL);
        }
    }

    private static void append(SparkSession spark, Dataset<Row> batch, String targetTable,
                               String batchId, String onExistingBatch) {
        StructType held = spark.table(targetTable).schema();
        SchemaDrift.refuseOnConflict(targetTable, held, batch.schema());
        Dataset<Row> aligned = align(batch, held);

        boolean present = batchId != null && !spark.table(targetTable)
                .filter(functions.col(BATCH_COLUMN).equalTo(functions.lit(batchId)))
                .limit(1).isEmpty();
        if (present && ON_EXISTING_FAIL.equals(onExistingBatch)) {
            throw new IllegalStateException("Batch " + batchId + " is already in " + targetTable
                    + "; bronze is append-only. Re-run with --onExistingBatch "
                    + ON_EXISTING_REPLACE + " to replay it deliberately.");
        }

        try {
            if (present) {
                Column predicate = functions.col(BATCH_COLUMN).equalTo(functions.lit(batchId));
                LOGGER.log(Level.FINE, () -> "INGESTION replacing batch " + batchId
                        + " in " + targetTable);
                // An explicit predicate, not overwritePartitions(): dynamic
                // overwrite deletes the whole table when the partition spec is
                // absent, and reports success. This either replaces the named
                // batch or fails.
                aligned.writeTo(targetTable).option("merge-schema", "true").overwrite(predicate);
            } else {
                LOGGER.log(Level.FINE, () -> "INGESTION appending batch " + batchId
                        + " to " + targetTable);
                aligned.writeTo(targetTable).option("merge-schema", "true").append();
            }
        } catch (org.apache.spark.sql.catalyst.analysis.NoSuchTableException exception) {
            throw new IllegalStateException(targetTable
                    + " disappeared between the existence check and the write", exception);
        }
    }

    /**
     * Puts the batch into the table's column order, adding a typed null for any
     * column the table has and the batch does not.
     *
     * <p>A source system that stops sending a column has not changed the
     * table's meaning, but leaving the write to guess produces behaviour that
     * depends on the engine's resolution settings. Making the null explicit
     * makes the outcome the same on every run.
     */
    static Dataset<Row> align(Dataset<Row> batch, StructType held) {
        var batchColumns = Arrays.stream(batch.columns()).map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        var projection = new ArrayList<Column>();
        Dataset<Row> filled = batch;
        for (StructField field : held.fields()) {
            if (batchColumns.contains(field.name().toLowerCase(Locale.ROOT))) {
                projection.add(functions.col(field.name()));
            } else {
                filled = filled.withColumn(field.name(),
                        functions.lit(null).cast(field.dataType()));
                projection.add(functions.col(field.name()));
            }
        }
        // Columns the batch adds go last, which is where Iceberg's schema merge
        // appends them too.
        var heldNames = Arrays.stream(held.fieldNames()).map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (String column : batch.columns()) {
            if (!heldNames.contains(column.toLowerCase(Locale.ROOT))) {
                projection.add(functions.col(column));
            }
        }
        return filled.select(projection.toArray(new Column[0]));
    }

    /** Adds the columns that say which batch a row arrived in, and from where. */
    private static Dataset<Row> stamp(Dataset<Row> source, String batchId, String sourceFile,
                                      Clock clock) {
        // From the injected clock, not current_timestamp(): a value the job
        // cannot state is a value no test can assert on, and this one decides
        // which partition a replay lands in.
        Timestamp ingestedAt = Timestamp.from(Instant.now(clock));
        return source
                .withColumn(BATCH_COLUMN, functions.lit(batchId))
                .withColumn(INGESTED_AT_COLUMN, functions.lit(ingestedAt))
                .withColumn(SOURCE_FILE_COLUMN, functions.lit(sourceFile));
    }

    /**
     * Reads the landing file by its extension. The format is not guessed from
     * the content: a CSV read as JSON yields one null column per row and a
     * table that looks empty rather than a job that failed.
     *
     * <p>An explicit {@code --schema} is preferred over inference. Inference
     * reads types out of the values, so the same column is an integer in one
     * batch and a string in the next, {@code 007} becomes {@code 7}, and the
     * table's shape is decided by whichever rows happened to arrive.
     */
    private static Dataset<Row> read(SparkSession spark, String sourceFile, String schema) {
        String lower = sourceFile.toLowerCase(Locale.ROOT);
        DataFrameReader reader = spark.read();
        if (schema != null) {
            reader = reader.schema(schema);
        }
        if (lower.endsWith(".csv")) {
            reader = reader.option("header", "true");
            if (schema == null) {
                reader = reader.option("inferSchema", "true");
            }
            return reader.csv(sourceFile);
        }
        if (lower.endsWith(".json") || lower.endsWith(".ndjson")) {
            return reader.json(sourceFile);
        }
        throw new IllegalArgumentException(
                "Unsupported landing file extension, expected .csv, .json or .ndjson: " + sourceFile);
    }

    private static Dataset<Row> normalise(Dataset<Row> source) {
        Dataset<Row> result = source;
        for (StructField field : source.schema().fields()) {
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
