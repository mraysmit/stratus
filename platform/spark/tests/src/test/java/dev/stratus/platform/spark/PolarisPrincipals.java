// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates and removes catalog principals over the Polaris management API.
 *
 * <p>A test that proves two principals are separated has to be able to make a
 * second one, and making one is an ordinary API call — so it belongs in the
 * test that needs it, not in a shell script the test would have to be launched
 * from. This is the Java form of what
 * {@code spark-compose-bootstrap-principal.sh} does for {@code svc-spark}, and
 * the call sequence is taken from it.
 *
 * <p>The root credential comes from the Polaris harness's own private
 * {@code .env}, which is the only place it exists. That cross-harness read is
 * the same one the bootstrap script performs and records.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
final class PolarisPrincipals {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern ACCESS_TOKEN =
            Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

    private final String managementApi;
    private final String catalogApi;
    private final String catalogName;
    private final HttpClient http;

    private PolarisPrincipals(String catalogUri, String catalogName) {
        // The catalog API is <base>/api/catalog; management is <base>/api/management/v1.
        this.catalogApi = catalogUri;
        this.managementApi = catalogUri.substring(0, catalogUri.lastIndexOf("/catalog"))
                + "/management/v1";
        this.catalogName = catalogName;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    static PolarisPrincipals connect() {
        // Before the client: its default TLS context is fixed when it is built.
        HarnessTruststore.installed();
        return new PolarisPrincipals(HarnessConnection.polarisCatalogUri(),
                HarnessConnection.polarisCatalogName());
    }

    /**
     * Creates a principal with the given secret and grants it a catalog role,
     * returning the {@code clientId:clientSecret} a client authenticates with.
     *
     * <p>Converges rather than insists: a principal left behind by an
     * interrupted run is reset to the secret asked for, because a principal
     * whose secret nobody knows is unusable and cannot be recovered.
     */
    String create(String name, String secret, String catalogRole) {
        String token = rootToken();

        int created = post(managementApi + "/principals", token,
                "{\"principal\": {\"name\": \"" + name + "\", \"clientId\": \"" + name
                        + "\"}, \"credentialRotationRequired\": false}");
        if (created != 200 && created != 201 && created != 409) {
            throw new IllegalStateException(
                    "Polaris refused to create principal " + name + ": " + created);
        }

        expect(post(managementApi + "/principals/" + name + "/reset", token,
                "{\"clientId\": \"" + name + "\", \"clientSecret\": \"" + secret + "\"}"),
                "reset the credential of " + name, 200);

        String role = name + "_role";
        int roleCreated = post(managementApi + "/principal-roles", token,
                "{\"principalRole\": {\"name\": \"" + role + "\"}}");
        if (roleCreated != 200 && roleCreated != 201 && roleCreated != 409) {
            throw new IllegalStateException("Polaris refused to create role " + role);
        }

        expect(put(managementApi + "/principals/" + name + "/principal-roles", token,
                "{\"principalRole\": {\"name\": \"" + role + "\"}}"),
                "assign " + role + " to " + name, 200, 201);

        expect(put(managementApi + "/principal-roles/" + role + "/catalog-roles/" + catalogName,
                token, "{\"catalogRole\": {\"name\": \"" + catalogRole + "\"}}"),
                "grant " + catalogRole + " to " + role, 200, 201);

        SparkVerificationLogging.principalProvisioned(name, role, catalogRole);
        return name + ":" + secret;
    }

    /**
     * Creates a principal that can authenticate but has been granted nothing in
     * the catalog.
     *
     * <p>This is the narrower half of an isolation test. A principal that
     * cannot obtain a token at all would prove only that a wrong password
     * fails; one that authenticates and is then refused the data proves that
     * authorisation is what separated it.
     */
    String createWithoutCatalogAccess(String name, String secret) {
        String token = rootToken();

        int created = post(managementApi + "/principals", token,
                "{\"principal\": {\"name\": \"" + name + "\", \"clientId\": \"" + name
                        + "\"}, \"credentialRotationRequired\": false}");
        if (created != 200 && created != 201 && created != 409) {
            throw new IllegalStateException(
                    "Polaris refused to create principal " + name + ": " + created);
        }
        expect(post(managementApi + "/principals/" + name + "/reset", token,
                "{\"clientId\": \"" + name + "\", \"clientSecret\": \"" + secret + "\"}"),
                "reset the credential of " + name, 200);

        String role = name + "_role";
        int roleCreated = post(managementApi + "/principal-roles", token,
                "{\"principalRole\": {\"name\": \"" + role + "\"}}");
        if (roleCreated != 200 && roleCreated != 201 && roleCreated != 409) {
            throw new IllegalStateException("Polaris refused to create role " + role);
        }
        expect(put(managementApi + "/principals/" + name + "/principal-roles", token,
                "{\"principalRole\": {\"name\": \"" + role + "\"}}"),
                "assign " + role + " to " + name, 200, 201);

        // Deliberately no catalog-role grant. That absence is the test.
        SparkVerificationLogging.principalProvisioned(name, role, "none");
        return name + ":" + secret;
    }

    /** Removes a principal and its role, so a run leaves the catalog as it found it. */
    void remove(String name) {
        String token = rootToken();
        delete(managementApi + "/principals/" + name, token);
        delete(managementApi + "/principal-roles/" + name + "_role", token);
        SparkVerificationLogging.principalRemoved(name);
    }

    /** A token for the harness root principal, which is the only thing that may create others. */
    private String rootToken() {
        Path env = HarnessConnection.repositoryRoot()
                .resolve("platform/polaris/compose-service/.env");
        Map<String, String> settings = HarnessConnection.readSettings(env);
        String credentials = settings.get("POLARIS_BOOTSTRAP_CREDENTIALS");
        if (credentials == null || credentials.split(",").length < 3) {
            throw new IllegalStateException(
                    "POLARIS_BOOTSTRAP_CREDENTIALS is not realm,client-id,client-secret in " + env);
        }
        String[] parts = credentials.split(",");
        return token(parts[1], parts[2]);
    }

    /** Exchanges a client credential for a bearer token; also how a client proves its own. */
    String token(String clientId, String clientSecret) {
        var request = HttpRequest.newBuilder(URI.create(catalogApi + "/v1/oauth/tokens"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=client_credentials&client_id=" + clientId
                                + "&client_secret=" + clientSecret + "&scope=PRINCIPAL_ROLE:ALL"))
                .build();
        String body = send(request, "obtain a token for " + clientId).body();
        Matcher matcher = ACCESS_TOKEN.matcher(body);
        if (!matcher.find()) {
            // The body is not echoed: a token response is credential material.
            throw new IllegalStateException("Polaris issued no token for " + clientId);
        }
        return matcher.group(1);
    }

    private int post(String uri, String token, String body) {
        return send(HttpRequest.newBuilder(URI.create(uri))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), "POST " + uri).statusCode();
    }

    private int put(String uri, String token, String body) {
        return send(HttpRequest.newBuilder(URI.create(uri))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), "PUT " + uri).statusCode();
    }

    private void delete(String uri, String token) {
        send(HttpRequest.newBuilder(URI.create(uri))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build(), "DELETE " + uri);
    }

    private HttpResponse<String> send(HttpRequest request, String what) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to " + what, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted trying to " + what, exception);
        }
    }

    private static void expect(int status, String what, int... accepted) {
        for (int candidate : accepted) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Polaris answered " + status + " when asked to " + what);
    }
}
