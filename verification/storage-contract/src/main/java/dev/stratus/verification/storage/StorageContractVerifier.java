package dev.stratus.verification.storage;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

public final class StorageContractVerifier {
    private static final int MULTIPART_PART_SIZE = 5 * 1024 * 1024;
    private static final byte[] ROUND_TRIP_PAYLOAD = "stratus-ceph-verification".getBytes(StandardCharsets.UTF_8);

    private final ObjectStorageClient client;
    private final Clock clock;

    public StorageContractVerifier(ObjectStorageClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public VerificationReport verify(Set<String> requiredBuckets, String probeBucket) {
        var timestamp = Instant.now(clock);
        var checks = new ArrayList<VerificationCheck>();
        var buckets = client.listBuckets();
        var missing = requiredBuckets.stream().filter(bucket -> !buckets.contains(bucket)).sorted().toList();
        if (!missing.isEmpty()) {
            checks.add(new VerificationCheck("required-buckets", false, "Missing required buckets: " + String.join(", ", missing)));
            return new VerificationReport(timestamp, false, checks);
        }
        checks.add(VerificationCheck.passed("required-buckets", "Found all " + requiredBuckets.size() + " required buckets"));

        var prefix = "verification/" + timestamp.toEpochMilli() + "/";
        var roundTripKey = prefix + "round-trip.txt";
        var multipartKey = prefix + "multipart.bin";
        var cleanupFailures = new ArrayList<String>();
        try {
            client.put(probeBucket, roundTripKey, ROUND_TRIP_PAYLOAD);
            var readBack = client.get(probeBucket, roundTripKey);
            if (!Arrays.equals(ROUND_TRIP_PAYLOAD, readBack)) {
                throw new IllegalStateException("read-back content did not match uploaded content");
            }
            checks.add(VerificationCheck.passed("object-round-trip", "PUT and GET content matched"));

            if (client.size(probeBucket, roundTripKey) != ROUND_TRIP_PAYLOAD.length
                    || !client.list(probeBucket, prefix).contains(roundTripKey)) {
                throw new IllegalStateException("HEAD size or prefix listing did not contain the probe object");
            }
            checks.add(VerificationCheck.passed("head-and-list", "HEAD size and prefix listing matched"));

            var multipartPayload = new byte[MULTIPART_PART_SIZE + 1024];
            Arrays.fill(multipartPayload, (byte) 0x5a);
            client.multipartPut(probeBucket, multipartKey, multipartPayload, MULTIPART_PART_SIZE);
            if (client.size(probeBucket, multipartKey) != multipartPayload.length) {
                throw new IllegalStateException("multipart object size did not match uploaded content");
            }
            checks.add(VerificationCheck.passed("multipart-upload", "Multipart upload completed with expected size"));
        } catch (RuntimeException exception) {
            var name = checks.size() == 1 ? "object-round-trip" : checks.size() == 2 ? "head-and-list" : "multipart-upload";
            checks.add(VerificationCheck.failed(name, exception));
        } finally {
            delete(probeBucket, roundTripKey, cleanupFailures);
            delete(probeBucket, multipartKey, cleanupFailures);
        }
        checks.add(cleanupFailures.isEmpty()
                ? VerificationCheck.passed("probe-cleanup", "Removed all verification probe objects")
                : new VerificationCheck("probe-cleanup", false, String.join("; ", cleanupFailures)));
        return new VerificationReport(timestamp, checks.stream().allMatch(VerificationCheck::passed), checks);
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
