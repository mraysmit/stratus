// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * The diagnostic level a platform job runs at, and how its records are
 * rendered.
 *
 * <p>Without this every {@code Level.FINE} record a job emits is discarded by
 * the JDK's default level before anything can read it. The jobs would carry
 * diagnostics that no operator could ever turn on, which is the same as not
 * having them: the completion gate asks for INFO and DEBUG behaviour that is
 * exercised, not merely present in the source.
 *
 * <p>The level is read from {@code STRATUS_LOG_LEVEL}, the same switch the
 * Ceph, catalog, and secrets verifiers use, so one setting governs a run end to
 * end — the suite on the workstation and the job inside the cluster.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class JobLogging {

    /** The package every job's logger sits under. */
    static final String LOGGER_NAME = "dev.stratus.jobs.spark";

    private JobLogging() {
    }

    /** Applies the level from the environment. Called first by every job's main. */
    public static void configureFromEnvironment() {
        configure(System.getenv().getOrDefault("STRATUS_LOG_LEVEL", "INFO"));
    }

    static void configure(String configuredLevel) {
        Level level = switch (configuredLevel.toUpperCase(Locale.ROOT)) {
            case "DEBUG" -> Level.FINE;
            case "INFO" -> Level.INFO;
            default -> throw new IllegalArgumentException("STRATUS_LOG_LEVEL must be INFO or DEBUG");
        };
        Logger.getLogger(LOGGER_NAME).setLevel(level);
        for (var handler : Logger.getLogger("").getHandlers()) {
            handler.setLevel(level);
            handler.setFormatter(new OperationalLevelFormatter());
        }
    }

    /**
     * Renders records in the operational level vocabulary: the JDK backend
     * names the diagnostic level FINE, but operators configure DEBUG, so
     * transcripts say DEBUG. One line per record with a UTC timestamp, which is
     * what makes a job's records greppable in a transcript that also carries
     * Spark's own multi-line output.
     */
    static final class OperationalLevelFormatter extends Formatter {

        @Override
        public String format(LogRecord logRecord) {
            String level = Level.FINE.equals(logRecord.getLevel())
                    ? "DEBUG" : logRecord.getLevel().getName();
            var line = new StringBuilder()
                    .append(DateTimeFormatter.ISO_INSTANT.format(
                            logRecord.getInstant().truncatedTo(ChronoUnit.MILLIS)))
                    .append(' ').append(level)
                    .append(' ').append(logRecord.getLoggerName())
                    .append(' ').append(formatMessage(logRecord))
                    .append(System.lineSeparator());
            if (logRecord.getThrown() != null) {
                var stackTrace = new StringWriter();
                logRecord.getThrown().printStackTrace(new PrintWriter(stackTrace));
                line.append(stackTrace);
            }
            return line.toString();
        }
    }
}
