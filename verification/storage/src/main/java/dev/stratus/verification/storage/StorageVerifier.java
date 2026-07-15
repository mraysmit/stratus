// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Implementation of StorageVerifier functionality.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
public final class StorageVerifier {
    static final String CONTRACT_DESCRIPTION =
            "Stratus storage contract verification evidence: success=true means every S3 contract check against Ceph RGW passed";

    // ISO-8601 timestamp with offset, single line per record: the standard JUL
    // SimpleFormatter format property, applied before any formatter exists.
    static final String LOG_FORMAT = "%1$tFT%1$tT.%1$tL%1$tz %4$s %2$s %5$s%6$s%n";

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", LOG_FORMAT);
    }

    static final Logger LOGGER = Logger.getLogger(StorageVerifier.class.getName());
    private static FileHandler persistentHandler;
    private static final int MULTIPART_PART_SIZE = 5 * 1024 * 1024;
    private static final int LARGE_OBJECT_SIZE = 1024 * 1024;
    private static final byte[] ROUND_TRIP_PAYLOAD = "stratus-ceph-verification".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OVERWRITE_PAYLOAD = "stratus-ceph-overwrite".getBytes(StandardCharsets.UTF_8);

    private final ObjectStorageClient client;
    private final Clock clock;

    public StorageVerifier(ObjectStorageClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    static void configureLogging(String configuredLevel) {
        var level = switch (configuredLevel.toUpperCase()) {
            case "DEBUG" -> Level.FINE;
            case "INFO" -> Level.INFO;
            default -> throw new IllegalArgumentException("STRATUS_LOG_LEVEL must be INFO or DEBUG");
        };
        LOGGER.setLevel(level);
        for (var handler : Logger.getLogger("").getHandlers()) {
            handler.setLevel(level);
        }
    }

    static synchronized void configurePersistentLogging(Path pattern, int maximumBytes, int retainedFiles) {
        if (maximumBytes <= 0 || retainedFiles <= 0) {
            throw new IllegalArgumentException("STRATUS_LOG_MAX_BYTES and STRATUS_LOG_FILE_COUNT must be positive integers");
        }
        closePersistentLogging();
        try {
            Files.createDirectories(pattern.toAbsolutePath().getParent());
            persistentHandler = new FileHandler(pattern.toString(), maximumBytes, retainedFiles, true);
            persistentHandler.setFormatter(new SimpleFormatter());
            persistentHandler.setLevel(LOGGER.getLevel());
            LOGGER.addHandler(persistentHandler);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot create persistent log " + pattern, exception);
        }
    }

    static synchronized void closePersistentLogging() {
        if (persistentHandler != null) {
            LOGGER.removeHandler(persistentHandler);
            persistentHandler.flush();
            persistentHandler.close();
            persistentHandler = null;
        }
    }

    public VerificationReport verify(Set<String> requiredBuckets, String probeBucket) {
        var timestamp = Instant.now(clock);
        LOGGER.info(() -> "Starting storage contract verification against probe bucket " + probeBucket);
        LOGGER.fine(() -> "Required buckets: " + requiredBuckets.stream().sorted().toList());
        var checks = new ArrayList<VerificationCheck>();
        try {
            var buckets = client.listBuckets();
            var missing = requiredBuckets.stream().filter(bucket -> !buckets.contains(bucket)).sorted().toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Missing required buckets: " + String.join(", ", missing));
            }
            checks.add(VerificationCheck.passed("required-buckets", "Found all " + requiredBuckets.size() + " required buckets"));
            LOGGER.fine("Required bucket check passed");
        } catch (RuntimeException exception) {
            recordFailure(checks, "required-buckets", exception);
            return new VerificationReport(CONTRACT_DESCRIPTION, timestamp, false, checks);
        }

        var prefix = "verification/" + timestamp.toEpochMilli() + "/";
        var roundTripKey = prefix + "round-trip.txt";
        var zeroByteKey = prefix + "zero-byte.bin";
        var overwriteKey = prefix + "overwrite.txt";
        var specialKey = prefix + "special characters/plus+percent%/unicode-λ.txt";
        var largeKey = prefix + "large-single-put.bin";
        var headAndListKey = prefix + "head-and-list.txt";
        var paginationPrefix = prefix + "pagination/";
        var paginationKeys = java.util.List.of(
                paginationPrefix + "000.txt", paginationPrefix + "001.txt", paginationPrefix + "002.txt");
        var concurrentKeys = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> prefix + "concurrent/" + index + ".bin").toList();
        var multipartKey = prefix + "multipart.bin";
        var probeKeys = new ArrayList<>(java.util.List.of(
                roundTripKey, zeroByteKey, overwriteKey, specialKey, largeKey, headAndListKey, multipartKey));
        probeKeys.addAll(paginationKeys);
        probeKeys.addAll(concurrentKeys);
        var cleanupFailures = new ArrayList<String>();
        try {
            runCheck(checks, "missing-object", "HEAD reported the unused probe key as absent", () -> {
                if (client.exists(probeBucket, roundTripKey)) {
                    throw new IllegalStateException("new verification probe unexpectedly already exists");
                }
            });
            runCheck(checks, "object-round-trip", "PUT and GET content matched", () -> {
                client.put(probeBucket, roundTripKey, ROUND_TRIP_PAYLOAD);
                if (!Arrays.equals(ROUND_TRIP_PAYLOAD, client.get(probeBucket, roundTripKey))) {
                    throw new IllegalStateException("read-back content did not match uploaded content");
                }
            });
            runCheck(checks, "zero-byte-object", "Zero-byte PUT, HEAD, and GET matched", () -> {
                client.put(probeBucket, zeroByteKey, new byte[0]);
                if (client.size(probeBucket, zeroByteKey) != 0 || client.get(probeBucket, zeroByteKey).length != 0) {
                    throw new IllegalStateException("zero-byte object did not retain zero length");
                }
            });
            runCheck(checks, "object-overwrite", "Overwrite replaced the original content", () -> {
                client.put(probeBucket, overwriteKey, ROUND_TRIP_PAYLOAD);
                client.put(probeBucket, overwriteKey, OVERWRITE_PAYLOAD);
                if (!Arrays.equals(OVERWRITE_PAYLOAD, client.get(probeBucket, overwriteKey))) {
                    throw new IllegalStateException("overwrite content did not replace the original object");
                }
            });
            runCheck(checks, "special-character-key", "Encoded path and Unicode key round-tripped", () -> {
                client.put(probeBucket, specialKey, ROUND_TRIP_PAYLOAD);
                if (!Arrays.equals(ROUND_TRIP_PAYLOAD, client.get(probeBucket, specialKey))) {
                    throw new IllegalStateException("special-character key content did not round trip");
                }
            });
            runCheck(checks, "large-single-put", "1 MiB PUT, HEAD, and GET matched", () -> {
                var largePayload = new byte[LARGE_OBJECT_SIZE];
                Arrays.fill(largePayload, (byte) 0x3c);
                client.put(probeBucket, largeKey, largePayload);
                if (client.size(probeBucket, largeKey) != largePayload.length
                        || !Arrays.equals(largePayload, client.get(probeBucket, largeKey))) {
                    throw new IllegalStateException("large single PUT content or size did not match");
                }
            });
            runCheck(checks, "head-and-list", "HEAD size and prefix listing matched", () -> {
                client.put(probeBucket, headAndListKey, OVERWRITE_PAYLOAD);
                if (client.size(probeBucket, headAndListKey) != OVERWRITE_PAYLOAD.length
                        || !client.list(probeBucket, prefix).contains(headAndListKey)) {
                    throw new IllegalStateException("HEAD size or prefix listing did not contain the probe object");
                }
            });
            runCheck(checks, "paginated-list", "All objects were returned across forced two-item pages", () -> {
                paginationKeys.forEach(key -> client.put(probeBucket, key, ROUND_TRIP_PAYLOAD));
                if (!client.list(probeBucket, paginationPrefix).containsAll(paginationKeys)) {
                    throw new IllegalStateException("paginated prefix listing omitted one or more probe objects");
                }
            });
            runCheck(checks, "concurrent-access", "Eight concurrent PUT, GET, and HEAD operations matched", () -> {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = concurrentKeys.stream().map(key -> CompletableFuture.runAsync(() -> {
                        var payload = key.getBytes(StandardCharsets.UTF_8);
                        client.put(probeBucket, key, payload);
                        if (client.size(probeBucket, key) != payload.length
                                || !Arrays.equals(payload, client.get(probeBucket, key))) {
                            throw new IllegalStateException("concurrent object content or size did not match for " + key);
                        }
                    }, executor)).toArray(CompletableFuture[]::new);
                    CompletableFuture.allOf(futures).join();
                }
            });
            runCheck(checks, "multipart-upload", "Multipart upload completed with expected size", () -> {
                var multipartPayload = new byte[MULTIPART_PART_SIZE + 1024];
                Arrays.fill(multipartPayload, (byte) 0x5a);
                client.multipartPut(probeBucket, multipartKey, multipartPayload, MULTIPART_PART_SIZE);
                if (client.size(probeBucket, multipartKey) != multipartPayload.length) {
                    throw new IllegalStateException("multipart object size did not match uploaded content");
                }
            });
        } finally {
            probeKeys.forEach(key -> delete(probeBucket, key, cleanupFailures));
        }
        checks.add(cleanupFailures.isEmpty()
                ? VerificationCheck.passed("probe-cleanup", "Removed all verification probe objects")
                : new VerificationCheck("probe-cleanup", false, String.join("; ", cleanupFailures)));
        var report = new VerificationReport(CONTRACT_DESCRIPTION, timestamp,
                checks.stream().allMatch(VerificationCheck::passed), checks);
        LOGGER.info(() -> "Storage contract verification completed with success=" + report.success());
        return report;
    }

    private static void runCheck(ArrayList<VerificationCheck> checks, String name, String successDetail, Runnable action) {
        LOGGER.fine(() -> "Starting storage contract check " + name);
        try {
            action.run();
            checks.add(VerificationCheck.passed(name, successDetail));
            LOGGER.fine(() -> "Storage contract check " + name + " passed");
        } catch (RuntimeException exception) {
            recordFailure(checks, name, exception);
        }
    }

    private static void recordFailure(ArrayList<VerificationCheck> checks, String name, RuntimeException exception) {
        checks.add(VerificationCheck.failed(name, exception));
        var detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        LOGGER.log(Level.WARNING, "Storage contract check " + name + " failed: " + detail, exception);
    }

    private void delete(String bucket, String key, ArrayList<String> failures) {
        try {
            client.delete(bucket, key);
            if (client.exists(bucket, key)) {
                throw new IllegalStateException("object still exists after DELETE");
            }
            LOGGER.fine(() -> "Deleted verification probe bucket=" + bucket + " key=" + key);
        } catch (RuntimeException exception) {
            var detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            failures.add(key + ": " + detail);
            LOGGER.log(Level.WARNING, "Verification probe cleanup failed bucket=" + bucket + " key=" + key, exception);
        }
    }
}
