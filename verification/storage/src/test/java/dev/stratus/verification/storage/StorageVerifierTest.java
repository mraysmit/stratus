// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.logging.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline tests for the verifier's pure logic: failure descriptions, logging
 * configuration, log rotation, and the real client's behavior when the
 * network endpoint does not exist. Storage behavior is proven exclusively
 * against the live local Ceph cluster by CephRgwIntegrationTest — no
 * simulated S3 endpoint is permitted anywhere in this module.
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
    void reportsUnreachableEndpointAsFailedBucketDiscovery() {
        StorageVerifier.configureLogging("DEBUG");
        // A closed loopback port: a real connection failure, not a simulation.
        try (var client = S3ObjectStorageClient.create(new StorageVerifierConfig(
                URI.create("http://127.0.0.1:9"), "local-access-key", "local-secret-key", true,
                StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing",
                Duration.ofMillis(500), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(3)))) {
            var report = new StorageVerifier(client, CLOCK)
                    .verify(StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");

            assertFalse(report.success());
            assertEquals(1, report.checks().size());
            assertEquals("required-buckets", report.checks().getFirst().name());
            assertTrue(logCapture.records().stream().anyMatch(record -> record.getLevel().equals(Level.WARNING)
                    && record.getMessage().contains("required-buckets failed")));
            assertTrue(logCapture.records().stream().anyMatch(record -> record.getLevel().equals(Level.WARNING)
                    && record.getMessage().contains("operation=LIST_BUCKETS failed")
                    && !record.getMessage().contains(" status=")
                    && record.getThrown() != null));
            assertTrue(logCapture.records().stream().anyMatch(record -> record.getLevel().equals(Level.FINE)
                    && record.getMessage().contains("S3 HTTP attempt method=GET")));
            assertTrue(logCapture.records().stream().noneMatch(record -> record.getMessage().contains("local-secret-key")));
        }
    }

    @Test
    void createsFailedCheckAndDetailFromExceptionWithNullMessage() {
        assertEquals("IllegalStateException", VerificationCheck.failed("failure", new IllegalStateException()).detail());
        assertEquals("IllegalStateException", VerificationCheck.describe(new IllegalStateException()));
        assertEquals("broken", VerificationCheck.describe(new IllegalStateException("broken")));
    }

    @Test
    void configuresInfoAndDebugLoggingAndRejectsUnknownLevels() {
        StorageVerifier.configureLogging("debug");
        assertEquals(Level.FINE, StorageVerifier.LOGGER.getLevel());

        StorageVerifier.LOGGER.info("TEST INFO logging verified");
        StorageVerifier.LOGGER.fine("TEST DEBUG logging verified");

        assertTrue(logCapture.records().stream().anyMatch(record -> record.getLevel().equals(Level.INFO)
                && record.getMessage().equals("TEST INFO logging verified")));
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getLevel().equals(Level.FINE)
                && record.getMessage().equals("TEST DEBUG logging verified")));

        StorageVerifier.configureLogging("INFO");
        assertEquals(Level.INFO, StorageVerifier.LOGGER.getLevel());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> StorageVerifier.configureLogging("TRACE")).getMessage().contains("INFO or DEBUG"));
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
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> StorageVerifier.configurePersistentLogging(pattern, 0, 3)).getMessage().contains("positive"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> StorageVerifier.configurePersistentLogging(pattern, 256, 0)).getMessage().contains("positive"));

        var ordinaryFile = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(ordinaryFile, "content");
        assertTrue(assertThrows(UncheckedIOException.class,
                () -> StorageVerifier.configurePersistentLogging(ordinaryFile.resolve("log.%g"), 256, 2))
                .getMessage().contains("Cannot create persistent log"));
        StorageVerifier.configurePersistentLogging(pattern, 256, 3);
        StorageVerifier.closePersistentLogging();
        StorageVerifier.closePersistentLogging();
        StorageVerifier.configureLogging("INFO");
    }
}
