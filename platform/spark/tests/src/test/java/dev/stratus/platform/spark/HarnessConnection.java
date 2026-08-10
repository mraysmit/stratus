// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a client needs to reach the platform, read from the providers' published
 * settings.
 *
 * <p>Every value here comes from a file a provider harness publishes for its
 * consumers (ADR-P1-003), or from the secret store the credentials are pushed
 * to (ADR-P1-004). Nothing is duplicated into this module and nothing is passed
 * in by a wrapper script: a test is a client, and a client discovers the
 * platform the same way any other consumer does.
 *
 * <p>This is the Java form of what {@code spark-compose-common.sh} does for the
 * container path. It exists because the tests are a Java client of the
 * platform, and a Java client should not need a shell to tell it where the
 * platform is.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
final class HarnessConnection {

    private static final Duration SECRET_STORE_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern JSON_STRING_FIELD =
            Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]+)\"");

    private HarnessConnection() {
    }

    /** The repository root, found by walking up from wherever Maven was run. */
    static Path repositoryRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("platform/spark/compose-cluster"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not locate the repository root from " + here);
    }

    /** The Ceph RGW endpoint its harness publishes to consumers. */
    static String cephEndpoint() {
        return required(cephSettings(), "CEPH_RGW_ENDPOINT", "platform/ceph/compose-cluster/connection.env");
    }

    /**
     * The Polaris catalog API, on loopback.
     *
     * <p>The proxy certificate carries {@code IP:127.0.0.1} as a subject
     * alternative name precisely so a client on the workstation needs no
     * hosts-file entry. The {@code polaris.stratus.local} form is for processes
     * inside the container network, which resolve it through the shared bridge.
     */
    static String polarisCatalogUri() {
        Map<String, String> settings = polarisSettings();
        String loopback = settings.get("POLARIS_LOOPBACK_ENDPOINT");
        String endpoint = loopback != null && !loopback.isBlank()
                ? loopback
                : required(settings, "POLARIS_ENDPOINT", "platform/polaris/compose-service/connection.env");
        return endpoint + "/api/catalog";
    }

    static String polarisCatalogName() {
        return required(polarisSettings(), "POLARIS_CATALOG",
                "platform/polaris/compose-service/connection.env");
    }

    /** The Ceph harness CA, as an absolute path. */
    static Path cephCertificateAuthority() {
        return repositoryRoot().resolve("platform/ceph/compose-cluster")
                .resolve(required(cephSettings(), "CEPH_HARNESS_CA_CERT",
                        "platform/ceph/compose-cluster/connection.env"));
    }

    /** The Polaris harness CA, as an absolute path. */
    static Path polarisCertificateAuthority() {
        return repositoryRoot().resolve("platform/polaris/compose-service")
                .resolve(required(polarisSettings(), "POLARIS_HARNESS_CA_CERT",
                        "platform/polaris/compose-service/connection.env"));
    }

    /**
     * The catalog credential the Spark harness generated for {@code svc-spark}.
     *
     * <p>Read from that harness's private {@code .env}, which is where it is
     * generated and never tracked. This is the same cross-harness read the
     * principal bootstrap performs, and the same reason: the secret exists
     * nowhere else.
     */
    static String sparkPolarisCredential() {
        Map<String, String> settings = readSettings(
                repositoryRoot().resolve("platform/spark/compose-cluster/.env"));
        String id = required(settings, "SPARK_POLARIS_CLIENT_ID",
                "platform/spark/compose-cluster/.env");
        String secret = required(settings, "SPARK_POLARIS_CLIENT_SECRET",
                "platform/spark/compose-cluster/.env");
        return id + ":" + secret;
    }

    /**
     * The {@code svc-spark} object-storage key pair, pulled from the secret
     * store rather than copied from a file (ADR-P1-004).
     *
     * <p>The Ceph provisioning step publishes it there; if it is absent, the
     * remedy is to run that step rather than to hand-wire a key.
     */
    static Map<String, String> objectStorageCredentials(String identity) {
        Path openbao = repositoryRoot().resolve("platform/openbao/compose-service");
        Map<String, String> settings = readSettings(openbao.resolve("connection.env"));
        String endpoint = required(settings, "OPENBAO_ENDPOINT",
                "platform/openbao/compose-service/connection.env");
        Path tokenFile = openbao.resolve(required(settings, "OPENBAO_TOKEN_FILE",
                "platform/openbao/compose-service/connection.env"));
        if (!Files.isReadable(tokenFile)) {
            throw new IllegalStateException("Missing " + tokenFile
                    + ". Start the secret store first: bash platform/openbao/compose-service/"
                    + "scripts/lifecycle/openbao-compose-startup.sh");
        }

        String path = endpoint + "/v1/" + settings.get("OPENBAO_KV_MOUNT")
                + "/data/" + settings.get("OPENBAO_SERVICE_IDENTITY_PATH") + "/" + identity;
        String body = get(path, readToken(tokenFile));

        var credentials = new LinkedHashMap<String, String>();
        credentials.put("accessKey", jsonField(body, "access_key", identity));
        credentials.put("secretKey", jsonField(body, "secret_key", identity));
        return credentials;
    }

    private static String get(String uri, String token) {
        var request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(SECRET_STORE_TIMEOUT)
                .header("X-Vault-Token", token)
                .GET()
                .build();
        try {
            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(SECRET_STORE_TIMEOUT).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "The secret store answered " + response.statusCode() + " for " + uri);
            }
            return response.body();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to reach the secret store at " + uri, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted reading " + uri, exception);
        }
    }

    private static String jsonField(String body, String field, String identity) {
        Matcher matcher = Pattern.compile(String.format(JSON_STRING_FIELD.pattern(), field))
                .matcher(body);
        if (!matcher.find()) {
            // Deliberately does not echo the body: it is a secret payload.
            throw new IllegalStateException("The secret store holds no " + field + " for " + identity
                    + ". Publish it by running: bash platform/ceph/compose-cluster/scripts/verify/"
                    + "ceph-compose-provision-service-identities.sh");
        }
        return matcher.group(1);
    }

    private static String readToken(Path tokenFile) {
        try {
            return Files.readString(tokenFile).trim();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read " + tokenFile, exception);
        }
    }

    private static Map<String, String> cephSettings() {
        return readSettings(repositoryRoot().resolve("platform/ceph/compose-cluster/connection.env"));
    }

    private static Map<String, String> polarisSettings() {
        return readSettings(repositoryRoot().resolve("platform/polaris/compose-service/connection.env"));
    }

    /** Reads a {@code KEY=value} settings file, ignoring comments and blanks. */
    static Map<String, String> readSettings(Path file) {
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("Missing " + file
                    + "; the harness that owns it must be started before a client can reach it");
        }
        var settings = new LinkedHashMap<String, String>();
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator > 0) {
                    settings.put(trimmed.substring(0, separator).trim(),
                            trimmed.substring(separator + 1).trim());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read " + file, exception);
        }
        return settings;
    }

    private static String required(Map<String, String> settings, String key, String source) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is not published in " + source);
        }
        return value;
    }
}
