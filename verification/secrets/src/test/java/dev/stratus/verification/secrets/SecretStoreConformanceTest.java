// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Stratus secret-store conformance suite, proven against the live
 * OpenBao developer harness over its HTTP API: authenticated KV round trips,
 * version increments on overwrite (the rotation primitive), refusal of
 * forged and missing tokens without echoing real material, and the published
 * service-identity layout the platform harnesses rely on.
 *
 * <p>The endpoint, token, and KV layout come from the environment, so the
 * suite runs unchanged against any KV-v2-compatible store deployment.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
@Tag("secrets-integration")
final class SecretStoreConformanceTest {

    private static final Pattern VERSION = Pattern.compile("\"version\" *: *([0-9]+)");

    private SecretStoreVerifierConfig config;
    private HttpClient client;
    private String probePath;

    @BeforeEach
    void requireLiveStore() {
        if (Boolean.getBoolean("secrets.integration.required")) {
            assertTrue(Boolean.parseBoolean(System.getenv("STRATUS_SECRETS_INTEGRATION")),
                    "STRATUS_SECRETS_INTEGRATION=true is required by the selected Maven profile");
            for (String name : Set.of("OPENBAO_ENDPOINT", "OPENBAO_TOKEN")) {
                assertTrue(System.getenv(name) != null && !System.getenv(name).isBlank(),
                        name + " is required by the selected Maven profile");
            }
        }
        assumeTrue(Boolean.parseBoolean(System.getenv("STRATUS_SECRETS_INTEGRATION")),
                "Set STRATUS_SECRETS_INTEGRATION=true to run against a live secret store");

        config = SecretStoreVerifierConfig.from(System.getenv());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        probePath = "stratus/verify/probe-" + UUID.randomUUID().toString().replace("-", "");
        SecretsVerificationLogging.storeConnected(config);
    }

    @AfterEach
    void removeProbeSecret() {
        if (config == null) {
            return;
        }
        // Cleanup is unconditional: a failed assertion must not leave a probe
        // secret behind for the next run to trip over.
        send(request("DELETE", metadataUrl(probePath), null, config.token()));
    }

    @Test
    void writesReadsAndDeletesASecretRoundTrip() {
        var written = send(request("POST", dataUrl(probePath),
                "{\"data\":{\"probe\":\"round-trip-value\"}}", config.token()));
        assertTrue(written.statusCode() == 200 || written.statusCode() == 204,
                "the authenticated write must succeed, got " + written.statusCode());
        SecretsVerificationLogging.kvEvent("write-confirmed", dataUrl(probePath).getPath(),
                versionOf(written.body()));

        var read = send(request("GET", dataUrl(probePath), null, config.token()));
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("\"probe\":\"round-trip-value\""),
                "the read must return the written value");
        SecretsVerificationLogging.kvEvent("read-confirmed", dataUrl(probePath).getPath(),
                versionOf(read.body()));

        var deleted = send(request("DELETE", metadataUrl(probePath), null, config.token()));
        assertTrue(deleted.statusCode() == 200 || deleted.statusCode() == 204,
                "the delete must succeed, got " + deleted.statusCode());
        var afterDelete = send(request("GET", dataUrl(probePath), null, config.token()));
        assertEquals(404, afterDelete.statusCode(), "the deleted secret must no longer be readable");
        SecretsVerificationLogging.kvEvent("delete-confirmed", dataUrl(probePath).getPath(), 0);
    }

    @Test
    void overwritingASecretCreatesANewVersion() {
        var first = send(request("POST", dataUrl(probePath),
                "{\"data\":{\"probe\":\"first\"}}", config.token()));
        var second = send(request("POST", dataUrl(probePath),
                "{\"data\":{\"probe\":\"second\"}}", config.token()));

        assertEquals(1, versionOf(first.body()), "the first write must create version 1");
        assertEquals(2, versionOf(second.body()), "an overwrite must create the next version");
        var read = send(request("GET", dataUrl(probePath), null, config.token()));
        assertTrue(read.body().contains("\"probe\":\"second\""),
                "a read must return the latest version");
        SecretsVerificationLogging.kvEvent("rotation-confirmed", dataUrl(probePath).getPath(), 2);
    }

    @Test
    void rejectsAForgedTokenWithoutEchoingTheRealOne() {
        var response = send(request("GET", dataUrl(probePath), null, "forged-token-0000"));

        assertEquals(403, response.statusCode(), "a forged token must be refused");
        assertFalse(response.body().contains(config.token()),
                "the refusal must not echo the real token");
        SecretsVerificationLogging.negativeConfirmed("forged-token", response.statusCode());
    }

    @Test
    void rejectsAnUnauthenticatedRequest() {
        var response = send(request("GET", dataUrl(probePath), null, null));

        assertTrue(Set.of(400, 401, 403).contains(response.statusCode()),
                "a request without a token must be refused, got " + response.statusCode());
        SecretsVerificationLogging.negativeConfirmed("missing-token", response.statusCode());
    }

    @Test
    void servesThePublishedServiceIdentityFields() {
        var response = send(request("GET",
                dataUrl(config.serviceIdentityPath() + "/svc-polaris"), null, config.token()));

        assertEquals(200, response.statusCode(),
                "svc-polaris must be published; run ceph-compose-provision-service-identities.sh with OpenBao up");
        assertTrue(response.body().contains("\"access_key\":\""),
                "the identity must carry an access_key field");
        assertTrue(response.body().contains("\"secret_key\":\""),
                "the identity must carry a secret_key field");
        SecretsVerificationLogging.identityValidated(config.serviceIdentityPath() + "/svc-polaris",
                java.util.List.of("access_key", "secret_key"));
    }

    private URI dataUrl(String path) {
        return URI.create(config.endpoint() + "/v1/" + config.kvMount() + "/data/" + path);
    }

    private URI metadataUrl(String path) {
        return URI.create(config.endpoint() + "/v1/" + config.kvMount() + "/metadata/" + path);
    }

    private static HttpRequest request(String method, URI url, String body, String token) {
        var builder = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(10));
        if (token != null) {
            builder.header("X-Vault-Token", token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return builder.build();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new UncheckedIOException("Secret store request failed: " + request.uri().getPath(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted talking to the secret store", exception);
        }
    }

    private static int versionOf(String body) {
        Matcher matcher = VERSION.matcher(body);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
