// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Offline mapping contract from verifier configuration to Iceberg REST catalog properties.
 *
 * <h2>Rationale and proof boundary</h2>
 *
 * <p>Polaris OAuth, catalog naming, S3FileIO, path-style access, and credential delegation must map
 * to exact Iceberg property names. An apparently valid configuration can otherwise fall back to a
 * deprecated token endpoint, infer authentication, or request vended credentials unexpectedly.
 * The shared {@link CatalogTestEnvironment} supplies one coherent set of fake inputs. This test
 * proves mapping only; it does not contact Polaris or Ceph.
 *
 * <h2>Maintenance and promotion</h2>
 *
 * <p>When Iceberg or Polaris changes a property contract, update the named property constants,
 * mapper, fixture, compatibility documentation, and live conformance tests together. Developer,
 * UAT, and production must inject environment-owned secrets and endpoints and promote the same
 * approved verifier and runtime digests; these fake values must never become deployment defaults.
 *
 * <p>This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.1.0
 */
@Tag("unit")
final class RestCatalogPropertiesTest {

    private static final String PROPERTY_URI = "uri";
    private static final String PROPERTY_CREDENTIAL = "credential";
    private static final String PROPERTY_SCOPE = "scope";
    private static final String PROPERTY_WAREHOUSE = "warehouse";
    private static final String PROPERTY_FILE_IO = "io-impl";
    private static final String PROPERTY_S3_ENDPOINT = "s3.endpoint";
    private static final String PROPERTY_S3_ACCESS_KEY = "s3.access-key-id";
    private static final String PROPERTY_S3_SECRET_KEY = "s3.secret-access-key";
    private static final String PROPERTY_PATH_STYLE = "s3.path-style-access";
    private static final String PROPERTY_ACCESS_DELEGATION =
            "header.X-Iceberg-Access-Delegation";
    private static final String PROPERTY_AUTH_TYPE = "rest.auth.type";
    private static final String PROPERTY_OAUTH_SERVER_URI = "oauth2-server-uri";
    private static final String EXPECTED_PATH_STYLE_ENABLED = "true";
    private static final String EXPECTED_PATH_STYLE_DISABLED = "false";
    private static final String EXPECTED_ACCESS_DELEGATION = "none";
    private static final String EXPECTED_AUTH_TYPE = "oauth2";

    @Test
    void mapsTheCompleteRestCatalogClientPropertySet() {
        Map<String, String> properties = RestCatalogProperties.from(config());

        assertEquals(CatalogTestEnvironment.FIXTURE_POLARIS_URI,
                properties.get(PROPERTY_URI));
        assertEquals(CatalogTestEnvironment.EXPECTED_CREDENTIAL,
                properties.get(PROPERTY_CREDENTIAL));
        assertEquals(CatalogTestEnvironment.EXPECTED_SCOPE,
                properties.get(PROPERTY_SCOPE));
        assertEquals(CatalogTestEnvironment.FIXTURE_CATALOG,
                properties.get(PROPERTY_WAREHOUSE),
                "for a Polaris catalog the warehouse property is the catalog name");
        assertEquals(CatalogTestEnvironment.EXPECTED_FILE_IO,
                properties.get(PROPERTY_FILE_IO));
        assertEquals(CatalogTestEnvironment.FIXTURE_CEPH_ENDPOINT,
                properties.get(PROPERTY_S3_ENDPOINT));
        assertEquals(CatalogTestEnvironment.FIXTURE_CEPH_ACCESS_KEY,
                properties.get(PROPERTY_S3_ACCESS_KEY));
        assertEquals(CatalogTestEnvironment.FIXTURE_CEPH_SECRET_KEY,
                properties.get(PROPERTY_S3_SECRET_KEY));
        assertEquals(EXPECTED_PATH_STYLE_ENABLED, properties.get(PROPERTY_PATH_STYLE));
        assertEquals(EXPECTED_ACCESS_DELEGATION, properties.get(PROPERTY_ACCESS_DELEGATION),
                "the verifier supplies its own storage credentials and must decline "
                        + "vended-credential delegation");
        assertEquals(EXPECTED_AUTH_TYPE, properties.get(PROPERTY_AUTH_TYPE),
                "the auth type must be explicit; relying on inference logs a warning on every "
                        + "connection");
        assertEquals(CatalogTestEnvironment.EXPECTED_TOKEN_ENDPOINT,
                properties.get(PROPERTY_OAUTH_SERVER_URI),
                "the token endpoint must be explicit; Iceberg's automatic fallback is deprecated "
                        + "for removal");
    }

    @Test
    void reflectsADisabledPathStyleFlag() {
        var environment = CatalogTestEnvironment.completeEnvironment();
        environment.put(
                CatalogTestEnvironment.ENV_PATH_STYLE_ACCESS, EXPECTED_PATH_STYLE_DISABLED);

        assertEquals(EXPECTED_PATH_STYLE_DISABLED, RestCatalogProperties.from(
                CatalogVerifierConfig.from(environment)).get(PROPERTY_PATH_STYLE));
    }

    private static CatalogVerifierConfig config() {
        return CatalogVerifierConfig.from(CatalogTestEnvironment.completeEnvironment());
    }
}
