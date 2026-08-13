// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.spark.sql.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Proves the batch pipeline over a week of arrivals rather than a single file.
 *
 * <p>A pipeline tested on one batch is tested on the only day it cannot get
 * wrong. What breaks a data platform is the second day: a batch that should add
 * to what is there rather than replace it, a replay that carries an older
 * version of a record already corrected, a source system that adds a column or
 * changes one, a batch that fails its checks and is fixed and sent again. Each
 * of those is a scenario here, and each is one the previous suite would have
 * passed while the platform did the wrong thing.
 *
 * <p>The tests are ordered because they are one table's history. Every fixture
 * is a file under {@code src/test/resources/landing}, uploaded through the
 * storage owner's own client, so the pipeline starts from a real object written
 * the way a source system would deliver one.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("spark-integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
final class SparkIncrementalLoadVerificationTest {

    private static final Duration JOB = Duration.ofMinutes(10);
    /** The status codes the jobs document; see {@code JobExit}. */
    private static final int EXIT_PROMOTION_BLOCKED = 2;
    private static final int EXIT_SCHEMA_DRIFT = 3;

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String LANDING_PREFIX = "spark-incremental/" + SUFFIX;
    private static final String BRONZE = "stratus.bronze.incremental_customers_" + SUFFIX;
    private static final String SILVER = "stratus.silver.incremental_customers_" + SUFFIX;
    private static final String COUNTRIES = "stratus.silver.incremental_countries_" + SUFFIX;

    /**
     * The schema is declared rather than inferred. Inference reads types out of
     * whichever rows happened to arrive, so the same column is an integer in
     * one batch and a string in the next — which would mean the drift these
     * tests examine was manufactured by the CSV parser rather than sent by the
     * source system.
     */
    private static final String SCHEMA =
            "customer_id INT, email STRING, country STRING, updated_at TIMESTAMP";
    private static final String SCHEMA_WITH_SEGMENT = SCHEMA + ", segment STRING";
    private static final String SCHEMA_WITH_STRING_ID =
            "customer_id STRING, email STRING, country STRING, updated_at TIMESTAMP";

    private static final String BATCH_1 = "2026-08-01";
    private static final String BATCH_2 = "2026-08-02";
    private static final String BATCH_3_LATE = "2026-08-03-replay";
    private static final String BATCH_4_SEGMENT = "2026-08-04";
    private static final String BATCH_5_TYPECHANGE = "2026-08-05";
    private static final String BATCH_6_DEFECTIVE = "2026-08-06";
    private static final String BATCH_7_NDJSON = "2026-08-07";
    private static final String BATCH_8_TIED = "2026-08-08";

    private static final String BLOCKED_RUN = "incremental-blocked-" + SUFFIX;

    /** Recorded so the orphan probe can be removed even if its own test fails. */
    private static String bronzeDataPrefix;

    /**
     * The session every read in this class goes through.
     *
     * <p>Reads used to run {@code spark-sql} in the master container, one
     * Spark application per value. This class reads 84 values, so it paid for
     * 84 applications — and each one stood up a Hive metastore the platform
     * has no use for. One session, built the way the jobs build theirs, reads
     * them all (code style rules 10.1).
     */
    private static StratusSparkClient client;

    /**
     * The jobs, run in this client's driver.
     *
     * <p>All scenario steps use this one driver. Hadoop 3.4.3 contains the
     * current-JDK Subject compatibility fix, so ingestion no longer needs a
     * fresh container-side driver merely to read {@code s3a://}. A separate
     * pipeline smoke test retains the real packaged {@code spark-submit}
     * boundary.
     */
    private static PlatformJobs jobs;

    /**
     * The jobs' own log records, so the lineage payload can be asserted on.
     *
     * <p>{@code LineageEvent.emit} is called from inside each job's
     * {@code run}, so a job running in this driver still produces it — through
     * {@code java.util.logging} rather than a subprocess's stdout.
     */
    private static final List<String> JOB_LOG = new ArrayList<>();

    /**
     * Held deliberately: {@code LogManager} keeps only a weak reference to a
     * logger, so one nothing else refers to is collected and takes its handlers
     * with it, leaving the capture silently empty.
     */
    private static Logger jobLogger;

    @BeforeAll
    static void placeTheReferenceData() {
        LiveSparkCluster.require();
        client = StratusSparkClient.connect(
                SparkClientConfig.serviceIdentity("stratus-incremental-verification", 17085, 17086));
        jobs = new PlatformJobs(client);

        jobLogger = Logger.getLogger("dev.stratus.jobs.spark");
        jobLogger.setLevel(Level.ALL);
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                synchronized (JOB_LOG) {
                    JOB_LOG.add(record.getMessage());
                }
            }

            @Override public void flush() {
            }

            @Override public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        jobLogger.addHandler(capture);

        client.sql("CREATE TABLE " + COUNTRIES + " (country STRING) USING iceberg");
        client.sql("INSERT INTO " + COUNTRIES + " VALUES ('GB'), ('US'), ('DE'), ('FR')");
        assertEquals("4", client.scalar("SELECT count(*) FROM " + COUNTRIES),
                "the country reference table must hold its four codes");
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
        // Asserted by execution: session.sql throws if a drop fails, so a
        // cleanup that silently left probe tables in a governed zone cannot
        // report green.
        try {
            if (client != null) {
                for (String table : new String[] {SILVER, COUNTRIES, BRONZE}) {
                    client.sql("DROP TABLE IF EXISTS " + table + " PURGE");
                }
            }
        } finally {
            if (client != null) {
                client.close();
            }
        }
        String landing = "stratus-landing/" + LANDING_PREFIX;
        LiveSparkCluster.removeObjectPrefix(landing, JOB);
        String remaining = LiveSparkCluster.listObjectPrefix(landing, JOB);
        SparkVerificationLogging.objectPrefixListed(landing, remaining);
        assertEquals("", remaining, "the landing fixtures must be gone after cleanup");

        if (bronzeDataPrefix != null) {
            // The orphan probe is deliberately unknown to the table, so a
            // PURGE drop does not account for it.
            LiveSparkCluster.removeObjectPrefix(bronzeDataPrefix, JOB);
            assertEquals("", LiveSparkCluster.listObjectPrefix(bronzeDataPrefix, JOB),
                    "no probe object may remain in the governed bronze bucket");
        }
    }

    @Test
    @Order(1)
    void theFirstBatchCreatesBronzePartitionedByBatchWithItsWriteProperties() {
        var result = ingest("customers-batch-1.csv", BATCH_1, SCHEMA);

        assertTrue(result.succeeded(), "the first ingestion must succeed: " + result.describe());
        assertEquals("5", scalar("count(*)", BRONZE), "every row of the first batch must land");
        assertEquals("5", scalar("count(*)", BRONZE + " WHERE stratus_batch_id = '" + BATCH_1 + "'"),
                "every row must carry the batch it arrived in");
        assertEquals("0", scalar("count(*)", BRONZE + " WHERE stratus_source_file IS NULL"),
                "every row must record the object it was read from");
        assertEquals("0", scalar("count(*)", BRONZE + " WHERE stratus_ingested_at IS NULL"),
                "every row must record when it was ingested");

        // One partition per batch is what makes a replay a metadata operation
        // that cannot reach another batch's files.
        assertEquals("1", scalar("count(*)", BRONZE + ".partitions"),
                "bronze must be partitioned by batch");

        SparkVerificationLogging.batchIngested(BRONZE, BATCH_1, scalar("count(*)", BRONZE),
                scalar("count(*)", BRONZE + ".partitions"), "customers-batch-1.csv", SCHEMA);

        Map<String, String> properties = tableProperties(BRONZE);
        assertEquals("copy-on-write", properties.get("write.merge.mode"), properties.toString());
        assertEquals("serializable", properties.get("write.delete.isolation-level"),
                properties.toString());
        assertEquals("parquet", properties.get("write.format.default"), properties.toString());
        assertEquals("true", properties.get("write.spark.accept-any-schema"), properties.toString());
        assertEquals("true", properties.get("stratus.append-only"), properties.toString());
    }

    @Test
    @Order(2)
    void reSendingTheSameBatchIsRefusedRatherThanSilentlyDoubled() {
        var result = ingest("customers-batch-1.csv", BATCH_1, SCHEMA);

        assertFalse(result.succeeded(),
                "a batch already in the table must be refused: " + result.describe());
        assertTrue(result.detail().contains("append-only"),
                "the refusal must say why: " + result.detail());
        assertTrue(result.detail().contains(BATCH_1),
                "the refusal must name the batch: " + result.detail());
        assertEquals("5", scalar("count(*)", BRONZE), "the refused batch must not have been written");

        SparkVerificationLogging.batchRefused(BRONZE, BATCH_1, "fail", result.exitCode(),
                "bronze is append-only; a replay must be asked for");
    }

    @Test
    @Order(3)
    void replayingABatchDeliberatelyRewritesThatBatchAndNothingElse() {
        var result = ingest("customers-batch-1.csv", BATCH_1, SCHEMA, "--onExistingBatch", "replace");

        assertTrue(result.succeeded(), "a deliberate replay must succeed: " + result.describe());
        assertEquals("5", scalar("count(*)", BRONZE), "a replay must converge, not accumulate");
        assertEquals("alice@example.com",
                scalar("email", BRONZE + " WHERE customer_id = 1"),
                "the replayed rows must be the same rows");

        SparkVerificationLogging.batchIngested(BRONZE, BATCH_1, scalar("count(*)", BRONZE),
                scalar("count(*)", BRONZE + ".partitions"), "customers-batch-1.csv (replayed)", SCHEMA);
        SparkVerificationLogging.scenarioPassed("deliberate replay",
                "the batch was rewritten in place and converged on the same rows");
    }

    @Test
    @Order(4)
    void aSecondBatchAccumulatesInsteadOfReplacingTheFirst() {
        // The scenario the previous suite could not have: it ingested once, so
        // a job that replaced the table on every run looked identical to one
        // that appended. This is the assertion that tells them apart.
        var result = ingest("customers-batch-2.csv", BATCH_2, SCHEMA);

        assertTrue(result.succeeded(), "the second ingestion must succeed: " + result.describe());
        assertEquals("8", scalar("count(*)", BRONZE),
                "the second batch must add to the first, not replace it");
        assertEquals("2", scalar("count(DISTINCT stratus_batch_id)", BRONZE),
                "both batches must be identifiable in the table");
        assertEquals("1", scalar("count(*)", BRONZE + " WHERE customer_id = 5"),
                "a customer only the first batch carried must still be there");
        assertEquals("2", scalar("count(*)", BRONZE + ".partitions"),
                "each batch must be its own partition");

        SparkVerificationLogging.batchIngested(BRONZE, BATCH_2, scalar("count(*)", BRONZE),
                scalar("count(*)", BRONZE + ".partitions"), "customers-batch-2.csv", SCHEMA);
        SparkVerificationLogging.scenarioPassed("bronze accumulates",
                "the second batch added to the first rather than replacing it");
    }

    @Test
    @Order(5)
    void transformingTheFirstBatchCreatesSilverWithoutTheBronzeAuditColumns() {
        var result = transform(BATCH_1);

        assertTrue(result.succeeded(), "the first transform must succeed: " + result.describe());
        assertTrue(jobLog().contains("\"type\": \"TRANSFORM\""),
                "every job must emit its lineage payload: " + jobLog());
        assertEquals("5", scalar("count(*)", SILVER), "silver must hold the first batch's customers");

        // Bronze provenance belongs to a row that arrived once. A silver row is
        // rewritten by whichever batch last corrected it, so carrying the batch
        // id forward would state something that stops being true.
        List<String> columns = client.sql("DESCRIBE TABLE " + SILVER).stream()
                .map(row -> row.getString(0))
                .toList();
        assertFalse(columns.contains("stratus_batch_id"),
                "silver must not carry the ingestion audit columns: " + columns);

        SparkVerificationLogging.silverUpserted(SILVER, BATCH_1, scalar("count(*)", SILVER),
                "customer_id=2", scalar("email", SILVER + " WHERE customer_id = 2"));

        Map<String, String> properties = tableProperties(SILVER);
        assertEquals("copy-on-write", properties.get("write.merge.mode"), properties.toString());
        assertEquals("hash", properties.get("write.distribution-mode"), properties.toString());
        assertFalse(properties.containsKey("stratus.append-only"),
                "silver is upserted, not appended: " + properties);
    }

    @Test
    @Order(6)
    void aCorrectionInALaterBatchUpdatesTheRowItCorrects() {
        var result = transform(BATCH_2);

        assertTrue(result.succeeded(), "the second transform must succeed: " + result.describe());
        assertEquals("7", scalar("count(*)", SILVER),
                "two new customers must be inserted and the corrected one updated in place");
        assertEquals("bob.corrected@example.com",
                scalar("email", SILVER + " WHERE customer_id = 2"),
                "the newer version of the record must win");
        assertEquals("1", scalar("count(*)", SILVER + " WHERE customer_id = 2"),
                "an update must not become a second row");

        SparkVerificationLogging.silverUpserted(SILVER, BATCH_2, scalar("count(*)", SILVER),
                "customer_id=2", scalar("email", SILVER + " WHERE customer_id = 2"));
        SparkVerificationLogging.scenarioPassed("correction applied",
                "the newer version of the record replaced the older one in place");
    }

    @Test
    @Order(7)
    void aReplayCarryingAnOlderVersionDoesNotOverwriteTheCorrection() {
        // The failure this guards against is silent: both rows are valid, the
        // merge succeeds, and silver quietly ends up holding the version that
        // was already superseded. Nothing downstream can tell.
        var ingested = ingest("customers-batch-3-late.csv", BATCH_3_LATE, SCHEMA);
        assertTrue(ingested.succeeded(), "the late batch must still be recorded: " + ingested.describe());
        assertEquals("10", scalar("count(*)", BRONZE),
                "bronze records what arrived, including a replay of old state");

        var result = transform(BATCH_3_LATE);

        assertTrue(result.succeeded(), "the transform must succeed: " + result.describe());
        assertEquals("7", scalar("count(*)", SILVER), "a replay of old state must insert nothing");
        assertEquals("bob.corrected@example.com",
                scalar("email", SILVER + " WHERE customer_id = 2"),
                "the older row must not overwrite the correction");
        assertEquals("frank@example.com",
                scalar("email", SILVER + " WHERE customer_id = 6"),
                "nor overwrite a row it is older than by hours rather than days");

        SparkVerificationLogging.batchIngested(BRONZE, BATCH_3_LATE, scalar("count(*)", BRONZE),
                scalar("count(*)", BRONZE + ".partitions"), "customers-batch-3-late.csv", SCHEMA);
        SparkVerificationLogging.staleReplayIgnored(SILVER, BATCH_3_LATE, "customer_id=2",
                scalar("email", SILVER + " WHERE customer_id = 2"));
        SparkVerificationLogging.staleReplayIgnored(SILVER, BATCH_3_LATE, "customer_id=6",
                scalar("email", SILVER + " WHERE customer_id = 6"));
    }

    @Test
    @Order(8)
    void aBatchThatAddsAColumnEvolvesTheTableInsteadOfReplacingIt() {
        var result = ingest("customers-batch-4-segment.csv", BATCH_4_SEGMENT, SCHEMA_WITH_SEGMENT);

        assertTrue(result.succeeded(),
                "a source system adding a field must not stop the pipeline: " + result.describe());
        assertEquals("12", scalar("count(*)", BRONZE), "the batch must be appended");
        assertEquals("10", scalar("count(*)", BRONZE + " WHERE segment IS NULL"),
                "rows that arrived before the column existed must read back null");
        assertEquals("enterprise", scalar("segment", BRONZE + " WHERE customer_id = 8"),
                "the new column must carry its values");

        SparkVerificationLogging.schemaEvolved(BRONZE, "segment",
                scalar("count(*)", BRONZE + " WHERE segment IS NULL"));
    }

    @Test
    @Order(9)
    void aBatchThatChangesAColumnTypeIsRefusedByName() {
        var result = ingest("customers-batch-5-typechange.csv", BATCH_5_TYPECHANGE,
                SCHEMA_WITH_STRING_ID);

        assertEquals(EXIT_SCHEMA_DRIFT, result.exitCode(),
                "schema drift has its own status code so an orchestrator can escalate rather than "
                        + "retry: " + result.describe());
        assertTrue(result.detail().contains("customer_id"),
                "the refusal must name the column: " + result.detail());
        assertTrue(result.detail().contains("int"),
                "and the type the table holds: " + result.detail());
        assertTrue(result.detail().contains("string"),
                "and the type the batch carries: " + result.detail());

        assertEquals("12", scalar("count(*)", BRONZE), "a refused batch must write nothing");
        assertEquals("0", scalar("count(*)", BRONZE + " WHERE stratus_batch_id = '"
                + BATCH_5_TYPECHANGE + "'"), "not even partially");

        SparkVerificationLogging.schemaDriftRefused(BRONZE, result.exitCode(),
                scalar("count(*)", BRONZE), "customer_id: table holds int, batch carries string");
    }

    @Test
    @Order(10)
    void aKeyArrivingTwiceAtTheSameInstantCollapsesToTheSameRowOnEveryRun() {
        var ingested = ingest("customers-batch-8-tied.csv", BATCH_8_TIED, SCHEMA);
        assertTrue(ingested.succeeded(), "the tied batch must ingest: " + ingested.describe());
        assertEquals("14", scalar("count(*)", BRONZE), "bronze keeps both rows as they arrived");

        var first = transform(BATCH_8_TIED);
        assertTrue(first.succeeded(), "the transform must succeed: " + first.describe());
        assertEquals("8", scalar("count(*)", SILVER), "the tied key must collapse to one row");
        String kept = scalar("email", SILVER + " WHERE customer_id = 20");

        // Ordering by the sequence alone leaves this to whichever row the
        // engine read first, so the same input could produce a different silver
        // table on the next run and every quality result would be measuring
        // something else.
        var second = transform(BATCH_8_TIED);
        assertTrue(second.succeeded(), "the repeated transform must succeed: " + second.describe());
        assertEquals("8", scalar("count(*)", SILVER), "a repeated transform must not add a row");
        assertEquals(kept, scalar("email", SILVER + " WHERE customer_id = 20"),
                "a repeated transform over the same input must keep the same row");

        SparkVerificationLogging.silverUpserted(SILVER, BATCH_8_TIED, scalar("count(*)", SILVER),
                "customer_id=20", kept);
        SparkVerificationLogging.scenarioPassed("deterministic tie-break",
                "two rows sharing a key and a sequence value resolved to the same one twice");
    }

    @Test
    @Order(11)
    void aDefectiveBatchIsBlockedInsideTheTransformAndSucceedsAfterItIsCorrected() {
        var ingested = ingest("customers-batch-6-defective.csv", BATCH_6_DEFECTIVE, SCHEMA);
        assertTrue(ingested.succeeded(), "bronze accepts what arrived: " + ingested.describe());
        assertEquals("17", scalar("count(*)", BRONZE), "the defective batch is still recorded");

        var quality = runQualityChecks(BLOCKED_RUN, defectiveDataRules());
        assertTrue(quality.succeeded(),
                "the quality job reports rather than decides: " + quality.describe());
        assertEquals("2", scalar("count(*)", results(BLOCKED_RUN) + " AND status = 'FAILED'"),
                "the null email and the unknown country must both be recorded as failures");
        SparkVerificationLogging.qualityRunRecorded(BLOCKED_RUN, BRONZE,
                scalar("count(*)", results(BLOCKED_RUN)),
                scalar("count(*)", results(BLOCKED_RUN) + " AND status = 'FAILED'"));

        // The gate consulted from inside the job, which is how Airflow will run
        // it in Increment 4 — one task, one status code.
        var blocked = transform(BATCH_6_DEFECTIVE, BLOCKED_RUN);
        assertEquals(EXIT_PROMOTION_BLOCKED, blocked.exitCode(),
                "a failed blocking rule must stop the transform: " + blocked.describe());
        assertTrue(blocked.detail().startsWith("PROMOTION BLOCK"),
                "the gate must record its verdict: " + blocked.detail());
        assertFalse(blocked.detail().contains("failing=none"),
                "a blocked verdict must name the rules that failed: " + blocked.detail());
        assertEquals("8", scalar("count(*)", SILVER), "a blocked transform must write nothing");
        SparkVerificationLogging.promotionDecided(BLOCKED_RUN, BRONZE, "BLOCK", blocked.exitCode());

        var corrected = ingest("customers-batch-6-corrected.csv", BATCH_6_DEFECTIVE, SCHEMA,
                "--onExistingBatch", "replace");
        assertTrue(corrected.succeeded(), "the corrected batch must replay: " + corrected.describe());
        assertEquals("16", scalar("count(*)", BRONZE),
                "the corrected batch must replace the defective one, not join it");

        String cleanRun = "incremental-clean-" + SUFFIX;
        var recheck = runQualityChecks(cleanRun, defectiveDataRules());
        assertTrue(recheck.succeeded(), "the re-check must run: " + recheck.describe());
        assertEquals("0", scalar("count(*)", results(cleanRun) + " AND status = 'FAILED'"),
                "the same rules must pass once the data is corrected");

        var promoted = transform(BATCH_6_DEFECTIVE, cleanRun);
        assertTrue(promoted.succeeded(), "the corrected batch must transform: " + promoted.describe());
        assertEquals("10", scalar("count(*)", SILVER), "the two corrected customers must reach silver");
        assertEquals("liam@example.com", scalar("email", SILVER + " WHERE customer_id = 10"),
                "and carry their corrected values");

        SparkVerificationLogging.qualityRunRecorded(cleanRun, BRONZE,
                scalar("count(*)", results(cleanRun)),
                scalar("count(*)", results(cleanRun) + " AND status = 'FAILED'"));
        SparkVerificationLogging.promotionDecided(cleanRun, BRONZE, "PROMOTE", promoted.exitCode());
        SparkVerificationLogging.silverUpserted(SILVER, BATCH_6_DEFECTIVE, scalar("count(*)", SILVER),
                "customer_id=10", scalar("email", SILVER + " WHERE customer_id = 10"));
        SparkVerificationLogging.scenarioPassed("failed batch replayed",
                "the same rules that blocked the batch passed once it was corrected");
    }

    @Test
    @Order(12)
    void anOverrideIsRecordedAlongsideTheVerdictItOverridesRatherThanReplacingIt() {
        var result = jobs.overrideGate(BLOCKED_RUN, BRONZE, "data-steward",
                "known upstream defect, tracked as STRATUS-1");

        assertTrue(result.succeeded(), "an override must promote: " + result.describe());
        assertTrue(result.detail().contains("PROMOTION OVERRIDDEN"),
                "an override must never be silent: " + result.detail());

        assertEquals("1", scalar("count(*)", results(BLOCKED_RUN) + " AND status = 'overridden'"),
                "the override must be recorded as its own result");
        assertEquals("2", scalar("count(*)", results(BLOCKED_RUN) + " AND status = 'FAILED'"),
                "and must not edit the verdict it overrides");

        String who = client.scalar("SELECT failure_detail FROM "
                + results(BLOCKED_RUN) + " AND status = 'overridden'");
        assertTrue(who.contains("data-steward"),
                "the record must name who overrode it: " + who);

        SparkVerificationLogging.overrideRecorded(BLOCKED_RUN, "data-steward",
                scalar("count(*)", results(BLOCKED_RUN) + " AND status = 'FAILED'"));
    }

    @Test
    @Order(13)
    void theFreshnessRuleMeasuresAgeRatherThanReportingOne() {
        // Both directions in one run, over the same table: the business
        // timestamps are days old and the ingest timestamps are minutes old, so
        // a rule that always failed and a rule that always passed would each be
        // caught here.
        String run = "incremental-freshness-" + SUFFIX;
        String checks = "["
                + "{\"type\":\"freshness\",\"name\":\"business_time_recent\",\"severity\":\"blocking\","
                + "\"column\":\"updated_at\",\"maxAgeMinutes\":60},"
                + "{\"type\":\"freshness\",\"name\":\"ingest_time_recent\",\"severity\":\"blocking\","
                + "\"column\":\"stratus_ingested_at\",\"maxAgeMinutes\":1440}]";
        var result = runQualityChecks(run, checks);
        assertTrue(result.succeeded(), "the freshness run must complete: " + result.describe());

        Row stale = onlyRow("SELECT status, failure_detail FROM "
                + results(run) + " AND check_name = 'business_time_recent'");
        assertEquals("FAILED", stale.getString(0),
                "business time is days behind and must fail an hourly SLA");
        assertTrue(stale.getString(1).contains("minutes old"),
                "the record must quantify the staleness: " + stale.getString(1));

        assertEquals("PASSED", client.scalar("SELECT status FROM "
                        + results(run) + " AND check_name = 'ingest_time_recent'"),
                "the rows were ingested minutes ago and must pass a daily SLA");

        SparkVerificationLogging.qualityRuleOutcome(run, "business_time_recent", "FAILED",
                stale.getString(1));
        SparkVerificationLogging.qualityRuleOutcome(run, "ingest_time_recent", "PASSED",
                "within the daily SLA");
    }

    @Test
    @Order(14)
    void theReferentialIntegrityRuleNamesTheReferenceItCouldNotFind() {
        Row broken = onlyRow("SELECT status, failure_detail FROM "
                + results(BLOCKED_RUN) + " AND check_name = 'country_known'");

        assertEquals("FAILED", broken.getString(0), "an unknown country code must fail");
        assertTrue(broken.getString(1).contains(COUNTRIES),
                "the record must name the reference table: " + broken.getString(1));

        // The control. Without it a rule that failed on every table would have
        // passed the assertion above.
        String run = "incremental-references-" + SUFFIX;
        var clean = runQualityChecks(run, referenceRule(), SILVER);
        assertTrue(clean.succeeded(), "the control run must complete: " + clean.describe());
        assertEquals("0", scalar("count(*)", results(run) + " AND status <> 'PASSED'"),
                "every country in silver is in the reference table, so the rule must pass");

        SparkVerificationLogging.qualityRuleOutcome(BLOCKED_RUN, "country_known", "FAILED",
                broken.getString(1));
        SparkVerificationLogging.qualityRuleOutcome(run, "country_known", "PASSED",
                "every country in " + SILVER + " is present in " + COUNTRIES);
    }

    @Test
    @Order(15)
    void maintenanceExpiresSnapshotsWithoutChangingWhatTheTableHolds() {
        String before = scalar("count(*)", BRONZE + ".snapshots");
        String rows = scalar("count(*)", BRONZE);
        assertTrue(Integer.parseInt(before) > 1,
                "sixteen batches and replays must have left snapshots to expire, found " + before);

        // Expiry alone, so nothing else can commit a snapshot while this is
        // being counted.
        var expiry = jobs.maintain(BRONZE, new String[] {"expire_snapshots"},
                aMomentFromNow(), "1");

        assertTrue(expiry.succeeded(), "snapshot expiry must succeed: " + expiry.describe());
        assertEquals("1", scalar("count(*)", BRONZE + ".snapshots"),
                "retain_last=1 must leave exactly one of the " + before + " snapshots");

        // Compaction is not asserted to reduce the file count: bronze holds one
        // file per batch partition and there is nothing for it to merge. What
        // must hold either way is that maintenance changed no data.
        var compaction = jobs.maintain(BRONZE, new String[] {"rewrite_data_files"}, null, null);

        assertTrue(compaction.succeeded(), "compaction must succeed: " + compaction.describe());
        assertEquals(rows, scalar("count(*)", BRONZE), "maintenance must not change the row count");
        assertEquals("liam@example.com", scalar("email", BRONZE + " WHERE customer_id = 10"),
                "nor any row's contents");

        SparkVerificationLogging.maintenanceOutcome(BRONZE, "expire_snapshots", before,
                scalar("count(*)", BRONZE + ".snapshots"), rows);
        SparkVerificationLogging.maintenanceOutcome(BRONZE, "rewrite_data_files",
                "dataFiles unchanged by design", scalar("count(*)", BRONZE + ".files"), rows);
    }

    @Test
    @Order(16)
    void orphanDeletionRefusesARetentionShortEnoughToRaceALiveWrite() {
        // What this cannot prove, and why. Iceberg refuses any retention inside
        // 24 hours, because a file written minutes ago may belong to a commit
        // still in flight. Every file in this harness is minutes old, and an
        // object's modification time is set by the storage server on write, so
        // there is no way to present it with an orphan old enough to delete.
        // Deleting a genuinely aged orphan is therefore out of reach here.
        //
        // What it does prove is the property that matters more: the destructive
        // operation refuses rather than half-running, and nothing is touched
        // when it does.
        String liveFile = scalar("file_path", BRONZE + ".files LIMIT 1");
        assertTrue(liveFile.startsWith("s3://stratus-bronze/"),
                "bronze data must be in the governed bucket: " + liveFile);
        String withoutScheme = liveFile.substring("s3://".length());
        bronzeDataPrefix = withoutScheme.substring(0, withoutScheme.lastIndexOf('/'));
        String liveFileName = liveFile.substring(liveFile.lastIndexOf('/') + 1);
        String orphan = bronzeDataPrefix + "/stratus-orphan-probe.parquet";

        var placed = LiveSparkCluster.writeObject(orphan, "not a real data file", JOB);
        assertTrue(placed.succeeded(), "the orphan probe must be written: " + placed.describe());
        assertTrue(LiveSparkCluster.listObjectPrefix(bronzeDataPrefix, JOB)
                        .contains("stratus-orphan-probe"),
                "the probe must be in place, or the assertions below prove nothing");

        var refused = jobs.maintain(BRONZE, new String[] {"delete_orphan_files"},
                aMomentFromNow(), null);

        assertFalse(refused.succeeded(),
                "a retention inside the concurrent-write window must be refused: "
                        + refused.describe());
        assertTrue(refused.detail().contains("less than 24 hours"),
                "the refusal must say what makes the interval unsafe: " + refused.detail());

        // The negative control: the same operation with a retention outside that
        // window runs to completion. Without it, the refusal above is
        // indistinguishable from an operation that never works at all.
        var ran = jobs.maintain(BRONZE, new String[] {"delete_orphan_files"},
                aDayAndAHalfAgo(), null);

        assertTrue(ran.succeeded(), "a safe retention must run: " + ran.describe());
        assertTrue(ran.detail().contains("MAINTENANCE delete_orphan_files"),
                "and report what it did: " + ran.detail());

        String remaining = LiveSparkCluster.listObjectPrefix(bronzeDataPrefix, JOB);
        assertTrue(remaining.contains(liveFileName),
                "a file the table refers to must survive both runs: " + remaining);
        assertTrue(remaining.contains("stratus-orphan-probe"),
                "and so must a stray younger than the retention: " + remaining);
        assertEquals("16", scalar("count(*)", BRONZE), "neither run may touch live data");

        SparkVerificationLogging.negativeConfirmed("orphan retention inside the write window",
                refused.exitCode(), "Iceberg refuses any interval under 24 hours");
        SparkVerificationLogging.maintenanceOutcome(BRONZE, "delete_orphan_files",
                "probe present", "probe younger than the retention, so kept",
                scalar("count(*)", BRONZE));
        SparkVerificationLogging.objectPrefixListed(bronzeDataPrefix, remaining);
    }

    @Test
    @Order(17)
    void theSameContractHoldsForABatchDeliveredAsNdjson() {
        var result = ingest("customers-batch-7.ndjson", BATCH_7_NDJSON, SCHEMA);

        assertTrue(result.succeeded(), "an ndjson batch must ingest: " + result.describe());
        assertEquals("18", scalar("count(*)", BRONZE), "it must accumulate like any other batch");
        assertEquals("mia@example.com", scalar("email", BRONZE + " WHERE customer_id = 11"),
                "its values must survive the format");
        assertEquals("2", scalar("count(*)", BRONZE + " WHERE stratus_batch_id = '"
                + BATCH_7_NDJSON + "'"), "and carry the same audit columns");

        var transformed = transform(BATCH_7_NDJSON);
        assertTrue(transformed.succeeded(), "and reach silver: " + transformed.describe());
        assertEquals("12", scalar("count(*)", SILVER), "both customers must be inserted");

        SparkVerificationLogging.batchIngested(BRONZE, BATCH_7_NDJSON, scalar("count(*)", BRONZE),
                scalar("count(*)", BRONZE + ".partitions"), "customers-batch-7.ndjson", SCHEMA);
        SparkVerificationLogging.silverUpserted(SILVER, BATCH_7_NDJSON, scalar("count(*)", SILVER),
                "customer_id=11", scalar("email", SILVER + " WHERE customer_id = 11"));
    }

    private static PlatformJobs.Outcome ingest(String resource, String batchId,
                                               String schema, String... extra) {
        var uploaded = LiveSparkCluster.uploadLandingResource(resource,
                LANDING_PREFIX + "/" + resource, JOB);
        assertTrue(uploaded.succeeded(), "the landing fixture must upload: " + uploaded.describe());

        if (extra.length != 0
                && (extra.length != 2 || !"--onExistingBatch".equals(extra[0]))) {
            throw new IllegalArgumentException(
                    "The ingestion scenario accepts only --onExistingBatch <mode>");
        }
        String existing = extra.length == 0 ? "reject" : extra[1];
        return jobs.ingest(
                "s3a://stratus-landing/" + LANDING_PREFIX + "/" + resource,
                BRONZE, "crm", batchId, existing, schema,
                "ingest-" + batchId + "-" + SUFFIX);
    }

    private static PlatformJobs.Outcome transform(String batchId) {
        return transform(batchId, null);
    }

    private static PlatformJobs.Outcome transform(String batchId, String qualityRunId) {
        return jobs.transform(BRONZE, SILVER, new String[] {"customer_id"}, "updated_at",
                batchId, "transform-" + batchId + "-" + SUFFIX, qualityRunId);
    }

    private static PlatformJobs.Outcome runQualityChecks(String runId, String checks) {
        return runQualityChecks(runId, checks, BRONZE);
    }

    /**
     * The rules travel as a Java string. They used to be Base64-encoded because
     * the container runtime on Windows stripped the double quotes out of a JSON
     * command-line argument; nothing is on a command line now.
     */
    private static PlatformJobs.Outcome runQualityChecks(String runId, String checks,
                                                         String table) {
        return jobs.checkQuality(table, checks, runId);
    }

    /**
     * Rules chosen for what the defective batch actually breaks. Uniqueness is
     * deliberately absent: bronze holds every version of a record it ever
     * received, so a rule demanding one row per key would fail on a correctly
     * working table.
     */
    private static String defectiveDataRules() {
        return "["
                + "{\"type\":\"row_count_min\",\"name\":\"has_rows\",\"severity\":\"blocking\","
                + "\"minRows\":1},"
                + "{\"type\":\"completeness\",\"name\":\"email_mandatory\",\"severity\":\"blocking\","
                + "\"column\":\"email\",\"maxNullRate\":0.0},"
                + "{\"type\":\"referential_integrity\",\"name\":\"country_known\","
                + "\"severity\":\"blocking\",\"column\":\"country\",\"referenceTable\":\"" + COUNTRIES
                + "\",\"referenceColumn\":\"country\"}]";
    }

    private static String referenceRule() {
        return "[{\"type\":\"referential_integrity\",\"name\":\"country_known\","
                + "\"severity\":\"blocking\",\"column\":\"country\",\"referenceTable\":\"" + COUNTRIES
                + "\",\"referenceColumn\":\"country\"}]";
    }

    private static String results(String runId) {
        return "stratus.platform.quality_check_results WHERE run_id = '" + runId + "'";
    }

    /** Every job log record emitted so far, as one searchable block. */
    private static String jobLog() {
        synchronized (JOB_LOG) {
            return String.join("\n", JOB_LOG);
        }
    }

    private static String scalar(String expression, String from) {
        return client.scalar("SELECT " + expression + " FROM " + from);
    }

    /**
     * The single row a check expects, failing if the query matched none or
     * several. Reading {@code get(0)} without this passes on a duplicated
     * record and throws something unhelpful on an absent one.
     */
    private static Row onlyRow(String statement) {
        List<Row> rows = client.sql(statement);
        assertEquals(1, rows.size(), "expected exactly one row from: " + statement);
        return rows.get(0);
    }

    /**
     * The properties the catalog actually holds for a table, by name.
     *
     * <p>{@code SHOW TBLPROPERTIES} returns a two-column result, so the pairs
     * are read from the columns. This used to split the console output of
     * {@code spark-sql} on tab characters, which asserted on a display format:
     * a property value containing a tab, or any change to how the CLI aligns
     * its columns, produced a wrong answer silently.
     */
    private static Map<String, String> tableProperties(String table) {
        var properties = new LinkedHashMap<String, String>();
        for (Row row : client.sql("SHOW TBLPROPERTIES " + table)) {
            properties.put(row.getString(0), row.getString(1));
        }
        assertFalse(properties.isEmpty(), "the table must report properties: " + table);
        SparkVerificationLogging.tablePropertiesInspected(table, properties);
        return properties;
    }

    /**
     * A retention boundary just ahead of now, so maintenance considers
     * everything this suite has written. Reading the wall clock is right here:
     * the statement being tested is "everything up to this moment".
     */
    private static String aMomentFromNow() {
        return LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** Outside Iceberg's 24-hour floor for removing files no table refers to. */
    private static String aDayAndAHalfAgo() {
        return LocalDateTime.now(ZoneOffset.UTC).minusHours(36)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
