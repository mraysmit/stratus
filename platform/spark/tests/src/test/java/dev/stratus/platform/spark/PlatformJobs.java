// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import dev.stratus.jobs.spark.IngestionJob;
import dev.stratus.jobs.spark.JobExit;
import dev.stratus.jobs.spark.MaintenanceJob;
import dev.stratus.jobs.spark.MaterialisationJob;
import dev.stratus.jobs.spark.PromotionGate;
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
            return "exit=" + exitCode + " " + detail;
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
            if (table != null && client.session().catalog().tableExists(table)) {
                client.session().catalog().refreshTable(table);
            }
        }
    }

    Outcome ingest(String sourceFile, String targetTable, String sourceSystem, String batchId,
                   String onExistingBatch, String schema, String runId) {
        refresh(targetTable);
        return run(() -> {
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
            var decision = PromotionGate.evaluate(client.session(), qualityRunId, sourceTable);
            if (decision.blocked()) {
                return new Outcome(JobExit.PROMOTION_BLOCKED, decision.describe());
            }
        }
        return run(() -> {
            TransformJob.run(client.session(), sourceTable, targetTable, businessKey,
                    sequenceColumn, sourceBatch, runId, Clock.systemUTC());
            return "transformed into " + targetTable;
        });
    }

    Outcome materialise(String[] sourceTables, String targetTable, String sql, String runId) {
        refresh(sourceTables);
        refresh(targetTable);
        return run(() -> {
            MaterialisationJob.run(client.session(), sourceTables, targetTable, sql, runId,
                    Clock.systemUTC());
            return "materialised " + targetTable;
        });
    }

    Outcome checkQuality(String targetTable, String checksJson, String runId) {
        refresh(targetTable);
        return run(() -> {
            List<?> results = QualityCheckJob.run(client.session(), targetTable, checksJson, runId,
                    null, Clock.systemUTC());
            return "recorded " + results.size() + " results for " + runId;
        });
    }

    Outcome maintain(String targetTable, String[] operations, String olderThan, String retainLast) {
        refresh(targetTable);
        return run(() -> String.join("; ", MaintenanceJob.run(client.session(), targetTable,
                operations, olderThan, retainLast)));
    }

    Outcome gate(String runId, String targetTable) {
        refresh(targetTable);
        var decision = PromotionGate.evaluate(client.session(), runId, targetTable);
        return new Outcome(decision.blocked() ? JobExit.PROMOTION_BLOCKED : JobExit.SUCCESS,
                decision.describe());
    }

    /**
     * Maps an outcome to the status code the job documents.
     *
     * <p>Schema drift has a code of its own because an orchestrator retries a
     * failed job and escalates a refused one, and drift will still be there on
     * the retry.
     */
    private Outcome run(ThrowingSupplier work) {
        try {
            String detail = work.get();
            SparkVerificationLogging.jobCompleted(client.config().principalId(),
                    JobExit.SUCCESS, detail);
            return new Outcome(JobExit.SUCCESS, detail);
        } catch (SchemaDrift.Refusal refusal) {
            SparkVerificationLogging.jobCompleted(client.config().principalId(),
                    JobExit.SCHEMA_DRIFT, refusal.getMessage());
            return new Outcome(JobExit.SCHEMA_DRIFT, refusal.getMessage());
        } catch (RuntimeException failure) {
            String detail = failure.getMessage() == null
                    ? failure.getClass().getName() : failure.getMessage();
            SparkVerificationLogging.jobCompleted(client.config().principalId(),
                    JobExit.FAILURE, detail);
            return new Outcome(JobExit.FAILURE, detail);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get();
    }
}
