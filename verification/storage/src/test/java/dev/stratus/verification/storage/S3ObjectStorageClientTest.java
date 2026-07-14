package dev.stratus.verification.storage;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.time.Duration;
import software.amazon.awssdk.core.exception.SdkClientException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("protocol")
class S3ObjectStorageClientTest {
    private ProtocolEndpoint endpoint;
    private S3ObjectStorageClient storage;
    private VerifierLogCapture logCapture;

    @BeforeEach
    void startProtocolEndpoint() throws IOException {
        logCapture = new VerifierLogCapture();
        StorageVerifier.configureLogging("DEBUG");
        endpoint = new ProtocolEndpoint();
        var config = new StorageVerifierConfig(endpoint.uri(), "local-access-key", "local-secret-key", true,
                StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing",
                Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(6));
        storage = S3ObjectStorageClient.create(config);
    }

    @AfterEach
    void stopProtocolEndpoint() {
        storage.close();
        endpoint.close();
        logCapture.close();
    }

    @Test
    void exchangesRealS3HttpRequestsForObjectAndListingOperations() {
        assertEquals(Set.of("stratus-landing"), storage.listBuckets());
        storage.put("stratus-landing", "prefix/object", new byte[]{1, 2});
        assertArrayEquals(new byte[]{1, 2}, storage.get("stratus-landing", "prefix/object"));
        assertEquals(2, storage.size("stratus-landing", "prefix/object"));
        assertTrue(storage.exists("stratus-landing", "prefix/object"));
        assertTrue(!storage.exists("stratus-landing", "missing"));
        assertEquals(Set.of("prefix/object"), storage.list("stratus-landing", "prefix/"));
        storage.delete("stratus-landing", "prefix/object");

        assertTrue(endpoint.requests.stream().anyMatch(request -> request.startsWith("PUT /stratus-landing/prefix/object")));
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.startsWith("GET /stratus-landing/prefix/object")));
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.startsWith("HEAD /stratus-landing/prefix/object")));
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.contains("list-type=2")));
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.contains("max-keys=2")));
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.startsWith("DELETE /stratus-landing/prefix/object")));
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getLevel().equals(Level.FINE)
                && record.getMessage().contains("S3 operation=PUT bucket=stratus-landing key=prefix/object bytes=2")
                && record.getMessage().contains("succeeded elapsedMs=")));
        assertTrue(logCapture.records().stream().noneMatch(record -> record.getMessage().contains("local-secret-key")));
    }

    @Test
    void completesMultipartUploadThroughTheHttpProtocol() {
        storage.multipartPut("stratus-landing", "multipart.bin", new byte[7], 4);

        assertEquals(2, endpoint.requests.stream().filter(request -> request.startsWith("PUT /stratus-landing/multipart.bin")
                && request.contains("partNumber=")).count());
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.startsWith("POST /stratus-landing/multipart.bin")
                && request.contains("uploads")));
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.startsWith("POST /stratus-landing/multipart.bin")
                && request.contains("uploadId=upload-1")));
    }

    @Test
    void abortsMultipartUploadWhenTheEndpointRejectsAPart() {
        endpoint.rejectParts = true;

        assertThrows(S3Exception.class,
                () -> storage.multipartPut("stratus-landing", "multipart.bin", new byte[1], 1));
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.startsWith("DELETE /stratus-landing/multipart.bin")
                && request.contains("uploadId=upload-1")));
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getLevel().equals(Level.WARNING)
                && record.getMessage().contains("status=500 requestId=protocol-request-1")
                && record.getThrown() instanceof S3Exception));
    }

    @Test
    void followsContinuationTokensUntilEveryListingPageIsCollected() {
        endpoint.paginatedListing = true;

        assertEquals(Set.of("prefix/object-0", "prefix/object-1", "prefix/object-2"),
                storage.list("stratus-landing", "prefix/"));

        assertEquals(2, endpoint.requests.stream().filter(request -> request.contains("list-type=2")).count());
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.contains("continuation-token=page-2")));
    }

    @Test
    void retainsMultipartAbortFailureAsSuppressedDiagnostic() {
        endpoint.rejectParts = true;
        endpoint.rejectAbort = true;

        var failure = assertThrows(S3Exception.class,
                () -> storage.multipartPut("stratus-landing", "multipart.bin", new byte[1], 1));

        assertTrue(failure.getSuppressed().length >= 1);
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getMessage().contains("operation=MULTIPART_ABORT")
                && record.getThrown() instanceof S3Exception));
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getMessage().contains("requestId=unavailable")));
    }

    @Test
    void logsClientSideFailuresWithoutServiceMetadata() {
        endpoint.close();

        assertThrows(RuntimeException.class, storage::listBuckets);
        assertThrows(RuntimeException.class, () -> storage.exists("stratus-landing", "object"));

        assertTrue(logCapture.records().stream().anyMatch(record -> record.getLevel().equals(Level.WARNING)
                && record.getMessage().contains("operation=LIST_BUCKETS failed")
                && !record.getMessage().contains(" status=")
                && record.getThrown() != null));
    }

    @Test
    void propagatesUnexpectedHeadFailureFromExistsCheck() {
        endpoint.rejectHead = true;

        var failure = assertThrows(S3Exception.class, () -> storage.exists("stratus-landing", "denied"));

        assertEquals(403, failure.statusCode());
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getMessage().contains("operation=EXISTS")
                && record.getMessage().contains("status=403")));
    }

    @Test
    void acceptsOnlyAuthenticationRejectionStatusesForInvalidCredentials() {
        endpoint.rejectBucketListingStatus = 403;
        storage.verifyCredentialsRejected();
        endpoint.rejectBucketListingStatus = 401;
        storage.verifyCredentialsRejected();

        assertTrue(logCapture.records().stream().anyMatch(record -> record.getMessage().contains("operation=INVALID_CREDENTIALS")
                && record.getMessage().contains("rejectedStatus=403")));
    }

    @Test
    void rejectsUnexpectedAuthenticationOutcomes() {
        assertThrows(IllegalStateException.class, storage::verifyCredentialsRejected);
        endpoint.rejectBucketListingStatus = 500;
        assertEquals(500, assertThrows(S3Exception.class, storage::verifyCredentialsRejected).statusCode());
        endpoint.close();
        assertThrows(RuntimeException.class, storage::verifyCredentialsRejected);
    }

    @Test
    void provesThatASeparateOwnersBucketCannotBeListed() {
        endpoint.rejectObjectListingStatus = 403;
        storage.verifyListDenied("stratus-denied");

        endpoint.rejectObjectListingStatus = 404;
        assertEquals(404, assertThrows(S3Exception.class,
                () -> storage.verifyListDenied("stratus-denied")).statusCode());
    }

    @Test
    void retriesTransientThrottlingAndLogsEveryHttpAttempt() {
        endpoint.transientBucketFailures = 2;
        endpoint.transientBucketStatus = 503;

        assertEquals(Set.of("stratus-landing"), storage.listBuckets());

        assertEquals(3, endpoint.requests.stream().filter(request -> request.equals("GET /")).count());
        assertEquals(3, logCapture.records().stream()
                .filter(record -> record.getMessage().contains("S3 HTTP attempt method=GET")).count());
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getMessage().contains("response status=503")));
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getMessage().contains("response status=200")));
    }

    @Test
    void enforcesConfiguredSocketAndApiCallTimeouts() {
        storage.close();
        endpoint.bucketListingDelayMillis = 2_000;
        var config = new StorageVerifierConfig(endpoint.uri(), "local-access-key", "local-secret-key", true,
                StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing",
                Duration.ofMillis(100), Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(700));
        storage = S3ObjectStorageClient.create(config);

        var started = System.nanoTime();
        assertThrows(SdkClientException.class, storage::listBuckets);
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMillis < 2_000, "configured total timeout must bound retries, elapsedMs=" + elapsedMillis);
        assertTrue(logCapture.records().stream().anyMatch(record -> record.getMessage().contains("operation=LIST_BUCKETS failed")));
    }

    private static final class ProtocolEndpoint implements AutoCloseable {
        private static final byte[] OBJECT = new byte[]{1, 2};
        private final HttpServer server;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private volatile boolean rejectParts;
        private volatile boolean rejectAbort;
        private volatile boolean rejectHead;
        private volatile boolean paginatedListing;
        private volatile int rejectBucketListingStatus;
        private volatile int rejectObjectListingStatus;
        private volatile int transientBucketFailures;
        private volatile int transientBucketStatus;
        private volatile long bucketListingDelayMillis;

        private ProtocolEndpoint() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private void handle(HttpExchange exchange) throws IOException {
            var query = exchange.getRequestURI().getRawQuery();
            var request = exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath()
                    + (query == null ? "" : "?" + query);
            requests.add(request);
            exchange.getRequestBody().readAllBytes();

            var method = exchange.getRequestMethod();
            var path = exchange.getRequestURI().getPath();
            if (method.equals("GET") && path.equals("/") && query == null) {
                if (bucketListingDelayMillis > 0) {
                    try {
                        Thread.sleep(bucketListingDelayMillis);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (transientBucketFailures > 0) {
                    transientBucketFailures--;
                    exchange.getResponseHeaders().set("x-amz-request-id", "transient-request");
                    respond(exchange, transientBucketStatus,
                            "<Error><Code>SlowDown</Code><Message>retry</Message></Error>");
                } else if (rejectBucketListingStatus > 0) {
                    respond(exchange, rejectBucketListingStatus,
                            "<Error><Code>AccessDenied</Code><Message>rejected</Message></Error>");
                } else {
                    respond(exchange, 200, "<ListAllMyBucketsResult><Buckets><Bucket><Name>stratus-landing</Name>"
                            + "<CreationDate>2026-07-13T00:00:00Z</CreationDate></Bucket></Buckets></ListAllMyBucketsResult>");
                }
            } else if (method.equals("GET") && query != null && query.contains("list-type=2")) {
                if (rejectObjectListingStatus > 0) {
                    respond(exchange, rejectObjectListingStatus,
                            "<Error><Code>AccessDenied</Code><Message>rejected</Message></Error>");
                } else if (paginatedListing && query.contains("continuation-token=page-2")) {
                    respond(exchange, 200, listing(false, null, "prefix/object-2"));
                } else if (paginatedListing) {
                    respond(exchange, 200, listing(true, "page-2", "prefix/object-0", "prefix/object-1"));
                } else {
                    respond(exchange, 200, listing(false, null, "prefix/object"));
                }
            } else if (method.equals("GET")) {
                respond(exchange, 200, OBJECT, "application/octet-stream");
            } else if (method.equals("HEAD") && rejectHead) {
                respondHead(exchange, 403);
            } else if (method.equals("HEAD") && path.endsWith("/missing")) {
                respondHead(exchange, 404);
            } else if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", "2");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if (method.equals("POST") && query != null && query.startsWith("uploads")) {
                respond(exchange, 200, "<InitiateMultipartUploadResult><Bucket>stratus-landing</Bucket>"
                        + "<Key>multipart.bin</Key><UploadId>upload-1</UploadId></InitiateMultipartUploadResult>");
            } else if (method.equals("PUT") && query != null && query.contains("partNumber=")) {
                if (rejectParts) {
                    exchange.getResponseHeaders().set("x-amz-request-id", "protocol-request-1");
                    respond(exchange, 500, "<Error><Code>InternalError</Code><Message>part rejected</Message></Error>");
                } else {
                    exchange.getResponseHeaders().set("ETag", "\"part-etag\"");
                    respond(exchange, 200, new byte[0], "application/xml");
                }
            } else if (method.equals("POST") && query != null && query.contains("uploadId=")) {
                respond(exchange, 200, "<CompleteMultipartUploadResult><Bucket>stratus-landing</Bucket>"
                        + "<Key>multipart.bin</Key><ETag>\"complete-etag\"</ETag></CompleteMultipartUploadResult>");
            } else if (method.equals("DELETE") && query != null && query.contains("uploadId=") && rejectAbort) {
                respond(exchange, 500, "<Error><Code>InternalError</Code><Message>abort rejected</Message></Error>");
            } else {
                respond(exchange, 200, new byte[0], "application/xml");
            }
        }

        private static String listing(boolean truncated, String nextToken, String... keys) {
            var xml = new StringBuilder("<ListBucketResult><Name>stratus-landing</Name><Prefix>prefix/</Prefix>")
                    .append("<KeyCount>").append(keys.length).append("</KeyCount><MaxKeys>2</MaxKeys><IsTruncated>")
                    .append(truncated).append("</IsTruncated>");
            if (nextToken != null) xml.append("<NextContinuationToken>").append(nextToken).append("</NextContinuationToken>");
            for (var key : keys) xml.append("<Contents><Key>").append(key).append("</Key><Size>2</Size></Contents>");
            return xml.append("</ListBucketResult>").toString();
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            respond(exchange, status, body.getBytes(StandardCharsets.UTF_8), "application/xml");
        }

        private static void respondHead(HttpExchange exchange, int status) throws IOException {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }

        private static void respond(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
