// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Proves the platform batch pipeline end to end against the live cluster
 * (P1-3.3-V1): a raw landing file becomes a bronze table, quality rules run
 * and are recorded, a failing blocking rule stops promotion, the transform
 * deduplicates into silver, materialisation produces gold, and maintenance
 * runs on a real table.
 *
 * <p>The tests are ordered because they are one pipeline, not five
 * independent facts: each stage consumes what the previous one wrote. The
 * fixture carries a deliberate duplicate so the quality gate has something
 * real to block on — a gate only tested on clean data proves nothing.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("spark-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
final class SparkPipelineVerificationTest {

    private static final Duration JOB = Duration.ofMinutes(10);
    private static final String JOBS = "dev.stratus.jobs.spark.";

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String LANDING_PREFIX = "spark-pipeline/" + SUFFIX;
    private static final String SOURCE_FILE =
            "s3a://stratus-landing/" + LANDING_PREFIX + "/customers.csv";
    private static final String BRONZE = "stratus.bronze.pipeline_customers_" + SUFFIX;
    private static final String SILVER = "stratus.silver.pipeline_customers_" + SUFFIX;
    private static final String GOLD = "stratus.gold.pipeline_customers_by_country_" + SUFFIX;
    private static final String QUALITY_RUN = "quality-" + SUFFIX;
    private static final String BATCH = "pipeline-" + SUFFIX;

    /**
     * Four rows with one duplicated business key and one blank email, so the
     * uniqueness rule fails and the completeness rule has a null to count
     * once ingestion has turned the blank into one.
     */
    private static final String FIXTURE_CSV = String.join("\n",
            "customer_id,email,country,updated_at",
            "1,alice@example.com,GB,2026-08-09T10:00:00Z",
            "2,bob@example.com,GB,2026-08-09T10:00:00Z",
            "2,bob.updated@example.com,US,2026-08-09T10:05:00Z",
            "3, ,US,2026-08-09T10:00:00Z");

    @BeforeAll
    static void placeTheLandingFile() {
        LiveSparkCluster.require();
        var written = LiveSparkCluster.writeLandingContent(
                LANDING_PREFIX + "/customers.csv", FIXTURE_CSV, JOB);
        assertTrue(written.succeeded(), "the landing fixture must upload: " + written.describe());
    }

    @BeforeEach
    void requireLiveCluster() {
        LiveSparkCluster.require();
    }

    @AfterAll
    static void removeProbeTablesAndObjects() {
        // Asserted, not assumed. A cleanup whose result is discarded fails
        // silently and leaves probe tables in a governed zone while the suite
        // still reports green — which is exactly how the S3A cleanup went
        // unnoticed until a bucket listing showed the residue.
        for (String table : new String[] {GOLD, SILVER, BRONZE}) {
            var dropped = LiveSparkCluster.sparkSql("DROP TABLE IF EXISTS " + table + " PURGE", JOB);
            assertTrue(dropped.succeeded(), "probe table " + table + " must be dropped: "
                    + dropped.describe());
        }
        String prefix = "stratus-landing/" + LANDING_PREFIX;
        LiveSparkCluster.removeObjectPrefix(prefix, JOB);
        String remaining = LiveSparkCluster.listObjectPrefix(prefix, JOB);
        SparkVerificationLogging.objectPrefixListed(prefix, remaining);
        assertEquals("", remaining, "the landing fixture must be gone after cleanup");
    }

    @Test
    @Order(1)
    void sparkConnectsToCluster() {
        var result = LiveSparkCluster.sparkSql("SELECT 1 AS connected", JOB);

        assertTrue(result.succeeded(), "a statement must run on the cluster: " + result.describe());
        assertTrue(result.output().contains("Application Id: app-"),
                "the statement must run as a cluster application, not locally: " + result.output());
    }

    @Test
    @Order(2)
    void sparkCanResolvePolarisNamespaces() {
        var result = LiveSparkCluster.sparkSql("SHOW NAMESPACES IN stratus", JOB);

        assertTrue(result.succeeded(), "the catalog must resolve: " + result.describe());
        for (String zone : new String[] {"bronze", "silver", "gold", "platform"}) {
            assertTrue(result.output().contains(zone), zone + " must resolve: " + result.output());
        }
        SparkVerificationLogging.namespacesResolved(List.of("bronze", "silver", "gold", "platform"));
    }

    @Test
    @Order(3)
    void ingestionJobWritesBronzeTable() {
        var result = LiveSparkCluster.submitJob(JOBS + "IngestionJob", JOB,
                "--sourceFile", SOURCE_FILE,
                "--targetTable", BRONZE,
                "--sourceSystem", "crm",
                "--batchId", BATCH,
                "--runId", "ingest-" + SUFFIX);

        assertTrue(result.succeeded(), "ingestion must succeed: " + result.describe());
        assertTrue(result.output().contains("STRATUS_LINEAGE"),
                "ingestion must emit a lineage payload: " + result.output());
        assertTrue(result.output().contains("\"type\": \"INGESTION\""),
                "the lineage payload must declare its type: " + result.output());

        assertEquals("4", LiveSparkCluster.scalar("count(*)", BRONZE, JOB),
                "all four source rows must land in bronze");

        // Normalisation is part of the contract: the blank email must have
        // become a null, or the completeness rule below measures nothing.
        assertEquals("1", LiveSparkCluster.scalar("count(*)", BRONZE + " WHERE email IS NULL", JOB),
                "the blank email must be normalised to null");

        SparkVerificationLogging.batchIngested(BRONZE, BATCH,
                LiveSparkCluster.scalar("count(*)", BRONZE, JOB),
                LiveSparkCluster.scalar("count(*)", BRONZE + ".partitions", JOB),
                SOURCE_FILE, "inferred");
    }

    @Test
    @Order(4)
    void qualityJobRunsOnBronzeTable() {
        String checks = "["
                + "{\"type\":\"row_count_min\",\"name\":\"has_rows\",\"severity\":\"blocking\",\"minRows\":1},"
                + "{\"type\":\"schema_conformance\",\"name\":\"expected_columns\",\"severity\":\"blocking\","
                + "\"columns\":[\"customer_id\",\"email\",\"country\"]},"
                + "{\"type\":\"completeness\",\"name\":\"email_mostly_present\",\"severity\":\"warning\","
                + "\"column\":\"email\",\"maxNullRate\":0.1},"
                + "{\"type\":\"completeness\",\"name\":\"email_mandatory\",\"severity\":\"blocking\","
                + "\"column\":\"email\",\"maxNullRate\":0.0},"
                + "{\"type\":\"uniqueness\",\"name\":\"customer_id_unique\",\"severity\":\"blocking\","
                + "\"columns\":[\"customer_id\"]}]";

        // Base64 rather than raw JSON: submitting through the container
        // runtime on Windows strips the double quotes, and the job then fails
        // on a document that was correct when this test wrote it.
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(checks.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var result = LiveSparkCluster.submitJob(JOBS + "QualityCheckJob", JOB,
                "--targetTable", BRONZE, "--checksBase64", encoded, "--runId", QUALITY_RUN);

        assertTrue(result.succeeded(),
                "the quality job must complete even when rules fail: " + result.describe());
        assertTrue(result.output().contains("QUALITY COMPLETE"),
                "the job must log its summary: " + result.output());

        assertEquals("5", LiveSparkCluster.scalar("count(*)",
                        "stratus.platform.quality_check_results WHERE run_id = '" + QUALITY_RUN + "'", JOB),
                "every rule must be recorded, passing or not");

        SparkVerificationLogging.qualityRunRecorded(QUALITY_RUN, BRONZE, "5",
                LiveSparkCluster.scalar("count(*)",
                        "stratus.platform.quality_check_results WHERE run_id = '" + QUALITY_RUN
                                + "' AND status = 'FAILED'", JOB));
    }

    @Test
    @Order(5)
    void uniquenessCheckDetectsDuplicates() {
        var result = LiveSparkCluster.sparkSql(
                "SELECT status, failure_detail FROM stratus.platform.quality_check_results "
                        + "WHERE run_id = '" + QUALITY_RUN + "' AND check_name = 'customer_id_unique'", JOB);

        assertTrue(result.succeeded(), "the result must be queryable: " + result.describe());
        assertTrue(result.output().contains("FAILED"),
                "the duplicated business key must fail a blocking uniqueness rule: " + result.output());
        assertTrue(result.output().contains("duplicate"),
                "the record must say what failed: " + result.output());

        // Nulls in a mandatory column must fail with a detail that says how
        // far off it was, which is the Phase 1 plan's own quality-fail row.
        var mandatory = LiveSparkCluster.sparkSql(
                "SELECT status, failure_detail FROM stratus.platform.quality_check_results "
                        + "WHERE run_id = '" + QUALITY_RUN + "' AND check_name = 'email_mandatory'", JOB);
        assertTrue(mandatory.output().contains("FAILED"),
                "a mandatory column with nulls must fail a blocking rule: " + mandatory.output());
        assertTrue(mandatory.output().contains("null rate"),
                "the record must quantify the failure: " + mandatory.output());

        // The warning rule must not be recorded as a failure: the difference
        // is exactly what the promotion gate acts on.
        var warning = LiveSparkCluster.sparkSql(
                "SELECT status FROM stratus.platform.quality_check_results WHERE run_id = '"
                        + QUALITY_RUN + "' AND check_name = 'email_mostly_present'", JOB);
        assertTrue(warning.output().contains("WARNING"),
                "a failing non-blocking rule must be a warning: " + warning.output());

        SparkVerificationLogging.qualityRuleOutcome(QUALITY_RUN, "customer_id_unique", "FAILED",
                result.output());
        SparkVerificationLogging.qualityRuleOutcome(QUALITY_RUN, "email_mandatory", "FAILED",
                mandatory.output());
        SparkVerificationLogging.qualityRuleOutcome(QUALITY_RUN, "email_mostly_present", "WARNING",
                warning.output());
    }

    @Test
    @Order(6)
    void promotionGateBlocksOnFailedUniquenesCheck() {
        var result = LiveSparkCluster.submitJob(JOBS + "PromotionGate", JOB,
                "--runId", QUALITY_RUN, "--targetTable", BRONZE);

        assertFalse(result.succeeded(),
                "a failed blocking rule must stop promotion with a non-zero exit: " + result.describe());
        assertTrue(result.output().contains("PROMOTION BLOCK"),
                "the gate must record its verdict: " + result.output());
        assertTrue(result.output().contains("customer_id_unique"),
                "the verdict must name the failing rule: " + result.output());

        SparkVerificationLogging.promotionDecided(QUALITY_RUN, BRONZE, "BLOCK", result.exitCode());
    }

    @Test
    @Order(7)
    void transformJobWritesSilverTableAfterDeduplication() {
        var result = LiveSparkCluster.submitJob(JOBS + "TransformJob", JOB,
                "--sourceTable", BRONZE,
                "--targetTable", SILVER,
                "--businessKey", "customer_id",
                "--sequenceColumn", "updated_at",
                "--runId", "transform-" + SUFFIX);

        assertTrue(result.succeeded(), "the transform must succeed: " + result.describe());
        assertTrue(result.output().contains("\"type\": \"TRANSFORM\""),
                "every job must emit its lineage payload, not only ingestion: " + result.output());

        assertEquals("3", LiveSparkCluster.scalar("count(*)", SILVER, JOB),
                "deduplication must collapse the repeated business key");

        // Ordering decides which duplicate survives, and a pipeline that
        // cannot say which one is not reproducible.
        var kept = LiveSparkCluster.sparkSql(
                "SELECT email FROM " + SILVER + " WHERE customer_id = 2", JOB);
        assertTrue(kept.output().contains("bob.updated@example.com"),
                "the most recent row must be the one kept: " + kept.output());

        SparkVerificationLogging.silverUpserted(SILVER, BATCH,
                LiveSparkCluster.scalar("count(*)", SILVER, JOB), "customer_id=2",
                LiveSparkCluster.scalar("email", SILVER + " WHERE customer_id = 2", JOB));
    }

    @Test
    @Order(8)
    void materialisationJobWritesGoldTable() {
        var result = LiveSparkCluster.submitJob(JOBS + "MaterialisationJob", JOB,
                "--sourceTables", SILVER,
                "--targetTable", GOLD,
                "--sql", "SELECT country, count(*) AS customers FROM " + SILVER + " GROUP BY country",
                "--runId", "gold-" + SUFFIX);

        assertTrue(result.succeeded(), "materialisation must succeed: " + result.describe());
        assertTrue(result.output().contains("\"type\": \"MATERIALISATION\""),
                "every job must emit its lineage payload: " + result.output());

        var rows = LiveSparkCluster.sparkSql(
                "SELECT country, customers FROM " + GOLD + " ORDER BY country", JOB);
        assertTrue(rows.output().contains("GB") && rows.output().contains("US"),
                "both country groups must be present: " + rows.output());

        var files = LiveSparkCluster.sparkSql("SELECT file_path FROM " + GOLD + ".files", JOB);
        assertTrue(files.output().contains("s3://stratus-gold/gold/"),
                "gold data must land in the governed gold location: " + files.output());
    }

    @Test
    @Order(9)
    void maintenanceJobRunsOnBronzeTable() {
        var result = LiveSparkCluster.submitJob(JOBS + "MaintenanceJob", JOB,
                "--targetTable", BRONZE,
                "--operations", "expire_snapshots,rewrite_data_files");

        assertTrue(result.succeeded(), "maintenance must succeed: " + result.describe());
        assertTrue(result.output().contains("MAINTENANCE expire_snapshots"),
                "snapshot expiry must report its metrics: " + result.output());
        assertTrue(result.output().contains("MAINTENANCE rewrite_data_files"),
                "file rewrite must report its metrics: " + result.output());

        assertEquals("4", LiveSparkCluster.scalar("count(*)", BRONZE, JOB),
                "maintenance must not change what the table contains");

        SparkVerificationLogging.maintenanceOutcome(BRONZE, "expire_snapshots,rewrite_data_files",
                "before maintenance", LiveSparkCluster.scalar("count(*)", BRONZE + ".snapshots", JOB),
                LiveSparkCluster.scalar("count(*)", BRONZE, JOB));
    }

    @Test
    @Order(10)
    void orphanFileDeletionIsRefusedWithoutAnExplicitRetention() {
        // The destructive operation is the one that must not inherit a
        // default: without a retention age it can remove files a concurrent
        // write has staged but not yet committed.
        var result = LiveSparkCluster.submitJob(JOBS + "MaintenanceJob", JOB,
                "--targetTable", BRONZE,
                "--operations", "delete_orphan_files");

        assertFalse(result.succeeded(),
                "orphan deletion without --olderThan must be refused: " + result.describe());
        assertTrue(result.output().contains("requires an explicit --olderThan"),
                "the refusal must say what is missing: " + result.output());

        SparkVerificationLogging.negativeConfirmed("orphan deletion without a retention",
                result.exitCode(), "the destructive operation must not inherit a default");
    }

    @Test
    @Order(11)
    void promotionGatePromotesWhenEveryBlockingCheckPasses() {
        // The blocking case above proves the gate can refuse. Without this it
        // is indistinguishable from a gate that always refuses, which would
        // stop the pipeline everywhere and look like caution.
        String cleanRun = "quality-clean-" + SUFFIX;
        String checks = "["
                + "{\"type\":\"row_count_min\",\"name\":\"has_rows\",\"severity\":\"blocking\",\"minRows\":1},"
                + "{\"type\":\"uniqueness\",\"name\":\"customer_id_unique\",\"severity\":\"blocking\","
                + "\"columns\":[\"customer_id\"]}]";
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(checks.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Silver is the deduplicated table, so the same rules that failed on
        // bronze pass here — the data changed, not the rules.
        var quality = LiveSparkCluster.submitJob(JOBS + "QualityCheckJob", JOB,
                "--targetTable", SILVER, "--checksBase64", encoded, "--runId", cleanRun);
        assertTrue(quality.succeeded(), "the clean quality run must succeed: " + quality.describe());

        var passed = LiveSparkCluster.sparkSql(
                "SELECT status FROM stratus.platform.quality_check_results WHERE run_id = '"
                        + cleanRun + "' AND status <> 'PASSED'", JOB);
        assertFalse(passed.output().contains("FAILED"),
                "no rule may fail on the deduplicated table: " + passed.output());

        var gate = LiveSparkCluster.submitJob(JOBS + "PromotionGate", JOB,
                "--runId", cleanRun, "--targetTable", SILVER);

        assertTrue(gate.succeeded(),
                "a run whose blocking rules all pass must be promoted: " + gate.describe());
        assertTrue(gate.output().contains("PROMOTION PROMOTE"),
                "the gate must record the promote verdict: " + gate.output());

        SparkVerificationLogging.qualityRunRecorded(cleanRun, SILVER, "2", "0");
        SparkVerificationLogging.promotionDecided(cleanRun, SILVER, "PROMOTE", gate.exitCode());
        SparkVerificationLogging.scenarioPassed("gate can promote",
                "the same rules that blocked bronze passed on the deduplicated table");
    }

    @Test
    @Order(12)
    void qualityResultsTableContainsAllRunRecords() {
        var result = LiveSparkCluster.sparkSql(
                "SELECT check_name, severity, status FROM stratus.platform.quality_check_results "
                        + "WHERE run_id = '" + QUALITY_RUN + "' ORDER BY check_name", JOB);

        assertTrue(result.succeeded(), "the results table must be queryable: " + result.describe());
        for (String checkName : new String[] {
                "customer_id_unique", "email_mostly_present", "expected_columns", "has_rows"}) {
            assertTrue(result.output().contains(checkName),
                    checkName + " must be in the run's history: " + result.output());
        }

        // The zone partition is what makes this table queryable per zone; a
        // record written without it would still read back here.
        var zone = LiveSparkCluster.sparkSql(
                "SELECT DISTINCT zone FROM stratus.platform.quality_check_results WHERE run_id = '"
                        + QUALITY_RUN + "'", JOB);
        assertEquals(true, zone.output().contains("bronze"),
                "results must record the zone they were measured in: " + zone.output());
    }
}
