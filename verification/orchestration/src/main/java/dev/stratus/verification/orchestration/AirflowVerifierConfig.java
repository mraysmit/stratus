// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, validated connection and polling configuration for the Airflow verifier. */
public record AirflowVerifierConfig(
        URI baseUri,
        String username,
        String password,
        boolean anonymousAdmin,
        Duration pollInterval,
        Duration runTimeout) {

    private static final long DEFAULT_POLL_INTERVAL_MS = 2_000;
    private static final long DEFAULT_RUN_TIMEOUT_MS = 900_000;
    private static final long MAX_POLL_INTERVAL_MS = 60_000;
    private static final long MAX_RUN_TIMEOUT_MS = 3_600_000;

    public AirflowVerifierConfig {
        Objects.requireNonNull(baseUri, "baseUri");
        username = requireValue(username, "STRATUS_AIRFLOW_USERNAME");
        password = requireValue(password, "STRATUS_AIRFLOW_PASSWORD");
        Objects.requireNonNull(pollInterval, "pollInterval");
        Objects.requireNonNull(runTimeout, "runTimeout");
    }

    public static AirflowVerifierConfig from(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        URI baseUri = parseOrigin(requireValue(
                environment.get("STRATUS_AIRFLOW_BASE_URL"), "STRATUS_AIRFLOW_BASE_URL"));
        boolean allowHttp = Boolean.parseBoolean(
                environment.getOrDefault("STRATUS_AIRFLOW_ALLOW_HTTP", "false"));
        if (!"https".equalsIgnoreCase(baseUri.getScheme()) && !allowHttp) {
            throw new IllegalArgumentException("STRATUS_AIRFLOW_BASE_URL must use HTTPS unless "
                    + "STRATUS_AIRFLOW_ALLOW_HTTP=true for disposable development");
        }
        return new AirflowVerifierConfig(
                baseUri,
                environment.get("STRATUS_AIRFLOW_USERNAME"),
                environment.get("STRATUS_AIRFLOW_PASSWORD"),
                Boolean.parseBoolean(environment.getOrDefault(
                        "STRATUS_AIRFLOW_ANONYMOUS_ADMIN", "false")),
                parseDuration(environment, "STRATUS_AIRFLOW_POLL_INTERVAL_MS",
                        DEFAULT_POLL_INTERVAL_MS, MAX_POLL_INTERVAL_MS),
                parseDuration(environment, "STRATUS_AIRFLOW_RUN_TIMEOUT_MS",
                        DEFAULT_RUN_TIMEOUT_MS, MAX_RUN_TIMEOUT_MS));
    }

    private static URI parseOrigin(String value) {
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("STRATUS_AIRFLOW_BASE_URL is not a valid URL", failure);
        }
        if (uri.getScheme() == null || uri.getHost() == null
                || !Set.of("http", "https").contains(uri.getScheme().toLowerCase())) {
            throw new IllegalArgumentException(
                    "STRATUS_AIRFLOW_BASE_URL must be an absolute HTTP(S) URL with a host");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (!uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("STRATUS_AIRFLOW_BASE_URL must be an origin URL "
                    + "without credentials, path, query, or fragment");
        }
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return URI.create(normalized);
    }

    private static Duration parseDuration(
            Map<String, String> environment, String name, long defaultMs, long maximumMs) {
        String value = environment.getOrDefault(name, Long.toString(defaultMs));
        final long milliseconds;
        try {
            milliseconds = Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be an integer number of milliseconds", failure);
        }
        if (milliseconds <= 0 || milliseconds > maximumMs) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximumMs);
        }
        return Duration.ofMillis(milliseconds);
    }

    private static String requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    @Override
    public String toString() {
        return "AirflowVerifierConfig[baseUri=" + baseUri
                + ", username=" + username
                + ", password=<redacted>"
                + ", anonymousAdmin=" + anonymousAdmin
                + ", pollInterval=" + pollInterval
                + ", runTimeout=" + runTimeout + "]";
    }
}
