// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * Job 3 — materialisation: one or more silver tables become a gold table.
 *
 * <p>The aggregation is domain-specific, so it is supplied as SQL rather than
 * hardcoded here: the job owns the contract — read only registered silver
 * tables, consult the promotion gate, write one gold table, emit lineage —
 * while the query owns the business meaning. That keeps a new gold dataset a
 * configuration change rather than a new job class.
 *
 * <p>The supplied SQL is read against the named source tables and nothing
 * else. It runs with the engine's own catalog privileges, so it is platform
 * configuration and not user input; the source tables are stated separately
 * precisely so lineage records what was actually read.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class MaterialisationJob {

    static final Set<String> ARGUMENTS = Set.of(
            "sourceTables", "targetTable", "sql", "runId", "qualityRunId");

    private static final Logger LOGGER = Logger.getLogger(MaterialisationJob.class.getName());

    private MaterialisationJob() {
    }

    public static void main(String... argv) {
        JobLogging.configureFromEnvironment();
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String[] sourceTables = arguments.requireList("sourceTables");
        String targetTable = arguments.require("targetTable");
        String sql = arguments.require("sql");
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());

        SparkSession spark = SparkSession.builder()
                .appName("stratus-materialisation-" + targetTable)
                .getOrCreate();
        try {
            arguments.optional("qualityRunId").ifPresent(qualityRunId -> {
                var decision = PromotionGate.evaluate(spark, qualityRunId, sourceTables[0]);
                LOGGER.info(decision.describe());
                if (decision.blocked()) {
                    spark.stop();
                    System.exit(JobExit.PROMOTION_BLOCKED);
                }
            });

            long written = run(spark, sourceTables, targetTable, sql, runId, Clock.systemUTC());
            LOGGER.info(String.format("MATERIALISATION COMPLETE table=%s rows=%d sources=%s runId=%s",
                    targetTable, written, String.join(",", sourceTables), runId));
        } finally {
            spark.stop();
        }
    }

    static long run(SparkSession spark, String[] sourceTables, String targetTable,
                    String sql, String runId, Clock clock) {
        // Resolving each source first turns a typo into an error naming the
        // missing table, rather than an analysis failure inside the query.
        for (String sourceTable : sourceTables) {
            spark.table(sourceTable);
        }

        Dataset<Row> materialised = spark.sql(sql);
        // A full rebuild is the documented gold write mode (§6.4.6), so replace
        // is correct here in a way it is not for bronze or silver.
        ZoneWriteProperties.onCreate(materialised.writeTo(targetTable), ZoneWriteProperties.gold())
                .createOrReplace();
        ZoneWriteProperties.align(spark, targetTable, ZoneWriteProperties.gold());
        LineageEvent.emit("MATERIALISATION", String.join(",", sourceTables), targetTable, runId, clock);
        return spark.table(targetTable).count();
    }
}
