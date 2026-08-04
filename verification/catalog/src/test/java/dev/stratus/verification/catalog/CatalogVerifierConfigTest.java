// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pure validation behavior of the catalog verifier configuration: every
 * required value is enforced by name, insecure transport needs the explicit
 * disposable-development override, and secrets never leak through toString.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
@Tag("unit")
final class CatalogVerifierConfigTest {

    private static Map<String, String> completeEnvironment() {
        var environment = new HashMap<String, String>();
        environment.put("STRATUS_POLARIS_URI", "https://polaris.stratus.local:8181/api/catalog");
        environment.put("STRATUS_POLARIS_CLIENT_ID", "stratus-root");
        environment.put("STRATUS_POLARIS_CLIENT_SECRET", "polaris-secret-value");
        environment.put("STRATUS_POLARIS_CATALOG", "stratus");
        environment.put("CEPH_RGW_ENDPOINT", "https://object-store.stratus.local:8443");
        environment.put("CEPH_RGW_ACCESS_KEY", "svc-polaris-abc123");
        environment.put("CEPH_RGW_SECRET_KEY", "storage-secret-value");
        return environment;
    }

    @Test
    void buildsFromACompleteEnvironmentWithDefaults() {
        var config = CatalogVerifierConfig.from(completeEnvironment());

        assertEquals("https://polaris.stratus.local:8181/api/catalog", config.polarisUri().toString());
        assertEquals("stratus-root", config.clientId());
        assertEquals("stratus", config.catalogName());
        assertEquals("https://object-store.stratus.local:8443", config.storageEndpoint().toString());
        assertTrue(config.pathStyleAccess(), "path-style access must default to true for RGW");
    }

    @Test
    void rejectsEveryMissingRequiredValueByName() {
        for (String required : new String[] {
                "STRATUS_POLARIS_URI",
                "STRATUS_POLARIS_CLIENT_ID",
                "STRATUS_POLARIS_CLIENT_SECRET",
                "STRATUS_POLARIS_CATALOG",
                "CEPH_RGW_ENDPOINT",
                "CEPH_RGW_ACCESS_KEY",
                "CEPH_RGW_SECRET_KEY"}) {
            var environment = completeEnvironment();
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
        var environment = completeEnvironment();
        environment.put("STRATUS_POLARIS_URI", "http://127.0.0.1:8181/api/catalog");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> CatalogVerifierConfig.from(environment));
        assertTrue(failure.getMessage().contains("STRATUS_POLARIS_ALLOW_HTTP"));
    }

    @Test
    void allowsPlainHttpPolarisUriWithTheExplicitDevelopmentOverride() {
        var environment = completeEnvironment();
        environment.put("STRATUS_POLARIS_URI", "http://127.0.0.1:8181/api/catalog");
        environment.put("STRATUS_POLARIS_ALLOW_HTTP", "true");

        var config = CatalogVerifierConfig.from(environment);

        assertEquals("http", config.polarisUri().getScheme());
    }

    @Test
    void rejectsAPolarisUriWithEmbeddedCredentialsOrQuery() {
        for (String invalid : new String[] {
                "https://user:secret@polaris.stratus.local:8181/api/catalog",
                "https://polaris.stratus.local:8181/api/catalog?token=x"}) {
            var environment = completeEnvironment();
            environment.put("STRATUS_POLARIS_URI", invalid);
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
            var environment = completeEnvironment();
            environment.put("CEPH_RGW_ENDPOINT", invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogVerifierConfig.from(environment),
                    invalid + " must be rejected");
        }
    }

    @Test
    void rejectsPlainHttpStorageEndpointWithoutTheDevelopmentOverride() {
        var environment = completeEnvironment();
        environment.put("CEPH_RGW_ENDPOINT", "http://object-store.stratus.local:8443");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> CatalogVerifierConfig.from(environment));
        assertTrue(failure.getMessage().contains("CEPH_RGW_ALLOW_HTTP"));
    }

    @Test
    void redactsBothSecretsFromToString() {
        var rendered = CatalogVerifierConfig.from(completeEnvironment()).toString();

        assertFalse(rendered.contains("polaris-secret-value"), "the Polaris client secret must be redacted");
        assertFalse(rendered.contains("storage-secret-value"), "the storage secret key must be redacted");
        assertTrue(rendered.contains("<redacted>"));
    }
}
