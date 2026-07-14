package dev.stratus.verification.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.time.Duration;

public record StorageVerifierConfig(
        URI endpoint,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess,
        Set<String> requiredBuckets,
        String probeBucket,
        Duration connectionTimeout,
        Duration socketTimeout,
        Duration apiCallAttemptTimeout,
        Duration apiCallTimeout) {

    public static final Set<String> REQUIRED_BUCKETS = Set.of(
            "stratus-landing",
            "stratus-bronze",
            "stratus-silver",
            "stratus-gold",
            "stratus-platform");

    public StorageVerifierConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        accessKey = requireValue(accessKey, "CEPH_RGW_ACCESS_KEY");
        secretKey = requireValue(secretKey, "CEPH_RGW_SECRET_KEY");
        requiredBuckets = Set.copyOf(requiredBuckets);
        probeBucket = requireValue(probeBucket, "CEPH_RGW_PROBE_BUCKET");
        if (!requiredBuckets.contains(probeBucket)) {
            throw new IllegalArgumentException("CEPH_RGW_PROBE_BUCKET must be one of the required Stratus buckets");
        }
        requirePositive(connectionTimeout, "S3_CONNECTION_TIMEOUT_MS");
        requirePositive(socketTimeout, "S3_SOCKET_TIMEOUT_MS");
        requirePositive(apiCallAttemptTimeout, "S3_API_CALL_ATTEMPT_TIMEOUT_MS");
        requirePositive(apiCallTimeout, "S3_API_CALL_TIMEOUT_MS");
        if (apiCallTimeout.compareTo(apiCallAttemptTimeout) < 0) {
            throw new IllegalArgumentException("S3_API_CALL_TIMEOUT_MS must be at least S3_API_CALL_ATTEMPT_TIMEOUT_MS");
        }
    }

    public static StorageVerifierConfig from(Map<String, String> environment) {
        var endpoint = parseEndpoint(requireValue(environment.get("CEPH_RGW_ENDPOINT"), "CEPH_RGW_ENDPOINT"));
        var allowHttp = Boolean.parseBoolean(environment.getOrDefault("CEPH_RGW_ALLOW_HTTP", "false"));
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !allowHttp) {
            throw new IllegalArgumentException("CEPH_RGW_ENDPOINT must use HTTPS unless CEPH_RGW_ALLOW_HTTP=true for disposable development");
        }
        if (!Set.of("http", "https").contains(endpoint.getScheme().toLowerCase())) {
            throw new IllegalArgumentException("CEPH_RGW_ENDPOINT must use http or https");
        }
        if (endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || (!endpoint.getPath().isEmpty() && !"/".equals(endpoint.getPath()))) {
            throw new IllegalArgumentException("CEPH_RGW_ENDPOINT must be an origin URL without credentials, path, query, or fragment");
        }
        var orderedBuckets = new LinkedHashSet<>(REQUIRED_BUCKETS);
        return new StorageVerifierConfig(
                endpoint,
                environment.get("CEPH_RGW_ACCESS_KEY"),
                environment.get("CEPH_RGW_SECRET_KEY"),
                Boolean.parseBoolean(environment.getOrDefault("S3_PATH_STYLE_ACCESS", "true")),
                orderedBuckets,
                environment.getOrDefault("CEPH_RGW_PROBE_BUCKET", "stratus-landing"),
                duration(environment, "S3_CONNECTION_TIMEOUT_MS", 5000),
                duration(environment, "S3_SOCKET_TIMEOUT_MS", 10000),
                duration(environment, "S3_API_CALL_ATTEMPT_TIMEOUT_MS", 15000),
                duration(environment, "S3_API_CALL_TIMEOUT_MS", 30000));
    }

    private static URI parseEndpoint(String value) {
        try {
            var endpoint = new URI(value);
            if (endpoint.getScheme() == null || endpoint.getHost() == null) {
                throw new IllegalArgumentException("CEPH_RGW_ENDPOINT must be an absolute URL with a host");
            }
            return endpoint;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("CEPH_RGW_ENDPOINT is not a valid URL", exception);
        }
    }

    private static String requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static Duration duration(Map<String, String> environment, String name, long defaultMillis) {
        try {
            return Duration.ofMillis(Long.parseLong(environment.getOrDefault(name, Long.toString(defaultMillis))));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer number of milliseconds", exception);
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
    }

    @Override
    public String toString() {
        return "StorageVerifierConfig[endpoint=" + endpoint
                + ", accessKey=" + accessKey
                + ", secretKey=<redacted>"
                + ", pathStyleAccess=" + pathStyleAccess
                + ", requiredBuckets=" + requiredBuckets
                + ", probeBucket=" + probeBucket
                + ", connectionTimeout=" + connectionTimeout
                + ", socketTimeout=" + socketTimeout
                + ", apiCallAttemptTimeout=" + apiCallAttemptTimeout
                + ", apiCallTimeout=" + apiCallTimeout + "]";
    }
}
