// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Structured event correlation and monotonic timing for the Spark test fork. */
final class SparkTelemetry {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            "dev.stratus.platform.spark.telemetry");
    private static final String RUN_ID = System.getProperty("stratus.run.id",
            System.getenv().getOrDefault("STRATUS_RUN_ID", "local-" + ProcessHandle.current().pid()));
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Map<String, ConcurrentLinkedQueue<Long>> DURATIONS =
            new ConcurrentHashMap<>();

    private SparkTelemetry() {
    }

    static String runId() {
        return RUN_ID;
    }

    static void installRunContext() {
        MDC.put("suiteRunId", RUN_ID);
    }

    static String nextOperationId(String prefix) {
        return prefix + '-' + String.format("%05d", SEQUENCE.incrementAndGet());
    }

    static Operation start(String event, String metric, String details) {
        installRunContext();
        String operationId = nextOperationId(event);
        MdcScope operationContext = MdcScope.put("operationId", operationId);
        LOGGER.debug("event={}_started suiteRunId={} operationId={} {}",
                event, RUN_ID, operationId, SparkLogSanitizer.token(details, 2048));
        return new Operation(event, metric, operationId, details, System.nanoTime(), operationContext);
    }

    static void record(String metric, long durationNanos) {
        DURATIONS.computeIfAbsent(metric, ignored -> new ConcurrentLinkedQueue<>())
                .add(Math.max(0L, durationNanos));
    }

    static void resetMetric(String metric) {
        DURATIONS.remove(metric);
    }

    static List<TimingSummary> summaries() {
        return DURATIONS.entrySet().stream()
                .map(entry -> TimingSummary.from(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(TimingSummary::metric))
                .toList();
    }

    static void logSummaries() {
        for (TimingSummary summary : summaries()) {
            LOGGER.info("event=timing_summary suiteRunId={} metric={} samples={} totalMs={} meanMs={} "
                            + "minMs={} p50Ms={} p95Ms={} maxMs={}",
                    RUN_ID, SparkLogSanitizer.token(summary.metric()), summary.samples(),
                    summary.totalMillis(), summary.meanMillis(), summary.minimumMillis(),
                    summary.p50Millis(), summary.p95Millis(), summary.maximumMillis());
        }
    }

    static final class Operation implements AutoCloseable {

        private final String event;
        private final String metric;
        private final String operationId;
        private final String initialDetails;
        private final long startedAt;
        private final MdcScope operationContext;
        private boolean completed;

        private Operation(String event, String metric, String operationId,
                          String initialDetails, long startedAt, MdcScope operationContext) {
            this.event = event;
            this.metric = metric;
            this.operationId = operationId;
            this.initialDetails = initialDetails;
            this.startedAt = startedAt;
            this.operationContext = operationContext;
        }

        String operationId() {
            return operationId;
        }

        long elapsedMillis() {
            return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        }

        void succeeded(String details) {
            finish("SUCCESS", details, null);
        }

        void failed(Throwable failure, String details) {
            finish("FAILED", details, failure);
        }

        private void finish(String status, String details, Throwable failure) {
            if (completed) {
                return;
            }
            completed = true;
            long elapsed = System.nanoTime() - startedAt;
            record(metric, elapsed);
            String combined = SparkLogSanitizer.token(initialDetails + ' ' + details, 2048);
            if (failure == null) {
                LOGGER.info("event={}_completed suiteRunId={} operationId={} status={} durationMs={} {}",
                        event, RUN_ID, operationId, status, Duration.ofNanos(elapsed).toMillis(), combined);
            } else {
                LOGGER.error("event={}_completed suiteRunId={} operationId={} status={} durationMs={} "
                                + "exceptionClass={} exceptionMessage={} {}",
                        event, RUN_ID, operationId, status, Duration.ofNanos(elapsed).toMillis(),
                        failure.getClass().getName(),
                        SparkLogSanitizer.token(failure.getMessage(), 1024), combined);
                LOGGER.debug("event={}_failure_detail suiteRunId={} operationId={} stackTrace={}",
                        event, RUN_ID, operationId, SparkLogSanitizer.stackTrace(failure, 8192));
            }
        }

        @Override
        public void close() {
            try {
                if (!completed) {
                    finish("ABANDONED", "",
                            new IllegalStateException("operation did not report an outcome"));
                }
            } finally {
                operationContext.close();
            }
        }
    }

    private static final class MdcScope implements AutoCloseable {

        private final String key;
        private final String previous;

        private MdcScope(String key, String previous) {
            this.key = key;
            this.previous = previous;
        }

        private static MdcScope put(String key, String value) {
            String previous = MDC.get(key);
            MDC.put(key, value);
            return new MdcScope(key, previous);
        }

        @Override
        public void close() {
            if (previous == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        }
    }

    record TimingSummary(String metric, int samples, long totalMillis, long meanMillis,
                         long minimumMillis, long p50Millis, long p95Millis, long maximumMillis) {

        static TimingSummary from(String metric, Iterable<Long> durations) {
            var ordered = new ArrayList<Long>();
            durations.forEach(ordered::add);
            ordered.sort(Long::compareTo);
            long total = ordered.stream().mapToLong(Long::longValue).sum();
            return new TimingSummary(metric, ordered.size(), nanosToMillis(total),
                    nanosToMillis(total / ordered.size()), nanosToMillis(ordered.get(0)),
                    nanosToMillis(percentile(ordered, 0.50)),
                    nanosToMillis(percentile(ordered, 0.95)),
                    nanosToMillis(ordered.get(ordered.size() - 1)));
        }

        private static long percentile(List<Long> ordered, double percentile) {
            int index = Math.max(0, (int) Math.ceil(percentile * ordered.size()) - 1);
            return ordered.get(index);
        }

        private static long nanosToMillis(long nanos) {
            return Duration.ofNanos(nanos).toMillis();
        }
    }
}
