package dev.stratus.verification.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageVerifierMainTest {
    @Test
    void returnsSuccessAndPrintsReport() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        var exit = StorageVerifierMain.run(environment(), ignored -> new StubClient(Set.copyOf(StorageVerifierConfig.REQUIRED_BUCKETS)),
                new PrintStream(output), new PrintStream(error));
        assertEquals(0, exit);
        assertTrue(output.toString().contains("\"success\":true"));
        assertEquals("", error.toString());
    }

    @Test
    void returnsFailureForContractConfigurationAndRuntimeErrors() {
        var sink = new ByteArrayOutputStream();
        assertEquals(2, StorageVerifierMain.run(environment(), ignored -> new StubClient(Set.of()),
                new PrintStream(sink), new PrintStream(sink)));
        assertEquals(64, StorageVerifierMain.run(Map.of(), ignored -> { throw new AssertionError(); },
                new PrintStream(sink), new PrintStream(sink)));
        assertEquals(2, StorageVerifierMain.run(environment(), ignored -> { throw new IllegalStateException("offline"); },
                new PrintStream(sink), new PrintStream(sink)));
        assertTrue(sink.toString().contains("Configuration error:"));
        assertTrue(sink.toString().contains("Verification error: offline"));
    }

    @Test
    void mainUsesConfiguredProcessBoundaries() {
        var previousEnvironment = StorageVerifierMain.environment;
        var previousFactory = StorageVerifierMain.clientFactory;
        var previousOutput = StorageVerifierMain.output;
        var previousError = StorageVerifierMain.error;
        var previousExit = StorageVerifierMain.exit;
        var status = new int[]{-1};
        try {
            StorageVerifierMain.environment = StorageVerifierMainTest::environment;
            StorageVerifierMain.clientFactory = ignored -> new StubClient(StorageVerifierConfig.REQUIRED_BUCKETS);
            StorageVerifierMain.output = new PrintStream(new ByteArrayOutputStream());
            StorageVerifierMain.error = new PrintStream(new ByteArrayOutputStream());
            StorageVerifierMain.exit = value -> status[0] = value;
            StorageVerifierMain.main(new String[0]);
            assertEquals(0, status[0]);
        } finally {
            StorageVerifierMain.environment = previousEnvironment;
            StorageVerifierMain.clientFactory = previousFactory;
            StorageVerifierMain.output = previousOutput;
            StorageVerifierMain.error = previousError;
            StorageVerifierMain.exit = previousExit;
        }
    }

    private static Map<String, String> environment() {
        return Map.of("CEPH_RGW_ENDPOINT", "https://object-store.stratus.local", "CEPH_RGW_ACCESS_KEY", "key",
                "CEPH_RGW_SECRET_KEY", "secret", "STRATUS_LOG_FILE", "target/test-logs/storage-contract-verifier.%g.log",
                "STRATUS_LOG_MAX_BYTES", "4096", "STRATUS_LOG_FILE_COUNT", "2");
    }

    private static final class StubClient implements ObjectStorageClient {
        private final Set<String> buckets;
        StubClient(Set<String> buckets) { this.buckets = buckets; }
        public Set<String> listBuckets() { return buckets; }
        public void put(String bucket, String key, byte[] content) { }
        public byte[] get(String bucket, String key) { return "stratus-ceph-verification".getBytes(); }
        public long size(String bucket, String key) { return key.endsWith("multipart.bin") ? 5 * 1024 * 1024 + 1024 : 25; }
        public Set<String> list(String bucket, String prefix) { return Set.of(prefix + "round-trip.txt"); }
        public void multipartPut(String bucket, String key, byte[] content, int partSize) { }
        public void delete(String bucket, String key) { }
        public void close() { }
    }
}
