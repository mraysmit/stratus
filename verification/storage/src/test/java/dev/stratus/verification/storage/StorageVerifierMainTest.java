// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for the verifier's process boundary: configuration failure
 * exit codes, evidence-write failure, and behavior when the real client
 * cannot reach any endpoint. Successful contract runs, negative security
 * modes, and evidence content are proven exclusively against the live local
 * Ceph cluster by CephRgwIntegrationTest — no simulated S3 endpoint is
 * permitted anywhere in this module.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
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
    void returnsFailureWhenTheEndpointIsUnreachable() {
        var output = new ByteArrayOutputStream();
        var exit = StorageVerifierMain.run(unreachableEnvironment(), S3ObjectStorageClient::create,
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(2, exit);
        assertTrue(output.toString().contains("\"success\":false"));
        assertTrue(output.toString().contains("required-buckets"));
    }

    @Test
    void returnsConfigurationFailureBeforeAnyNetworkOperation() {
        var sink = new ByteArrayOutputStream();
        assertEquals(64, StorageVerifierMain.run(Map.of(), S3ObjectStorageClient::create,
                new PrintStream(sink), new PrintStream(sink)));

        var invalidMode = new HashMap<>(unreachableEnvironment());
        invalidMode.put("STRATUS_VERIFICATION_MODE", "UNKNOWN");
        assertEquals(64, StorageVerifierMain.run(invalidMode, S3ObjectStorageClient::create,
                new PrintStream(sink), new PrintStream(sink)));
        assertTrue(sink.toString().contains("Configuration error:"));
    }

    @Test
    void reportsMissingAndBlankDeniedBucketAsFailedNegativeChecks() {
        var missingBucket = new HashMap<>(unreachableEnvironment());
        missingBucket.put("STRATUS_VERIFICATION_MODE", "ACCESS_DENIED");
        var output = new ByteArrayOutputStream();
        assertEquals(2, StorageVerifierMain.run(missingBucket, S3ObjectStorageClient::create,
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream())));
        assertTrue(output.toString().contains("CEPH_RGW_DENIED_BUCKET is required"));

        missingBucket.put("CEPH_RGW_DENIED_BUCKET", " ");
        assertEquals(2, StorageVerifierMain.run(missingBucket, S3ObjectStorageClient::create,
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream())));
    }

    @Test
    void reportsFailureWhenEvidenceFileCannotBeWritten() {
        var withUnwritableEvidence = new HashMap<>(unreachableEnvironment());
        withUnwritableEvidence.put("STRATUS_EVIDENCE_FILE", java.nio.file.Path.of("target", "test-logs").toString());
        var error = new ByteArrayOutputStream();
        assertEquals(2, StorageVerifierMain.run(withUnwritableEvidence, S3ObjectStorageClient::create,
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(error)));
        assertTrue(error.toString().contains("Cannot write evidence file"));
    }

    @Test
    void mainUsesConfiguredProcessBoundaries() {
        var previousEnvironment = StorageVerifierMain.environment;
        var previousOutput = StorageVerifierMain.output;
        var previousError = StorageVerifierMain.error;
        var previousExit = StorageVerifierMain.exit;
        var status = new int[]{-1};
        try {
            StorageVerifierMain.environment = StorageVerifierMainTest::unreachableEnvironment;
            StorageVerifierMain.output = new PrintStream(new ByteArrayOutputStream());
            StorageVerifierMain.error = new PrintStream(new ByteArrayOutputStream());
            StorageVerifierMain.exit = value -> status[0] = value;
            StorageVerifierMain.main(new String[0]);
            assertEquals(2, status[0]);
        } finally {
            StorageVerifierMain.environment = previousEnvironment;
            StorageVerifierMain.output = previousOutput;
            StorageVerifierMain.error = previousError;
            StorageVerifierMain.exit = previousExit;
        }
    }

    /** A closed loopback port: connections are really refused, nothing is simulated. */
    private static Map<String, String> unreachableEnvironment() {
        return Map.ofEntries(
                Map.entry("CEPH_RGW_ENDPOINT", "http://127.0.0.1:9"),
                Map.entry("CEPH_RGW_ALLOW_HTTP", "true"),
                Map.entry("CEPH_RGW_ACCESS_KEY", "key"),
                Map.entry("CEPH_RGW_SECRET_KEY", "secret"),
                Map.entry("S3_CONNECTION_TIMEOUT_MS", "500"),
                Map.entry("S3_SOCKET_TIMEOUT_MS", "500"),
                Map.entry("S3_API_CALL_ATTEMPT_TIMEOUT_MS", "1000"),
                Map.entry("S3_API_CALL_TIMEOUT_MS", "3000"),
                Map.entry("STRATUS_LOG_FILE", "target/test-logs/storage-verifier.%g.log"),
                Map.entry("STRATUS_LOG_MAX_BYTES", "4096"),
                Map.entry("STRATUS_LOG_FILE_COUNT", "2"));
    }
}
