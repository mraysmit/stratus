// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Offline validation contract for the storage verifier's public environment interface.
 *
 * <h2>Rationale and proof boundary</h2>
 *
 * <p>The storage verifier must reject missing credentials, malformed endpoints, insecure transport
 * without an explicit disposable-development override, and invalid timeout relationships before
 * creating an S3 client. Named {@code ENV_*}, {@code FIXTURE_*}, and {@code EXPECTED_*} constants
 * keep the public interface separate from fake data and derived defaults. This class proves parsing
 * and validation only; it does not contact Ceph or establish TLS and authorization behavior.
 *
 * <h2>Maintenance and promotion</h2>
 *
 * <p>Change environment names or defaults with production parsing, deployment templates, live
 * verifier tests, and operator documentation in one atomic change. Keep invalid examples beside
 * the behavior they explain. Developer, UAT, and production must inject environment-owned secrets
 * and endpoints and run the same immutable verifier artifact; fixture credentials must never be
 * copied into a deployment.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.1.0
 */
@Tag("unit")
class StorageVerifierConfigTest {

    private static final String ENV_ENDPOINT = "CEPH_RGW_ENDPOINT";
    private static final String ENV_ACCESS_KEY = "CEPH_RGW_ACCESS_KEY";
    private static final String ENV_SECRET_KEY = "CEPH_RGW_SECRET_KEY";
    private static final String ENV_ALLOW_HTTP = "CEPH_RGW_ALLOW_HTTP";
    private static final String ENV_PATH_STYLE_ACCESS = "S3_PATH_STYLE_ACCESS";
    private static final String ENV_PROBE_BUCKET = "CEPH_RGW_PROBE_BUCKET";
    private static final String ENV_CONNECTION_TIMEOUT = "S3_CONNECTION_TIMEOUT_MS";
    private static final String ENV_SOCKET_TIMEOUT = "S3_SOCKET_TIMEOUT_MS";
    private static final String ENV_API_CALL_ATTEMPT_TIMEOUT =
            "S3_API_CALL_ATTEMPT_TIMEOUT_MS";
    private static final String ENV_API_CALL_TIMEOUT = "S3_API_CALL_TIMEOUT_MS";

    private static final String FIXTURE_ENDPOINT = "https://object-store.stratus.local";
    private static final String FIXTURE_ACCESS_KEY = "verification-user";
    private static final String FIXTURE_SECRET_KEY = "verification-secret";
    private static final String FIXTURE_VALID_ORIGIN = "https://host";
    private static final String EXPECTED_DEFAULT_PROBE_BUCKET = "stratus-landing";
    private static final Duration FIXTURE_TIMEOUT = Duration.ofSeconds(1);
    private static final List<String> REQUIRED_ENVIRONMENT_VARIABLES = List.of(
            ENV_ENDPOINT, ENV_ACCESS_KEY, ENV_SECRET_KEY);
    private static final List<String> TIMEOUT_ENVIRONMENT_VARIABLES = List.of(
            ENV_CONNECTION_TIMEOUT,
            ENV_SOCKET_TIMEOUT,
            ENV_API_CALL_ATTEMPT_TIMEOUT,
            ENV_API_CALL_TIMEOUT);

    @Test
    void loadsStrictHttpsCephConfiguration() {
        var config = StorageVerifierConfig.from(environment());

        assertEquals(FIXTURE_ENDPOINT, config.endpoint().toString());
        assertEquals(FIXTURE_ACCESS_KEY, config.accessKey());
        assertTrue(config.pathStyleAccess());
        assertEquals(StorageVerifierConfig.REQUIRED_BUCKETS, config.requiredBuckets());
        assertEquals(Duration.ofSeconds(5), config.connectionTimeout());
        assertEquals(Duration.ofSeconds(10), config.socketTimeout());
        assertEquals(Duration.ofSeconds(15), config.apiCallAttemptTimeout());
        assertEquals(Duration.ofSeconds(30), config.apiCallTimeout());
    }

    @Test
    void rejectsPlaintextEndpointByDefault() {
        var environment = environment();
        environment.put(ENV_ENDPOINT, "http://object-store.stratus.local");

        var error = assertThrows(IllegalArgumentException.class,
                () -> StorageVerifierConfig.from(environment));

        assertTrue(error.getMessage().contains("HTTPS"));
    }

    @Test
    void permitsHttpOnlyWhenExplicitlyEnabledForDisposableDevelopment() {
        var environment = environment();
        environment.put(ENV_ENDPOINT, "http://127.0.0.1:8000");
        environment.put(ENV_ALLOW_HTTP, "true");

        assertEquals("http", StorageVerifierConfig.from(environment).endpoint().getScheme());
    }

    @Test
    void rejectsEndpointWithEmbeddedCredentialsOrPath() {
        var withCredentials = environment();
        withCredentials.put(ENV_ENDPOINT, "https://user:secret@object-store.stratus.local");
        assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(withCredentials));

