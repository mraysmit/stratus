// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Set;
import org.apache.iceberg.rest.RESTCatalog;

/**
 * Shared entry point for the live catalog-integration tests: enforces the
 * profile's opt-in switch, validates the required environment by name, and
 * connects the real REST catalog client. Kept in one place so every
 * conformance class states the same environment contract.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-06
 * @version 1.0.0
 */
final class LiveCatalog {

    private LiveCatalog() {
    }

    /**
     * Skips the calling test unless the live opt-in switch is set; under the
     * catalog-integration profile the switch and the full environment are
     * required instead, so the profile can never silently pass by skipping.
     */
    static RESTCatalog connect() {
        if (Boolean.getBoolean("catalog.integration.required")) {
            assertTrue(Boolean.parseBoolean(System.getenv("STRATUS_CATALOG_INTEGRATION")),
                    "STRATUS_CATALOG_INTEGRATION=true is required by the selected Maven profile");
            for (String name : Set.of(
                    "STRATUS_POLARIS_URI",
                    "STRATUS_POLARIS_CLIENT_ID",
                    "STRATUS_POLARIS_CLIENT_SECRET",
                    "STRATUS_POLARIS_CATALOG",
                    "CEPH_RGW_ENDPOINT",
                    "CEPH_RGW_ACCESS_KEY",
                    "CEPH_RGW_SECRET_KEY")) {
                assertTrue(System.getenv(name) != null && !System.getenv(name).isBlank(),
                        name + " is required by the selected Maven profile");
            }
        }
        assumeTrue(Boolean.parseBoolean(System.getenv("STRATUS_CATALOG_INTEGRATION")),
                "Set STRATUS_CATALOG_INTEGRATION=true to run against a live catalog");

        var config = CatalogVerifierConfig.from(System.getenv());
        var catalog = new RESTCatalog();
        catalog.initialize(config.catalogName(), RestCatalogProperties.from(config));
        CatalogVerificationLogging.catalogConnected(config);
        return catalog;
    }
}
