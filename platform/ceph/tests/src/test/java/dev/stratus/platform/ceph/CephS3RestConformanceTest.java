// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.ceph;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Stratus object-storage data contract proven over raw AWS Signature
 * Version 4 REST against a live Ceph RGW endpoint, with no AWS SDK in the call
 * path.
 *
 * <p>{@code CephRgwConformanceTest} proves the same storage semantics through the
 * SDK. This class exists because the SDK can mask a wire-level defect: a
 * signing, header, payload-hash, or path-style routing change in RGW would be
 * absorbed by the SDK's own compatibility handling and never surface. Here the
 * request bytes are constructed and signed directly, so what is asserted is the
 * HTTP protocol behavior of the product itself.
 *
 * <p>The endpoint, credentials, and probe bucket come from the environment, so
 * the class runs unchanged against any Ceph RGW deployment.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-30
 * @version 1.0.0
 */
@Tag("ceph-integration")
final class CephS3RestConformanceTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String REGION = "default";
    private static final String SERVICE = "s3";
    private static final String DATASET = "customer-master";
    private static final String INITIAL_VERSION = "raw-v1";
    private static final String REVISED_VERSION = "corrected-v2";
    private static final String CUSTOMER_HEADER =
        "customer_id,full_name,email,country,segment,status,annual_revenue,currency,created_at,updated_at,source_system";

    private String bucket;
    private String objectKey;
    private SignatureV4RestClient client;

    @BeforeEach
    void requireLiveCluster() {
        if (Boolean.getBoolean("ceph.integration.required")) {
            assertTrue(Boolean.parseBoolean(System.getenv("CEPH_RGW_INTEGRATION")),
                "CEPH_RGW_INTEGRATION=true is required by the selected Maven profile");
            for (String name : Set.of(
                    "CEPH_RGW_ENDPOINT",
                    "CEPH_RGW_ACCESS_KEY",
                    "CEPH_RGW_SECRET_KEY",
                    "CEPH_RGW_PROBE_BUCKET")) {
                assertTrue(System.getenv(name) != null && !System.getenv(name).isBlank(),
                    name + " is required by the selected Maven profile");
            }
            // Virtual-host addressing needs wildcard DNS for the endpoint domain, which
            // an on-premises deployment does not necessarily publish. Fail with the
            // reason rather than skipping a selected live profile.
            assertFalse("false".equalsIgnoreCase(String.valueOf(System.getenv("S3_PATH_STYLE_ACCESS"))),
                "S3_PATH_STYLE_ACCESS=false is unsupported by the raw REST conformance test: "
                    + "virtual-host addressing requires wildcard DNS for the endpoint domain");
        }
        assumeTrue(Boolean.parseBoolean(System.getenv("CEPH_RGW_INTEGRATION")),
            "Set CEPH_RGW_INTEGRATION=true to run against a live Ceph RGW endpoint");

        Map<String, String> environment = System.getenv();
        bucket = environment.get("CEPH_RGW_PROBE_BUCKET");
        objectKey = "verification/business/customers/" + java.util.UUID.randomUUID() + "/customers.csv";
        client = new SignatureV4RestClient(
            URI.create(environment.get("CEPH_RGW_ENDPOINT")),
            environment.get("CEPH_RGW_ACCESS_KEY"),
            environment.get("CEPH_RGW_SECRET_KEY"),
            REGION, SERVICE, CONNECT_TIMEOUT);
    }

    @AfterEach
    void removeProbeObjectAndReleaseTheClient() {
        if (client == null) {
            return;
        }
        try {
            // Cleanup is unconditional: a failed assertion must not leave a probe
            // object behind for the next run to trip over.
            client.sendForOperation("cleanup-object", "DELETE", "/" + bucket + "/" + objectKey,
                Map.of(), null, REQUEST_TIMEOUT);
        } finally {
            client.close();
        }
    }

    @Test
    void writesRewritesReadsListsAndDeletesTheCustomerDatasetOverSignedRest() {
        byte[] initial = fixture("customer-master-v1.csv");
        byte[] revised = fixture("customer-master-v2.csv");
        DatasetSummary initialSummary = summarize(initial);
        DatasetSummary revisedSummary = summarize(revised);
        assertEquals(new DatasetSummary(10, 9, 1,
            List.of("AE", "DE", "ES", "FR", "GB", "JP", "SE", "SG", "US")), initialSummary);
        assertEquals(new DatasetSummary(10, 10, 0,
            List.of("AE", "CA", "DE", "ES", "FR", "GB", "JP", "SE", "SG", "US")), revisedSummary);
        assertFalse(java.util.Arrays.equals(initial, revised), "the correction must materially rewrite the dataset");

        String path = "/" + bucket + "/" + objectKey;
        String resource = "bucket=" + bucket + " key=" + objectKey;

        HttpResponse<byte[]> write = client.send("PUT", path, Map.of(), initial, REQUEST_TIMEOUT);
        assertEquals(200, write.statusCode(), () -> "initial customer dataset write rejected: " + body(write));
        String initialEtag = write.headers().firstValue("ETag")
            .orElseThrow(() -> new AssertionError("RGW must return an ETag for the initial customer dataset"));
        logDataset("write-confirmed", INITIAL_VERSION, resource, initialSummary, initial);

        HttpResponse<byte[]> initialRead = client.send("GET", path, Map.of(), null, REQUEST_TIMEOUT);
        assertEquals(200, initialRead.statusCode(), () -> "initial customer dataset read rejected: "
            + body(initialRead));
        assertArrayEquals(initial, initialRead.body(), "the initial customer dataset must read back byte-for-byte");
        assertEquals(String.valueOf(initial.length),
            initialRead.headers().firstValue("Content-Length").orElse("absent"));
        assertEquals(initialEtag, initialRead.headers().firstValue("ETag").orElse("absent"));
        logDataset("read-after-write-confirmed", INITIAL_VERSION, resource, initialSummary, initialRead.body());

        HttpResponse<byte[]> rewrite = client.sendForOperation("rewrite-object", "PUT", path, Map.of(),
            revised, REQUEST_TIMEOUT);
        assertEquals(200, rewrite.statusCode(), () -> "corrected customer dataset rewrite rejected: "
            + body(rewrite));
        String revisedEtag = rewrite.headers().firstValue("ETag")
            .orElseThrow(() -> new AssertionError("RGW must return an ETag for the corrected customer dataset"));
        assertFalse(initialEtag.equals(revisedEtag), "rewriting different customer data must change the ETag");
        logDataset("rewrite-confirmed", REVISED_VERSION, resource, revisedSummary, revised);

        HttpResponse<byte[]> revisedRead = client.send("GET", path, Map.of(), null, REQUEST_TIMEOUT);
        assertEquals(200, revisedRead.statusCode(), () -> "corrected customer dataset read rejected: "
            + body(revisedRead));
        assertArrayEquals(revised, revisedRead.body(), "the rewritten customer dataset must read back byte-for-byte");
        assertEquals(revisedEtag, revisedRead.headers().firstValue("ETag").orElse("absent"));
        logDataset("read-after-rewrite-confirmed", REVISED_VERSION, resource, revisedSummary, revisedRead.body());

        String prefix = objectKey.substring(0, objectKey.lastIndexOf('/') + 1);
        HttpResponse<byte[]> listing = client.send("GET", "/" + bucket,
            SignatureV4RestClient.query("list-type", "2", "prefix", prefix), null, REQUEST_TIMEOUT);
        assertEquals(200, listing.statusCode(), () -> "customer dataset listing rejected: " + body(listing));
        assertTrue(body(listing).contains("<Key>" + objectKey + "</Key>"),
            () -> "the landing prefix must contain the customer dataset: " + body(listing));
        logDataset("listing-confirmed", REVISED_VERSION, resource, revisedSummary, revised);

        HttpResponse<byte[]> delete = client.send("DELETE", path, Map.of(), null, REQUEST_TIMEOUT);
        assertEquals(204, delete.statusCode(), () -> "customer dataset delete rejected: " + body(delete));
        logDataset("delete-confirmed", REVISED_VERSION, resource, revisedSummary, revised);

        HttpResponse<byte[]> afterDelete = client.send("GET", path, Map.of(), null, REQUEST_TIMEOUT);
        assertEquals(404, afterDelete.statusCode(), () ->
            "the deleted customer dataset must no longer be readable: " + body(afterDelete));
        logDataset("post-delete-absence-confirmed", REVISED_VERSION, resource, revisedSummary, revised);
    }

    @Test
    void listsTheWrittenObjectByPrefixOverSignedRest() {
        byte[] content = fixture("customer-master-v1.csv");
        assertEquals(200, client.send("PUT", "/" + bucket + "/" + objectKey, Map.of(), content, REQUEST_TIMEOUT)
            .statusCode());

        String prefix = objectKey.substring(0, objectKey.lastIndexOf('/') + 1);
        HttpResponse<byte[]> listing = client.send("GET", "/" + bucket,
            SignatureV4RestClient.query("list-type", "2", "prefix", prefix), null, REQUEST_TIMEOUT);

        assertEquals(200, listing.statusCode(), () -> "listing rejected: " + body(listing));
        String xml = body(listing);
        assertTrue(xml.contains("<Key>" + objectKey + "</Key>"),
            () -> "the listing must report the written key under its prefix: " + xml);
        assertTrue(xml.contains("<Name>" + bucket + "</Name>"),
            () -> "the listing must identify the bucket it enumerated: " + xml);
    }

    @Test
    void rejectsATamperedSignatureWithoutEchoingTheSecret() {
        HttpResponse<byte[]> response = client.sendWithAuthorization("GET", "/" + bucket, Map.of(),
            "AWS4-HMAC-SHA256 Credential=" + System.getenv("CEPH_RGW_ACCESS_KEY")
                + "/20260730/" + REGION + "/" + SERVICE + "/aws4_request"
                + ", SignedHeaders=host;x-amz-content-sha256;x-amz-date"
                + ", Signature=" + "0".repeat(64),
            REQUEST_TIMEOUT);

        assertEquals(403, response.statusCode(), () -> "a forged signature must be refused: " + body(response));
        String xml = body(response);
        assertTrue(xml.contains("SignatureDoesNotMatch") || xml.contains("AccessDenied"),
            () -> "RGW must name the signature failure: " + xml);
        assertFalse(xml.contains(System.getenv("CEPH_RGW_SECRET_KEY")),
            "an error body must never echo the secret key");
    }

    @Test
    void rejectsAnUnsignedAnonymousRequest() {
        HttpResponse<byte[]> response = client.sendWithAuthorization("GET", "/" + bucket, Map.of(), null,
            REQUEST_TIMEOUT);

        assertEquals(403, response.statusCode(),
            () -> "an unauthenticated bucket listing must be refused: " + body(response));
        assertTrue(body(response).contains("AccessDenied"),
            () -> "RGW must refuse anonymous access explicitly: " + body(response));
    }

    @Test
    void rejectsAnUnknownAccessKey() {
        HttpResponse<byte[]> response = client.sendWithAuthorization("GET", "/" + bucket, Map.of(),
            "AWS4-HMAC-SHA256 Credential=stratus-no-such-key/20260730/" + REGION + "/" + SERVICE + "/aws4_request"
                + ", SignedHeaders=host;x-amz-content-sha256;x-amz-date"
                + ", Signature=" + "0".repeat(64),
            REQUEST_TIMEOUT);

        assertEquals(403, response.statusCode(),
            () -> "an unknown access key must be refused: " + body(response));
        assertTrue(body(response).contains("InvalidAccessKeyId") || body(response).contains("AccessDenied"),
            () -> "RGW must reject an identity it does not know: " + body(response));
    }

    private static String body(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static byte[] fixture(String name) {
        try (var input = CephS3RestConformanceTest.class.getResourceAsStream("/datasets/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing business dataset fixture /datasets/" + name);
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read business dataset fixture " + name, e);
        }
    }

    private static DatasetSummary summarize(byte[] data) {
        List<String> lines = new String(data, StandardCharsets.UTF_8).lines()
            .filter(line -> !line.isBlank()).toList();
        assertFalse(lines.isEmpty(), "the customer dataset must have a header");
        assertEquals(CUSTOMER_HEADER, lines.getFirst());
        Set<String> customerIds = new HashSet<>();
        Set<String> countries = new TreeSet<>();
        int missingEmails = 0;
        for (String row : lines.subList(1, lines.size())) {
            String[] columns = row.split(",", -1);
            assertEquals(11, columns.length, () -> "malformed customer row: " + row);
            assertFalse(columns[0].isBlank(), () -> "customer_id is required: " + row);
            assertFalse(columns[1].isBlank(), () -> "full_name is required: " + row);
            customerIds.add(columns[0]);
            countries.add(columns[3]);
            if (columns[2].isBlank()) {
                missingEmails++;
            }
        }
        return new DatasetSummary(lines.size() - 1, customerIds.size(), missingEmails,
            List.copyOf(countries));
    }

    private static void logDataset(String action, String version, String resource,
                                   DatasetSummary summary, byte[] data) {
        RestApiLogging.businessDatasetEvent(action, DATASET, version, resource,
            summary.rows(), summary.distinctCustomers(), summary.missingEmails(), summary.countries(), data);
    }

    private record DatasetSummary(int rows, int distinctCustomers, int missingEmails, List<String> countries) {
    }
}
