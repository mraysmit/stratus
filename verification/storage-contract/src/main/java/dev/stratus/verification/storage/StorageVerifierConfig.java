package dev.stratus.verification.storage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record StorageVerifierConfig(
        URI endpoint,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess,
        Set<String> requiredBuckets,
        String probeBucket) {

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
                environment.getOrDefault("CEPH_RGW_PROBE_BUCKET", "stratus-landing"));
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

    @Override
    public String toString() {
        return "StorageVerifierConfig[endpoint=" + endpoint
                + ", accessKey=" + accessKey
                + ", secretKey=<redacted>"
                + ", pathStyleAccess=" + pathStyleAccess
                + ", requiredBuckets=" + requiredBuckets
                + ", probeBucket=" + probeBucket + "]";
    }
}
