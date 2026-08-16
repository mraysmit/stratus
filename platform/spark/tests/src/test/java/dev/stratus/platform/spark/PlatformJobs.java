// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import dev.stratus.jobs.spark.IngestionJob;
import dev.stratus.jobs.spark.JobExit;
import dev.stratus.jobs.spark.MaintenanceJob;
import dev.stratus.jobs.spark.MaterialisationJob;
import dev.stratus.jobs.spark.PromotionGate;
import dev.stratus.jobs.spark.PromotionDecision;
import dev.stratus.jobs.spark.QualityCheckJob;
import dev.stratus.jobs.spark.SchemaDrift;
import dev.stratus.jobs.spark.TransformJob;
import java.time.Clock;
import java.util.List;

/**
 * Runs the platform jobs in a client's own driver, and reports what each one
 * did as the status code its {@code main} would have exited with.
 *
 * <p>A client of the platform runs job code in its own process, submitted to
 * the cluster. That is what this does. The suite it replaces ran
 * {@code spark-submit} inside the master container, which exercised the
 * cluster's own Spark installation and the identity baked into its
 * configuration file — never the path a client takes, and never the client's
 * own principal.
 *
 * <p>The jobs' {@code main} methods are deliberately not called: they end in
 * {@code System.exit}, which would take the test JVM with them. What they add
 * over {@code run} is argument parsing and the mapping from an outcome to a
 * status code, and both are covered by the offline tests in {@code jobs/spark}.
 * The mapping is reproduced here so a live test can still assert on the
 * documented codes rather than on an exception type.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-10
 * @version 1.0.0
 */
final class PlatformJobs {

    private final StratusSparkClient client;

    PlatformJobs(StratusSparkClient client) {
        this.client = client;
    }

    /** One job's outcome: the status code its {@code main} would have used, and why. */
    record Outcome(int exitCode, String detail) {

        boolean succeeded() {
            return exitCode == JobExit.SUCCESS;
        }

        String describe() {
            return "exit=" + exitCode + " " + SparkLogSanitizer.token(detail, 4096);
        }
    }

    /**
     * Reloads the catalog's view of the tables a job is about to touch.
     *
     * <p>A job submitted as its own application always begins with fresh
     * metadata. A job running inside a long-lived client session begins with
     * whatever that session last cached, so writes made by another application
     * since can be invisible to it. Asked to keep one snapshot of eight,
     * {@code expire_snapshots} found only the one snapshot the session
     * remembered, expired nothing, and reported success — while a read in the
     * same suite correctly reported eight. Observed 2026-08-12.
     *
     * <p>Refreshing here rather than in the tests keeps the two submission
     * paths behaving the same way, which is the whole point of running the
     * jobs in the driver.
     */
    private void refresh(String... tables) {
        for (String table : tables) {
            if (table != null) {
                try (var observed = SparkTelemetry.start("catalog_refresh", "spark.catalog.refresh",
                        "applicationId=" + client.applicationId()
                                + " table=" + SparkLogSanitizer.token(table))) {
                    try {
                        boolean exists = client.session().catalog().tableExists(table);
                        if (exists) {
                            client.session().catalog().refreshTable(table);
                        }
                        observed.succeeded("exists=" + exists);
                    } catch (Exception failure) {
                        observed.failed(failure, "");
                        throw unchecked(failure);
                    }
                }
            }
        }
    }

    Outcome ingest(String sourceFile, String targetTable, String sourceSystem, String batchId,
                   String onExistingBatch, String schema, String runId) {
        refresh(targetTable);
        return run("ingestion", "table=" + targetTable + " batchId=" + batchId
                + " runId=" + runId, () -> {
            IngestionJob.run(client.session(), sourceFile, targetTable, sourceSystem, batchId,
                    onExistingBatch, schema, runId, Clock.systemUTC());
            return "ingested batch " + batchId + " into " + targetTable;
        });
    }

    Outcome transform(String sourceTable, String targetTable, String[] businessKey,
                      String sequenceColumn, String sourceBatch, String runId,
                      String qualityRunId) {
        refresh(sourceTable, targetTable);
        // The gate is consulted first, exactly as the job's main does, so a
        // blocked run writes nothing and reports the documented status code.
        if (qualityRunId != null) {
            var decision = evaluateGate(qualityRunId, sourceTable);
            if (decision.blocked()) {
                return new Outcome(JobExit.PROMOTION_BLOCKED, decision.describe());
            }
        }
        return run("transform", "sourceTable=" + sourceTable + " targetTable=" + targetTable
                + " runId=" + runId, () -> {
            TransformJob.run(client.session(), sourceTable, targetTable, businessKey,
                    sequenceColumn, sourceBatch, runId, Clock.systemUTC());
            return "transformed into " + targetTable;
        });
    }

