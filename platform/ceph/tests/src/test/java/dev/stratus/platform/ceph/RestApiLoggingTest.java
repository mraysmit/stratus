// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.ceph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real SLF4J-to-JDK logging backend without replacing it with a
 * mock and proves that sensitive REST material cannot enter log records.
 */
@Tag("unit")
final class RestApiLoggingTest {

    private final Logger backend = Logger.getLogger(RestApiLogging.LOGGER_NAME);
    private final Logger root = Logger.getLogger("");
    private final CapturingHandler capture = new CapturingHandler();
    private final Map<Handler, Level> rootHandlerLevels = new IdentityHashMap<>();
    private Level originalLevel;
    private boolean originalUseParentHandlers;

    @BeforeEach
    void captureTheRealBackend() {
        originalLevel = backend.getLevel();
        originalUseParentHandlers = backend.getUseParentHandlers();
        for (Handler handler : root.getHandlers()) {
            rootHandlerLevels.put(handler, handler.getLevel());
        }
        capture.setLevel(Level.ALL);
        backend.setUseParentHandlers(false);
        backend.addHandler(capture);
    }

    @AfterEach
    void restoreTheBackend() {
        backend.removeHandler(capture);
        backend.setUseParentHandlers(originalUseParentHandlers);
        backend.setLevel(originalLevel);
        rootHandlerLevels.forEach(Handler::setLevel);
    }

    @Test
    void infoRecordsOnlyTheSanitizedCompletionSummary() {
        RestApiLogging.configure("INFO");

        var exchange = RestApiLogging.started("dashboard", "authenticate", "dashboard-session", "POST",
            sensitiveUri(), "top-secret-password".getBytes(StandardCharsets.UTF_8), true, true);
        RestApiLogging.completed(exchange, 201,
            "top-secret-token".getBytes(StandardCharsets.UTF_8), true, responseHeaders());

        assertEquals(1, capture.records.size());
        LogRecord completion = capture.records.getFirst();
        assertEquals(Level.INFO, completion.getLevel());
        assertTrue(completion.getMessage().contains(
            "surface=dashboard operation=authenticate resource=dashboard-session method=POST status=201"));
        assertTrue(completion.getMessage().contains("requestDataSha256=redacted"));
        assertTrue(completion.getMessage().contains("responseDataSha256=redacted"));
        assertSensitiveValuesAreAbsent(capture.text());
    }

    @Test
    void debugAddsSafeProtocolMetadataWithoutQueryValuesOrCredentials() {
        RestApiLogging.configure("DEBUG");
        byte[] data = "stratus-test-object-data".getBytes(StandardCharsets.UTF_8);

        var exchange = RestApiLogging.started("s3", "write-object",
            "bucket=stratus-landing key=verification/object.bin", "PUT", sensitiveUri(), data, false, true);
        RestApiLogging.completed(exchange, 200, data, false, responseHeaders());

        assertEquals(3, capture.records.size());
        assertEquals(List.of(Level.FINE, Level.INFO, Level.FINE),
            capture.records.stream().map(LogRecord::getLevel).toList());
        String output = capture.text();
        assertTrue(output.contains("queryParameters=[password, token]"));
        assertTrue(output.contains("requestBytes=" + data.length));
        assertTrue(output.contains("responseBytes=" + data.length));
        assertTrue(output.contains("requestDataSha256=" + sha256(data)));
        assertTrue(output.contains("responseDataSha256=" + sha256(data)));
        assertTrue(output.contains("authenticationMaterialPresent=true"));
        assertTrue(output.contains("requestId=request-123"));
        assertTrue(output.contains("contentType=application/json"));
        assertTrue(output.contains("etag=test-etag"));
        assertSensitiveValuesAreAbsent(output);
    }

