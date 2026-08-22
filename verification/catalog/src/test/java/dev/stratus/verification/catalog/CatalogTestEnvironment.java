// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import java.util.HashMap;
import java.util.Map;

/**
 * Package-local fixture contract for catalog verifier configuration tests.
 *
 * <h2>Rationale and naming</h2>
 *
 * <p>{@code ENV_*} constants name the public configuration interface, {@code FIXTURE_*} constants
 * are deliberately fake input values, and {@code EXPECTED_*} constants describe derived client
 * properties. Centralizing them keeps configuration validation and REST-property mapping tests on
 * one coherent fixture without introducing test values into production code.
 *
 * <h2>Maintenance and promotion</h2>
 *
 * <p>When the configuration interface changes, update this fixture, production parsing, mapping
 * tests, deployment templates, and operator documentation together. These values must never be
 * replaced with real credentials. Passing tests prove deterministic mapping only: developer, UAT,
 * and production must inject environment-owned secrets and endpoints, validate TLS and
 * authorization live, and promote unchanged approved artifacts between environments.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-22
 * @version 1.0.0
 */
final class CatalogTestEnvironment {

    static final String ENV_POLARIS_URI = "STRATUS_POLARIS_URI";
    static final String ENV_POLARIS_CLIENT_ID = "STRATUS_POLARIS_CLIENT_ID";
    static final String ENV_POLARIS_CLIENT_SECRET = "STRATUS_POLARIS_CLIENT_SECRET";
    static final String ENV_POLARIS_CATALOG = "STRATUS_POLARIS_CATALOG";
    static final String ENV_POLARIS_ALLOW_HTTP = "STRATUS_POLARIS_ALLOW_HTTP";
    static final String ENV_CEPH_ENDPOINT = "CEPH_RGW_ENDPOINT";
    static final String ENV_CEPH_ACCESS_KEY = "CEPH_RGW_ACCESS_KEY";
    static final String ENV_CEPH_SECRET_KEY = "CEPH_RGW_SECRET_KEY";
    static final String ENV_CEPH_ALLOW_HTTP = "CEPH_RGW_ALLOW_HTTP";
    static final String ENV_PATH_STYLE_ACCESS = "S3_PATH_STYLE_ACCESS";

    static final String FIXTURE_POLARIS_URI =
            "https://polaris.stratus.local:8181/api/catalog";
    static final String FIXTURE_POLARIS_CLIENT_ID = "stratus-root";
    static final String FIXTURE_POLARIS_CLIENT_SECRET = "polaris-secret-value";
    static final String FIXTURE_CATALOG = "stratus";
    static final String FIXTURE_CEPH_ENDPOINT = "https://object-store.stratus.local:8443";
    static final String FIXTURE_CEPH_ACCESS_KEY = "svc-polaris-abc123";
    static final String FIXTURE_CEPH_SECRET_KEY = "storage-secret-value";

    static final String EXPECTED_CREDENTIAL =
            FIXTURE_POLARIS_CLIENT_ID + ":" + FIXTURE_POLARIS_CLIENT_SECRET;
    static final String EXPECTED_SCOPE = "PRINCIPAL_ROLE:ALL";
    static final String EXPECTED_FILE_IO = "org.apache.iceberg.aws.s3.S3FileIO";
    static final String EXPECTED_TOKEN_ENDPOINT = FIXTURE_POLARIS_URI + "/v1/oauth/tokens";

    private CatalogTestEnvironment() {
    }

    static Map<String, String> completeEnvironment() {
        var environment = new HashMap<String, String>();
        environment.put(ENV_POLARIS_URI, FIXTURE_POLARIS_URI);
        environment.put(ENV_POLARIS_CLIENT_ID, FIXTURE_POLARIS_CLIENT_ID);
        environment.put(ENV_POLARIS_CLIENT_SECRET, FIXTURE_POLARIS_CLIENT_SECRET);
        environment.put(ENV_POLARIS_CATALOG, FIXTURE_CATALOG);
        environment.put(ENV_CEPH_ENDPOINT, FIXTURE_CEPH_ENDPOINT);
        environment.put(ENV_CEPH_ACCESS_KEY, FIXTURE_CEPH_ACCESS_KEY);
        environment.put(ENV_CEPH_SECRET_KEY, FIXTURE_CEPH_SECRET_KEY);
        return environment;
    }
}
