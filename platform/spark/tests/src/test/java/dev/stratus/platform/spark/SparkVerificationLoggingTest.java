// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.ServiceLoader;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.SLF4JServiceProvider;

/** Exercises sanitized records through the real SLF4J/Log4j2 path. */
@Tag("unit")
final class SparkVerificationLoggingTest {

    private TestLogCapture capture;

    @BeforeEach
    void captureTheRealProvider() {
        capture = new TestLogCapture(SparkVerificationLogging.LOGGER_NAME);
    }

    @AfterEach
    void restoreTheProvider() {
        capture.close();
    }

    @Test
    void aCommandIsRecordedAtBothLevels() {
        SparkVerificationLogging.commandCompleted("submit IngestionJob",
                List.of("docker", "compose", "exec", "spark-master", "spark-submit"),
                0, 1234L, "2026-08-09T10:00:00Z INFO dev.stratus.jobs.spark.IngestionJob "
                        + "INGESTION COMPLETE table=stratus.bronze.customers rowsInBatch=5");

        assertTrue(messageAt(Level.INFO).contains("exitCode=0"));
        assertTrue(messageAt(Level.INFO).contains("durationMs=1234"));
        assertTrue(capture.at(Level.DEBUG).size() >= 2, capture.at(Level.DEBUG).toString());
    }

    @Test
    void aJobsRecordsAreSelectedFromEngineNoise() {
        String banner = "WARN NativeCodeLoader: noise\n".repeat(400);
        String output = banner
                + "INFO dev.stratus.jobs.spark.IngestionJob INGESTION COMPLETE rowsInBatch=5\n"
                + "DEBUG dev.stratus.jobs.spark.SchemaDrift SCHEMA COMPARE conflicts=0\n";

        List<String> records = SparkVerificationLogging.jobRecords(output, 0);
        assertEquals(2, records.size());
        assertTrue(records.stream().noneMatch(line -> line.contains("NativeCodeLoader")));
    }

    @Test
    void lineageIsRelayedWithoutALoggerName() {
        assertEquals(1, SparkVerificationLogging.jobRecords(
                "noise\nSTRATUS_LINEAGE {\"type\": \"INGESTION\"}\nmore noise", 0).size());
    }

    @Test
    void aFailedSubmissionThatLoggedNothingKeepsItsTail() {
        String output = "line\n".repeat(50)
                + "Exception in thread \"main\" java.lang.IllegalStateException";

        List<String> records = SparkVerificationLogging.jobRecords(output, 1);
        assertFalse(records.isEmpty());
        assertTrue(records.get(records.size() - 1).contains("IllegalStateException"));
    }

    @Test
    void aSuccessfulCommandWithoutJobRecordsRelaysNothing() {
        assertEquals(List.of(), SparkVerificationLogging.jobRecords(
                "WARN ObjectStore: noise\nTime taken: 3.935 seconds\n", 0));
        assertEquals(List.of(), SparkVerificationLogging.jobRecords("", 0));
        assertEquals(List.of(), SparkVerificationLogging.jobRecords(null, 0));
    }

    @Test
    void joinedAndSplitCredentialsNeverReachARecord() {
        String first = "forged-secret-0000000000000000";
        String second = "split-secret-1111111111111111";
        SparkVerificationLogging.commandCompleted("spark-sql",
                List.of("spark-sql", "--conf",
                        "spark.sql.catalog.forged.credential=svc-spark:" + first,
                        "--password", second, "-e", "SHOW NAMESPACES IN forged"),
                1, 5L, "authorization=Bearer third-secret");

        String messages = capture.events().stream()
                .map(event -> event.getMessage().getFormattedMessage())
                .reduce("", (left, right) -> left + ' ' + right);
        assertFalse(messages.contains(first), messages);
        assertFalse(messages.contains(second), messages);
        assertFalse(messages.contains("third-secret"), messages);
        assertTrue(messages.contains("<redacted>"), messages);
    }

    @Test
    void theKeyOfAJoinedCredentialIsKept() {
        List<String> redacted = SparkVerificationLogging.redact(
                List.of("--conf", "spark.sql.catalog.forged.credential=svc-spark:hunter2"));

        assertEquals("--conf", redacted.get(0));
        assertEquals("spark.sql.catalog.forged.credential=<redacted>", redacted.get(1));
    }

    @Test
    void ordinaryArgumentsAreRecordedAndRecordsStayOnOneLine() {
        assertEquals(List.of("--targetTable", "stratus.bronze.customers"),
                SparkVerificationLogging.redact(
                        List.of("--targetTable", "stratus.bronze.customers")));

        SparkVerificationLogging.commandCompleted("spark-sql", List.of("spark-sql"),
                0, 1L, "first line\nsecond line\r\nthird\tline");
        for (var event : capture.events()) {
            String message = event.getMessage().getFormattedMessage();
            assertFalse(message.contains("\n"), message);
            assertFalse(message.contains("\t"), message);
        }
    }

    @Test
    void exactlyOneSlf4jProviderIsActiveAndItIsLog4j2() {
        List<SLF4JServiceProvider> providers =
                ServiceLoader.load(SLF4JServiceProvider.class).stream()
                        .map(ServiceLoader.Provider::get).toList();

        assertEquals(1, providers.size(), "SLF4J providers: " + providers);
        assertTrue(providers.get(0).getClass().getName().startsWith("org.apache.logging.slf4j."));
        assertTrue(LoggerFactory.getILoggerFactory().getClass().getName().toLowerCase().contains("log4j"));
    }

    private String messageAt(Level level) {
        List<String> messages = capture.messages(level);
        assertFalse(messages.isEmpty(), "no record at " + level);
        return messages.get(0);
    }
}
