// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.ceph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The Ceph RGW Admin Operations REST API proven against a live cluster through
 * a scoped, read-only identity.
 *
 * <p>"Admin Operations API" is Ceph's own name for the {@code /admin} REST
 * surface; it authenticates with the same AWS Signature Version 4 scheme as the
 * S3 data API. The identity used here holds only {@code buckets=read} and
 * {@code usage=read} caps. Two of the tests below exist specifically to prove
 * that boundary holds in both directions: an identity with no caps is refused,
 * and this identity cannot reach the user endpoint that would expose other
 * identities' keys.
 *
 * <p>JSON is parsed with SnakeYAML because JSON is a subset of YAML, which
 * avoids adding a dependency for test-only response inspection.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-30
 * @version 1.0.0
 */
@Tag("ceph-integration")
final class CephAdminOpsRestConformanceTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STATS_TIMEOUT = Duration.ofSeconds(15);
    private static final String REGION = "default";
    private static final String SERVICE = "s3";

    private String bucket;
    private String objectKey;
    private SignatureV4RestClient adminReader;
    private SignatureV4RestClient dataIdentity;

    @BeforeEach
    void requireLiveCluster() {
        if (Boolean.getBoolean("ceph.integration.required")) {
            assertTrue(Boolean.parseBoolean(System.getenv("CEPH_RGW_INTEGRATION")),
                "CEPH_RGW_INTEGRATION=true is required by the selected Maven profile");
            for (String name : Set.of(
                    "CEPH_RGW_ENDPOINT",
                    "CEPH_RGW_ACCESS_KEY",
                    "CEPH_RGW_SECRET_KEY",
                    "CEPH_RGW_PROBE_BUCKET",
                    "CEPH_ADMIN_OPS_ACCESS_KEY",
                    "CEPH_ADMIN_OPS_SECRET_KEY")) {
                assertTrue(System.getenv(name) != null && !System.getenv(name).isBlank(),
                    name + " is required by the selected Maven profile");
            }
        }
        assumeTrue(Boolean.parseBoolean(System.getenv("CEPH_RGW_INTEGRATION")),
            "Set CEPH_RGW_INTEGRATION=true to run against a live Ceph RGW endpoint");

        Map<String, String> environment = System.getenv();
        URI endpoint = URI.create(environment.get("CEPH_RGW_ENDPOINT"));
        bucket = environment.get("CEPH_RGW_PROBE_BUCKET");
        objectKey = "verification/admin-ops/" + java.util.UUID.randomUUID() + ".bin";
        adminReader = new SignatureV4RestClient(endpoint,
            environment.get("CEPH_ADMIN_OPS_ACCESS_KEY"), environment.get("CEPH_ADMIN_OPS_SECRET_KEY"),
            REGION, SERVICE, CONNECT_TIMEOUT);
        dataIdentity = new SignatureV4RestClient(endpoint,
            environment.get("CEPH_RGW_ACCESS_KEY"), environment.get("CEPH_RGW_SECRET_KEY"),
            REGION, SERVICE, CONNECT_TIMEOUT);
    }

    @AfterEach
    void removeProbeObjectAndReleaseTheClients() {
        try {
            if (dataIdentity != null) {
                dataIdentity.send("DELETE", "/" + bucket + "/" + objectKey, Map.of(), null, REQUEST_TIMEOUT);
            }
        } finally {
            if (dataIdentity != null) {
                dataIdentity.close();
            }
            if (adminReader != null) {
                adminReader.close();
            }
        }
    }

    @Test
    void listsThePlatformBucketsOverTheAdminOperationsApi() {
        HttpResponse<byte[]> response = adminReader.send("GET", "/admin/bucket",
            SignatureV4RestClient.query("format", "json"), null, REQUEST_TIMEOUT);

        assertEquals(200, response.statusCode(), () -> "the admin bucket listing was refused: " + body(response));
        Object parsed = new Yaml().load(body(response));
        assertTrue(parsed instanceof List, () -> "the admin bucket listing must be a JSON array: " + body(response));
        List<?> buckets = (List<?>) parsed;
        assertTrue(buckets.contains(bucket),
            () -> "the admin API must report the probe bucket " + bucket + ", but listed: " + buckets);
    }

    @Test
    void reportsOwnerAndUsageStatisticsForTheProbeBucket() {
        HttpResponse<byte[]> response = adminReader.send("GET", "/admin/bucket",
            SignatureV4RestClient.query("format", "json", "bucket", bucket, "stats", "True"),
            null, REQUEST_TIMEOUT);

        assertEquals(200, response.statusCode(), () -> "bucket statistics were refused: " + body(response));
        Map<?, ?> stats = asMap(new Yaml().load(body(response)));
        assertEquals(bucket, stats.get("bucket"), () -> "unexpected bucket in the response: " + stats);
        assertNotNull(stats.get("owner"), () -> "the admin API must report the bucket owner: " + stats);
        assertNotNull(stats.get("usage"), () -> "the admin API must report bucket usage: " + stats);
    }

    @Test
    void bucketStatisticsReflectAnObjectWrittenOverTheDataApi() {
        long before = objectCount();
        assertEquals(200, dataIdentity.send("PUT", "/" + bucket + "/" + objectKey, Map.of(),
            "stratus admin-ops reflection probe".getBytes(StandardCharsets.UTF_8), REQUEST_TIMEOUT).statusCode());

        // RGW updates bucket-index statistics on write, but the admin API reads them
        // through a separate path. A bounded wait tolerates that real asynchrony
        // rather than assuming an instant that the product does not promise.
        long deadline = System.nanoTime() + STATS_TIMEOUT.toNanos();
        long after = objectCount();
        while (after <= before && System.nanoTime() < deadline) {
            after = objectCount();
        }
        assertTrue(after > before, () ->
            "the admin API object count must reflect an object written over the data API: before=" + before
                + " after=" + objectCount());
    }

    @Test
    void refusesAnIdentityThatHoldsNoAdministrativeCaps() {
        HttpResponse<byte[]> response = dataIdentity.send("GET", "/admin/bucket",
            SignatureV4RestClient.query("format", "json"), null, REQUEST_TIMEOUT);

        assertEquals(403, response.statusCode(), () ->
            "the storage identity holds no caps and must not reach the Admin Operations API: " + body(response));
        assertTrue(body(response).contains("AccessDenied"),
            () -> "RGW must refuse an uncapped identity explicitly: " + body(response));
    }

    @Test
    void scopedCapsCannotReachTheEndpointThatWouldExposeIdentityKeys() {
        HttpResponse<byte[]> response = adminReader.send("GET", "/admin/user",
            SignatureV4RestClient.query("format", "json", "uid", System.getenv("CEPH_DEMO_UID") == null
                ? "stratus-verifier" : System.getenv("CEPH_DEMO_UID")),
            null, REQUEST_TIMEOUT);

        assertEquals(403, response.statusCode(), () ->
            "buckets/usage caps must not grant user access: " + body(response));
        assertFalse(body(response).contains(System.getenv("CEPH_RGW_SECRET_KEY")),
            "a refused user request must never echo an identity's secret key");
    }

    private long objectCount() {
        HttpResponse<byte[]> response = adminReader.send("GET", "/admin/bucket",
            SignatureV4RestClient.query("format", "json", "bucket", bucket, "stats", "True"),
            null, REQUEST_TIMEOUT);
        assertEquals(200, response.statusCode(), () -> "bucket statistics were refused: " + body(response));
        Map<?, ?> usage = asMap(asMap(new Yaml().load(body(response))).get("usage"));
        long total = 0;
        for (Object category : usage.values()) {
            Object objects = asMap(category).get("num_objects");
            if (objects instanceof Number count) {
                total += count.longValue();
            }
        }
        return total;
    }

    private static Map<?, ?> asMap(Object value) {
        assertTrue(value instanceof Map, () -> "expected a JSON object but got: " + value);
        return (Map<?, ?>) value;
    }

    private static String body(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
