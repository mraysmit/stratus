// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.io.PrintWriter;
import java.io.StringWriter;
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
    private static final String SUITE_RUN_ID = System.getProperty("stratus.run.id",
            System.getenv().getOrDefault("STRATUS_RUN_ID", "unscoped"));
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)((?:[\\\"']?(?:secret|password|token|credential|authorization|api[-_.]?key|"
                    + "access[-_.]?key|private[-_.]?key)[\\\"']?)\\s*[:=]\\s*)"
                    + "(?:\\\"[^\\\"]*\\\"|'[^']*'|[^,;\\s}\\]]+)");
    private static final Pattern SQL_SECRET = Pattern.compile(
            "(?i)(\\b(?:secret|password|token|credential|authorization|api[-_.]?key|"
                    + "access[-_.]?key|private[-_.]?key)\\b\\s+)"
                    + "(?:\\\"[^\\\"]*\\\"|'[^']*'|[^,;\\s]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^,;\\s]+");
    private static final Pattern URI_USER_INFO = Pattern.compile("(://)[^/@\\s]+@");

    private JobTelemetry() {
    }

    static <T> T measure(String job, String phase, String runId, String table,
                         Supplier<T> work) {
        String operationId = job.toLowerCase(Locale.ROOT) + '-'
                + String.format("%05d", SEQUENCE.incrementAndGet());
        long startedAt = System.nanoTime();
        try (var context = openContext(runId);
             var operationContext = MdcScope.put("operationId", operationId)) {
            LOGGER.debug("event=job_phase_started jobType={} phase={} jobRunId={} operationId={} table={}",
                    oneLine(job), oneLine(phase), oneLine(runId), operationId, oneLine(table));
            try {
                T result = work.get();
                LOGGER.info("event=job_phase_completed jobType={} phase={} jobRunId={} operationId={} "
                                + "table={} status=SUCCESS durationMs={}",
                        oneLine(job), oneLine(phase), oneLine(runId), operationId, oneLine(table),
                        Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
                return result;
            } catch (Exception failure) {
                LOGGER.error("event=job_phase_completed jobType={} phase={} jobRunId={} operationId={} "
                                + "table={} status=FAILED durationMs={} exceptionClass={} exceptionMessage={}",
                        oneLine(job), oneLine(phase), oneLine(runId), operationId, oneLine(table),
                        Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                        failure.getClass().getName(), oneLine(failure.getMessage()));
                logFailureDetail(job, phase, runId, operationId, failure);
                throw unchecked(failure);
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
        try (var context = openContext(runId);
             var operationContext = MdcScope.put("operationId", operationId)) {
            LOGGER.debug("event=job_phase_started jobType={} phase={} jobRunId={} operationId={} table={}",
                    oneLine(job), oneLine(phase), oneLine(runId), operationId, oneLine(table));
            try {
                T result = work.get();
                LOGGER.info("event=job_phase_completed jobType={} phase={} jobRunId={} operationId={} "
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
        LOGGER.error("event=job_phase_completed jobType={} phase={} jobRunId={} operationId={} "
                        + "table={} status=FAILED durationMs={} exceptionClass={} exceptionMessage={}",
                oneLine(job), oneLine(phase), oneLine(runId), operationId, oneLine(table),
                Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                failure.getClass().getName(), oneLine(failure.getMessage()));
        logFailureDetail(job, phase, runId, operationId, failure);
    }

    private static void logFailureDetail(String job, String phase, String runId,
                                         String operationId, Throwable failure) {
        var rendered = new StringWriter();
        failure.printStackTrace(new PrintWriter(rendered));
        LOGGER.debug("event=job_phase_failure_detail jobType={} phase={} jobRunId={} "
                        + "operationId={} stackTrace={}",
                oneLine(job), oneLine(phase), oneLine(runId), operationId,
                oneLine(rendered.toString(), 8192));
    }

    private static RuntimeException unchecked(Exception failure) {
        return failure instanceof RuntimeException runtimeFailure
                ? runtimeFailure : new IllegalStateException(failure.getMessage(), failure);
    }

    static Context openContext(String jobRunId) {
        String callerSuiteRunId = MDC.get("suiteRunId");
        String suiteRunId = callerSuiteRunId == null ? SUITE_RUN_ID : callerSuiteRunId;
        return new Context(MdcScope.put("suiteRunId", oneLine(suiteRunId)),
                MdcScope.put("jobRunId", oneLine(jobRunId)));
    }

    @FunctionalInterface
    interface CheckedSupplier<T, E extends Exception> {
        T get() throws E;
    }

    private static String oneLine(String value) {
        return oneLine(value, 512);
    }

    private static String oneLine(String value, int maximumLength) {
        if (value == null) {
            return "unset";
        }
        String redacted = BEARER.matcher(value).replaceAll("Bearer <redacted>");
        redacted = KEY_VALUE_SECRET.matcher(redacted).replaceAll("$1<redacted>");
        redacted = SQL_SECRET.matcher(redacted).replaceAll("$1<redacted>");
        redacted = URI_USER_INFO.matcher(redacted).replaceAll("$1<redacted>@");
        String flattened = redacted.replace('\r', '_').replace('\n', '_').replace('\t', '_');
        return flattened.length() <= maximumLength
                ? flattened : flattened.substring(0, maximumLength);
    }

    static final class Context implements AutoCloseable {

        private final MdcScope suiteContext;
        private final MdcScope jobContext;

        private Context(MdcScope suiteContext, MdcScope jobContext) {
            this.suiteContext = suiteContext;
            this.jobContext = jobContext;
        }

        @Override
        public void close() {
            jobContext.close();
            suiteContext.close();
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
}