        var withPath = environment();
        withPath.put(ENV_ENDPOINT, "https://object-store.stratus.local/s3");
        assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(withPath));
    }

    @Test
    void neverExposesSecretInDiagnosticText() {
        var config = StorageVerifierConfig.from(environment());

        assertFalse(config.toString().contains(FIXTURE_SECRET_KEY));
    }

    @Test
    void rejectsMissingMalformedAndUnsupportedConfiguration() {
        for (String name : REQUIRED_ENVIRONMENT_VARIABLES) {
            var missing = environment();
            missing.remove(name);
            assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(missing));
        }
        for (var endpoint : new String[]{"not a uri", "relative", "https:opaque", "ftp://object-store.stratus.local",
                "https://object-store.stratus.local?query=yes", "https://object-store.stratus.local#fragment"}) {
            var invalid = environment();
            invalid.put(ENV_ENDPOINT, endpoint);
            invalid.put(ENV_ALLOW_HTTP, "true");
            assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(invalid));
        }
    }

    @Test
    void validatesDirectConstructionAndOptionalSettings() {
        assertThrows(NullPointerException.class, () -> new StorageVerifierConfig(
                null, FIXTURE_ACCESS_KEY, FIXTURE_SECRET_KEY, true,
                StorageVerifierConfig.REQUIRED_BUCKETS, EXPECTED_DEFAULT_PROBE_BUCKET,
                FIXTURE_TIMEOUT, FIXTURE_TIMEOUT, FIXTURE_TIMEOUT, FIXTURE_TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> new StorageVerifierConfig(
                URI.create(FIXTURE_VALID_ORIGIN), " ", FIXTURE_SECRET_KEY, true,
                StorageVerifierConfig.REQUIRED_BUCKETS, EXPECTED_DEFAULT_PROBE_BUCKET,
                FIXTURE_TIMEOUT, FIXTURE_TIMEOUT, FIXTURE_TIMEOUT, FIXTURE_TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> new StorageVerifierConfig(
                URI.create(FIXTURE_VALID_ORIGIN), FIXTURE_ACCESS_KEY, " ", true,
                StorageVerifierConfig.REQUIRED_BUCKETS, EXPECTED_DEFAULT_PROBE_BUCKET,
                FIXTURE_TIMEOUT, FIXTURE_TIMEOUT, FIXTURE_TIMEOUT, FIXTURE_TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> new StorageVerifierConfig(
                URI.create(FIXTURE_VALID_ORIGIN), FIXTURE_ACCESS_KEY, FIXTURE_SECRET_KEY, true,
                StorageVerifierConfig.REQUIRED_BUCKETS, "other",
                FIXTURE_TIMEOUT, FIXTURE_TIMEOUT, FIXTURE_TIMEOUT, FIXTURE_TIMEOUT));
        var custom = environment();
        custom.put(ENV_PATH_STYLE_ACCESS, "false");
        custom.put(ENV_PROBE_BUCKET, "stratus-gold");
        assertFalse(StorageVerifierConfig.from(custom).pathStyleAccess());
        assertEquals("stratus-gold", StorageVerifierConfig.from(custom).probeBucket());
        var rootPath = environment();
        rootPath.put(ENV_ENDPOINT, FIXTURE_ENDPOINT + "/");
        assertEquals("/", StorageVerifierConfig.from(rootPath).endpoint().getPath());
    }

    @Test
    void validatesAllClientTimeoutSettings() {
        var custom = environment();
        custom.put(ENV_CONNECTION_TIMEOUT, "101");
        custom.put(ENV_SOCKET_TIMEOUT, "202");
        custom.put(ENV_API_CALL_ATTEMPT_TIMEOUT, "303");
        custom.put(ENV_API_CALL_TIMEOUT, "404");
        var config = StorageVerifierConfig.from(custom);
        assertEquals(Duration.ofMillis(101), config.connectionTimeout());
        assertEquals(Duration.ofMillis(202), config.socketTimeout());
        assertEquals(Duration.ofMillis(303), config.apiCallAttemptTimeout());
        assertEquals(Duration.ofMillis(404), config.apiCallTimeout());

        for (String name : TIMEOUT_ENVIRONMENT_VARIABLES) {
            var malformed = environment();
            malformed.put(name, "not-a-number");
            assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(malformed));
            var zero = environment();
            zero.put(name, "0");
            assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(zero));
        }
        var negative = environment();
        negative.put(ENV_SOCKET_TIMEOUT, "-1");
        assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(negative));
        var inverted = environment();
        inverted.put(ENV_API_CALL_ATTEMPT_TIMEOUT, "500");
        inverted.put(ENV_API_CALL_TIMEOUT, "499");
        assertThrows(IllegalArgumentException.class, () -> StorageVerifierConfig.from(inverted));

        assertThrows(NullPointerException.class, () -> new StorageVerifierConfig(
                URI.create(FIXTURE_VALID_ORIGIN), FIXTURE_ACCESS_KEY, FIXTURE_SECRET_KEY, true,
                StorageVerifierConfig.REQUIRED_BUCKETS, EXPECTED_DEFAULT_PROBE_BUCKET,
                null, FIXTURE_TIMEOUT, FIXTURE_TIMEOUT, FIXTURE_TIMEOUT));
    }

    private static Map<String, String> environment() {
        var environment = new HashMap<String, String>();
        environment.put(ENV_ENDPOINT, FIXTURE_ENDPOINT);
        environment.put(ENV_ACCESS_KEY, FIXTURE_ACCESS_KEY);
        environment.put(ENV_SECRET_KEY, FIXTURE_SECRET_KEY);
        environment.put(ENV_PATH_STYLE_ACCESS, "true");
        return environment;
    }
}
