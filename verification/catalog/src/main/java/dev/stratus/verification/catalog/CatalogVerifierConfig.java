// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration for the catalog conformance verifier: the Polaris
 * REST endpoint and principal, the target catalog, and the object-storage
 * binding the verifier uses to confirm where table files actually land.
 * Invalid configuration fails here, before any network operation begins.
 *
 * This record is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
public record CatalogVerifierConfig(
        URI polarisUri,
        String clientId,
        String clientSecret,
        String catalogName,
        URI storageEndpoint,
        String storageAccessKey,
        String storageSecretKey,
        boolean pathStyleAccess) {

    public CatalogVerifierConfig {
        Objects.requireNonNull(polarisUri, "polarisUri");
        clientId = requireValue(clientId, "STRATUS_POLARIS_CLIENT_ID");
        clientSecret = requireValue(clientSecret, "STRATUS_POLARIS_CLIENT_SECRET");
        catalogName = requireValue(catalogName, "STRATUS_POLARIS_CATALOG");
        Objects.requireNonNull(storageEndpoint, "storageEndpoint");
        storageAccessKey = requireValue(storageAccessKey, "CEPH_RGW_ACCESS_KEY");
        storageSecretKey = requireValue(storageSecretKey, "CEPH_RGW_SECRET_KEY");
    }

    public static CatalogVerifierConfig from(Map<String, String> environment) {
        var polarisUri = parseAbsoluteHttpUri(
                requireValue(environment.get("STRATUS_POLARIS_URI"), "STRATUS_POLARIS_URI"),
                "STRATUS_POLARIS_URI");
        requireSecureScheme(polarisUri, "STRATUS_POLARIS_URI", "STRATUS_POLARIS_ALLOW_HTTP", environment);
        if (polarisUri.getUserInfo() != null || polarisUri.getQuery() != null || polarisUri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "STRATUS_POLARIS_URI must not contain credentials, a query, or a fragment");
        }

        var storageEndpoint = parseAbsoluteHttpUri(
                requireValue(environment.get("CEPH_RGW_ENDPOINT"), "CEPH_RGW_ENDPOINT"),
                "CEPH_RGW_ENDPOINT");
        requireSecureScheme(storageEndpoint, "CEPH_RGW_ENDPOINT", "CEPH_RGW_ALLOW_HTTP", environment);
        if (storageEndpoint.getUserInfo() != null || storageEndpoint.getQuery() != null
                || storageEndpoint.getFragment() != null
                || (!storageEndpoint.getPath().isEmpty() && !"/".equals(storageEndpoint.getPath()))) {
            throw new IllegalArgumentException(
                    "CEPH_RGW_ENDPOINT must be an origin URL without credentials, path, query, or fragment");
        }

        return new CatalogVerifierConfig(
                polarisUri,
                environment.get("STRATUS_POLARIS_CLIENT_ID"),
                environment.get("STRATUS_POLARIS_CLIENT_SECRET"),
                environment.get("STRATUS_POLARIS_CATALOG"),
                storageEndpoint,
                environment.get("CEPH_RGW_ACCESS_KEY"),
                environment.get("CEPH_RGW_SECRET_KEY"),
                Boolean.parseBoolean(environment.getOrDefault("S3_PATH_STYLE_ACCESS", "true")));
    }

    private static URI parseAbsoluteHttpUri(String value, String name) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(name + " is not a valid URL", exception);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an absolute URL with a host");
        }
        if (!Set.of("http", "https").contains(uri.getScheme().toLowerCase())) {
            throw new IllegalArgumentException(name + " must use http or https");
        }
        return uri;
    }

    private static void requireSecureScheme(URI uri, String name, String overrideName,
                                            Map<String, String> environment) {
        var allowHttp = Boolean.parseBoolean(environment.getOrDefault(overrideName, "false"));
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !allowHttp) {
            throw new IllegalArgumentException(
                    name + " must use HTTPS unless " + overrideName + "=true for disposable development");
        }
    }

    private static String requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    @Override
    public String toString() {
        return "CatalogVerifierConfig[polarisUri=" + polarisUri
                + ", clientId=" + clientId
                + ", clientSecret=<redacted>"
                + ", catalogName=" + catalogName
                + ", storageEndpoint=" + storageEndpoint
                + ", storageAccessKey=" + storageAccessKey
                + ", storageSecretKey=<redacted>"
                + ", pathStyleAccess=" + pathStyleAccess + "]";
    }
}
