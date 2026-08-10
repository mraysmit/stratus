// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real logging backend the Spark conformance suite emits
 * through, without replacing it, and proves a catalog credential cannot reach
 * a record at either level.
 *
 * <p>The credential case is not hypothetical: the forged-principal check hands
 * spark-sql a secret as a {@code --conf} argument, and the suite records the
 * arguments of every command it runs. Redaction is the only thing standing
 * between that and a transcript.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("unit")
final class SparkVerificationLoggingTest {

    private final Logger backend = Logger.getLogger(SparkVerificationLogging.LOGGER_NAME);
    private final CapturingHandler capture = new CapturingHandler();
    private Level originalLevel;
    private boolean originalUseParentHandlers;

    @BeforeEach
    void captureTheRealBackend() {
        originalLevel = backend.getLevel();
        originalUseParentHandlers = backend.getUseParentHandlers();
        capture.setLevel(Level.ALL);
        backend.setLevel(Level.ALL);
        backend.setUseParentHandlers(false);
        backend.addHandler(capture);
    }

    @AfterEach
    void restoreTheBackend() {
        backend.removeHandler(capture);
        backend.setLevel(originalLevel);
        backend.setUseParentHandlers(originalUseParentHandlers);
    }

    @Test
    void aCommandIsRecordedAtBothLevels() {
        SparkVerificationLogging.commandCompleted("submit IngestionJob",
                List.of("docker", "compose", "exec", "spark-master", "spark-submit"),
                0, 1234L, "2026-08-09T10:00:00Z INFO dev.stratus.jobs.spark.IngestionJob "
                        + "INGESTION COMPLETE table=stratus.bronze.customers rowsInBatch=5");

        assertTrue(messageAt(Level.INFO).contains("exitCode=0"), messageAt(Level.INFO));
        assertTrue(messageAt(Level.INFO).contains("durationMs=1234"), messageAt(Level.INFO));
        assertTrue(capture.at(Level.FINE).size() >= 2,
                "the argv and the job's own output must both be diagnostic: " + capture.at(Level.FINE));
    }

    @Test
    void theJobsOwnRecordsReachTheTranscript() {
        // The point of the relay. A Spark job logs inside the cluster, so its
        // records arrive here as command output and are otherwise read once,
        // asserted on, and discarded — leaving a transcript that says a
        // pipeline passed without saying what it did.
        SparkVerificationLogging.commandCompleted("submit TransformJob", List.of("spark-submit"),
                0, 10L, "2026-08-09T10:00:00Z INFO dev.stratus.jobs.spark.TransformJob "
                        + "TRANSFORM COMPLETE table=stratus.silver.customers rows=7");

        String diagnostic = String.join(" ", capture.messages(Level.FINE));
        assertTrue(diagnostic.contains("TRANSFORM COMPLETE"), diagnostic);
        assertTrue(diagnostic.contains("rows=7"), diagnostic);
    }

    @Test
    void aJobsRecordsAreSelectedFromTheEngineNoiseAroundThem() {
        // The failure this replaces: keeping the first few kilobytes of a
        // submission keeps only the engine's startup banner. A job says what it
        // did at the end, after pages of output nobody needs.
        String banner = "WARN NativeCodeLoader: Unable to load native-hadoop library\n".repeat(400);
        String output = banner
                + "2026-08-09T10:00:00Z INFO dev.stratus.jobs.spark.IngestionJob "
                + "INGESTION COMPLETE table=stratus.bronze.customers batchId=2026-08-01\n"
                + "2026-08-09T10:00:01Z DEBUG dev.stratus.jobs.spark.SchemaDrift "
                + "SCHEMA COMPARE table=stratus.bronze.customers conflicts=0\n";

        List<String> records = SparkVerificationLogging.jobRecords(output, 0);

        assertEquals(2, records.size(), "both job records must be selected: " + records);
        assertTrue(records.get(0).contains("INGESTION COMPLETE"), records.get(0));
        assertTrue(records.get(1).contains("SCHEMA COMPARE"), records.get(1));
        assertTrue(records.stream().noneMatch(line -> line.contains("NativeCodeLoader")),
                "engine noise must not be relayed: " + records);
    }

    @Test
    void theLineagePayloadIsRelayedEvenThoughItCarriesNoLoggerName() {
        List<String> records = SparkVerificationLogging.jobRecords(
                "noise\nSTRATUS_LINEAGE {\"type\": \"INGESTION\"}\nmore noise", 0);

        assertEquals(1, records.size(), records.toString());
        assertTrue(records.get(0).contains("STRATUS_LINEAGE"), records.get(0));
    }

    @Test
    void aFailedSubmissionThatLoggedNothingKeepsItsTail() {
        // A job that died before logging leaves its stack trace at the end, and
        // that is the one case where the noise is the evidence.
        String output = "line\n".repeat(50) + "Exception in thread \"main\" java.lang.IllegalStateException";

        List<String> records = SparkVerificationLogging.jobRecords(output, 1);

        assertFalse(records.isEmpty(), "a failed submission must leave something behind");
        assertTrue(records.get(records.size() - 1).contains("IllegalStateException"),
                "the tail must carry the failure: " + records);
    }