    @Test
    void matchingFingerprintsShowWhereTestDataIsWrittenAndRead() {
        RestApiLogging.configure("INFO");
        byte[] data = "payload-that-must-round-trip".getBytes(StandardCharsets.UTF_8);
        String resource = "bucket=stratus-landing key=verification/round-trip.bin";

        var write = RestApiLogging.started("s3", "write-object", resource, "PUT",
            URI.create("https://object-store.stratus.local:8443/stratus-landing/verification/round-trip.bin"),
            data, false, true);
        RestApiLogging.completed(write, 200, new byte[0], false, responseHeaders());
        var read = RestApiLogging.started("s3", "read-object", resource, "GET",
            URI.create("https://object-store.stratus.local:8443/stratus-landing/verification/round-trip.bin"),
            new byte[0], false, true);
        RestApiLogging.completed(read, 200, data, false, responseHeaders());

        String output = capture.text();
        String fingerprint = sha256(data);
        assertTrue(output.contains("operation=write-object resource=" + resource
            + " method=PUT status=200 requestBytes=" + data.length
            + " requestDataSha256=" + fingerprint));
        assertTrue(output.contains("operation=read-object resource=" + resource
            + " method=GET status=200 requestBytes=0 requestDataSha256=none responseBytes=" + data.length
            + " responseDataSha256=" + fingerprint));
    }

    @Test
    void businessLifecycleRecordIdentifiesTheDatasetAndItsQualityStateWithoutLoggingRows() {
        RestApiLogging.configure("INFO");
        byte[] data = ("customer_id,full_name,email,country\n"
            + "CUST-1001,Alice Smith,alice.smith@example.test,GB\n"
            + "CUST-1001,Alice Smith,alice.smith@example.test,GB\n"
            + "CUST-1002,Bob Jones,,US\n").getBytes(StandardCharsets.UTF_8);

        RestApiLogging.businessDatasetEvent("write-confirmed", "customer-master", "raw-v1",
            "bucket=stratus-landing key=verification/business/customers/run-1/customers.csv",
            3, 2, 1, List.of("GB", "US"), data);

        String output = capture.text();
        assertTrue(output.contains("Business dataset lifecycle action=write-confirmed"
            + " dataset=customer-master version=raw-v1"));
        assertTrue(output.contains("resource=bucket=stratus-landing"
            + " key=verification/business/customers/run-1/customers.csv"));
        assertTrue(output.contains("rows=3 distinctBusinessKeys=2 missingEmails=1 countries=[GB, US]"));
        assertTrue(output.contains("datasetBytes=" + data.length + " datasetSha256=" + sha256(data)));
        assertFalse(output.contains("Alice Smith"));
        assertFalse(output.contains("alice.smith@example.test"));
    }

    @Test
    void rejectsAnUnknownConfiguredLevel() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> RestApiLogging.configure("TRACE"));

        assertEquals("STRATUS_LOG_LEVEL must be INFO or DEBUG", failure.getMessage());
    }

    private static URI sensitiveUri() {
        return URI.create("https://object-store.stratus.local:8443/stratus-landing/object"
            + "?token=top-secret-token&password=top-secret-password");
    }

    private static HttpHeaders responseHeaders() {
        return HttpHeaders.of(Map.of(
            "content-type", List.of("application/json"),
            "etag", List.of("test-etag"),
            "x-amz-request-id", List.of("request-123"),
            "set-cookie", List.of("session=top-secret-cookie")), (name, value) -> true);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertSensitiveValuesAreAbsent(String output) {
        assertFalse(output.contains("top-secret-token"));
        assertFalse(output.contains("top-secret-password"));
        assertFalse(output.contains("top-secret-cookie"));
        assertFalse(output.toLowerCase().contains("authorization="));
        assertFalse(output.toLowerCase().contains("signature="));
    }

    private static final class CapturingHandler extends Handler {
        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (isLoggable(record)) {
                records.add(record);
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        private String text() {
            return records.stream().map(LogRecord::getMessage).reduce("", (left, right) -> left + "\n" + right);
        }
    }
}
