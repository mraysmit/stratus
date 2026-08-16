// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stratus.jobs.spark.IngestionJob;
import dev.stratus.jobs.spark.JobExit;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.spark.sql.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

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
 * <p>Two different things happen here and they use different mechanisms.
 * <b>Doing</b> is a platform job: submitted with {@code spark-submit}, exactly
 * as production runs it. <b>Observing</b> is a query through
 * {@link StratusSparkClient}, a real {@code SparkSession} built the way the
 * jobs build theirs, returning typed rows.
 *
 * <p>Observation used to run {@code spark-sql} in the master container and
 * assert on substrings of its console output. That was wrong twice over: the
 * platform has no Hive anywhere, yet the Hive CLI stood up a Derby metastore on
 * every call, and an assertion against printed text passes or fails on a
 * display format rather than on a value. Reading a typed {@code Row} removes
 * both (code style rules 10.1).
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 2.0.0
 */
@Tag("spark-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
final class SparkPipelineVerificationTest {

    @RegisterExtension
    private static final SparkSuiteContext SPARK = new SparkSuiteContext();

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
    private static final String RESULTS =
            "stratus.platform.quality_check_results_pipeline_" + SUFFIX;

    private static StratusSparkClient client;
    private static PlatformJobs jobs;

    /**
     * The jobs' own log records, captured so the lineage payload can be
     * asserted on.
     *
     * <p>{@code LineageEvent.emit} is called from inside each job's
     * {@code run}, not its {@code main}, and writes through SLF4J. Running the
     * job in this driver therefore still
     * produces the payload — it arrives here instead of on a subprocess's
     * stdout, which is a stronger place to read it from.
     */
    private static TestLogCapture jobLogCapture;

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
    static void placeTheLandingFileAndConnect() {
        LiveSparkCluster.require();
        // The fixture is uploaded with the storage owner's own client, so the
        // pipeline starts from a real object written the way a source system
        // would deliver one — not from a table Spark made earlier.
        var written = LiveSparkCluster.writeLandingContent(
                LANDING_PREFIX + "/customers.csv", FIXTURE_CSV, JOB);
        assertTrue(written.succeeded(), "the landing fixture must upload: " + written.describe());

        client = SPARK.client(
                SparkClientConfig.serviceIdentity("stratus-pipeline-verification", 17083, 17084)
                        .withApplicationCores(2));
        QualityResultFixture.create(client, RESULTS);
        jobs = new PlatformJobs(client, RESULTS);

        jobLogCapture = new TestLogCapture("dev.stratus.jobs.spark");
    }

    /** Every job log record emitted so far, as one searchable block. */
    private static String jobLog() {
        return jobLogCapture == null ? "" : jobLogCapture.events().stream()
                .map(event -> event.getMessage().getFormattedMessage())
                .reduce("", (left, right) -> left + '\n' + right);
    }

    @AfterAll
    static void stopCapturingJobLogs() {
        if (jobLogCapture != null) {
            jobLogCapture.close();
        }
    }

    @BeforeEach
    void requireLiveCluster() {
        LiveSparkCluster.require();
    }

    @AfterAll
    static void removeProbeTablesAndObjects() {
        if (!LiveSparkCluster.enabled()) {
            // Nothing ran, so there is nothing to clean. JUnit runs this even
            // when @BeforeAll aborted, and dropping a table on a cluster that
            // was never up reports a cleanup failure for a suite that skipped.
            return;
        }
        try {
            if (client != null) {
                // Asserted by execution: session.sql throws if the drop fails,
                // so a cleanup that silently left probe tables in a governed
                // zone cannot report green.
                for (String table : new String[] {RESULTS, GOLD, SILVER, BRONZE}) {
                    client.sql("DROP TABLE IF EXISTS " + table + " PURGE");
                }
            }
        } finally {
            if (client != null) {
                client.close();
            }
        }
        String prefix = "stratus-landing/" + LANDING_PREFIX;
        LiveSparkCluster.removeObjectPrefix(prefix, JOB);
        String remaining = LiveSparkCluster.listObjectPrefix(prefix, JOB);
        SparkVerificationLogging.objectPrefixListed(prefix, remaining);
        assertEquals("", remaining, "the landing fixture must be gone after cleanup");
    }

    @Test
    @Order(1)
    void theClientRunsOnTheClusterRatherThanLocally() {
        // A session that fell back to running in this JVM answers every other
        // assertion in this class just as happily, so the application id is
        // checked before anything is read from it.
        assertTrue(client.applicationId().startsWith("app-"),
                "the client must run as a cluster application, got: " + client.applicationId());
        assertEquals("1", client.scalar("SELECT 1 AS connected"),
                "a statement must execute on the cluster");
    }

    @Test
    @Order(2)
    void sparkCanResolvePolarisNamespaces() {
        List<String> zones = client.sql("SHOW NAMESPACES IN stratus").stream()
                .map(row -> row.get(0).toString())
                .toList();

        for (String zone : new String[] {"bronze", "silver", "gold", "platform"}) {
            assertTrue(zones.contains(zone), zone + " must resolve, got: " + zones);
        }
        SparkVerificationLogging.namespacesResolved(zones);
    }

    @Test
    @Order(3)
    void ingestionJobWritesBronzeTable() {
        // This is the suite's one deliberate fresh process. The incremental
        // scenario proves ingestion repeatedly through its long-lived client;
        // this call proves the packaged jar, arguments, process exit and real
        // spark-submit boundary without charging that startup to every batch.
        var result = LiveSparkCluster.submitJob(JOBS + "IngestionJob", JOB,
                "--sourceFile", SOURCE_FILE,
                "--targetTable", BRONZE,
                "--sourceSystem", "crm",
                "--batchId", BATCH,
                "--onExistingBatch", IngestionJob.ON_EXISTING_FAIL,
                "--runId", "ingest-" + SUFFIX);

        assertTrue(result.succeeded(), "ingestion must succeed: " + result.describe());
        assertTrue(result.output().contains("STRATUS_LINEAGE"),
                "ingestion must emit a lineage payload: " + result.describe());
        assertTrue(result.output().contains("\"type\": \"INGESTION\""),
                "the lineage payload must declare its type: " + result.describe());

        Row bronze = onlyRow("SELECT count(*) AS rows, "
                + "count_if(email IS NULL) AS null_emails FROM " + BRONZE);
        String bronzeRows = bronze.get(0).toString();
        assertEquals("4", bronzeRows,
                "all four source rows must land in bronze");

        // Normalisation is part of the contract: the blank email must have
        // become a null, or the completeness rule below measures nothing.
        assertEquals("1", bronze.get(1).toString(),
                "the blank email must be normalised to null");

        SparkVerificationLogging.batchIngested(BRONZE, BATCH, bronzeRows,
                client.scalar("SELECT count(*) FROM " + BRONZE + ".partitions"),
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

        // The rules travel as a Java string. Base64 was only ever needed
        // because the container runtime on Windows stripped the double quotes
        // out of a JSON command-line argument; nothing is on a command line
        // now.
        var outcome = jobs.checkQuality(BRONZE, checks, QUALITY_RUN);

        assertTrue(outcome.succeeded(),
                "the quality job must complete even when rules fail: " + outcome.describe());
        assertTrue(outcome.detail().contains("5"),
                "the job must report how many results it recorded: " + outcome.describe());

        Row recorded = onlyRow("SELECT count(*) AS results, "
                + "count_if(status = 'FAILED') AS failures FROM " + RESULTS
                + " WHERE run_id = '" + QUALITY_RUN + "'");
        assertEquals("5", recorded.get(0).toString(),
                "every rule must be recorded, passing or not");

        SparkVerificationLogging.qualityRunRecorded(QUALITY_RUN, BRONZE,
                recorded.get(0).toString(), recorded.get(1).toString());
    }

    @Test
    @Order(5)
    void uniquenessCheckDetectsDuplicates() {
        Map<String, Row> observed = new LinkedHashMap<>();
        for (Row row : client.sql("SELECT check_name, status, failure_detail FROM " + RESULTS
                + " WHERE run_id = '" + QUALITY_RUN + "' AND check_name IN "
                + "('customer_id_unique', 'email_mandatory', 'email_mostly_present')")) {
            observed.put(row.getString(0), row);
        }
        assertEquals(3, observed.size(), "all three quality observations must be present");
        Row unique = observed.get("customer_id_unique");

        assertEquals("FAILED", unique.getString(1),
                "the duplicated business key must fail a blocking uniqueness rule");
        assertTrue(unique.getString(2).contains("duplicate"),
                "the record must say what failed: " + unique.getString(2));

        // Nulls in a mandatory column must fail with a detail that says how
        // far off it was, which is the Phase 1 plan's own quality-fail row.
        Row mandatory = observed.get("email_mandatory");
        assertEquals("FAILED", mandatory.getString(1),
                "a mandatory column with nulls must fail a blocking rule");
        assertTrue(mandatory.getString(2).contains("null rate"),
                "the record must quantify the failure: " + mandatory.getString(2));

        // The warning rule must not be recorded as a failure: the difference
        // is exactly what the promotion gate acts on.
        Row warning = observed.get("email_mostly_present");
        assertEquals("WARNING", warning.getString(1),
                "a failing non-blocking rule must be a warning, not a failure");

        SparkVerificationLogging.qualityRuleOutcome(QUALITY_RUN, "customer_id_unique",
                unique.getString(1), unique.getString(2));
        SparkVerificationLogging.qualityRuleOutcome(QUALITY_RUN, "email_mandatory",
                mandatory.getString(1), mandatory.getString(2));
        SparkVerificationLogging.qualityRuleOutcome(QUALITY_RUN, "email_mostly_present",
                warning.getString(1), "a warning rule is recorded, not enforced");
    }

    @Test
    @Order(6)
    void promotionGateBlocksOnFailedUniquenesCheck() {
        var outcome = jobs.gate(QUALITY_RUN, BRONZE);

        // The documented status code, not merely "non-zero": an orchestrator
        // retries a failure and escalates a refusal, so the two must differ.
        assertEquals(JobExit.PROMOTION_BLOCKED, outcome.exitCode(),
                "a failed blocking rule must stop promotion with the documented code: "
                        + outcome.describe());
        assertTrue(outcome.detail().contains("customer_id_unique"),
                "the verdict must name the failing rule: " + outcome.detail());

        SparkVerificationLogging.promotionDecided(QUALITY_RUN, BRONZE, "BLOCK", outcome.exitCode());
    }

    @Test
    @Order(7)
    void transformJobWritesSilverTableAfterDeduplication() {
        // No quality run is passed, so the gate is not consulted — this test
        // is about deduplication, and the gate has its own tests either side.
        var outcome = jobs.transform(BRONZE, SILVER, new String[] {"customer_id"},
                "updated_at", null, "transform-" + SUFFIX, null);

        assertTrue(outcome.succeeded(), "the transform must succeed: " + outcome.describe());
        assertTrue(jobLog().contains("\"type\": \"TRANSFORM\""),
                "every job must emit its lineage payload, not only ingestion: " + jobLog());

        Row silver = onlyRow("SELECT count(*) AS rows, "
                + "max(CASE WHEN customer_id = 2 THEN email END) AS corrected_email FROM " + SILVER);
        String silverRows = silver.get(0).toString();
        assertEquals("3", silverRows,
                "deduplication must collapse the repeated business key");

        // Ordering decides which duplicate survives, and a pipeline that
        // cannot say which one is not reproducible.
        String kept = silver.getString(1);
        assertEquals("bob.updated@example.com", kept,
                "the most recent row must be the one kept");

        SparkVerificationLogging.silverUpserted(
                SILVER, BATCH, silverRows, "customer_id=2", kept);
    }

    @Test
    @Order(8)
    void materialisationJobWritesGoldTable() {
        var outcome = jobs.materialise(new String[] {SILVER}, GOLD,
                "SELECT country, count(*) AS customers FROM " + SILVER + " GROUP BY country",
                "gold-" + SUFFIX);

        assertTrue(outcome.succeeded(), "materialisation must succeed: " + outcome.describe());
        assertTrue(jobLog().contains("\"type\": \"MATERIALISATION\""),
                "every job must emit its lineage payload: " + jobLog());

        Map<String, String> byCountry = new LinkedHashMap<>();
        for (Row row : client.sql("SELECT country, customers FROM " + GOLD + " ORDER BY country")) {
            byCountry.put(row.get(0).toString(), row.get(1).toString());
        }
        // The aggregate is asserted, not merely present: silver holds one GB
        // customer and two US customers after deduplication.
        assertEquals(Map.of("GB", "1", "US", "2"), byCountry,
                "gold must hold the per-country counts of the deduplicated table");

        List<String> paths = client.sql("SELECT file_path FROM " + GOLD + ".files").stream()
                .map(row -> row.getString(0))
                .toList();
        assertFalse(paths.isEmpty(), "gold must have data files");
        for (String path : paths) {
            assertTrue(path.startsWith("s3://stratus-gold/gold/"),
                    "gold data must land in the governed gold location, got: " + path);
        }
    }

    @Test
    @Order(9)
    void maintenanceJobRunsOnBronzeTable() {
        String snapshotsBefore = client.scalar("SELECT count(*) FROM " + BRONZE + ".snapshots");

        var outcome = jobs.maintain(BRONZE,
                new String[] {"expire_snapshots", "rewrite_data_files"}, null, null);

        assertTrue(outcome.succeeded(), "maintenance must succeed: " + outcome.describe());
        assertTrue(outcome.detail().contains("expire_snapshots"),
                "snapshot expiry must report its metrics: " + outcome.detail());
        assertTrue(outcome.detail().contains("rewrite_data_files"),
                "file rewrite must report its metrics: " + outcome.detail());

        String rowsAfter = client.scalar("SELECT count(*) FROM " + BRONZE);
        assertEquals("4", rowsAfter,
                "maintenance must not change what the table contains");

        String snapshotsAfter = client.scalar("SELECT count(*) FROM " + BRONZE + ".snapshots");
        SparkVerificationLogging.maintenanceOutcome(BRONZE, "expire_snapshots,rewrite_data_files",
                snapshotsBefore, snapshotsAfter, rowsAfter);
    }

    @Test
    @Order(10)
    void orphanFileDeletionIsRefusedWithoutAnExplicitRetention() {
        // The destructive operation is the one that must not inherit a
        // default: without a retention age it can remove files a concurrent
        // write has staged but not yet committed.
        var outcome = jobs.maintain(BRONZE, new String[] {"delete_orphan_files"}, null, null);

        assertFalse(outcome.succeeded(),
                "orphan deletion without a retention must be refused: " + outcome.describe());
        assertTrue(outcome.detail().contains("requires an explicit --olderThan"),
                "the refusal must say what is missing: " + outcome.detail());

        SparkVerificationLogging.negativeConfirmed("orphan deletion without a retention",
                outcome.exitCode(), "the destructive operation must not inherit a default");
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
        // Silver is the deduplicated table, so the same rules that failed on
        // bronze pass here — the data changed, not the rules.
        var quality = jobs.checkQuality(SILVER, checks, cleanRun);
        assertTrue(quality.succeeded(), "the clean quality run must succeed: " + quality.describe());

        Row clean = onlyRow("SELECT count(*) AS results, "
                + "count_if(status <> 'PASSED') AS failures FROM " + RESULTS
                + " WHERE run_id = '" + cleanRun + "'");
        assertEquals("0", clean.get(1).toString(),
                "no rule may fail on the deduplicated table");

        var gate = jobs.gate(cleanRun, SILVER);

        assertEquals(JobExit.SUCCESS, gate.exitCode(),
                "a run whose blocking rules all pass must be promoted: " + gate.describe());

        SparkVerificationLogging.qualityRunRecorded(
                cleanRun, SILVER, clean.get(0).toString(), clean.get(1).toString());
        SparkVerificationLogging.promotionDecided(cleanRun, SILVER, "PROMOTE", gate.exitCode());
        SparkVerificationLogging.scenarioPassed("gate can promote",
                "the same rules that blocked bronze passed on the deduplicated table");
    }

    @Test
    @Order(12)
    void qualityResultsTableContainsAllRunRecords() {
        List<Row> records = client.sql("SELECT check_name, zone FROM " + RESULTS
                + " WHERE run_id = '" + QUALITY_RUN + "' ORDER BY check_name");
        List<String> names = records.stream()
                .map(row -> row.getString(0))
                .toList();

        assertEquals(List.of("customer_id_unique", "email_mandatory", "email_mostly_present",
                        "expected_columns", "has_rows"), names,
                "every rule of the run must be in its history, once each");

        // The zone partition is what makes this table queryable per zone; a
        // record written without it would still read back here.
        assertTrue(records.stream().allMatch(row -> "bronze".equals(row.getString(1))),
                "results must record the zone they were measured in");
    }

    /**
     * The single row a check expects, failing if the query matched none or
     * several. A check that reads {@code rows.get(0)} without this passes on a
     * duplicated record and throws an unhelpful exception on an absent one.
     */
    private static Row onlyRow(String statement) {
        List<Row> rows = client.sql(statement);
        assertEquals(1, rows.size(), "expected exactly one row from: " + statement);
        return rows.get(0);
    }
}
