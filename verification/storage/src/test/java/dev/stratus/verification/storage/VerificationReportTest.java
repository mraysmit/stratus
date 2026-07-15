// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Implementation of VerificationReportTest functionality.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
@Tag("unit")
class VerificationReportTest {
    @Test
    void serializesEveryFieldAndEscapesJsonControlCharacters() {
        var checks = new ArrayList<>(List.of(
                VerificationCheck.passed("first", "slash\\ quote\" return\r newline\n"),
                new VerificationCheck("second", false, "failed")));
        var report = new VerificationReport("what \"this\" evidence proves",
                Instant.parse("2026-07-12T10:15:30Z"), false, checks);
        checks.clear();

        assertEquals("{\"description\":\"what \\\"this\\\" evidence proves\","
                + "\"timestamp\":\"2026-07-12T10:15:30Z\",\"success\":false,\"checks\":["
                + "{\"name\":\"first\",\"passed\":true,\"detail\":\"slash\\\\ quote\\\" return\\r newline\\n\"},"
                + "{\"name\":\"second\",\"passed\":false,\"detail\":\"failed\"}]}", report.toJson());
        assertFalse(report.checks().isEmpty());
    }
}
