// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Offline checks for client configuration that must fail before networking. */
@Tag("unit")
final class SparkClientConfigTest {

    @Test
    void derivesTheExplicitPolarisTokenEndpointFromTheCatalogEndpoint() {
        SparkClientConfig config = config(1);

        assertEquals("https://polaris.example:8181/api/catalog/v1/oauth/tokens",
                config.catalogOAuth2Uri());
    }

    @Test
    void refusesAnApplicationWithNoExecutableCore() {
        assertThrows(IllegalArgumentException.class, () -> config(0));
    }

    @Test
    void aResourceOverridePreservesTheConnectionAndChangesOnlyTheCoreRequest() {
        SparkClientConfig original = config(1);
        SparkClientConfig expanded = original.withApplicationCores(2);

        assertEquals(2, expanded.applicationCores());
        assertEquals(original.catalogUri(), expanded.catalogUri());
        assertEquals(original.principalCredential(), expanded.principalCredential());
    }

    private static SparkClientConfig config(int cores) {
        return new SparkClientConfig(
                "configuration-test", "spark://127.0.0.1:7077", "stratus",
                "https://polaris.example:8181/api/catalog", "client:secret",
                "https://object-store.example", "access", "secret",
                "host.docker.internal", 17077, 17078, cores);
    }
}
