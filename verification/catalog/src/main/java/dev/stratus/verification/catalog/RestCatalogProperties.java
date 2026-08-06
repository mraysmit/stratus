// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the Iceberg REST catalog client property set from verifier
 * configuration. Property keys are Iceberg's public configuration surface
 * (CatalogProperties and S3FileIOProperties), kept as literals so this main
 * tree stays free of Iceberg dependencies; for a Polaris catalog the
 * {@code warehouse} property carries the catalog name, not a storage
 * location.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
public final class RestCatalogProperties {

    private RestCatalogProperties() {
    }

    public static Map<String, String> from(CatalogVerifierConfig config) {
        var properties = new LinkedHashMap<String, String>();
        properties.put("uri", config.polarisUri().toString());
        properties.put("credential", config.clientId() + ":" + config.clientSecret());
        properties.put("scope", "PRINCIPAL_ROLE:ALL");
        // Both auth properties are explicit: leaving them to be inferred logs
        // a warning per connection, and the automatic token-endpoint fallback
        // is deprecated for removal (apache/iceberg#10537). The endpoint is
        // the Iceberg REST OAuth path under the catalog URI, matching what
        // the harness bootstrap authenticates against.
        properties.put("rest.auth.type", "oauth2");
        properties.put("oauth2-server-uri", config.polarisUri() + "/v1/oauth/tokens");
        properties.put("warehouse", config.catalogName());
        properties.put("io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
        properties.put("s3.endpoint", config.storageEndpoint().toString());
        properties.put("s3.access-key-id", config.storageAccessKey());
        properties.put("s3.secret-access-key", config.storageSecretKey());
        properties.put("s3.path-style-access", Boolean.toString(config.pathStyleAccess()));
        // The verifier supplies its own storage credentials, so it declines
        // credential vending instead of asking the catalog to subscope.
        properties.put("header.X-Iceberg-Access-Delegation", "none");
        return properties;
    }
}
