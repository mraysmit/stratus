// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Job 4 — quality checks: runs the supplied rules against a table and writes
 * one record per rule to {@code platform.quality_check_results}.
 *
 * <p>Every rule produces a record whether it passes or fails. A quality
 * history that only records failures cannot distinguish a rule that passed
 * from one that never ran, and the promotion gate has to tell those apart.
 *
 * <p>A rule that fails is {@code FAILED} when it is blocking and
 * {@code WARNING} when it is not, which is the distinction the promotion gate
 * acts on. The job itself always exits zero: deciding what a failure means is
 * the gate's responsibility, not the measurer's.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class QualityCheckJob {

    public static final String RESULTS_TABLE = "stratus.platform.quality_check_results";
    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_WARNING = "WARNING";
    public static final String SEVERITY_BLOCKING = "blocking";

    static final Set<String> ARGUMENTS = Set.of(
            "targetTable", "checks", "checksBase64", "runId", "pipelineRunId");

    private static final Logger LOGGER = LoggerFactory.getLogger(QualityCheckJob.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private QualityCheckJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String targetTable = arguments.require("targetTable");
        String checks = checkDefinitions(arguments);
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());

        SparkSession spark = SparkSession.builder()
                .appName("stratus-quality-" + targetTable)
                .getOrCreate();
        try {
            List<Row> results = run(spark, targetTable, checks, runId,
                    arguments.optional("pipelineRunId").orElse(null), Clock.systemUTC());
            long passed = results.stream().filter(row -> STATUS_PASSED.equals(row.getString(7))).count();
            long failed = results.stream().filter(row -> STATUS_FAILED.equals(row.getString(7))).count();
            long warnings = results.stream().filter(row -> STATUS_WARNING.equals(row.getString(7))).count();
            LOGGER.info(
                    "QUALITY COMPLETE table={} runId={} checks={} passed={} failed={} warnings={}",
                    targetTable, runId, results.size(), passed, failed, warnings);
        } finally {
            spark.stop();
        }
    }

    /**
     * Reads the check definitions from either {@code --checks}, which carries
     * the JSON directly, or {@code --checksBase64}, which carries the same
     * JSON base64-encoded.
     *
     * <p>The encoded form exists because a JSON document does not survive
     * every path to a job. Submitting through a container runtime on Windows
     * strips the double quotes, and the job then fails on a document that was
     * correct when it was written. Encoding removes every character a shell or
     * an argument re-quoter would touch. Exactly one form must be given: a job
     * that silently preferred one over the other would run a set of rules
     * nobody chose.
     */
    static String checkDefinitions(JobArguments arguments) {
        var plain = arguments.optional("checks");
        var encoded = arguments.optional("checksBase64");
        if (plain.isPresent() == encoded.isPresent()) {
            throw new IllegalArgumentException(
                    "Supply exactly one of --checks or --checksBase64");
        }
        if (plain.isPresent()) {
            return plain.get();
        }
        try {
            return new String(java.util.Base64.getDecoder().decode(encoded.get()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "--checksBase64 is not valid base64: " + exception.getMessage(), exception);
        }
    }

    public static List<Row> run(SparkSession spark, String targetTable, String checksJson,
                         String runId, String pipelineRunId, Clock clock) {
        JsonNode definitions = JobTelemetry.measure("QUALITY", "parse_rules", runId, targetTable,
                () -> readDefinitions(checksJson));
        Dataset<Row> target = JobTelemetry.measure("QUALITY", "resolve_target", runId, targetTable,
                () -> spark.table(targetTable));
        String[] identifier = splitIdentifier(targetTable);
        Long snapshotId = JobTelemetry.measure("QUALITY", "resolve_snapshot", runId, targetTable,
                () -> currentSnapshotId(spark, targetTable));
        Timestamp checkedAt = Timestamp.from(Instant.now(clock));

        var rows = new ArrayList<Row>();
        for (JsonNode definition : definitions) {
            Outcome outcome = JobTelemetry.measure("QUALITY", "evaluate_rule", runId, targetTable,
                    () -> evaluate(spark, target, definition));
            String severity = text(definition, "severity", SEVERITY_BLOCKING);
            String status = outcome.passed
                    ? STATUS_PASSED
                    : (SEVERITY_BLOCKING.equalsIgnoreCase(severity) ? STATUS_FAILED : STATUS_WARNING);
            // The measurement behind the verdict, at DEBUG: an operator asking
            // why a rule failed needs the number it saw and the number it was
            // allowed, and neither belongs in the run's INFO summary.
            LOGGER.debug(
                    "QUALITY RULE table={} name={} type={} severity={} status={} metric={} threshold={}",
                    targetTable, text(definition, "name", null), text(definition, "type", null),
                    severity, status, outcome.metricValue, outcome.threshold);
            rows.add(RowFactory.create(
                    runId, identifier[1], identifier[2], identifier[1],
                    text(definition, "type", null), text(definition, "name", text(definition, "type", null)),
                    severity, status,
                    outcome.metricValue, outcome.threshold, outcome.detail,
                    pipelineRunId, checkedAt, snapshotId));
        }

        try {
            JobTelemetry.measureChecked("QUALITY", "persist_results", runId, targetTable, () -> {
                spark.createDataFrame(rows, resultSchema()).writeTo(RESULTS_TABLE).append();
                return null;
            });
        } catch (org.apache.spark.sql.catalyst.analysis.NoSuchTableException exception) {
            // The results table is provisioned by the catalog bootstrap, not
            // by this job: creating it here would invent a schema that the
            // rest of the platform reads.
            throw new IllegalStateException(RESULTS_TABLE + " does not exist; run the catalog "
                    + "bootstrap before running quality checks", exception);
        }
        return rows;
    }

    private static JsonNode readDefinitions(String checksJson) {
        JsonNode parsed;
        try {
            parsed = JSON.readTree(checksJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("--checks is not valid JSON: " + exception.getMessage(),
                    exception);
        }
        if (!parsed.isArray() || parsed.isEmpty()) {
            throw new IllegalArgumentException("--checks must be a non-empty JSON array");
        }
        return parsed;
    }

    private static Outcome evaluate(SparkSession spark, Dataset<Row> target, JsonNode definition) {
        String type = text(definition, "type", "");
        return switch (type) {
            case "schema_conformance" -> schemaConformance(target, definition);
            case "completeness" -> completeness(target, definition);
            case "uniqueness" -> uniqueness(target, definition);
            case "freshness" -> freshness(target, definition);
            case "referential_integrity" -> referentialIntegrity(spark, target, definition);
            case "row_count_min" -> rowCountMin(target, definition);
            default -> throw new IllegalArgumentException("Unsupported check type: " + type);
        };
    }

    private static Outcome schemaConformance(Dataset<Row> target, JsonNode definition) {
        List<String> expected = strings(definition, "columns");
        List<String> actual = Arrays.asList(target.columns());
        List<String> missing = expected.stream().filter(column -> !actual.contains(column)).toList();
        return new Outcome(missing.isEmpty(), (double) (expected.size() - missing.size()),
                (double) expected.size(),
                missing.isEmpty() ? null : "missing columns: " + String.join(",", missing));
    }

    private static Outcome completeness(Dataset<Row> target, JsonNode definition) {
        String column = text(definition, "column", null);
        double maxNullRate = definition.path("maxNullRate").asDouble(0.0);
        long total = target.count();
        // An empty table has no null rate to speak of; calling it complete is
        // the honest reading, and row_count_min is the rule that objects.
        double nullRate = total == 0 ? 0.0
                : (double) target.filter(functions.col(column).isNull()).count() / total;
        return new Outcome(nullRate <= maxNullRate, nullRate, maxNullRate,
                nullRate <= maxNullRate ? null
                        : String.format("null rate %.4f exceeds %.4f for column %s",
                                nullRate, maxNullRate, column));
    }

    private static Outcome uniqueness(Dataset<Row> target, JsonNode definition) {
        List<String> columns = strings(definition, "columns");
        long total = target.count();
        long distinct = target.select(columns.get(0),
                columns.subList(1, columns.size()).toArray(new String[0])).distinct().count();
        long duplicates = total - distinct;
        return new Outcome(duplicates == 0, (double) duplicates, 0.0,
                duplicates == 0 ? null
                        : duplicates + " duplicate rows on key " + String.join(",", columns));
    }

    private static Outcome freshness(Dataset<Row> target, JsonNode definition) {
        String column = text(definition, "column", null);
        double maxAgeMinutes = definition.path("maxAgeMinutes").asDouble(Double.MAX_VALUE);
        Row latest = target.select(functions.max(functions.col(column))).first();
        if (latest.isNullAt(0)) {
            return new Outcome(false, null, maxAgeMinutes, "no timestamp found in column " + column);
        }
        long latestMillis = latest.get(0) instanceof Timestamp timestamp
                ? timestamp.getTime()
                : Timestamp.valueOf(latest.get(0).toString()).getTime();
        double ageMinutes = (System.currentTimeMillis() - latestMillis) / 60_000.0;
        return new Outcome(ageMinutes <= maxAgeMinutes, ageMinutes, maxAgeMinutes,
                ageMinutes <= maxAgeMinutes ? null
                        : String.format("latest record is %.1f minutes old, SLA is %.1f",
                                ageMinutes, maxAgeMinutes));
    }

    private static Outcome referentialIntegrity(SparkSession spark, Dataset<Row> target,
                                                JsonNode definition) {
        String column = text(definition, "column", null);
        String referenceTable = text(definition, "referenceTable", null);
        String referenceColumn = text(definition, "referenceColumn", column);
        Dataset<Row> reference = spark.table(referenceTable).select(referenceColumn).distinct();
        // Nulls are absent references, not broken ones; completeness is the
        // rule that judges whether they are allowed.
        long orphans = target.filter(functions.col(column).isNotNull())
                .join(reference, target.col(column).equalTo(reference.col(referenceColumn)), "left_anti")
                .count();
        return new Outcome(orphans == 0, (double) orphans, 0.0,
                orphans == 0 ? null
                        : orphans + " values in " + column + " are absent from " + referenceTable);
    }

    private static Outcome rowCountMin(Dataset<Row> target, JsonNode definition) {
        long minimum = definition.path("minRows").asLong(1L);
        long rows = target.count();
        return new Outcome(rows >= minimum, (double) rows, (double) minimum,
                rows >= minimum ? null : rows + " rows is below the minimum of " + minimum);
    }

    private static Long currentSnapshotId(SparkSession spark, String targetTable) {
        List<Row> snapshots = spark.sql(
                        "SELECT snapshot_id FROM " + targetTable + ".snapshots ORDER BY committed_at DESC LIMIT 1")
                .collectAsList();
        return snapshots.isEmpty() || snapshots.get(0).isNullAt(0) ? null : snapshots.get(0).getLong(0);
    }

    /** Splits {@code catalog.namespace.table}; the namespace is the zone. */
    static String[] splitIdentifier(String targetTable) {
        String[] parts = targetTable.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Expected a catalog.namespace.table identifier, got: " + targetTable);
        }
        return parts;
    }

    /**
     * The shape a result record is written in.
     *
     * <p>Every writer to the results table uses this rather than reading the
     * deployed table's schema back. The two are not interchangeable: Iceberg
     * maps {@code checked_at} to {@code TIMESTAMP_NTZ}, and a row built with a
     * {@code java.sql.Timestamp} against that schema is refused outright, while
     * against this one Spark converts it on the write. Reading the table's
     * schema also makes the record's column order depend on whatever is
     * deployed.
     */
    static StructType resultSchema() {
        return new StructType()
                .add("run_id", DataTypes.StringType, false)
                .add("dataset_namespace", DataTypes.StringType, false)
                .add("dataset_name", DataTypes.StringType, false)
                .add("zone", DataTypes.StringType, false)
                .add("check_type", DataTypes.StringType, false)
                .add("check_name", DataTypes.StringType, false)
                .add("severity", DataTypes.StringType, false)
                .add("status", DataTypes.StringType, false)
                .add("metric_value", DataTypes.DoubleType, true)
                .add("threshold", DataTypes.DoubleType, true)
                .add("failure_detail", DataTypes.StringType, true)
                .add("pipeline_run_id", DataTypes.StringType, true)
                .add("checked_at", DataTypes.TimestampType, false)
                .add("iceberg_snapshot_id", DataTypes.LongType, true);
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode array = node.path(field);
        if (!array.isArray() || array.isEmpty()) {
            throw new IllegalArgumentException("Check field '" + field + "' must be a non-empty array");
        }
        var values = new ArrayList<String>();
        array.forEach(element -> values.add(element.asText()));
        return values;
    }

    /** One rule's measurement: what was observed, what was allowed, and why it failed. */
    private record Outcome(boolean passed, Double metricValue, Double threshold, String detail) {
    }
}