    @Test
    void aSuccessfulCommandThatLoggedNothingRelaysNothing() {
        // Every plain SQL statement prints an engine banner and nothing else.
        // Relaying a dozen lines of it per statement would bury the records
        // that do matter, which is the failure mode this whole class exists to
        // avoid — a transcript nobody can read is a transcript nobody keeps.
        String banner = "26/08/09 14:13:47 WARN ObjectStore: Version information not found\n"
                + "Spark master: spark://spark-master.stratus.local:7077\n"
                + "Time taken: 3.935 seconds\n";

        assertEquals(List.of(), SparkVerificationLogging.jobRecords(banner, 0));
    }

    @Test
    void nothingIsRelayedForACommandThatProducedNoOutput() {
        assertEquals(List.of(), SparkVerificationLogging.jobRecords("", 0));
        assertEquals(List.of(), SparkVerificationLogging.jobRecords(null, 0));
    }

    @Test
    void aCatalogCredentialNeverReachesARecord() {
        String secret = "forged-secret-0000000000000000";
        SparkVerificationLogging.commandCompleted("spark-sql",
                List.of("spark-sql", "--conf", "spark.sql.catalog.forged.credential=svc-spark:" + secret,
                        "-e", "SHOW NAMESPACES IN forged"),
                1, 5L, "refused");

        for (LogRecord record : capture.records) {
            assertFalse(record.getMessage().contains(secret),
                    "credential material must not reach a record: " + record.getMessage());
        }
        assertTrue(String.join(" ", capture.messages(Level.FINE)).contains("<redacted>"),
                "the argument must still be recorded, with its value removed");
    }

    @Test
    void theKeyOfARedactedArgumentIsKept() {
        // A redaction that removed the whole argument would hide which setting
        // was in play, and the setting is what an operator is looking for.
        List<String> redacted = SparkVerificationLogging.redact(
                List.of("--conf", "spark.sql.catalog.forged.credential=svc-spark:hunter2"));

        assertEquals("--conf", redacted.get(0));
        assertEquals("spark.sql.catalog.forged.credential=<redacted>", redacted.get(1));
    }

    @Test
    void everyKindOfSecretMarkerIsRedacted() {
        for (String argument : new String[] {
                "spark.hadoop.fs.s3a.secret.key=abc",
                "--password=abc",
                "spark.hadoop.fs.s3a.access-key=abc",
                "auth-token=abc"}) {
            List<String> redacted = SparkVerificationLogging.redact(List.of(argument));

            assertFalse(redacted.get(0).contains("abc"),
                    "value must be removed from: " + redacted.get(0));
        }
    }

    @Test
    void anOrdinaryArgumentIsRecordedInFull() {
        // The negative control. A redaction that replaced everything would pass
        // every assertion above and leave the transcript useless.
        List<String> redacted = SparkVerificationLogging.redact(
                List.of("--targetTable", "stratus.bronze.customers"));

        assertEquals(List.of("--targetTable", "stratus.bronze.customers"), redacted);
    }

    @Test
    void aRecordNeverSpansMoreThanOneLine() {
        // A job's output is thousands of lines. Reproducing its line breaks
        // would interleave it with every other record in the transcript.
        SparkVerificationLogging.commandCompleted("spark-sql", List.of("spark-sql"),
                0, 1L, "first line\nsecond line\r\nthird\tline");

        for (LogRecord record : capture.records) {
            assertFalse(record.getMessage().contains("\n"), record.getMessage());
            assertFalse(record.getMessage().contains("\t"), record.getMessage());
        }
    }

    @Test
    void onlyTheTwoOperationalLevelsAreAccepted() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> SparkVerificationLogging.configure("TRACE"));

        assertEquals("STRATUS_LOG_LEVEL must be INFO or DEBUG", failure.getMessage());
    }

    @Test
    void theDiagnosticLevelIsRenderedAsDebugRatherThanFine() {
        // Operators configure DEBUG; the JDK backend calls it FINE. A transcript
        // that said FINE would not match the switch that produced it.
        var record = new LogRecord(Level.FINE, "detail");
        record.setLoggerName(SparkVerificationLogging.LOGGER_NAME);

        String rendered = new SparkVerificationLogging.OperationalLevelFormatter().format(record);

        assertTrue(rendered.contains(" DEBUG "), rendered);
        assertFalse(rendered.contains(" FINE "), rendered);
    }

    private String messageAt(Level level) {
        List<String> messages = capture.messages(level);
        assertFalse(messages.isEmpty(), "no record at " + level);
        return messages.get(0);
    }

    /** Collects records from the real JDK logging backend. */
    private static final class CapturingHandler extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

        List<LogRecord> at(Level level) {
            return records.stream().filter(record -> record.getLevel().equals(level)).toList();
        }

        List<String> messages(Level level) {
            return at(level).stream().map(LogRecord::getMessage).toList();
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
