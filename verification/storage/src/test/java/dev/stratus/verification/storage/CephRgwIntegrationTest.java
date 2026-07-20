// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The verifier's storage behavior proven against the live local Ceph cluster
 * (platform/ceph/compose-cluster): the full S3 contract, missing-bucket detection, the
 * invalid-credential and cross-identity security negatives, evidence writing,
 * and exit semantics. Requires CEPH_RGW_INTEGRATION=true and the harness
 * environment; nothing here is simulated.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
@Tag("ceph-integration")
class CephRgwIntegrationTest {

    @BeforeEach
    void requireLiveCluster() {
        if (Boolean.getBoolean("ceph.integration.required")) {
            assertTrue(Boolean.parseBoolean(System.getenv("CEPH_RGW_INTEGRATION")),
                    "CEPH_RGW_INTEGRATION=true is required by the selected Maven profile");
        }
        assumeTrue(Boolean.parseBoolean(System.getenv("CEPH_RGW_INTEGRATION")),
                "Set CEPH_RGW_INTEGRATION=true to run against a live Ceph RGW endpoint");
    }

    @Test
    void verifiesLiveCephRgwContractAndWritesEvidence() throws java.io.IOException {
        var evidenceFile = Path.of("target", "live-evidence", "live-contract-report.json");
        Files.deleteIfExists(evidenceFile);
        var environment = liveEnvironment();
        environment.put("STRATUS_EVIDENCE_FILE", evidenceFile.toString());
        environment.put("STRATUS_LOG_LEVEL", "DEBUG");
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        var exit = StorageVerifierMain.run(environment, S3ObjectStorageClient::create,
                new PrintStream(output), new PrintStream(error));

        assertEquals(0, exit, () -> "verifier failed against the live cluster: " + output + error);
        assertTrue(output.toString().contains("\"success\":true"));
        assertEquals("", error.toString());
        var evidence = Files.readString(evidenceFile);
        assertTrue(evidence.startsWith("{\"description\":"),
                "Evidence file must contain only the report JSON: " + evidence);
        assertTrue(evidence.contains("\"multipart-upload\",\"passed\":true"));
        var secret = environment.get("CEPH_RGW_SECRET_KEY");
        assertFalse(evidence.contains(secret), "evidence must never contain the secret key");
        assertFalse(output.toString().contains(secret), "report output must never contain the secret key");
        var completion = Files.readAllLines(Path.of("target/live-logs/storage-verifier.0.log")).stream()
                .filter(line -> line.contains("Storage contract verification completed"))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertTrue(completion.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4} INFO \\S.*"),
                "Log records must be single lines starting with an ISO-8601 offset timestamp: " + completion);
    }

    @Test
    void reportsEveryMissingRequiredBucketAgainstTheLiveCluster() {
        var config = StorageVerifierConfig.from(System.getenv());
        var requiredPlusAbsent = new LinkedHashSet<>(config.requiredBuckets());
        requiredPlusAbsent.add("stratus-missing-bucket-probe");
        try (var client = S3ObjectStorageClient.create(config)) {
            var report = new StorageVerifier(client, Clock.systemUTC())
                    .verify(requiredPlusAbsent, config.probeBucket());

            assertFalse(report.success());
            assertEquals(1, report.checks().size());
            assertTrue(report.checks().getFirst().detail().contains("stratus-missing-bucket-probe"),
                    report.checks().getFirst().detail());
        }
    }

    @Test
    void liveRgwRejectsInvalidCredentials() {
        var environment = liveEnvironment();
        environment.put("STRATUS_VERIFICATION_MODE", "AUTH_FAILURE");
        environment.put("CEPH_RGW_ACCESS_KEY", "deliberately-invalid-access-key");
        environment.put("CEPH_RGW_SECRET_KEY", "deliberately-invalid-secret-key");
        var output = new ByteArrayOutputStream();

        var exit = StorageVerifierMain.run(environment, S3ObjectStorageClient::create,
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exit, output::toString);
        assertTrue(output.toString().contains("invalid-credentials-rejected"));
        assertTrue(output.toString().contains("\"success\":true"));
    }

    @Test
    void liveRgwDeniesListingTheSeparateOwnersBucket() {
        var environment = liveEnvironment();
        environment.put("STRATUS_VERIFICATION_MODE", "ACCESS_DENIED");
        environment.putIfAbsent("CEPH_RGW_DENIED_BUCKET", "stratus-denied");
        var output = new ByteArrayOutputStream();

        var exit = StorageVerifierMain.run(environment, S3ObjectStorageClient::create,
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(0, exit, output::toString);
        assertTrue(output.toString().contains("cross-identity-access-denied"));
        assertTrue(output.toString().contains("\"success\":true"));
    }

    @Test
    void reportsFailedInvalidCredentialExpectationWhenCredentialsAreValid() {
        var environment = liveEnvironment();
        environment.put("STRATUS_VERIFICATION_MODE", "AUTH_FAILURE");
        var output = new ByteArrayOutputStream();

        var exit = StorageVerifierMain.run(environment, S3ObjectStorageClient::create,
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(2, exit, output::toString);
        assertTrue(output.toString().contains("\"success\":false"));
        assertTrue(output.toString().contains("unexpectedly succeeded"));
    }

    @Test
    void exposesTheFivePlatformBucketsFromTheArchitecture() {
        try (var client = S3ObjectStorageClient.create(StorageVerifierConfig.from(System.getenv()))) {
            var buckets = client.listBuckets();
            assertTrue(buckets.containsAll(Set.of("stratus-landing", "stratus-bronze", "stratus-silver",
                    "stratus-gold", "stratus-platform")), buckets.toString());
        }
    }

    @Test
    void landingZoneKeyConventionRoundTripsAndListsBySourcePrefix() {
        // Architecture 4.1: s3://stratus-landing/<source-system>/<dataset>/<ingest-date>/<file-name>
        var key = "crm/customers/2026-07-16/customers.csv";
        var payload = "id,name\n1,stratus\n".getBytes(StandardCharsets.UTF_8);
        try (var client = S3ObjectStorageClient.create(StorageVerifierConfig.from(System.getenv()))) {
            client.put("stratus-landing", key, payload);
            try {
                assertTrue(Arrays.equals(payload, client.get("stratus-landing", key)));
                assertTrue(client.list("stratus-landing", "crm/customers/").contains(key));
            } finally {
                client.delete("stratus-landing", key);
            }
            assertFalse(client.exists("stratus-landing", key));
        }
    }

    @Test
    void refusesPlaintextHttpAtBothConfigAndEndpointLayers() {
        var plaintextEndpoint = System.getenv("CEPH_RGW_ENDPOINT").replace("https://", "http://");
        var withoutOverride = new HashMap<>(System.getenv());
        withoutOverride.put("CEPH_RGW_ENDPOINT", plaintextEndpoint);
        withoutOverride.remove("CEPH_RGW_ALLOW_HTTP");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> StorageVerifierConfig.from(withoutOverride)).getMessage().contains("HTTPS"));

        // Even with the disposable-development override, the endpoint itself only serves TLS.
        var withOverride = new HashMap<>(withoutOverride);
        withOverride.put("CEPH_RGW_ALLOW_HTTP", "true");
        try (var client = S3ObjectStorageClient.create(StorageVerifierConfig.from(withOverride))) {
            assertThrows(RuntimeException.class, client::listBuckets);
        }
    }

    @Test
    void deniedOwnerCredentialsCannotListAnyPlatformBucket() {
        var deniedAccessKey = System.getenv("CEPH_DENIED_ACCESS_KEY");
        var deniedSecretKey = System.getenv("CEPH_DENIED_SECRET_KEY");
        assumeTrue(deniedAccessKey != null && deniedSecretKey != null,
                "Set CEPH_DENIED_ACCESS_KEY and CEPH_DENIED_SECRET_KEY to prove credential isolation");
        var base = StorageVerifierConfig.from(System.getenv());
        var denied = new StorageVerifierConfig(base.endpoint(), deniedAccessKey, deniedSecretKey,
                base.pathStyleAccess(), base.requiredBuckets(), base.probeBucket(),
                base.connectionTimeout(), base.socketTimeout(), base.apiCallAttemptTimeout(), base.apiCallTimeout());
        try (var client = S3ObjectStorageClient.create(denied)) {
            for (var bucket : base.requiredBuckets()) {
                client.verifyListDenied(bucket);
            }
        }
    }

    @Test
    void abortedMultipartUploadLeavesNoObjectAndNoPendingUpload() {
        var config = StorageVerifierConfig.from(System.getenv());
        var bucket = config.probeBucket();
        var key = "verification/multipart-abort/probe.bin";
        try (var s3 = rawClient(config)) {
            var created = s3.createMultipartUpload(request -> request.bucket(bucket).key(key));
            s3.uploadPart(request -> request.bucket(bucket).key(key)
                            .uploadId(created.uploadId()).partNumber(1).contentLength(1024L),
                    RequestBody.fromBytes(new byte[1024]));
            assertTrue(s3.listMultipartUploads(request -> request.bucket(bucket).prefix(key)).uploads().stream()
                    .anyMatch(upload -> upload.uploadId().equals(created.uploadId())));

            s3.abortMultipartUpload(request -> request.bucket(bucket).key(key).uploadId(created.uploadId()));

            assertTrue(s3.listMultipartUploads(request -> request.bucket(bucket).prefix(key)).uploads().isEmpty(),
                    "aborted upload must not remain pending");
            assertEquals(404, assertThrows(S3Exception.class,
                    () -> s3.headObject(request -> request.bucket(bucket).key(key))).statusCode(),
                    "aborted upload must not materialize an object");
        }
    }

    @Test
    void readAfterWriteAndListAfterWriteAreImmediatelyConsistent() {
        var config = StorageVerifierConfig.from(System.getenv());
        var prefix = "verification/consistency/";
        try (var client = S3ObjectStorageClient.create(config)) {
            try {
                for (var index = 0; index < 5; index++) {
                    var key = prefix + index + ".txt";
                    var payload = ("consistency-" + index).getBytes(StandardCharsets.UTF_8);
                    client.put(config.probeBucket(), key, payload);
                    assertTrue(Arrays.equals(payload, client.get(config.probeBucket(), key)),
                            "read-after-write must return the written content for " + key);
                    assertTrue(client.exists(config.probeBucket(), key));
                    assertTrue(client.list(config.probeBucket(), prefix).contains(key),
                            "list-after-write must expose " + key);
                }
            } finally {
                for (var index = 0; index < 5; index++) {
                    client.delete(config.probeBucket(), prefix + index + ".txt");
                }
            }
            assertFalse(client.exists(config.probeBucket(), prefix + "0.txt"));
        }
    }

    @Test
    void listingsRemainCompleteAcrossManyForcedPages() {
        var config = StorageVerifierConfig.from(System.getenv());
        var prefix = "verification/pagination-stress/";
        var objectCount = 30;
        try (var client = S3ObjectStorageClient.create(config)) {
            try {
                for (var index = 0; index < objectCount; index++) {
                    client.put(config.probeBucket(), prefix + "%03d.txt".formatted(index),
                            new byte[]{(byte) index});
                }
                // The production client forces two-item pages, so this listing
                // exercises fifteen real continuation-token exchanges.
                var listed = client.list(config.probeBucket(), prefix);
                assertEquals(objectCount, listed.size(), listed.toString());
                for (var index = 0; index < objectCount; index++) {
                    assertTrue(listed.contains(prefix + "%03d.txt".formatted(index)));
                }
            } finally {
                for (var index = 0; index < objectCount; index++) {
                    client.delete(config.probeBucket(), prefix + "%03d.txt".formatted(index));
                }
            }
            assertTrue(client.list(config.probeBucket(), prefix).isEmpty());
        }
    }

    /** The raw SDK client mirrors production settings for storage-behavior checks the verifier API does not expose. */
    private static S3Client rawClient(StorageVerifierConfig config) {
        return S3Client.builder()
                .endpointOverride(config.endpoint())
                .region(Region.of("default"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.pathStyleAccess())
                        .build())
                .build();
    }

    private static Map<String, String> liveEnvironment() {
        var environment = new HashMap<>(System.getenv());
        environment.put("STRATUS_LOG_FILE", "target/live-logs/storage-verifier.%g.log");
        environment.putIfAbsent("STRATUS_LOG_MAX_BYTES", "10485760");
        environment.putIfAbsent("STRATUS_LOG_FILE_COUNT", "2");
        return environment;
    }
}
