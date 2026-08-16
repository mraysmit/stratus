// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lineage payload a platform job emits when it writes a table.
 *
 * <p>Increment 3 logs the payload to stdout; Increment 6 sends the same shape
 * to Atlas. Emitting it now, in the documented structure, is what makes that
 * later change a transport swap rather than a redesign — so the field names
 * here are part of the contract, not a convenience.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class LineageEvent {

    /** Stable marker so a transcript can be filtered to lineage alone. */
    public static final String MARKER = "STRATUS_LINEAGE";

    private static final Logger LOGGER = LoggerFactory.getLogger(LineageEvent.class);

    private LineageEvent() {
    }

    public static void emit(String type, String source, String target, String runId, Clock clock) {
        String payload = String.format(
                "{\"type\": \"%s\", \"source\": \"%s\", \"target\": \"%s\", "
                        + "\"run_id\": \"%s\", \"timestamp\": \"%s\"}",
                escape(type), escape(source), escape(target), escape(runId),
                Instant.now(clock).toString());
        LOGGER.info("{} {}", MARKER, payload);
    }

    /**
     * Escapes the characters that would otherwise produce a payload that is
     * not parseable JSON. Table and file names reach this method from job
     * arguments, so they are not assumed to be well behaved.
     */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
