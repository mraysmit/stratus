// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.secrets;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration for the secret-store conformance verifier: the
 * store endpoint, the access token, and the KV layout under verification.
 * Invalid configuration fails here, before any network operation begins.
 *
 * This record is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
public record SecretStoreVerifierConfig(
        URI endpoint,
        String token,
        String kvMount,
        String serviceIdentityPath) {

    public SecretStoreVerifierConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        token = requireValue(token, "OPENBAO_TOKEN");
        kvMount = requireValue(kvMount, "OPENBAO_KV_MOUNT");
        serviceIdentityPath = requireValue(serviceIdentityPath, "OPENBAO_SERVICE_IDENTITY_PATH");
    }

    public static SecretStoreVerifierConfig from(Map<String, String> environment) {
        var endpoint = parseOriginUrl(
                requireValue(environment.get("OPENBAO_ENDPOINT"), "OPENBAO_ENDPOINT"));
        var allowHttp = Boolean.parseBoolean(environment.getOrDefault("OPENBAO_ALLOW_HTTP", "false"));
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !allowHttp) {
            throw new IllegalArgumentException(
                    "OPENBAO_ENDPOINT must use HTTPS unless OPENBAO_ALLOW_HTTP=true for disposable development");
        }
        return new SecretStoreVerifierConfig(
                endpoint,
                environment.get("OPENBAO_TOKEN"),
                environment.getOrDefault("OPENBAO_KV_MOUNT", "secret"),
                environment.getOrDefault("OPENBAO_SERVICE_IDENTITY_PATH", "stratus/service-identities"));
    }

    private static URI parseOriginUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("OPENBAO_ENDPOINT is not a valid URL", exception);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("OPENBAO_ENDPOINT must be an absolute URL with a host");
        }
        if (!Set.of("http", "https").contains(uri.getScheme().toLowerCase())) {
            throw new IllegalArgumentException("OPENBAO_ENDPOINT must use http or https");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (!uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException(
                    "OPENBAO_ENDPOINT must be an origin URL without credentials, path, query, or fragment");
        }
        return uri;
    }

    private static String requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    @Override
    public String toString() {
        return "SecretStoreVerifierConfig[endpoint=" + endpoint
                + ", token=<redacted>"
                + ", kvMount=" + kvMount
                + ", serviceIdentityPath=" + serviceIdentityPath + "]";
    }
}