    Outcome materialise(String[] sourceTables, String targetTable, String sql, String runId) {
        refresh(sourceTables);
        refresh(targetTable);
        return run("materialisation", "targetTable=" + targetTable + " runId=" + runId, () -> {
            MaterialisationJob.run(client.session(), sourceTables, targetTable, sql, runId,
                    Clock.systemUTC());
            return "materialised " + targetTable;
        });
    }

    Outcome checkQuality(String targetTable, String checksJson, String runId) {
        refresh(targetTable);
        return run("quality", "targetTable=" + targetTable + " runId=" + runId, () -> {
            List<?> results = QualityCheckJob.run(client.session(), targetTable, checksJson, runId,
                    null, Clock.systemUTC());
            return "recorded " + results.size() + " results for " + runId;
        });
    }

    Outcome maintain(String targetTable, String[] operations, String olderThan, String retainLast) {
        refresh(targetTable);
        return run("maintenance", "targetTable=" + targetTable
                + " operations=" + String.join(",", operations),
                () -> String.join("; ", MaintenanceJob.run(client.session(), targetTable,
                operations, olderThan, retainLast)));
    }

    Outcome gate(String runId, String targetTable) {
        refresh(targetTable);
        var decision = evaluateGate(runId, targetTable);
        return new Outcome(decision.blocked() ? JobExit.PROMOTION_BLOCKED : JobExit.SUCCESS,
                decision.describe());
    }

    Outcome overrideGate(String runId, String targetTable, String principal, String reason) {
        refresh(targetTable);
        return run("promotion_override", "targetTable=" + targetTable + " runId=" + runId
                + " principal=" + principal, () -> {
            var decision = PromotionGate.override(client.session(), runId, targetTable,
                    principal, reason, Clock.systemUTC());
            return decision.blocked()
                    ? "PROMOTION OVERRIDDEN runId=" + runId + " principal=" + principal
                    : decision.describe();
        });
    }

    private PromotionDecision evaluateGate(String runId, String targetTable) {
        try (var observed = SparkTelemetry.start("promotion_gate", "spark.job.promotion_gate",
                "applicationId=" + client.applicationId() + " runId=" + runId
                        + " targetTable=" + targetTable)) {
            try {
                PromotionDecision decision = PromotionGate.evaluate(
                        client.session(), runId, targetTable);
                observed.succeeded("blocked=" + decision.blocked()
                        + " checks=" + decision.checksExamined());
                return decision;
            } catch (Exception failure) {
                observed.failed(failure, "");
                throw unchecked(failure);
            }
        }
    }

    /**
     * Maps an outcome to the status code the job documents.
     *
     * <p>Schema drift has a code of its own because an orchestrator retries a
     * failed job and escalates a refused one, and drift will still be there on
     * the retry.
     */
    private Outcome run(String jobType, String fields, ThrowingSupplier work) {
        try (var observed = SparkTelemetry.start("platform_job", "spark.job." + jobType,
                "applicationId=" + client.applicationId() + " jobType=" + jobType + ' ' + fields)) {
            try {
                String detail = work.get();
                SparkVerificationLogging.jobCompleted(client.config().principalId(),
                        JobExit.SUCCESS, detail);
                observed.succeeded("exitCode=" + JobExit.SUCCESS);
                return new Outcome(JobExit.SUCCESS, detail);
            } catch (SchemaDrift.Refusal refusal) {
                SparkVerificationLogging.jobCompleted(client.config().principalId(),
                        JobExit.SCHEMA_DRIFT, refusal.getMessage());
                observed.failed(refusal, "exitCode=" + JobExit.SCHEMA_DRIFT);
                return new Outcome(JobExit.SCHEMA_DRIFT, refusal.getMessage());
            } catch (Exception failure) {
                String detail = failure.getMessage() == null
                        ? failure.getClass().getName() : failure.getMessage();
                SparkVerificationLogging.jobCompleted(client.config().principalId(),
                        JobExit.FAILURE, detail);
                observed.failed(failure, "exitCode=" + JobExit.FAILURE);
                return new Outcome(JobExit.FAILURE, detail);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get();
    }

    private static RuntimeException unchecked(Exception failure) {
        return failure instanceof RuntimeException runtimeFailure
                ? runtimeFailure : new IllegalStateException(failure.getMessage(), failure);
    }
}
