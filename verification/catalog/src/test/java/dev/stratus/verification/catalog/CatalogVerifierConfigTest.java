// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Offline validation contract for the catalog verifier's public environment interface.
 *
 * <h2>Rationale and proof boundary</h2>
 *
 * <p>Every required value must be enforced by name, insecure transport requires an explicit
 * disposable-development override, endpoint URLs must be origins, and secrets must never leak
 * through diagnostics. The shared {@link CatalogTestEnvironment} keeps names and fake values
 * consistent with the REST-property mapping tests. This class proves parsing and validation, not
 * live TLS, authentication, catalog access, or storage authorization.
 *
 * <h2>Maintenance and promotion</h2>
 *
 * <p>Change environment names only with production parsing, deployment templates, mapping tests,
 * and operator documentation. Keep invalid examples local to the behavior they explain. Developer,
 * UAT, and production must inject their own managed endpoints and secrets and prove the same
 * immutable verifier artifact live; no fake fixture value is release evidence.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.1.0
 */
@Tag("unit")
final class CatalogVerifierConfigTest {

    @Test
    void buildsFromACompleteEnvironmentWithDefaults() {
        var config = CatalogVerifierConfig.from(CatalogTestEnvironment.completeEnvironment());

        assertEquals(CatalogTestEnvironment.FIXTURE_POLARIS_URI, config.polarisUri().toString());
        assertEquals(CatalogTestEnvironment.FIXTURE_POLARIS_CLIENT_ID, config.clientId());
        assertEquals(CatalogTestEnvironment.FIXTURE_CATALOG, config.catalogName());
        assertEquals(CatalogTestEnvironment.FIXTURE_CEPH_ENDPOINT,
                config.storageEndpoint().toString());
        assertTrue(config.pathStyleAccess(), "path-style access must default to true for RGW");
    }

    @Test
    void rejectsEveryMissingRequiredValueByName() {
        for (String required : new String[] {
                CatalogTestEnvironment.ENV_POLARIS_URI,
                CatalogTestEnvironment.ENV_POLARIS_CLIENT_ID,
                CatalogTestEnvironment.ENV_POLARIS_CLIENT_SECRET,
                CatalogTestEnvironment.ENV_POLARIS_CATALOG,
                CatalogTestEnvironment.ENV_CEPH_ENDPOINT,
                CatalogTestEnvironment.ENV_CEPH_ACCESS_KEY,
                CatalogTestEnvironment.ENV_CEPH_SECRET_KEY}) {
            var environment = CatalogTestEnvironment.completeEnvironment();
            environment.remove(required);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> CatalogVerifierConfig.from(environment),
                    required + " must be rejected when absent");
            assertTrue(failure.getMessage().contains(required),
                    "the failure must name " + required + " but was: " + failure.getMessage());
        }
    }

    @Test
    void rejectsPlainHttpPolarisUriWithoutTheDevelopmentOverride() {
        var environment = CatalogTestEnvironment.completeEnvironment();
        environment.put(CatalogTestEnvironment.ENV_POLARIS_URI,
                "http://127.0.0.1:8181/api/catalog");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> CatalogVerifierConfig.from(environment));
        assertTrue(failure.getMessage().contains(CatalogTestEnvironment.ENV_POLARIS_ALLOW_HTTP));
    }

    @Test
    void allowsPlainHttpPolarisUriWithTheExplicitDevelopmentOverride() {
        var environment = CatalogTestEnvironment.completeEnvironment();
        environment.put(CatalogTestEnvironment.ENV_POLARIS_URI,
                "http://127.0.0.1:8181/api/catalog");
        environment.put(CatalogTestEnvironment.ENV_POLARIS_ALLOW_HTTP, "true");

        var config = CatalogVerifierConfig.from(environment);

        assertEquals("http", config.polarisUri().getScheme());
    }

    @Test
    void rejectsAPolarisUriWithEmbeddedCredentialsOrQuery() {
        for (String invalid : new String[] {
                "https://user:secret@polaris.stratus.local:8181/api/catalog",
                "https://polaris.stratus.local:8181/api/catalog?token=x"}) {
            var environment = CatalogTestEnvironment.completeEnvironment();
            environment.put(CatalogTestEnvironment.ENV_POLARIS_URI, invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogVerifierConfig.from(environment),
                    invalid + " must be rejected");
        }
    }

    @Test
    void rejectsAStorageEndpointThatIsNotAnOriginUrl() {
        for (String invalid : new String[] {
                "https://object-store.stratus.local:8443/some/path",
                "https://user:secret@object-store.stratus.local:8443",
                "ftp://object-store.stratus.local:8443"}) {
            var environment = CatalogTestEnvironment.completeEnvironment();
            environment.put(CatalogTestEnvironment.ENV_CEPH_ENDPOINT, invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogVerifierConfig.from(environment),
                    invalid + " must be rejected");
        }
    }

    @Test
    void rejectsPlainHttpStorageEndpointWithoutTheDevelopmentOverride() {
        var environment = CatalogTestEnvironment.completeEnvironment();
        environment.put(CatalogTestEnvironment.ENV_CEPH_ENDPOINT,
                "http://object-store.stratus.local:8443");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> CatalogVerifierConfig.from(environment));
        assertTrue(failure.getMessage().contains(CatalogTestEnvironment.ENV_CEPH_ALLOW_HTTP));
    }

    @Test
    void redactsBothSecretsFromToString() {
        var rendered = CatalogVerifierConfig.from(
                CatalogTestEnvironment.completeEnvironment()).toString();

        assertFalse(rendered.contains(CatalogTestEnvironment.FIXTURE_POLARIS_CLIENT_SECRET),
                "the Polaris client secret must be redacted");
        assertFalse(rendered.contains(CatalogTestEnvironment.FIXTURE_CEPH_SECRET_KEY),
                "the storage secret key must be redacted");
        assertTrue(rendered.contains("<redacted>"));
    }
}
