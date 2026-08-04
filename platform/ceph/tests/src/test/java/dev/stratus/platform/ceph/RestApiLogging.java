// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.ceph;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sanitized protocol-boundary logging shared by the live Ceph REST conformance tests.
 *
 * <p>Request and response bodies, credentials, signatures, authorization
 * headers, cookies, and bearer tokens never cross this boundary. INFO records
 * the semantic operation and resource, byte counts, and SHA-256 fingerprints
 * of non-sensitive test data. DEBUG adds query parameter names (never values),
 * authentication presence, content type, ETag, and the server request ID.
 */
final class RestApiLogging {

    static final String LOGGER_NAME = "dev.stratus.platform.ceph.rest";
    private static final String LOG_FORMAT = "%1$tFT%1$tT.%1$tL%1$tz %4$s %2$s %5$s%6$s%n";

    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", LOG_FORMAT);
    }

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    static {
        configure(System.getenv().getOrDefault("STRATUS_LOG_LEVEL", "INFO"));
    }

    private RestApiLogging() {
    }

    static Exchange started(String surface, String operation, String resource, String method, URI uri,
                            byte[] requestData, boolean sensitiveRequestData,
                            boolean authenticationMaterialPresent) {
        byte[] data = requestData == null ? new byte[0] : requestData;
        Exchange exchange = new Exchange(surface, operation, safeToken(resource), method, safePath(uri),
            queryParameterNames(uri), data.length, fingerprint(data, sensitiveRequestData),
            authenticationMaterialPresent, System.nanoTime());
        LOGGER.debug("REST request started surface={} operation={} resource={} method={} path={} "
                + "queryParameters={} requestBytes={} requestDataSha256={} authenticationMaterialPresent={}",
            exchange.surface(), exchange.operation(), exchange.resource(), exchange.method(), exchange.path(),
            exchange.queryParameterNames(), exchange.requestBytes(), exchange.requestDataSha256(),
            exchange.authenticationMaterialPresent());
        return exchange;
    }

    static void completed(Exchange exchange, int status, byte[] responseData, boolean sensitiveResponseData,
                          HttpHeaders headers) {
        byte[] data = responseData == null ? new byte[0] : responseData;
        long elapsedMillis = elapsedMillis(exchange);
        String responseFingerprint = fingerprint(data, sensitiveResponseData);
        LOGGER.info("REST request completed surface={} operation={} resource={} method={} status={} "
                + "requestBytes={} requestDataSha256={} responseBytes={} responseDataSha256={} elapsedMs={}",
            exchange.surface(), exchange.operation(), exchange.resource(), exchange.method(), status,
            exchange.requestBytes(), exchange.requestDataSha256(), data.length, responseFingerprint, elapsedMillis);
        LOGGER.debug("REST response received surface={} operation={} resource={} method={} path={} status={} "
                + "elapsedMs={} queryParameters={} authenticationMaterialPresent={} requestId={} "
                + "contentType={} etag={}",
            exchange.surface(), exchange.operation(), exchange.resource(), exchange.method(), exchange.path(), status,
            elapsedMillis, exchange.queryParameterNames(), exchange.authenticationMaterialPresent(),
            requestId(headers), contentType(headers), etag(headers));
    }

    static void failed(Exchange exchange, Throwable failure) {
        LOGGER.warn("REST request failed surface={} operation={} resource={} method={} path={} elapsedMs={} "
                + "exception={}",
            exchange.surface(), exchange.operation(), exchange.resource(), exchange.method(), exchange.path(),
            elapsedMillis(exchange), failure.getClass().getSimpleName());
    }

    static void businessDatasetEvent(String action, String dataset, String version, String resource,
                                     int rows, int distinctBusinessKeys, int missingEmails,
                                     List<String> countries, byte[] data) {
        LOGGER.info("Business dataset lifecycle action={} dataset={} version={} resource={} rows={} "
                + "distinctBusinessKeys={} missingEmails={} countries={} datasetBytes={} datasetSha256={}",
            safeToken(action), safeToken(dataset), safeToken(version), safeToken(resource), rows,
            distinctBusinessKeys, missingEmails, countries.stream().map(RestApiLogging::safeToken).sorted().toList(),
            data.length, fingerprint(data, false));
    }

    static void configure(String configuredLevel) {
        Level level = switch (configuredLevel.toUpperCase(Locale.ROOT)) {
            case "DEBUG" -> Level.FINE;
            case "INFO" -> Level.INFO;
            default -> throw new IllegalArgumentException("STRATUS_LOG_LEVEL must be INFO or DEBUG");
        };
        Logger.getLogger(LOGGER_NAME).setLevel(level);
        for (var handler : Logger.getLogger("").getHandlers()) {
            handler.setLevel(level);
        }
    }

    private static long elapsedMillis(Exchange exchange) {
        return (System.nanoTime() - exchange.startedNanos()) / 1_000_000;
    }

    private static String safePath(URI uri) {
        String path = uri.getRawPath();
        return path == null || path.isBlank() ? "/" : safeToken(path);
    }

    private static List<String> queryParameterNames(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return Arrays.stream(query.split("&"))
            .map(parameter -> {
                int separator = parameter.indexOf('=');
                return safeToken(separator < 0 ? parameter : parameter.substring(0, separator));
            })
            .distinct()
            .sorted()
            .toList();
    }

    private static String requestId(HttpHeaders headers) {
        return headers.firstValue("x-amz-request-id")
            .or(() -> headers.firstValue("x-request-id"))
            .map(RestApiLogging::safeToken)
            .orElse("unavailable");
    }

    private static String contentType(HttpHeaders headers) {
        return headers.firstValue("content-type")
            .map(RestApiLogging::safeToken)
            .orElse("unavailable");
    }

    private static String etag(HttpHeaders headers) {
        return headers.firstValue("etag")
            .map(RestApiLogging::safeToken)
            .orElse("unavailable");
    }

    private static String fingerprint(byte[] data, boolean sensitive) {
        if (sensitive) {
            return "redacted";
        }
        if (data.length == 0) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for REST data logging", e);
        }
    }

    private static String safeToken(String value) {
        String singleLine = value.replace('\r', '_').replace('\n', '_').replace('\t', '_');
        return singleLine.length() <= 256 ? singleLine : singleLine.substring(0, 256);
    }

    record Exchange(String surface, String operation, String resource, String method, String path,
                    List<String> queryParameterNames, int requestBytes, String requestDataSha256,
                    boolean authenticationMaterialPresent, long startedNanos) {
    }
}
