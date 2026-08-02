// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.ceph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
 * The Ceph Dashboard REST API proven against a live cluster: token
 * authentication, its rejection of unauthenticated callers, and the
 * {@code /api/rgw} endpoints that create, read, and delete buckets.
 *
 * <p>This is the management REST surface, distinct from the S3 data API proven
 * by {@code CephS3RestContractTest} and the Admin Operations API proven by
 * {@code CephAdminOpsRestContractTest}. Bucket creation and deletion here are
 * real writes against the live cluster, not metadata reads.
 *
 * <p>The {@code /api/rgw} endpoints require the dashboard to hold RGW
 * credentials of its own. The harness configures those at startup; when they
 * are absent the dashboard answers with an error, and the assertions below name
 * that as the cause rather than reporting an opaque failure.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-30
 * @version 1.0.0
 */
@Tag("ceph-integration")
final class CephDashboardRestContractTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String ACCEPT = "application/vnd.ceph.api.v1.0+json";

    /**
     * Logout takes no parameters but is still a JSON POST. An absent body makes
     * the dashboard answer 411, an empty string 400, and no content type 415,
     * so the only accepted form is an empty JSON document.
     */
    private static final String EMPTY_JSON_BODY = "{}";

    private String endpoint;
    private String probeBucket;
    private String ownerUid;
    private HttpClient http;
    private String token;

    @BeforeEach
    void requireLiveCluster() {
        if (Boolean.getBoolean("ceph.integration.required")) {
            assertTrue(Boolean.parseBoolean(System.getenv("CEPH_RGW_INTEGRATION")),
                "CEPH_RGW_INTEGRATION=true is required by the selected Maven profile");
            for (String name : Set.of(
                    "CEPH_DASHBOARD_ENDPOINT",
                    "CEPH_DASHBOARD_USER",
                    "CEPH_DASHBOARD_PASSWORD",
                    "CEPH_RGW_PROBE_BUCKET")) {
                assertTrue(System.getenv(name) != null && !System.getenv(name).isBlank(),
                    name + " is required by the selected Maven profile");
            }
        }
        assumeTrue(Boolean.parseBoolean(System.getenv("CEPH_RGW_INTEGRATION")),
            "Set CEPH_RGW_INTEGRATION=true to run against a live Ceph RGW endpoint");

        Map<String, String> environment = System.getenv();
        endpoint = environment.get("CEPH_DASHBOARD_ENDPOINT");
        probeBucket = environment.get("CEPH_RGW_PROBE_BUCKET");
        ownerUid = environment.getOrDefault("CEPH_DEMO_UID", "stratus-verifier");
        http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        token = null;
    }

    @AfterEach
    void revokeTheSessionAndReleaseTheClient() {
        if (http == null) {
            return;
        }
        try {
            if (token != null) {
                send("POST", "/api/auth/logout", EMPTY_JSON_BODY);
            }
        } finally {
            http.close();
        }
    }

    @Test
    void authenticationIssuesASessionTokenAndReportsTheSignedInUser() {
        HttpResponse<String> response = authenticate();

        assertEquals(201, response.statusCode(), () -> "dashboard authentication failed: " + response.body());
        Map<?, ?> session = asMap(new Yaml().load(response.body()));
        assertNotNull(session.get("token"), () -> "authentication must return a session token: " + session);
        assertEquals(System.getenv("CEPH_DASHBOARD_USER"), session.get("username"),
            () -> "the session must identify the signed-in user: " + session);
        assertFalse(response.body().contains(System.getenv("CEPH_DASHBOARD_PASSWORD")),
            "the authentication response must never echo the password");
    }

    @Test
    void rejectsAnUnauthenticatedRequestWithUnauthorized() {
        HttpResponse<String> response = send("GET", "/api/summary", null);

        assertEquals(401, response.statusCode(),
            () -> "an unauthenticated dashboard request must be refused: " + response.body());
    }

    @Test
    void listsTheProbeBucketOverTheDashboardRgwApi() {
        signIn();
        HttpResponse<String> response = send("GET", "/api/rgw/bucket", null);

        assertEquals(200, response.statusCode(), () -> rgwFailure("bucket listing", response));
        Object parsed = new Yaml().load(response.body());
        assertTrue(parsed instanceof List, () -> "the bucket listing must be a JSON array: " + response.body());
        assertTrue(((List<?>) parsed).contains(probeBucket),
            () -> "the dashboard must list the probe bucket " + probeBucket + ", but returned: " + parsed);
    }

    @Test
    void createsReadsAndDeletesABucketOverTheDashboardRgwApi() {
        signIn();
        String bucket = "stratus-dashboard-probe-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = send("POST", "/api/rgw/bucket",
            "{\"bucket\":\"" + bucket + "\",\"uid\":\"" + ownerUid + "\"}");
        try {
            assertEquals(201, created.statusCode(), () -> rgwFailure("bucket creation", created));

            HttpResponse<String> read = send("GET", "/api/rgw/bucket/" + bucket, null);
            assertEquals(200, read.statusCode(), () -> rgwFailure("bucket read-back", read));
            Map<?, ?> detail = asMap(new Yaml().load(read.body()));
            assertEquals(bucket, detail.get("bucket"), () -> "unexpected bucket in the response: " + detail);
            assertEquals(ownerUid, detail.get("owner"), () -> "unexpected owner in the response: " + detail);
        } finally {
            HttpResponse<String> deleted = send("DELETE", "/api/rgw/bucket/" + bucket, null);
            assertEquals(204, deleted.statusCode(), () -> rgwFailure("bucket deletion", deleted));
        }

        // Deletion is asserted through the listing rather than by reading the
        // deleted bucket back. Reading one is not a stable signal: observed runs
        // answered 500 carrying the RGW NoSuchBucket code on one occasion and a
        // 502 HTML page from the TLS proxy on another. The listing is the
        // contract that matters and it is unambiguous.
        HttpResponse<String> listing = send("GET", "/api/rgw/bucket", null);
        assertEquals(200, listing.statusCode(), () -> rgwFailure("bucket listing", listing));
        assertFalse(((List<?>) new Yaml().load(listing.body())).contains(bucket),
            () -> "a deleted bucket must no longer be listed: " + listing.body());
    }

    @Test
    void logoutRevokesTheSessionToken() {
        signIn();
        assertEquals(200, send("POST", "/api/auth/logout", EMPTY_JSON_BODY).statusCode());

        HttpResponse<String> afterLogout = send("GET", "/api/summary", null);
        token = null;
        assertEquals(401, afterLogout.statusCode(),
            () -> "a revoked token must no longer be accepted: " + afterLogout.body());
    }

    private void signIn() {
        HttpResponse<String> response = authenticate();
        assertEquals(201, response.statusCode(), () -> "dashboard authentication failed: " + response.body());
        token = String.valueOf(asMap(new Yaml().load(response.body())).get("token"));
    }

    private HttpResponse<String> authenticate() {
        return send("POST", "/api/auth",
            "{\"username\":\"" + System.getenv("CEPH_DASHBOARD_USER") + "\","
                + "\"password\":\"" + System.getenv("CEPH_DASHBOARD_PASSWORD") + "\"}");
    }

    private HttpResponse<String> send(String method, String path, String body) {
        URI requestUri = URI.create(endpoint + path);
        byte[] requestData = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        boolean authenticationExchange = path.startsWith("/api/auth");
        RestApiLogging.Exchange exchange = RestApiLogging.started(
            "dashboard", operation(method, path), resource(method, path, body), method, requestUri,
            requestData, authenticationExchange, token != null || "/api/auth".equals(path));
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(requestUri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", ACCEPT)
            .method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            RestApiLogging.completed(exchange, response.statusCode(),
                response.body().getBytes(StandardCharsets.UTF_8), authenticationExchange, response.headers());
            return response;
        } catch (IOException e) {
            RestApiLogging.failed(exchange, e);
            throw new UncheckedIOException("Dashboard request failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            RestApiLogging.failed(exchange, e);
            throw new IllegalStateException("Interrupted during " + method + " " + path, e);
        }
    }

    private static String operation(String method, String path) {
        if ("/api/auth".equals(path)) {
            return "authenticate";
        }
        if ("/api/auth/logout".equals(path)) {
            return "logout";
        }
        if ("/api/summary".equals(path)) {
            return "read-cluster-summary";
        }
        if ("/api/rgw/bucket".equals(path)) {
            return "POST".equals(method) ? "create-bucket" : "list-buckets";
        }
        if (path.startsWith("/api/rgw/bucket/")) {
            return "DELETE".equals(method) ? "delete-bucket" : "read-bucket";
        }
        return "dashboard-request";
    }

    private static String resource(String method, String path, String body) {
        if (path.startsWith("/api/rgw/bucket/")) {
            return "bucket=" + path.substring("/api/rgw/bucket/".length());
        }
        if ("POST".equals(method) && "/api/rgw/bucket".equals(path) && body != null) {
            Object parsed = new Yaml().load(body);
            if (parsed instanceof Map<?, ?> values) {
                return "bucket=" + values.get("bucket") + " owner=" + values.get("uid");
            }
        }
        if ("/api/rgw/bucket".equals(path)) {
            return "buckets";
        }
        return path.startsWith("/api/auth") ? "dashboard-session" : "cluster";
    }

    private static String rgwFailure(String operation, HttpResponse<String> response) {
        return "Dashboard RGW " + operation + " answered HTTP " + response.statusCode()
            + ". If the dashboard holds no RGW credentials this endpoint cannot work; "
            + "startup configures them and logs a warning when it cannot. Response: " + response.body();
    }

    private static Map<?, ?> asMap(Object value) {
        assertTrue(value instanceof Map, () -> "expected a JSON object but got: " + value);
        return (Map<?, ?>) value;
    }
}
