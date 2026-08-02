// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.ceph;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A minimal REST client that signs requests with AWS Signature Version 4, the
 * scheme Ceph RGW implements for both its S3 data API and its Admin Operations
 * API. "AWS Signature Version 4" is retained here as the upstream protocol
 * identifier, not as cloud-provider terminology for a Stratus concept.
 *
 * <p>This client deliberately bypasses the AWS SDK. Its purpose is to prove
 * that Ceph honors the wire protocol itself — canonical request construction,
 * payload hashing, header signing, and TLS — rather than proving that the SDK
 * can talk to it. It is a real protocol implementation against a real endpoint,
 * never a stand-in for one.
 *
 * <p>Signing follows the canonical request, string-to-sign, derived-key
 * sequence documented by the SigV4 specification and implemented by RGW.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-30
 * @version 1.0.0
 */
final class SignatureV4RestClient implements AutoCloseable {

    /** The payload hash RGW expects when a request carries no body. */
    static final String EMPTY_PAYLOAD_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String TERMINATOR = "aws4_request";
    private static final DateTimeFormatter AMZ_DATE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);
    private static final DateTimeFormatter AMZ_DATE =
        DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);

    private final URI endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final String service;
    private final HttpClient http;

    SignatureV4RestClient(URI endpoint, String accessKey, String secretKey, String region, String service,
                          Duration connectTimeout) {
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
        this.service = service;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    /**
     * Signs and sends a request. The {@code path} is the already-decoded
     * resource path; {@code query} entries are canonicalized in sorted order.
     * The returned response body is raw bytes so binary round-trips can be
     * byte-compared without a charset in the way.
     */
    HttpResponse<byte[]> send(String method, String path, Map<String, String> query, byte[] body,
                              Duration requestTimeout) {
        byte[] payload = body == null ? new byte[0] : body;
        String payloadHash = hex(sha256(payload));
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDateTime = AMZ_DATE_TIME.format(now);
        String scopeDate = AMZ_DATE.format(now);
        String hostHeader = hostHeader();

        Map<String, String> signedHeaders = new TreeMap<>();
        signedHeaders.put("host", hostHeader);
        signedHeaders.put("x-amz-content-sha256", payloadHash);
        signedHeaders.put("x-amz-date", amzDateTime);

        String authorization = authorization(method, path, query, signedHeaders, payloadHash, amzDateTime, scopeDate);
        return dispatch(method, path, query, payload, signedHeaders, authorization, requestTimeout);
    }

    /**
     * Sends a request whose {@code Authorization} header is replaced wholesale.
     * Used to prove that RGW rejects a signature it did not compute, which is
     * genuine product behavior observed over the wire.
     */
    HttpResponse<byte[]> sendWithAuthorization(String method, String path, Map<String, String> query,
                                               String authorization, Duration requestTimeout) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", hostHeader());
        headers.put("x-amz-content-sha256", EMPTY_PAYLOAD_SHA256);
        headers.put("x-amz-date", AMZ_DATE_TIME.format(now));
        return dispatch(method, path, query, new byte[0], headers, authorization, requestTimeout);
    }

    private HttpResponse<byte[]> dispatch(String method, String path, Map<String, String> query, byte[] payload,
                                          Map<String, String> headers, String authorization,
                                          Duration requestTimeout) {
        URI requestUri = URI.create(endpoint + canonicalPath(path) + queryString(query));
        String surface = path.startsWith("/admin/") ? "rgw-admin" : "s3";
        RestApiLogging.Exchange exchange = RestApiLogging.started(
            surface, operation(surface, method, path, query), resource(surface, path, query),
            method, requestUri, payload, false, authorization != null);
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(requestUri)
            .timeout(requestTimeout)
            .method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
        for (Map.Entry<String, String> header : headers.entrySet()) {
            // The JDK client owns the Host header; setting it explicitly is rejected.
            if (!"host".equals(header.getKey())) {
                request.header(header.getKey(), header.getValue());
            }
        }
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            RestApiLogging.completed(exchange, response.statusCode(), response.body(), false, response.headers());
            return response;
        } catch (java.io.IOException e) {
            RestApiLogging.failed(exchange, e);
            throw new java.io.UncheckedIOException("Signed REST request failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            RestApiLogging.failed(exchange, e);
            throw new IllegalStateException("Interrupted during " + method + " " + path, e);
        }
    }

    private static String operation(String surface, String method, String path, Map<String, String> query) {
        if ("rgw-admin".equals(surface)) {
            if ("/admin/bucket".equals(path)) {
                return query.containsKey("bucket") ? "read-bucket-statistics" : "list-buckets";
            }
            return "/admin/user".equals(path) ? "read-user" : "admin-request";
        }
        return switch (method) {
            case "PUT" -> "write-object";
            case "DELETE" -> "delete-object";
            case "GET" -> query.containsKey("list-type") ? "list-objects"
                : path.substring(1).contains("/") ? "read-object" : "read-bucket";
            default -> "object-request";
        };
    }

    private static String resource(String surface, String path, Map<String, String> query) {
        if ("rgw-admin".equals(surface)) {
            if (query.containsKey("bucket")) {
                return "bucket=" + query.get("bucket");
            }
            if (query.containsKey("uid")) {
                return "uid=" + query.get("uid");
            }
            return path;
        }
        String withoutLeadingSlash = path.startsWith("/") ? path.substring(1) : path;
        int separator = withoutLeadingSlash.indexOf('/');
        if (separator < 0) {
            return "bucket=" + withoutLeadingSlash;
        }
        return "bucket=" + withoutLeadingSlash.substring(0, separator)
            + " key=" + withoutLeadingSlash.substring(separator + 1);
    }

    private String authorization(String method, String path, Map<String, String> query,
                                 Map<String, String> headers, String payloadHash,
                                 String amzDateTime, String scopeDate) {
        String signedHeaderNames = String.join(";", headers.keySet());
        StringBuilder canonicalHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            canonicalHeaders.append(header.getKey()).append(':').append(header.getValue().trim()).append('\n');
        }
        String canonicalRequest = String.join("\n",
            method,
            canonicalPath(path),
            canonicalQuery(query),
            canonicalHeaders.toString(),
            signedHeaderNames,
            payloadHash);

        String scope = String.join("/", scopeDate, region, service, TERMINATOR);
        String stringToSign = String.join("\n", ALGORITHM, amzDateTime, scope, hex(sha256(utf8(canonicalRequest))));
        byte[] signingKey = signingKey(scopeDate);
        String signature = hex(hmacSha256(signingKey, utf8(stringToSign)));

        return ALGORITHM + " Credential=" + accessKey + "/" + scope
            + ", SignedHeaders=" + signedHeaderNames
            + ", Signature=" + signature;
    }

    private byte[] signingKey(String scopeDate) {
        byte[] dateKey = hmacSha256(utf8("AWS4" + secretKey), utf8(scopeDate));
        byte[] regionKey = hmacSha256(dateKey, utf8(region));
        byte[] serviceKey = hmacSha256(regionKey, utf8(service));
        return hmacSha256(serviceKey, utf8(TERMINATOR));
    }

    private String hostHeader() {
        int port = endpoint.getPort();
        boolean defaultPort = port == -1
            || ("https".equals(endpoint.getScheme()) && port == 443)
            || ("http".equals(endpoint.getScheme()) && port == 80);
        return defaultPort ? endpoint.getHost() : endpoint.getHost() + ":" + port;
    }

    private static String canonicalPath(String path) {
        if (path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        List<String> encoded = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            encoded.add(uriEncode(segment, false));
        }
        return String.join("/", encoded);
    }

    private static String canonicalQuery(Map<String, String> query) {
        Map<String, String> sorted = new TreeMap<>(query);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> parameter : sorted.entrySet()) {
            parts.add(uriEncode(parameter.getKey(), true) + "=" + uriEncode(parameter.getValue(), true));
        }
        return String.join("&", parts);
    }

    private static String queryString(Map<String, String> query) {
        return query.isEmpty() ? "" : "?" + canonicalQuery(query);
    }

    /**
     * RFC 3986 encoding as SigV4 requires it: unreserved characters pass
     * through, everything else becomes uppercase percent-encoded bytes. The
     * path form preserves {@code /} because S3 does not double-encode it.
     */
    private static String uriEncode(String value, boolean encodeSlash) {
        StringBuilder encoded = new StringBuilder();
        for (byte raw : utf8(value)) {
            int character = raw & 0xFF;
            boolean unreserved = (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '-' || character == '.' || character == '_' || character == '~';
            if (unreserved) {
                encoded.append((char) character);
            } else if (character == '/' && !encodeSlash) {
                encoded.append('/');
            } else {
                encoded.append('%').append(String.format(Locale.ROOT, "%02X", character));
            }
        }
        return encoded.toString();
    }

    static Map<String, String> query(String... keysAndValues) {
        Map<String, String> query = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            query.put(keysAndValues[index], keysAndValues[index + 1]);
        }
        return query;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to sign REST requests", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(content);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 is required to sign REST requests", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return hex.toString();
    }

    @Override
    public void close() {
        http.close();
    }
}
