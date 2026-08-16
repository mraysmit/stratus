// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verifies lineage records through the real SLF4J/Log4j2 path. */
@Tag("unit")
final class LineageEventTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-09T10:15:30Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private TestLogCapture capture;

    @BeforeEach
    void captureTheRealProvider() {
        capture = new TestLogCapture(LineageEvent.class.getName());
    }

    @AfterEach
    void restoreTheProvider() {
        capture.close();
    }

    @Test
    void emitsTheDocumentedPayloadAsParseableJson() throws Exception {
        LineageEvent.emit("INGESTION", "external:crm/s3a://stratus-landing/customers.csv",
                "stratus.bronze.customers", "run-1", FIXED);

        assertEquals(1, capture.events().size());
        String message = capture.events().get(0).getMessage().getFormattedMessage();
        assertTrue(message.startsWith(LineageEvent.MARKER), message);

        var payload = JSON.readTree(message.substring(LineageEvent.MARKER.length()).trim());
        assertEquals("INGESTION", payload.get("type").asText());
        assertEquals("external:crm/s3a://stratus-landing/customers.csv", payload.get("source").asText());
        assertEquals("stratus.bronze.customers", payload.get("target").asText());
        assertEquals("run-1", payload.get("run_id").asText());
        assertEquals("2026-08-09T10:15:30Z", payload.get("timestamp").asText());
    }

    @Test
    void escapesValuesThatWouldOtherwiseBreakThePayload() throws Exception {
        LineageEvent.emit("INGESTION", "external:crm/a\"quoted\".csv",
                "stratus.bronze.odd\\name", "run-2", FIXED);

        String message = capture.events().get(0).getMessage().getFormattedMessage();
        var payload = JSON.readTree(message.substring(LineageEvent.MARKER.length()).trim());
        assertEquals("external:crm/a\"quoted\".csv", payload.get("source").asText());
        assertEquals("stratus.bronze.odd\\name", payload.get("target").asText());
    }

    @Test
    void lineageIsOperationalRatherThanDiagnostic() {
        LineageEvent.emit("TRANSFORM", "stratus.bronze.t", "stratus.silver.t", "run-3", FIXED);

        assertEquals(Level.INFO, capture.events().get(0).getLevel());
        assertFalse(capture.events().get(0).getMessage().getFormattedMessage().contains("password"));
    }
}
