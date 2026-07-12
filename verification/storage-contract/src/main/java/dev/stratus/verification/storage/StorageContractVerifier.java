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

public final class StorageContractVerifier {
    static final Logger LOGGER = Logger.getLogger(StorageContractVerifier.class.getName());
    private static FileHandler persistentHandler;
    private static final int MULTIPART_PART_SIZE = 5 * 1024 * 1024;
    private static final byte[] ROUND_TRIP_PAYLOAD = "stratus-ceph-verification".getBytes(StandardCharsets.UTF_8);

    private final ObjectStorageClient client;
    private final Clock clock;

    public StorageContractVerifier(ObjectStorageClient client, Clock clock) {
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
        var buckets = client.listBuckets();
        var missing = requiredBuckets.stream().filter(bucket -> !buckets.contains(bucket)).sorted().toList();
        if (!missing.isEmpty()) {
            LOGGER.warning(() -> "Storage contract verification failed; missing required buckets: " + missing);
            checks.add(new VerificationCheck("required-buckets", false, "Missing required buckets: " + String.join(", ", missing)));
            return new VerificationReport(timestamp, false, checks);
        }
        checks.add(VerificationCheck.passed("required-buckets", "Found all " + requiredBuckets.size() + " required buckets"));
        LOGGER.fine("Required bucket check passed");

        var prefix = "verification/" + timestamp.toEpochMilli() + "/";
        var roundTripKey = prefix + "round-trip.txt";
        var multipartKey = prefix + "multipart.bin";
        var cleanupFailures = new ArrayList<String>();
        try {
            client.put(probeBucket, roundTripKey, ROUND_TRIP_PAYLOAD);
            LOGGER.fine(() -> "Uploaded round-trip probe " + roundTripKey);
            var readBack = client.get(probeBucket, roundTripKey);
            if (!Arrays.equals(ROUND_TRIP_PAYLOAD, readBack)) {
                throw new IllegalStateException("read-back content did not match uploaded content");
            }
            checks.add(VerificationCheck.passed("object-round-trip", "PUT and GET content matched"));
            LOGGER.fine("Round-trip content check passed");

            if (client.size(probeBucket, roundTripKey) != ROUND_TRIP_PAYLOAD.length
                    || !client.list(probeBucket, prefix).contains(roundTripKey)) {
                throw new IllegalStateException("HEAD size or prefix listing did not contain the probe object");
            }
            checks.add(VerificationCheck.passed("head-and-list", "HEAD size and prefix listing matched"));
            LOGGER.fine("Object metadata and prefix listing checks passed");

            var multipartPayload = new byte[MULTIPART_PART_SIZE + 1024];
            Arrays.fill(multipartPayload, (byte) 0x5a);
            client.multipartPut(probeBucket, multipartKey, multipartPayload, MULTIPART_PART_SIZE);
            if (client.size(probeBucket, multipartKey) != multipartPayload.length) {
                throw new IllegalStateException("multipart object size did not match uploaded content");
            }
            checks.add(VerificationCheck.passed("multipart-upload", "Multipart upload completed with expected size"));
            LOGGER.fine(() -> "Multipart probe passed for " + multipartKey);
        } catch (RuntimeException exception) {
            var name = checks.size() == 1 ? "object-round-trip" : checks.size() == 2 ? "head-and-list" : "multipart-upload";
            checks.add(VerificationCheck.failed(name, exception));
            LOGGER.warning(() -> "Storage contract check " + name + " failed: " + exception.getMessage());
        } finally {
            delete(probeBucket, roundTripKey, cleanupFailures);
            delete(probeBucket, multipartKey, cleanupFailures);
        }
        checks.add(cleanupFailures.isEmpty()
                ? VerificationCheck.passed("probe-cleanup", "Removed all verification probe objects")
                : new VerificationCheck("probe-cleanup", false, String.join("; ", cleanupFailures)));
        var report = new VerificationReport(timestamp, checks.stream().allMatch(VerificationCheck::passed), checks);
        LOGGER.info(() -> "Storage contract verification completed with success=" + report.success());
        return report;
    }

    private void delete(String bucket, String key, ArrayList<String> failures) {
        try {
            client.delete(bucket, key);
        } catch (RuntimeException exception) {
            var detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            failures.add(key + ": " + detail);
        }
    }
}
