package dev.stratus.verification.storage;

import org.junit.jupiter.api.Test;

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

class StorageContractVerifierTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-12T10:15:30Z"), ZoneOffset.UTC);

    @Test
    void verifiesRequiredBucketsRoundTripMultipartAndCleanup() {
        var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        var verifier = new StorageContractVerifier(client, CLOCK);

        var report = verifier.verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

        assertTrue(report.success());
        assertEquals(5, report.checks().size());
        assertTrue(report.checks().stream().allMatch(VerificationCheck::passed));
        assertTrue(client.objects.isEmpty(), "verification objects must always be removed");
    }

    @Test
    void reportsEveryMissingBucketWithoutAttemptingWrites() {
        var client = new InMemoryObjectStorageClient(Set.of("stratus-landing", "stratus-bronze"));
        var verifier = new StorageContractVerifier(client, CLOCK);

        var report = verifier.verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

        assertFalse(report.success());
        assertTrue(report.checks().getFirst().detail().contains("stratus-silver"));
        assertTrue(report.checks().getFirst().detail().contains("stratus-gold"));
        assertTrue(report.checks().getFirst().detail().contains("stratus-platform"));
        assertEquals(1, report.checks().size());
        assertTrue(client.objects.isEmpty());
    }

    @Test
    void cleansUpProbeWhenReadBackDoesNotMatch() {
        var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        client.corruptReads = true;
        var verifier = new StorageContractVerifier(client, CLOCK);

        var report = verifier.verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

        assertFalse(report.success());
        assertTrue(report.checks().stream().anyMatch(check -> check.name().equals("object-round-trip") && !check.passed()));
        assertTrue(client.objects.isEmpty());
    }

    @Test
    void failsVerificationWhenProbeCleanupFails() {
        var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        client.failDeletes = true;
        var verifier = new StorageContractVerifier(client, CLOCK);

        var report = verifier.verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

        assertFalse(report.success());
        assertTrue(report.checks().stream().anyMatch(check -> check.name().equals("probe-cleanup") && !check.passed()));
        assertFalse(client.objects.isEmpty());
    }

    @Test
    void identifiesHeadListAndMultipartFailureStages() {
        var badSize = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        badSize.badRoundTripSize = true;
        assertTrue(new StorageContractVerifier(badSize, CLOCK).verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing")
                .checks().stream().anyMatch(check -> check.name().equals("head-and-list") && !check.passed()));

        var omittedListing = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        omittedListing.omitListing = true;
        assertTrue(new StorageContractVerifier(omittedListing, CLOCK).verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing")
                .checks().stream().anyMatch(check -> check.name().equals("head-and-list") && !check.passed()));

        var badMultipart = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
        badMultipart.badMultipartSize = true;
        assertTrue(new StorageContractVerifier(badMultipart, CLOCK).verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing")
                .checks().stream().anyMatch(check -> check.name().equals("multipart-upload") && !check.passed()));
    }

    @Test
    void recordsNullCleanupFailureAndEmitsInfoAndDebugLogs() {
        var records = new ArrayList<LogRecord>();
        var handler = new Handler() {
            public void publish(LogRecord record) { records.add(record); }
            public void flush() { }
            public void close() { }
        };
        var previousLevel = StorageContractVerifier.LOGGER.getLevel();
        StorageContractVerifier.LOGGER.setLevel(Level.FINE);
        handler.setLevel(Level.FINE);
        StorageContractVerifier.LOGGER.addHandler(handler);
        try {
            var client = new InMemoryObjectStorageClient(StorageVerifierConfig.REQUIRED_BUCKETS);
            client.failDeletesWithNullMessage = true;
            var report = new StorageContractVerifier(client, CLOCK)
                    .verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");
            assertFalse(report.success());
            assertTrue(report.checks().getLast().detail().contains("IllegalStateException"));
            assertTrue(records.stream().anyMatch(record -> record.getLevel().equals(Level.INFO)));
            assertTrue(records.stream().anyMatch(record -> record.getLevel().equals(Level.FINE)));
        } finally {
            StorageContractVerifier.LOGGER.removeHandler(handler);
            StorageContractVerifier.LOGGER.setLevel(previousLevel);
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
        StorageContractVerifier.LOGGER.addHandler(handler);
        try {
            StorageContractVerifier.configureLogging("debug");
            assertEquals(Level.FINE, StorageContractVerifier.LOGGER.getLevel());

            StorageContractVerifier.LOGGER.info("TEST INFO logging verified");
            StorageContractVerifier.LOGGER.fine("TEST DEBUG logging verified");

            assertTrue(records.stream().anyMatch(record -> record.getLevel().equals(Level.INFO)
                    && record.getMessage().equals("TEST INFO logging verified")));
            assertTrue(records.stream().anyMatch(record -> record.getLevel().equals(Level.FINE)
                    && record.getMessage().equals("TEST DEBUG logging verified")));

            StorageContractVerifier.configureLogging("INFO");
            assertEquals(Level.INFO, StorageContractVerifier.LOGGER.getLevel());
            assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> StorageContractVerifier.configureLogging("TRACE")).getMessage().contains("INFO or DEBUG"));
        } finally {
            StorageContractVerifier.LOGGER.removeHandler(handler);
            StorageContractVerifier.configureLogging("INFO");
        }
    }

    @Test
    void writesAndRotatesPersistentLogs(@org.junit.jupiter.api.io.TempDir Path temporaryDirectory) throws Exception {
        var pattern = temporaryDirectory.resolve("storage-contract-verifier.%g.log");
        StorageContractVerifier.configureLogging("DEBUG");
        StorageContractVerifier.configurePersistentLogging(pattern, 256, 3);
        for (var index = 0; index < 40; index++) {
            StorageContractVerifier.LOGGER.fine("TEST DEBUG rolling log record " + index + " with enough text to force rollover");
        }
        for (var handler : StorageContractVerifier.LOGGER.getHandlers()) handler.flush();
        StorageContractVerifier.closePersistentLogging();

        var files = Files.list(temporaryDirectory).filter(Files::isRegularFile).toList();
        assertTrue(files.size() >= 2, "the small test limit must create a rotated generation");
        assertTrue(files.stream().map(path -> {
            try { return Files.readString(path); } catch (java.io.IOException exception) { throw new UncheckedIOException(exception); }
        }).anyMatch(content -> content.contains("TEST DEBUG rolling log record")));

        StorageContractVerifier.configurePersistentLogging(pattern, 256, 3);
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> StorageContractVerifier.configurePersistentLogging(pattern, 0, 3)).getMessage().contains("positive"));
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> StorageContractVerifier.configurePersistentLogging(pattern, 256, 0)).getMessage().contains("positive"));

        var ordinaryFile = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(ordinaryFile, "content");
        assertTrue(org.junit.jupiter.api.Assertions.assertThrows(UncheckedIOException.class,
                () -> StorageContractVerifier.configurePersistentLogging(ordinaryFile.resolve("log.%g"), 256, 2))
                .getMessage().contains("Cannot create persistent log"));
        StorageContractVerifier.configurePersistentLogging(pattern, 256, 3);
        StorageContractVerifier.closePersistentLogging();
        StorageContractVerifier.closePersistentLogging();
        StorageContractVerifier.configureLogging("INFO");
    }

    private static final class InMemoryObjectStorageClient implements ObjectStorageClient {
        private final Set<String> buckets;
        private final Map<String, byte[]> objects = new HashMap<>();
        private boolean corruptReads;
        private boolean failDeletes;
        private boolean failDeletesWithNullMessage;
        private boolean badRoundTripSize;
        private boolean omitListing;
        private boolean badMultipartSize;

        private InMemoryObjectStorageClient(Set<String> buckets) {
            this.buckets = new HashSet<>(buckets);
        }

        @Override public Set<String> listBuckets() { return Set.copyOf(buckets); }
        @Override public void put(String bucket, String key, byte[] content) { objects.put(bucket + "/" + key, content.clone()); }
        @Override public byte[] get(String bucket, String key) {
            return corruptReads ? "corrupt".getBytes(StandardCharsets.UTF_8) : objects.get(bucket + "/" + key).clone();
        }
        @Override public long size(String bucket, String key) {
            var size = objects.get(bucket + "/" + key).length;
            if (key.endsWith("round-trip.txt") && badRoundTripSize) return size + 1;
            if (key.endsWith("multipart.bin") && badMultipartSize) return size + 1;
            return size;
        }
        @Override public Set<String> list(String bucket, String prefix) {
            var bucketPrefix = bucket + "/" + prefix;
            var matches = new HashSet<String>();
            objects.keySet().stream().filter(key -> key.startsWith(bucketPrefix))
                    .forEach(key -> matches.add(key.substring(bucket.length() + 1)));
            return omitListing ? Set.of() : matches;
        }
        @Override public void multipartPut(String bucket, String key, byte[] content, int partSize) { put(bucket, key, content); }
        @Override public void delete(String bucket, String key) {
            if (failDeletes) throw new IllegalStateException("delete denied");
            if (failDeletesWithNullMessage) throw new IllegalStateException();
            objects.remove(bucket + "/" + key);
        }
        @Override public void close() { }
    }
}
