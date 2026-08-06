// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Mapping from verifier configuration to the Iceberg REST catalog client
 * property set: the OAuth client credential, the warehouse (which for a
 * Polaris catalog is the catalog name, not a storage location), and the
 * S3FileIO binding against the object-store endpoint.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
@Tag("unit")
final class RestCatalogPropertiesTest {

    private static CatalogVerifierConfig config() {
        return CatalogVerifierConfig.from(Map.of(
                "STRATUS_POLARIS_URI", "https://polaris.stratus.local:8181/api/catalog",
                "STRATUS_POLARIS_CLIENT_ID", "stratus-root",
                "STRATUS_POLARIS_CLIENT_SECRET", "polaris-secret-value",
                "STRATUS_POLARIS_CATALOG", "stratus",
                "CEPH_RGW_ENDPOINT", "https://object-store.stratus.local:8443",
                "CEPH_RGW_ACCESS_KEY", "svc-polaris-abc123",
                "CEPH_RGW_SECRET_KEY", "storage-secret-value"));
    }

    @Test
    void mapsTheCompleteRestCatalogClientPropertySet() {
        Map<String, String> properties = RestCatalogProperties.from(config());

        assertEquals("https://polaris.stratus.local:8181/api/catalog", properties.get("uri"));
        assertEquals("stratus-root:polaris-secret-value", properties.get("credential"));
        assertEquals("PRINCIPAL_ROLE:ALL", properties.get("scope"));
        assertEquals("stratus", properties.get("warehouse"),
                "for a Polaris catalog the warehouse property is the catalog name");
        assertEquals("org.apache.iceberg.aws.s3.S3FileIO", properties.get("io-impl"));
        assertEquals("https://object-store.stratus.local:8443", properties.get("s3.endpoint"));
        assertEquals("svc-polaris-abc123", properties.get("s3.access-key-id"));
        assertEquals("storage-secret-value", properties.get("s3.secret-access-key"));
        assertEquals("true", properties.get("s3.path-style-access"));
        assertEquals("none", properties.get("header.X-Iceberg-Access-Delegation"),
                "the verifier supplies its own storage credentials and must decline vended-credential delegation");
        assertEquals("oauth2", properties.get("rest.auth.type"),
                "the auth type must be explicit; relying on inference logs a warning on every connection");
        assertEquals("https://polaris.stratus.local:8181/api/catalog/v1/oauth/tokens",
                properties.get("oauth2-server-uri"),
                "the token endpoint must be explicit; Iceberg's automatic fallback is deprecated for removal");
    }

    @Test
    void reflectsADisabledPathStyleFlag() {
        var environment = new java.util.HashMap<String, String>();
        environment.put("STRATUS_POLARIS_URI", "https://polaris.stratus.local:8181/api/catalog");
        environment.put("STRATUS_POLARIS_CLIENT_ID", "stratus-root");
        environment.put("STRATUS_POLARIS_CLIENT_SECRET", "secret");
        environment.put("STRATUS_POLARIS_CATALOG", "stratus");
        environment.put("CEPH_RGW_ENDPOINT", "https://object-store.stratus.local:8443");
        environment.put("CEPH_RGW_ACCESS_KEY", "key");
        environment.put("CEPH_RGW_SECRET_KEY", "secret");
        environment.put("S3_PATH_STYLE_ACCESS", "false");

        assertEquals("false", RestCatalogProperties.from(
                CatalogVerifierConfig.from(environment)).get("s3.path-style-access"));
    }
}
