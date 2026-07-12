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

    private static final class InMemoryObjectStorageClient implements ObjectStorageClient {
        private final Set<String> buckets;
        private final Map<String, byte[]> objects = new HashMap<>();
        private boolean corruptReads;
        private boolean failDeletes;

        private InMemoryObjectStorageClient(Set<String> buckets) {
            this.buckets = new HashSet<>(buckets);
        }

        @Override public Set<String> listBuckets() { return Set.copyOf(buckets); }
        @Override public void put(String bucket, String key, byte[] content) { objects.put(bucket + "/" + key, content.clone()); }
        @Override public byte[] get(String bucket, String key) {
            return corruptReads ? "corrupt".getBytes(StandardCharsets.UTF_8) : objects.get(bucket + "/" + key).clone();
        }
        @Override public long size(String bucket, String key) { return objects.get(bucket + "/" + key).length; }
        @Override public Set<String> list(String bucket, String prefix) {
            var bucketPrefix = bucket + "/" + prefix;
            var matches = new HashSet<String>();
            objects.keySet().stream().filter(key -> key.startsWith(bucketPrefix))
                    .forEach(key -> matches.add(key.substring(bucket.length() + 1)));
            return matches;
        }
        @Override public void multipartPut(String bucket, String key, byte[] content, int partSize) { put(bucket, key, content); }
        @Override public void delete(String bucket, String key) {
            if (failDeletes) throw new IllegalStateException("delete denied");
            objects.remove(bucket + "/" + key);
        }
        @Override public void close() { }
    }
}
