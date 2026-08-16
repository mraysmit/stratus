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
        SparkTelemetry.TimingSummary summary = SparkTelemetry.TimingSummary.from(
                "test.distribution", java.util.List.of(
                        Duration.ofMillis(1).toNanos(),
                        Duration.ofMillis(2).toNanos(),
                        Duration.ofMillis(100).toNanos()));

        assertEquals(3, summary.samples());
        assertEquals(103, summary.totalMillis());
        assertEquals(2, summary.p50Millis());
        assertEquals(100, summary.p95Millis());
        assertEquals(100, summary.maximumMillis());
    }

    @Test
    void operationFailuresDoNotLogCredentialMaterial() {
        String metric = "test.probe." + System.nanoTime();
        try (var capture = new TestLogCapture("dev.stratus.platform.spark.telemetry")) {
            String first = "first-secret-value";
            String second = "second-secret-value";
            try (var operation = SparkTelemetry.start("probe", metric,
                    "credential=" + first)) {
                operation.failed(new IllegalStateException("authorization=Bearer " + second), "");
            }

            String messages = capture.events().stream()
                    .map(event -> event.getMessage().getFormattedMessage())
                    .reduce("", (left, right) -> left + ' ' + right);
            assertFalse(messages.contains(first), messages);
            assertFalse(messages.contains(second), messages);
            assertTrue(messages.contains("<redacted>"), messages);
            assertTrue(messages.contains("event=probe_failure_detail"), messages);
            assertTrue(messages.contains("stackTrace="), messages);
        } finally {
            SparkTelemetry.resetMetric(metric);
        }
    }

    @Test
    void nestedOperationsRestoreTheOuterMdcContext() {
        org.slf4j.MDC.put("operationId", "outer-operation");
        String metric = "test.context." + System.nanoTime();
        try {
            try (var operation = SparkTelemetry.start("probe", metric, "detail=visible")) {
                assertEquals(operation.operationId(), org.slf4j.MDC.get("operationId"));
                assertEquals(SparkTelemetry.runId(), org.slf4j.MDC.get("suiteRunId"));
                operation.succeeded("");
            }
            assertEquals("outer-operation", org.slf4j.MDC.get("operationId"));
        } finally {
            SparkTelemetry.resetMetric(metric);
            org.slf4j.MDC.remove("operationId");
        }
    }
}
