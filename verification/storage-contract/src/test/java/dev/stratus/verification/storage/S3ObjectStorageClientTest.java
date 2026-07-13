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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("protocol")
class S3ObjectStorageClientTest {
    private ProtocolEndpoint endpoint;
    private S3ObjectStorageClient storage;

    @BeforeEach
    void startProtocolEndpoint() throws IOException {
        endpoint = new ProtocolEndpoint();
        var config = new StorageVerifierConfig(endpoint.uri(), "local-access-key", "local-secret-key", true,
                StorageVerifierConfig.REQUIRED_BUCKETS, "stratus-landing");
        storage = S3ObjectStorageClient.create(config);
    }

    @AfterEach
    void stopProtocolEndpoint() {
        storage.close();
        endpoint.close();
    }

    @Test
    void exchangesRealS3HttpRequestsForObjectAndListingOperations() {
        assertEquals(Set.of("stratus-landing"), storage.listBuckets());
        storage.put("stratus-landing", "prefix/object", new byte[]{1, 2});
        assertArrayEquals(new byte[]{1, 2}, storage.get("stratus-landing", "prefix/object"));
        assertEquals(2, storage.size("stratus-landing", "prefix/object"));
        assertEquals(Set.of("prefix/object"), storage.list("stratus-landing", "prefix/"));
        storage.delete("stratus-landing", "prefix/object");

        assertTrue(endpoint.requests.stream().anyMatch(request -> request.startsWith("PUT /stratus-landing/prefix/object")));
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.startsWith("GET /stratus-landing/prefix/object")));
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.startsWith("HEAD /stratus-landing/prefix/object")));
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.contains("list-type=2")));
        assertTrue(endpoint.requests.stream().anyMatch(request -> request.startsWith("DELETE /stratus-landing/prefix/object")));
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
    }

    private static final class ProtocolEndpoint implements AutoCloseable {
        private static final byte[] OBJECT = new byte[]{1, 2};
        private final HttpServer server;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private volatile boolean rejectParts;

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
                respond(exchange, 200, "<ListAllMyBucketsResult><Buckets><Bucket><Name>stratus-landing</Name>"
                        + "<CreationDate>2026-07-13T00:00:00Z</CreationDate></Bucket></Buckets></ListAllMyBucketsResult>");
            } else if (method.equals("GET") && query != null && query.contains("list-type=2")) {
                respond(exchange, 200, "<ListBucketResult><Name>stratus-landing</Name><Prefix>prefix/</Prefix>"
                        + "<KeyCount>1</KeyCount><MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated>"
                        + "<Contents><Key>prefix/object</Key><Size>2</Size></Contents></ListBucketResult>");
            } else if (method.equals("GET")) {
                respond(exchange, 200, OBJECT, "application/octet-stream");
            } else if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", "2");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            } else if (method.equals("POST") && query != null && query.startsWith("uploads")) {
                respond(exchange, 200, "<InitiateMultipartUploadResult><Bucket>stratus-landing</Bucket>"
                        + "<Key>multipart.bin</Key><UploadId>upload-1</UploadId></InitiateMultipartUploadResult>");
            } else if (method.equals("PUT") && query != null && query.contains("partNumber=")) {
                if (rejectParts) {
                    respond(exchange, 500, "<Error><Code>InternalError</Code><Message>part rejected</Message></Error>");
                } else {
                    exchange.getResponseHeaders().set("ETag", "\"part-etag\"");
                    respond(exchange, 200, new byte[0], "application/xml");
                }
            } else if (method.equals("POST") && query != null && query.contains("uploadId=")) {
                respond(exchange, 200, "<CompleteMultipartUploadResult><Bucket>stratus-landing</Bucket>"
                        + "<Key>multipart.bin</Key><ETag>\"complete-etag\"</ETag></CompleteMultipartUploadResult>");
            } else {
                respond(exchange, 200, new byte[0], "application/xml");
            }
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            respond(exchange, status, body.getBytes(StandardCharsets.UTF_8), "application/xml");
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
