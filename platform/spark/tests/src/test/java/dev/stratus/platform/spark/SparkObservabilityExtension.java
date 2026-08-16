// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Automatically records suite, class, fixture-inclusive test, and outcome timing. */
public final class SparkObservabilityExtension
        implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, TestWatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            "dev.stratus.platform.spark.junit");
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(SparkObservabilityExtension.class);
    private static final Map<String, Long> CLASS_STARTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> TEST_STARTS = new ConcurrentHashMap<>();

    @Override
    public void beforeAll(ExtensionContext context) {
        SparkTelemetry.installRunContext();
        context.getRoot().getStore(NAMESPACE).computeIfAbsent(
                "suite", ignored -> new SuiteLifetime(), SuiteLifetime.class);
        CLASS_STARTS.put(context.getUniqueId(), System.nanoTime());
        LOGGER.info("event=test_class_started suiteRunId={} testClass={}",
                SparkTelemetry.runId(), context.getRequiredTestClass().getName());
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        SparkTelemetry.installRunContext();
        String test = context.getRequiredTestClass().getSimpleName() + '#'
                + context.getRequiredTestMethod().getName();
        MDC.put("test", test);
        TEST_STARTS.put(context.getUniqueId(), System.nanoTime());
        LOGGER.info("event=test_started suiteRunId={} test={}", SparkTelemetry.runId(), test);
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        finish(context, "PASSED", null);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        finish(context, "FAILED", cause);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        finish(context, "ABORTED", cause);
    }

    @Override
    public void testDisabled(ExtensionContext context, java.util.Optional<String> reason) {
        String test = context.getRequiredTestClass().getSimpleName() + '#'
                + context.getRequiredTestMethod().getName();
        LOGGER.info("event=test_completed suiteRunId={} test={} status=SKIPPED durationMs=0 reason={}",
                SparkTelemetry.runId(), test,
                SparkLogSanitizer.token(reason.orElse("disabled"), 512));
    }

    private void finish(ExtensionContext context, String status, Throwable failure) {
        String test = context.getRequiredTestClass().getSimpleName() + '#'
                + context.getRequiredTestMethod().getName();
        long elapsed = System.nanoTime() - TEST_STARTS.getOrDefault(context.getUniqueId(), System.nanoTime());
        SparkTelemetry.record("junit.test", elapsed);
        SparkTelemetry.record("junit.test." + context.getRequiredTestClass().getSimpleName(), elapsed);
        if (failure == null) {
            LOGGER.info("event=test_completed suiteRunId={} test={} status={} durationMs={}",
                    SparkTelemetry.runId(), test, status, Duration.ofNanos(elapsed).toMillis());
        } else {
            LOGGER.error("event=test_completed suiteRunId={} test={} status={} durationMs={} "
                            + "exceptionClass={} exceptionMessage={}",
                    SparkTelemetry.runId(), test, status, Duration.ofNanos(elapsed).toMillis(),
                    failure.getClass().getName(), SparkLogSanitizer.token(failure.getMessage(), 1024));
            LOGGER.debug("event=test_failure_detail suiteRunId={} test={} stackTrace={}",
                    SparkTelemetry.runId(), test, SparkLogSanitizer.stackTrace(failure, 8192));
        }
        TEST_STARTS.remove(context.getUniqueId());
        MDC.remove("test");
    }

    @Override
    public void afterAll(ExtensionContext context) {
        long elapsed = System.nanoTime() - CLASS_STARTS.getOrDefault(
                context.getUniqueId(), System.nanoTime());
        SparkTelemetry.record("junit.class", elapsed);
        LOGGER.info("event=test_class_completed suiteRunId={} testClass={} durationMs={}",
                SparkTelemetry.runId(), context.getRequiredTestClass().getName(),
                Duration.ofNanos(elapsed).toMillis());
        CLASS_STARTS.remove(context.getUniqueId());
    }

    private static final class SuiteLifetime implements AutoCloseable {

        private final long startedAt = System.nanoTime();

        private SuiteLifetime() {
            SparkTelemetry.installRunContext();
            LOGGER.info("event=test_suite_started suiteRunId={}", SparkTelemetry.runId());
        }

        @Override
        public void close() {
            long elapsed = System.nanoTime() - startedAt;
            SparkTelemetry.record("junit.suite", elapsed);
            SparkTelemetry.logSummaries();
            LOGGER.info("event=test_suite_completed suiteRunId={} durationMs={}",
                    SparkTelemetry.runId(), Duration.ofNanos(elapsed).toMillis());
            MDC.clear();
        }
    }
}
