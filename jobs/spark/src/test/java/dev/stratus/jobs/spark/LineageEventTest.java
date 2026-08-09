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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real logging backend the jobs emit lineage through, without
 * replacing it. The payload shape is a contract Increment 6 will read, so it
 * is asserted as parseable JSON with the documented fields rather than as a
 * string that merely looks right.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("unit")
final class LineageEventTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-09T10:15:30Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Logger backend = Logger.getLogger(LineageEvent.class.getName());
    private final CapturingHandler capture = new CapturingHandler();
    private Level originalLevel;
    private boolean originalUseParentHandlers;

    @BeforeEach
    void captureTheRealBackend() {
        originalLevel = backend.getLevel();
        originalUseParentHandlers = backend.getUseParentHandlers();
        backend.setLevel(Level.ALL);
        backend.setUseParentHandlers(false);
        backend.addHandler(capture);
    }

    @AfterEach
    void restoreTheBackend() {
        backend.removeHandler(capture);
        backend.setLevel(originalLevel);
        backend.setUseParentHandlers(originalUseParentHandlers);
    }

    @Test
    void emitsTheDocumentedPayloadAsParseableJson() throws Exception {
        LineageEvent.emit("INGESTION", "external:crm/s3a://stratus-landing/customers.csv",
                "stratus.bronze.customers", "run-1", FIXED);

        assertEquals(1, capture.records.size(), "one event must produce one record");
        String message = capture.records.get(0).getMessage();
        assertTrue(message.startsWith(LineageEvent.MARKER),
                "the record must carry the filter marker: " + message);

        var payload = JSON.readTree(message.substring(LineageEvent.MARKER.length()).trim());
        assertEquals("INGESTION", payload.get("type").asText());
        assertEquals("external:crm/s3a://stratus-landing/customers.csv", payload.get("source").asText());
        assertEquals("stratus.bronze.customers", payload.get("target").asText());
        assertEquals("run-1", payload.get("run_id").asText());
        assertEquals("2026-08-09T10:15:30Z", payload.get("timestamp").asText());
    }

    @Test
    void escapesValuesThatWouldOtherwiseBreakThePayload() throws Exception {
        // Table and file names arrive from job arguments. A quote in one of
        // them would produce a record that no consumer can parse, and the
        // failure would surface in Increment 6 rather than here.
        LineageEvent.emit("INGESTION", "external:crm/a\"quoted\".csv",
                "stratus.bronze.odd\\name", "run-2", FIXED);

        String message = capture.records.get(0).getMessage();
        var payload = JSON.readTree(message.substring(LineageEvent.MARKER.length()).trim());

        assertEquals("external:crm/a\"quoted\".csv", payload.get("source").asText());
        assertEquals("stratus.bronze.odd\\name", payload.get("target").asText());
    }

    @Test
    void neverEmitsAtAnUncontrolledLevel() {
        LineageEvent.emit("TRANSFORM", "stratus.bronze.t", "stratus.silver.t", "run-3", FIXED);

        assertEquals(Level.INFO, capture.records.get(0).getLevel(),
                "lineage is an operational record, not a diagnostic");
        assertFalse(capture.records.get(0).getMessage().contains("password"),
                "no credential material may reach a lineage record");
    }

    /** Collects records from the real JDK logging backend. */
    private static final class CapturingHandler extends Handler {

        private final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
