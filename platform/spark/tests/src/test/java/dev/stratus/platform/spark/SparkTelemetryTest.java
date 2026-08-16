// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Deterministic checks for timing aggregation and failure sanitization. */
@Tag("unit")
final class SparkTelemetryTest {

    @Test
    void timingSummariesReportNearestRankPercentiles() {
        String metric = "test.distribution." + System.nanoTime();
        SparkTelemetry.record(metric, Duration.ofMillis(1).toNanos());
        SparkTelemetry.record(metric, Duration.ofMillis(2).toNanos());
        SparkTelemetry.record(metric, Duration.ofMillis(100).toNanos());

        SparkTelemetry.TimingSummary summary = SparkTelemetry.summaries().stream()
                .filter(candidate -> candidate.metric().equals(metric))
                .findFirst().orElseThrow();

        assertEquals(3, summary.samples());
        assertEquals(103, summary.totalMillis());
        assertEquals(2, summary.p50Millis());
        assertEquals(100, summary.p95Millis());
        assertEquals(100, summary.maximumMillis());
    }

    @Test
    void operationFailuresDoNotLogCredentialMaterial() {
        try (var capture = new TestLogCapture("dev.stratus.platform.spark.telemetry")) {
            String first = "first-secret-value";
            String second = "second-secret-value";
            try (var operation = SparkTelemetry.start("probe", "test.probe",
                    "credential=" + first)) {
                operation.failed(new IllegalStateException("authorization=Bearer " + second), "");
            }

            String messages = capture.events().stream()
                    .map(event -> event.getMessage().getFormattedMessage())
                    .reduce("", (left, right) -> left + ' ' + right);
            assertFalse(messages.contains(first), messages);
            assertFalse(messages.contains(second), messages);
            assertTrue(messages.contains("<redacted>"), messages);
        }
    }
}
