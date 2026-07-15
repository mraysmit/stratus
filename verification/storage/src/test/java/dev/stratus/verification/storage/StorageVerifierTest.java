// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Implementation of StorageVerifierTest functionality.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
@Tag("unit")
class StorageVerifierTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-12T10:15:30Z"), ZoneOffset.UTC);
    private VerifierLogCapture logCapture;

    @BeforeEach
    void captureVerifierLogs() {
        logCapture = new VerifierLogCapture();
    }

    @AfterEach
    void restoreVerifierLogging() {
        logCapture.close();
    }

    @Test
    void verifiesRequiredBucketsRoundTripMultipartAndCleanup() {
        var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        var verifier = new StorageVerifier(client, CLOCK);

        var report = verifier.verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

        assertTrue(report.success());
        assertEquals(12, report.checks().size());
        assertTrue(report.checks().stream().allMatch(VerificationCheck::passed));
        assertTrue(client.objects.isEmpty(), "verification objects must always be removed");
    }

    @Test
    void reportsEveryMissingBucketWithoutAttemptingWrites() {
        var client = new InMemoryObjectStorageClient(Set.of("stratus-landing", "stratus-bronze"));
        var verifier = new StorageVerifier(client, CLOCK);

        var report = verifier.verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

        assertFalse(report.success());
        assertTrue(report.checks().getFirst().detail().contains("stratus-silver"));
        assertTrue(report.checks().getFirst().detail().contains("stratus-gold"));
        assertTrue(report.checks().getFirst().detail().contains("stratus-platform"));
        assertEquals(1, report.checks().size());
        assertTrue(client.objects.isEmpty());
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getLevel().equals(Level.WARNING)
                && record.getMessage().contains("Missing required buckets")));
    }

    @Test
    void cleansUpProbeWhenReadBackDoesNotMatch() {
        var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        client.corruptReads = true;
        var verifier = new StorageVerifier(client, CLOCK);

        var report = verifier.verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

        assertFalse(report.success());
        assertTrue(report.checks().stream().anyMatch(check -> check.name().equals("object-round-trip") && !check.passed()));
        assertTrue(client.objects.isEmpty());
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getLevel().equals(Level.WARNING)
                && record.getMessage().contains("object-round-trip failed")));
    }

    @Test
    void failsVerificationWhenProbeCleanupFails() {
        var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        client.failDeletes = true;
        var verifier = new StorageVerifier(client, CLOCK);

        var report = verifier.verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

        assertFalse(report.success());
        assertTrue(report.checks().stream().anyMatch(check -> check.name().equals("probe-cleanup") && !check.passed()));
        assertFalse(client.objects.isEmpty());
    }

    @Test
    void identifiesHeadListAndMultipartFailureStages() {
        var badSize = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        badSize.badRoundTripSize = true;
        assertTrue(new StorageVerifier(badSize, CLOCK).verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing")
                .checks().stream().anyMatch(check -> check.name().equals("head-and-list") && !check.passed()));

        var omittedListing = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        omittedListing.omitListing = true;
        var omittedReport = new StorageVerifier(omittedListing, CLOCK)
                .verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");
        assertTrue(omittedReport.checks().stream().anyMatch(check -> check.name().equals("head-and-list") && !check.passed()));
        assertTrue(omittedReport.checks().stream().anyMatch(check -> check.name().equals("paginated-list") && !check.passed()));

        var badMultipart = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        badMultipart.badMultipartSize = true;
        assertTrue(new StorageVerifier(badMultipart, CLOCK).verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing")
                .checks().stream().anyMatch(check -> check.name().equals("multipart-upload") && !check.passed()));
    }

    @Test
    void reportsBucketDiscoveryFailureWithoutAnExceptionMessage() {
        var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        client.failBucketDiscoveryWithNullMessage = true;

        var report = new StorageVerifier(client, CLOCK)
                .verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

        assertFalse(report.success());
        assertEquals("IllegalStateException", report.checks().getFirst().detail());
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getMessage().contains("IllegalStateException")
                && record.getThrown() instanceof IllegalStateException));
    }

    @Test
    void identifiesEveryExpandedObjectSemanticFailure() {
        assertFailedCheck(client -> client.probeExistsInitially = true, "missing-object");
        assertFailedCheck(client -> client.badZeroByteSize = true, "zero-byte-object");
        assertFailedCheck(client -> client.badZeroByteRead = true, "zero-byte-object");
        assertFailedCheck(client -> client.corruptOverwrite = true, "object-overwrite");
        assertFailedCheck(client -> client.corruptSpecialKey = true, "special-character-key");
        assertFailedCheck(client -> client.badLargeSize = true, "large-single-put");
        assertFailedCheck(client -> client.corruptLargeRead = true, "large-single-put");
        assertFailedCheck(client -> client.badConcurrentSize = true, "concurrent-access");
        assertFailedCheck(client -> client.corruptConcurrentRead = true, "concurrent-access");
    }

    @Test
    void detectsAnObjectRetainedAfterDelete() {
        var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        client.retainDeletes = true;

        var report = new StorageVerifier(client, CLOCK)
                .verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

        assertFalse(report.success());
        assertTrue(report.checks().getLast().detail().contains("still exists after DELETE"));
    }

    @Test
    void reportsChecksIndependentlyAfterAnEarlierFailure() {
        var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        client.corruptReads = true;

        var report = new StorageVerifier(client, CLOCK)
                .verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

        assertFalse(report.success());
        assertEquals(12, report.checks().size());
        assertTrue(report.checks().stream().anyMatch(check -> check.name().equals("object-round-trip") && !check.passed()));
        assertTrue(report.checks().stream().anyMatch(check -> check.name().equals("paginated-list") && check.passed()));
        assertTrue(report.checks().stream().anyMatch(check -> check.name().equals("multipart-upload") && check.passed()));
        assertTrue(report.checks().stream().anyMatch(check -> check.name().equals("probe-cleanup") && check.passed()));
    }

    private static void assertFailedCheck(java.util.function.Consumer<InMemoryObjectStorageClient> fault,
                                          String expectedCheck) {
        var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        fault.accept(client);
        var report = new StorageVerifier(client, CLOCK)
                .verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");
        assertFalse(report.success());
        assertTrue(report.checks().stream().anyMatch(check -> check.name().equals(expectedCheck) && !check.passed()),
                report.toJson());
    }

    @Test
    void recordsNullCleanupFailureAndEmitsInfoAndDebugLogs() {
        var records = new ArrayList<LogRecord>();
        var handler = new Handler() {
            public void publish(LogRecord record) { records.add(record); }
            public void flush() { }
            public void close() { }
        };
        var previousLevel = StorageVerifier.LOGGER.getLevel();
        StorageVerifier.LOGGER.setLevel(Level.FINE);
        handler.setLevel(Level.FINE);
        StorageVerifier.LOGGER.addHandler(handler);
        try {
            var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
            client.failDeletesWithNullMessage = true;
            var report = new StorageVerifier(client, CLOCK)
                    .verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");
            assertFalse(report.success());
            assertTrue(report.checks().getLast().detail().contains("IllegalStateException"));
            assertTrue(records.stream().anyMatch(record -> record.getLevel().equals(Level.INFO)));
            assertTrue(records.stream().anyMatch(record -> record.getLevel().equals(Level.FINE)));
        } finally {
            StorageVerifier.LOGGER.removeHandler(handler);
            StorageVerifier.LOGGER.setLevel(previousLevel);
        }
    }

    @Test
    void createsFailedCheckFromExceptionWithNullMessage() {
        assertEquals("IllegalStateException", VerificationCheck.failed("failure", new IllegalStateException()).detail());
    }

    @Test
    void configuresInfoAndDebugLoggingAndRejectsUnknownLevels() {
        var records = new ArrayList<LogRecord>();
        var handler = new Handler() {
            public void publish(LogRecord record) { records.add(record); }
            public void flush() { }
            public void close() { }
        };
        handler.setLevel(Level.FINE);
        StorageVerifier.LOGGER.addHandler(handler);
        try {
            StorageVerifier.configureLogging("debug");
            assertEquals(Level.FINE, StorageVerifier.LOGGER.getLevel());

            StorageVerifier.LOGGER.info("TEST INFO logging verified");
            StorageVerifier.LOGGER.fine("TEST DEBUG logging verified");

            assertTrue(records.stream().anyMatch(record -> record.getLevel().equals(Level.INFO)
                    && record.getMessage().equals("TEST INFO logging verified")));
            assertTrue(records.stream().anyMatch(record -> record.getLevel().equals(Level.FINE)
                    && record.getMessage().equals("TEST DEBUG logging verified")));

            StorageVerifier.configureLogging("INFO");
            assertEquals(Level.INFO, StorageVerifier.LOGGER.getLevel());
            assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> StorageVerifier.configureLogging("TRACE")).getMessage().contains("INFO or DEBUG"));
        } finally {
            StorageVerifier.LOGGER.removeHandler(handler);
            StorageVerifier.configureLogging("INFO");
        }
    }

    @Test
    void writesAndRotatesPersistentLogs(@org.junit.jupiter.api.io.TempDir Path temporaryDirectory) throws Exception {
        var pattern = temporaryDirectory.resolve("storage-verifier.%g.log");
        StorageVerifier.configureLogging("DEBUG");
        StorageVerifier.configurePersistentLogging(pattern, 256, 3);
        for (var index = 0; index < 40; index++) {
            StorageVerifier.LOGGER.fine("TEST DEBUG rolling log record " + index + " with enough text to force rollover");
        }
        for (var handler : StorageVerifier.LOGGER.getHandlers()) handler.flush();
        StorageVerifier.closePersistentLogging();

        var files = Files.list(temporaryDirectory).filter(Files::isRegularFile).toList();
        assertTrue(files.size() >= 2, "the small test limit must create a rotated generation");
        assertTrue(files.stream().map(path -> {
            try { return Files.readString(path); } catch (java.io.IOException exception) { throw new UncheckedIOException(exception); }
        }).anyMatch(content -> content.contains("TEST DEBUG rolling log record")));

        StorageVerifier.configurePersistentLogging(pattern, 256, 3);
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> StorageVerifier.configurePersistentLogging(pattern, 0, 3)).getMessage().contains("positive"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> StorageVerifier.configurePersistentLogging(pattern, 256, 0)).getMessage().contains("positive"));

        var ordinaryFile = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(ordinaryFile, "content");
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(UncheckedIOException.class,
                () -> StorageVerifier.configurePersistentLogging(ordinaryFile.resolve("log.%g"), 256, 2))
                .getMessage().contains("Cannot create persistent log"));
        StorageVerifier.configurePersistentLogging(pattern, 256, 3);
        StorageVerifier.closePersistentLogging();
        StorageVerifier.closePersistentLogging();
        StorageVerifier.configureLogging("INFO");
    }

    private static final class InMemoryObjectStorageClient implements ObjectStorageClient {
        private final Set<String> buckets;
        private final Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();
        private boolean corruptReads;
        private boolean failDeletes;
        private boolean failDeletesWithNullMessage;
        private boolean badRoundTripSize;
        private boolean omitListing;
        private boolean badMultipartSize;
        private boolean probeExistsInitially;
        private boolean badZeroByteSize;
        private boolean badZeroByteRead;
        private boolean corruptOverwrite;
        private boolean corruptSpecialKey;
        private boolean badLargeSize;
        private boolean corruptLargeRead;
        private boolean retainDeletes;
        private boolean failBucketDiscoveryWithNullMessage;
        private boolean badConcurrentSize;
        private boolean corruptConcurrentRead;
        private final Map<String, Integer> putCounts = new java.util.concurrent.ConcurrentHashMap<>();
        private int existsCalls;

        private InMemoryObjectStorageClient(Set<String> buckets) {
            this.buckets = new HashSet<>(buckets);
        }

        @Override public Set<String> listBuckets() {
            if (failBucketDiscoveryWithNullMessage) throw new IllegalStateException();
            return Set.copyOf(buckets);
        }
        @Override public void put(String bucket, String key, byte[] content) {
            var objectKey = bucket + "/" + key;
            objects.put(objectKey, content.clone());
            putCounts.merge(objectKey, 1, Integer::sum);
        }
        @Override public byte[] get(String bucket, String key) {
            var objectKey = bucket + "/" + key;
            if (corruptReads
                    || (badZeroByteRead && key.endsWith("zero-byte.bin"))
                    || (corruptOverwrite && key.endsWith("overwrite.txt") && putCounts.getOrDefault(objectKey, 0) > 1)
                    || (corruptSpecialKey && key.contains("special characters"))
                    || (corruptLargeRead && key.endsWith("large-single-put.bin"))
                    || (corruptConcurrentRead && key.contains("/concurrent/"))) {
                return "corrupt".getBytes(StandardCharsets.UTF_8);
            }
            return objects.get(objectKey).clone();
        }
        @Override public long size(String bucket, String key) {
            var size = objects.get(bucket + "/" + key).length;
            if (key.endsWith("head-and-list.txt") && badRoundTripSize) return size + 1;
            if (key.endsWith("multipart.bin") && badMultipartSize) return size + 1;
            if (key.endsWith("zero-byte.bin") && badZeroByteSize) return 1;
            if (key.endsWith("large-single-put.bin") && badLargeSize) return size + 1;
            if (key.contains("/concurrent/") && badConcurrentSize) return size + 1;
            return size;
        }
        @Override public boolean exists(String bucket, String key) {
            existsCalls++;
            return (probeExistsInitially && existsCalls == 1) || objects.containsKey(bucket + "/" + key);
        }
        @Override public Set<String> list(String bucket, String prefix) {
            var bucketPrefix = bucket + "/" + prefix;
            var matches = new HashSet<String>();
            objects.keySet().stream().filter(key -> key.startsWith(bucketPrefix))
                    .forEach(key -> matches.add(key.substring(bucket.length() + 1)));
            return omitListing ? Set.of() : matches;
        }
        @Override public void verifyCredentialsRejected() { throw new UnsupportedOperationException(); }
        @Override public void verifyListDenied(String bucket) { throw new UnsupportedOperationException(); }
        @Override public void multipartPut(String bucket, String key, byte[] content, int partSize) { put(bucket, key, content); }
        @Override public void delete(String bucket, String key) {
            if (failDeletes) throw new IllegalStateException("delete denied");
            if (failDeletesWithNullMessage) throw new IllegalStateException();
            if (!retainDeletes) objects.remove(bucket + "/" + key);
        }
        @Override public void close() { }
    }
}
