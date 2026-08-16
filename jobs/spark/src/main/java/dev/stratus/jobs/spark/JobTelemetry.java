// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Monotonic, structured phase timing shared by packaged and in-process jobs. */
final class JobTelemetry {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            "dev.stratus.jobs.spark.telemetry");
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)((?:secret|password|token|credential|authorization|api[-_.]?key|"
                    + "access[-_.]?key|private[-_.]?key)\\s*[:=]\\s*)([^,;\\s}\\]]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^,;\\s]+");
    private static final Pattern URI_USER_INFO = Pattern.compile("(://)[^/@\\s]+@");

    private JobTelemetry() {
    }

    static <T> T measure(String job, String phase, String runId, String table,
                         Supplier<T> work) {
        String operationId = job.toLowerCase(Locale.ROOT) + '-'
                + String.format("%05d", SEQUENCE.incrementAndGet());
        long startedAt = System.nanoTime();
        try (var runContext = MDC.putCloseable("runId", oneLine(runId));
             var operationContext = MDC.putCloseable("operationId", operationId)) {
            LOGGER.debug("event=job_phase_started jobType={} phase={} runId={} operationId={} table={}",
                    oneLine(job), oneLine(phase), oneLine(runId), operationId, oneLine(table));
            try {
                T result = work.get();
                LOGGER.info("event=job_phase_completed jobType={} phase={} runId={} operationId={} "
                                + "table={} status=SUCCESS durationMs={}",
                        oneLine(job), oneLine(phase), oneLine(runId), operationId, oneLine(table),
                        Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
                return result;
            } catch (RuntimeException failure) {
                LOGGER.error("event=job_phase_completed jobType={} phase={} runId={} operationId={} "
                                + "table={} status=FAILED durationMs={} exceptionClass={} exceptionMessage={}",
                        oneLine(job), oneLine(phase), oneLine(runId), operationId, oneLine(table),
                        Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                        failure.getClass().getName(), oneLine(failure.getMessage()));
                throw failure;
            }
        }
    }

    static void measure(String job, String phase, String runId, String table, Runnable work) {
        measure(job, phase, runId, table, () -> {
            work.run();
            return null;
        });
    }

    static <T, E extends Exception> T measureChecked(String job, String phase, String runId,
                                                      String table, CheckedSupplier<T, E> work)
            throws E {
        String operationId = job.toLowerCase(Locale.ROOT) + '-'
                + String.format("%05d", SEQUENCE.incrementAndGet());
        long startedAt = System.nanoTime();
        try (var runContext = MDC.putCloseable("runId", oneLine(runId));
             var operationContext = MDC.putCloseable("operationId", operationId)) {
            LOGGER.debug("event=job_phase_started jobType={} phase={} runId={} operationId={} table={}",
                    oneLine(job), oneLine(phase), oneLine(runId), operationId, oneLine(table));
            try {
                T result = work.get();
                LOGGER.info("event=job_phase_completed jobType={} phase={} runId={} operationId={} "
                                + "table={} status=SUCCESS durationMs={}",
                        oneLine(job), oneLine(phase), oneLine(runId), operationId, oneLine(table),
                        Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
                return result;
            } catch (RuntimeException failure) {
                logFailure(job, phase, runId, table, operationId, startedAt, failure);
                throw failure;
            } catch (Exception failure) {
                logFailure(job, phase, runId, table, operationId, startedAt, failure);
                @SuppressWarnings("unchecked") E typed = (E) failure;
                throw typed;
            }
        }
    }

    private static void logFailure(String job, String phase, String runId, String table,
                                   String operationId, long startedAt, Exception failure) {
        LOGGER.error("event=job_phase_completed jobType={} phase={} runId={} operationId={} "
                        + "table={} status=FAILED durationMs={} exceptionClass={} exceptionMessage={}",
                oneLine(job), oneLine(phase), oneLine(runId), operationId, oneLine(table),
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                failure.getClass().getName(), oneLine(failure.getMessage()));
    }

    @FunctionalInterface
    interface CheckedSupplier<T, E extends Exception> {
        T get() throws E;
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "unset";
        }
        String redacted = BEARER.matcher(value).replaceAll("Bearer <redacted>");
        redacted = KEY_VALUE_SECRET.matcher(redacted).replaceAll("$1<redacted>");
        redacted = URI_USER_INFO.matcher(redacted).replaceAll("$1<redacted>@");
        String flattened = redacted.replace('\r', '_').replace('\n', '_').replace('\t', '_');
        return flattened.length() <= 512 ? flattened : flattened.substring(0, 512);
    }
}
