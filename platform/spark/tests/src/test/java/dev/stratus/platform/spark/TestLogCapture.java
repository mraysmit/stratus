// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

/** Captures the real Log4j2 provider used by SLF4J without replacing it. */
final class TestLogCapture extends AbstractAppender implements AutoCloseable {

    private final String loggerPrefix;
    private final Level originalLevel;
    private final LoggerContext context;
    private final Configuration configuration;
    private final org.apache.logging.log4j.core.config.LoggerConfig captureConfig;
    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    TestLogCapture(String loggerPrefix) {
        super("capture-" + loggerPrefix + '-' + System.nanoTime(), null,
                PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY);
        this.loggerPrefix = loggerPrefix;
        context = (LoggerContext) LogManager.getContext(false);
        configuration = context.getConfiguration();
        captureConfig = configuration.getLoggerConfig(loggerPrefix);
        originalLevel = captureConfig.getLevel();
        start();
        captureConfig.addAppender(this, Level.ALL, null);
        Configurator.setLevel(loggerPrefix, Level.ALL);
        context.updateLoggers();
    }

    @Override
    public void append(LogEvent event) {
        if (event.getLoggerName().startsWith(loggerPrefix)) {
            events.add(event.toImmutable());
        }
    }

    List<LogEvent> at(Level level) {
        return events.stream().filter(event -> event.getLevel().equals(level)).toList();
    }

    List<String> messages(Level level) {
        return at(level).stream().map(event -> event.getMessage().getFormattedMessage()).toList();
    }

    List<LogEvent> events() {
        return List.copyOf(events);
    }

    @Override
    public void close() {
        captureConfig.removeAppender(getName());
        Configurator.setLevel(loggerPrefix, originalLevel);
        context.updateLoggers();
        stop();
    }
}
