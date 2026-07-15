package dev.stratus.verification.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class StorageVerifierMainTest {
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
    void writesPureJsonReportToConfiguredEvidenceFile() throws java.io.IOException {
        var evidenceFile = java.nio.file.Path.of("target", "test-logs", "evidence-report.json");
        java.nio.file.Files.deleteIfExists(evidenceFile);
        var withEvidence = new HashMap<>(environment());
        withEvidence.put("STRATUS_EVIDENCE_FILE", evidenceFile.toString());
        assertEquals(0, StorageVerifierMain.run(withEvidence,
                ignored -> new StubClient(Set.copyOf(StorageVerifierConfig.REQUIRED_BUCKETS)),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
        var content = java.nio.file.Files.readString(evidenceFile);
        assertTrue(content.startsWith("{\"description\":"),
                "Evidence file must contain only the report JSON: " + content);
        assertTrue(content.contains("\"success\":true"));
    }

    @Test
    void reportsFailureWhenEvidenceFileCannotBeWritten() {
        var withUnwritableEvidence = new HashMap<>(environment());
        withUnwritableEvidence.put("STRATUS_EVIDENCE_FILE", java.nio.file.Path.of("target", "test-logs").toString());
        var error = new ByteArrayOutputStream();
        assertEquals(2, StorageVerifierMain.run(withUnwritableEvidence,
                ignored -> new StubClient(Set.copyOf(StorageVerifierConfig.REQUIRED_BUCKETS)),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(error)));
        assertTrue(error.toString().contains("Cannot write evidence file"));
    }

    @Test
    void persistentLogRecordsUseIsoTimestampedSingleLines() throws java.io.IOException {
        StorageVerifierMain.run(environment(), ignored -> new StubClient(Set.copyOf(StorageVerifierConfig.REQUIRED_BUCKETS)),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
        var logLines = java.nio.file.Files.readAllLines(java.nio.file.Path.of("target/test-logs/storage-verifier.0.log"));
        var completion = logLines.stream()
                .filter(line -> line.contains("Storage contract verification completed"))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertTrue(completion.matches(
                "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4} INFO \\S.*"),
                "Log records must be single lines starting with an ISO-8601 offset timestamp: " + completion);
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
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getLevel().equals(Level.WARNING)
                && record.getMessage().contains("Missing required buckets")));
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

    @Test
    void runsInvalidCredentialAndCrossIdentityNegativeModes() {
        var authEnvironment = new HashMap<>(environment());
        authEnvironment.put("STRATUS_VERIFICATION_MODE", "AUTH_FAILURE");
        var output = new ByteArrayOutputStream();
        assertEquals(0, StorageVerifierMain.run(authEnvironment,
                ignored -> new StubClient(StorageVerifierConfig.REQUIRED_BUCKETS),
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(output.toString().contains("invalid-credentials-rejected"));

        var deniedEnvironment = new HashMap<>(environment());
        deniedEnvironment.put("STRATUS_VERIFICATION_MODE", "ACCESS_DENIED");
        deniedEnvironment.put("CEPH_RGW_DENIED_BUCKET", "stratus-denied");
        output.reset();
        assertEquals(0, StorageVerifierMain.run(deniedEnvironment,
                ignored -> new StubClient(StorageVerifierConfig.REQUIRED_BUCKETS),
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(output.toString().contains("cross-identity-access-denied"));
    }

    @Test
    void reportsInvalidNegativeModeConfigurationAndFailedExpectation() {
        var invalidMode = new HashMap<>(environment());
        invalidMode.put("STRATUS_VERIFICATION_MODE", "UNKNOWN");
        assertEquals(64, StorageVerifierMain.run(invalidMode,
                ignored -> new StubClient(StorageVerifierConfig.REQUIRED_BUCKETS),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));

        var missingBucket = new HashMap<>(environment());
        missingBucket.put("STRATUS_VERIFICATION_MODE", "ACCESS_DENIED");
        assertEquals(2, StorageVerifierMain.run(missingBucket,
                ignored -> new StubClient(StorageVerifierConfig.REQUIRED_BUCKETS),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
        missingBucket.put("CEPH_RGW_DENIED_BUCKET", " ");
        assertEquals(2, StorageVerifierMain.run(missingBucket,
                ignored -> new StubClient(StorageVerifierConfig.REQUIRED_BUCKETS),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));

        var authEnvironment = new HashMap<>(environment());
        authEnvironment.put("STRATUS_VERIFICATION_MODE", "AUTH_FAILURE");
        var output = new ByteArrayOutputStream();
        assertEquals(2, StorageVerifierMain.run(authEnvironment,
                ignored -> new StubClient(StorageVerifierConfig.REQUIRED_BUCKETS, true),
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(output.toString().contains("\"success\":false"));
    }

    private static Map<String, String> environment() {
        return Map.of("CEPH_RGW_ENDPOINT", "https://object-store.stratus.local", "CEPH_RGW_ACCESS_KEY", "key",
                "CEPH_RGW_SECRET_KEY", "secret", "STRATUS_LOG_FILE", "target/test-logs/storage-verifier.%g.log",
                "STRATUS_LOG_MAX_BYTES", "4096", "STRATUS_LOG_FILE_COUNT", "2");
    }

    private static final class StubClient implements ObjectStorageClient {
        private final Set<String> buckets;
        private final boolean failNegativeChecks;
        // The verifier's concurrent-access check drives this from eight threads.
        private final Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();
        StubClient(Set<String> buckets) { this(buckets, false); }
        StubClient(Set<String> buckets, boolean failNegativeChecks) {
            this.buckets = buckets;
            this.failNegativeChecks = failNegativeChecks;
        }
        public Set<String> listBuckets() { return buckets; }
        public void put(String bucket, String key, byte[] content) { objects.put(bucket + "/" + key, content.clone()); }
        public byte[] get(String bucket, String key) { return objects.get(bucket + "/" + key).clone(); }
        public long size(String bucket, String key) { return objects.get(bucket + "/" + key).length; }
        public boolean exists(String bucket, String key) { return objects.containsKey(bucket + "/" + key); }
        public Set<String> list(String bucket, String prefix) {
            var bucketPrefix = bucket + "/" + prefix;
            return objects.keySet().stream().filter(key -> key.startsWith(bucketPrefix))
                    .map(key -> key.substring(bucket.length() + 1)).collect(java.util.stream.Collectors.toSet());
        }
        public void verifyCredentialsRejected() {
            if (failNegativeChecks) throw new IllegalStateException("credentials unexpectedly accepted");
        }
        public void verifyListDenied(String bucket) {
            if (failNegativeChecks) throw new IllegalStateException("bucket unexpectedly readable");
        }
        public void multipartPut(String bucket, String key, byte[] content, int partSize) { put(bucket, key, content); }
        public void delete(String bucket, String key) { objects.remove(bucket + "/" + key); }
        public void close() { }
    }
}
