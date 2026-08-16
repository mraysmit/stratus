// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a dataset may be promoted, from the quality results already
 * recorded for a run.
 *
 * <p>The decision is deterministic and reads nothing but
 * {@code platform.quality_check_results}: any blocking check recorded as
 * {@code FAILED} blocks, warnings never do, and a run with no recorded results
 * blocks rather than promotes. That last case matters — a quality job that
 * crashed before writing leaves no failures, and treating "no evidence" as
 * "no problem" would promote exactly the data nobody checked.
 *
 * <p>An override is recorded as an additional {@code overridden} record
 * against the same run, so the results table remains the whole history of what
 * was decided and by whom. Overriding is never silent and never edits the
 * original verdict.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class PromotionGate {

    public static final String STATUS_OVERRIDDEN = "overridden";

    static final Set<String> ARGUMENTS = Set.of(
            "runId", "targetTable", "override-reason", "override-principal");

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionGate.class);

    private PromotionGate() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String runId = arguments.require("runId");
        String targetTable = arguments.require("targetTable");

        SparkSession spark = SparkSession.builder().appName("stratus-promotion-gate").getOrCreate();
        try {
            PromotionDecision decision = evaluate(spark, runId, targetTable);
            LOGGER.info("{}", decision.describe());

            var overrideReason = arguments.optional("override-reason");
            var overridePrincipal = arguments.optional("override-principal");
            if (overrideReason.isPresent() != overridePrincipal.isPresent()) {
                throw new IllegalArgumentException(
                        "An override requires both --override-reason and --override-principal");
            }
            if (decision.blocked() && overrideReason.isPresent()) {
                override(spark, runId, targetTable, overridePrincipal.get(), overrideReason.get(),
                        Clock.systemUTC());
                LOGGER.info("PROMOTION OVERRIDDEN runId={} principal={}",
                        runId, overridePrincipal.get());
                return;
            }
            if (decision.blocked()) {
                spark.stop();
                System.exit(JobExit.PROMOTION_BLOCKED);
            }
        } finally {
            spark.stop();
        }
    }

    public static PromotionDecision evaluate(SparkSession spark, String runId, String targetTable) {
        List<Row> results = JobTelemetry.measure("PROMOTION", "read_evidence", runId, targetTable,
                () -> spark.table(QualityCheckJob.RESULTS_TABLE)
                        .filter(functions.col("run_id").equalTo(runId))
                        .select("check_name", "severity", "status")
                        .collectAsList());

        var failing = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        boolean overridden = false;
        for (Row row : results) {
            String status = row.getString(2);
            // Each recorded verdict at DEBUG: the INFO line says what the gate
            // decided, and this says which records it decided from.
            LOGGER.debug("PROMOTION EVIDENCE runId={} check={} severity={} status={}",
                    runId, row.getString(0), row.getString(1), status);
            if (STATUS_OVERRIDDEN.equals(status)) {
                overridden = true;
            } else if (QualityCheckJob.STATUS_FAILED.equals(status)) {
                failing.add(row.getString(0));
            } else if (QualityCheckJob.STATUS_WARNING.equals(status)) {
                warnings.add(row.getString(0));
            }
        }

        // No results at all is not a pass. See the class comment.
        boolean blocked = results.isEmpty() || (!failing.isEmpty() && !overridden);
        return new PromotionDecision(runId, targetTable, blocked, results.size(), failing, warnings);
    }

    /** Records an explicit override without changing the verdict it overrides. */
    public static PromotionDecision override(SparkSession spark, String runId, String targetTable,
                                             String principal, String reason, Clock clock) {
        if (principal == null || principal.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "An override requires a non-blank principal and reason");
        }
        PromotionDecision decision = evaluate(spark, runId, targetTable);
        if (decision.blocked()) {
            recordOverride(spark, decision, principal, reason, clock);
        }
        return decision;
    }

    private static void recordOverride(SparkSession spark, PromotionDecision decision,
                                       String principal, String reason, Clock clock) {
        String[] identifier = QualityCheckJob.splitIdentifier(decision.targetTable());
        Row record = RowFactory.create(
                decision.runId(), identifier[1], identifier[2], identifier[1],
                "promotion_override", "promotion_override", QualityCheckJob.SEVERITY_BLOCKING,
                STATUS_OVERRIDDEN, null, null,
                "overridden by " + principal + ": " + reason
                        + " (failing: " + String.join(",", decision.failingChecks()) + ")",
                null, Timestamp.from(Instant.now(clock)), null);
        try {
            // The declared shape, not the deployed table's. Iceberg maps
            // checked_at to TIMESTAMP_NTZ, and a java.sql.Timestamp against
            // that schema is refused where the same value against the declared
            // one is converted on the write.
            spark.createDataFrame(List.of(record), QualityCheckJob.resultSchema())
                    .writeTo(QualityCheckJob.RESULTS_TABLE).append();
        } catch (org.apache.spark.sql.catalyst.analysis.NoSuchTableException exception) {
            throw new IllegalStateException(QualityCheckJob.RESULTS_TABLE
                    + " does not exist; an override cannot be recorded without it", exception);
        }
    }
}
