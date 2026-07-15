// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

import java.time.Clock;
import java.io.PrintStream;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.nio.file.Path;
import java.util.logging.Level;
import java.time.Instant;
import java.util.List;

/**
 * Implementation of StorageVerifierMain functionality.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
public final class StorageVerifierMain {
    static Supplier<Map<String, String>> environment = System::getenv;
    static Function<StorageVerifierConfig, ObjectStorageClient> clientFactory = S3ObjectStorageClient::create;
    static PrintStream output = System.out;
    static PrintStream error = System.err;
    static IntConsumer exit = System::exit;

    private StorageVerifierMain() { }

    public static void main(String[] args) {
        exit.accept(run(environment.get(), clientFactory, output, error));
    }

    static int run(Map<String, String> environment, Function<StorageVerifierConfig, ObjectStorageClient> clientFactory,
                   PrintStream output, PrintStream error) {
        try {
            StorageVerifier.configureLogging(environment.getOrDefault("STRATUS_LOG_LEVEL", "INFO"));
            StorageVerifier.configurePersistentLogging(
                    Path.of(environment.getOrDefault("STRATUS_LOG_FILE", "logs/storage-verifier.%g.log")),
                    Integer.parseInt(environment.getOrDefault("STRATUS_LOG_MAX_BYTES", "10485760")),
                    Integer.parseInt(environment.getOrDefault("STRATUS_LOG_FILE_COUNT", "5")));
            var config = StorageVerifierConfig.from(environment);
            try (var client = clientFactory.apply(config)) {
                var clock = Clock.systemUTC();
                var report = switch (environment.getOrDefault("STRATUS_VERIFICATION_MODE", "CONTRACT")) {
                    case "CONTRACT" -> new StorageVerifier(client, clock)
                            .verify(config.requiredBuckets(), config.probeBucket());
                    case "AUTH_FAILURE" -> negativeReport(clock,
                            "Stratus negative security evidence (invalid credentials): success=true means "
                                    + "Ceph RGW rejected the deliberately invalid credentials",
                            "invalid-credentials-rejected",
                            "Ceph RGW rejected the deliberately invalid credentials", client::verifyCredentialsRejected);
                    case "ACCESS_DENIED" -> negativeReport(clock,
                            "Stratus negative security evidence (cross-identity denial): success=true means "
                                    + "Ceph RGW denied the verifier access to a bucket owned by a separate identity",
                            "cross-identity-access-denied",
                            "Ceph RGW denied listing a bucket owned by a separate identity",
                            () -> client.verifyListDenied(requireEnvironment(environment, "CEPH_RGW_DENIED_BUCKET")));
                    default -> throw new IllegalArgumentException(
                            "STRATUS_VERIFICATION_MODE must be CONTRACT, AUTH_FAILURE, or ACCESS_DENIED");
                };
                output.println(report.toJson());
                var evidenceFile = environment.getOrDefault("STRATUS_EVIDENCE_FILE", "");
                if (!evidenceFile.isEmpty()) {
                    writeEvidence(Path.of(evidenceFile), report.toJson());
                }
                return report.success() ? 0 : 2;
            }
        } catch (IllegalArgumentException exception) {
            StorageVerifier.LOGGER.log(Level.SEVERE, "Storage verifier configuration failed", exception);
            error.println("Configuration error: " + exception.getMessage());
            return 64;
        } catch (RuntimeException exception) {
            StorageVerifier.LOGGER.log(Level.SEVERE, "Storage verifier execution failed", exception);
            error.println("Verification error: " + exception.getMessage());
            return 2;
        } finally {
            StorageVerifier.closePersistentLogging();
        }
    }

    private static VerificationReport negativeReport(
            Clock clock, String description, String name, String successDetail, Runnable action) {
        var timestamp = Instant.now(clock);
        try {
            action.run();
            return new VerificationReport(description, timestamp, true, List.of(VerificationCheck.passed(name, successDetail)));
        } catch (RuntimeException exception) {
            StorageVerifier.LOGGER.log(Level.WARNING, "Negative storage check " + name + " failed", exception);
            return new VerificationReport(description, timestamp, false, List.of(VerificationCheck.failed(name, exception)));
        }
    }

    private static void writeEvidence(Path evidenceFile, String reportJson) {
        try {
            java.nio.file.Files.createDirectories(evidenceFile.toAbsolutePath().getParent());
            java.nio.file.Files.writeString(evidenceFile, reportJson + System.lineSeparator());
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException("Cannot write evidence file " + evidenceFile, exception);
        }
    }

    private static String requireEnvironment(Map<String, String> environment, String name) {
        var value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
