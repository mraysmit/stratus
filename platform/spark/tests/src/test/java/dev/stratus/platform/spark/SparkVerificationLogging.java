// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sanitized operational logging for the Spark conformance suite, following the
 * same conventions as the Ceph, catalog, and secrets suites: INFO records
 * lifecycle outcomes with stable identifiers; DEBUG adds the diagnostic detail
 * needed to investigate a failure — the command that ran and what the job
 * itself reported.
 *
 * <p>Relaying the job's own records matters more here than in the other suites.
 * A Spark job runs inside the cluster, so its log records reach this process as
 * the output of a submission and are otherwise read once, asserted on, and
 * thrown away. Without the relay a transcript can say a pipeline passed while
 * containing no evidence of what it did.
 *
 * <p>Secrets never cross this boundary. A catalog credential is supplied to
 * spark-sql as a {@code --conf} argument, so command arguments are redacted by
 * key before they are recorded rather than trusted to be harmless.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
final class SparkVerificationLogging {

    static final String LOGGER_NAME = "dev.stratus.platform.spark";

    /**
     * Argument fragments whose value is credential material. Matched
     * case-insensitively against the whole argument, because the value arrives
     * joined to its key in a single {@code --conf key=value} token.
     */
    private static final List<String> SECRET_MARKERS =
            List.of("credential", "secret", "password", "access-key", "token");

    private static final String REDACTED = "<redacted>";

    /** The logger name every platform job's records carry. */
    private static final String JOB_LOGGER_PREFIX = "dev.stratus.jobs.spark.";

    /** The lineage payload's marker, which carries no logger name of its own. */
    private static final String LINEAGE_MARKER = "STRATUS_LINEAGE";

    /** Kept from a submission that logged nothing, where the failure is the tail. */
    private static final int TAIL_LINES = 12;

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    static {
        configure(System.getenv().getOrDefault("STRATUS_LOG_LEVEL", "INFO"));
    }

    private SparkVerificationLogging() {
    }

    /**
     * Records one command run inside the cluster. The job's own output goes to
     * DEBUG in full: it carries the INGESTION, TRANSFORM, QUALITY, and
     * MAINTENANCE records the jobs emit through the production logging path,
     * and those are the only account of what the pipeline actually did.
     */
    static void commandCompleted(String description, List<String> argv, int exitCode,
                                 long durationMillis, String output) {
        LOGGER.info("Cluster command completed action={} exitCode={} durationMs={} outputBytes={}",
                safeToken(description), exitCode, durationMillis,
                output == null ? 0 : output.length());
        LOGGER.debug("Cluster command detail action={} argv={}",
                safeToken(description), redact(argv));
        for (String record : jobRecords(output, exitCode)) {
            LOGGER.debug("Job record action={} record={}", safeToken(description),
                    safeToken(record, 1024));
        }
    }

    /**
     * The job's own log records, picked out of a submission's output.
     *
     * <p>Selected rather than truncated. A submission prints pages of engine
     * startup before the job says anything, so keeping the first few kilobytes
     * keeps exactly the part nobody needs and drops the account of what the job
     * did — which is the whole reason for relaying it.
     *
     * <p>When nothing matches and the command failed, the tail is kept instead:
     * a job that died before logging anything leaves its stack trace there, and
     * that is the one case where the noise is the evidence. A command that
     * succeeded without logging — every plain SQL statement — leaves nothing,
     * because its banner is not worth a dozen lines in the transcript.
     */
    static List<String> jobRecords(String output, int exitCode) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<String> lines = output.lines().toList();
        List<String> records = lines.stream()
                .filter(line -> line.contains(JOB_LOGGER_PREFIX) || line.contains(LINEAGE_MARKER))
                .toList();
        if (!records.isEmpty() || exitCode == 0) {
            return records;
        }
        return lines.size() <= TAIL_LINES ? lines : lines.subList(lines.size() - TAIL_LINES, lines.size());
    }

    /** Records a landing fixture reaching the object store. */
    static void landingFixturePlaced(String object, int bytes, int exitCode) {
        LOGGER.info("Landing fixture placed object={} bytes={} exitCode={}",
                safeToken(object), bytes, exitCode);
    }

    /** Records a measured value a later assertion is taken on. */
    static void measurement(String expression, String from, String value) {
        LOGGER.info("Measurement taken expression={} value={}",
                safeToken(expression), safeToken(value));
        LOGGER.debug("Measurement detail expression={} from={} value={}",
                safeToken(expression), safeToken(from), safeToken(value));
    }

    /** Records a scenario's verdict, so a transcript reads as a sequence of outcomes. */
    static void scenarioPassed(String scenario, String detail) {
        LOGGER.info("Scenario passed scenario={} detail={}", safeToken(scenario), safeToken(detail));
    }

    /** Records a refusal that was expected, with the reason the job gave. */
    static void negativeConfirmed(String check, int exitCode, String detail) {
        LOGGER.info("Negative check confirmed check={} exitCode={} detail={}",
                safeToken(check), exitCode, safeToken(detail));
    }

    // --- The pipeline's own vocabulary ---------------------------------------
    //
    // The records above describe the harness; these describe the platform. A
    // transcript of commands and exit codes proves a suite ran, not that a
    // pipeline did the right thing, and it is the second that a reader is
    // looking for when a batch lands somewhere it should not have.

    /**
     * Records a statement a client submitted, and the principal it submitted
     * as. The principal is the point: a transcript that records only the
     * statement cannot show that two clients were separated.
     */
    static void statementSubmitted(String client, String principal, String statement) {
        LOGGER.info("Statement submitted client={} principal={}",
                safeToken(client), safeToken(principal));
        LOGGER.debug("Statement detail client={} principal={} statement={}",
                safeToken(client), safeToken(principal), safeToken(statement, 2048));
    }

    /** Records a client's session reaching the cluster, with the cluster's own id for it. */
    static void clientConnected(String client, String principal, String applicationId,
                                String masterUrl) {
        LOGGER.info("Client connected client={} principal={} applicationId={} master={}",
                safeToken(client), safeToken(principal), safeToken(applicationId),
                safeToken(masterUrl));
    }

    /** Records the standalone cluster's registered capacity. */
    static void clusterInspected(int workersAlive, int cores) {
        LOGGER.info("Cluster inspected workersAlive={} cores={}", workersAlive, cores);
    }

    /** Records the governed namespaces the catalog resolved. */
    static void namespacesResolved(List<String> zones) {
        LOGGER.info("Namespaces resolved count={} zones={}", zones.size(),
                safeToken(String.join(",", zones)));
    }

    /**
     * Records a batch reaching bronze. INFO carries the counts a reader checks
     * accumulation against; DEBUG carries where it came from and how it was
     * read, which is what a wrong count is diagnosed from.
     */
    static void batchIngested(String table, String batchId, String rowsInTable, String partitions,
                              String sourceFile, String schema) {
        LOGGER.info("Batch ingested table={} batchId={} rowsInTable={} partitions={}",
                safeToken(table), safeToken(batchId), safeToken(rowsInTable), safeToken(partitions));
        LOGGER.debug("Batch ingested detail table={} batchId={} sourceFile={} declaredSchema={}",
                safeToken(table), safeToken(batchId), safeToken(sourceFile), safeToken(schema, 512));
    }

    /** Records a batch the table refused, and the reason it gave. */
    static void batchRefused(String table, String batchId, String mode, int exitCode, String reason) {
        LOGGER.info("Batch refused table={} batchId={} mode={} exitCode={}",
                safeToken(table), safeToken(batchId), safeToken(mode), exitCode);
        LOGGER.debug("Batch refused detail table={} batchId={} reason={}",
                safeToken(table), safeToken(batchId), safeToken(reason, 1024));
    }

    /** Records a column arriving that the table did not have. */
    static void schemaEvolved(String table, String column, String rowsWithoutValue) {
        LOGGER.info("Schema evolved table={} addedColumn={} rowsWithoutValue={}",
                safeToken(table), safeToken(column), safeToken(rowsWithoutValue));
    }

    /** Records a batch refused because a column's type changed. */
    static void schemaDriftRefused(String table, int exitCode, String rowsUnchanged, String detail) {
        LOGGER.info("Schema drift refused table={} exitCode={} rowsUnchanged={}",
                safeToken(table), exitCode, safeToken(rowsUnchanged));
        LOGGER.debug("Schema drift detail table={} conflicts={}",
                safeToken(table), safeToken(detail, 1024));
    }

    /**
     * Records the result of an upsert into silver: which key was examined and
     * what value survived. A row count alone cannot distinguish a correction
     * that was applied from one that was silently dropped.
     */
    static void silverUpserted(String table, String batchId, String rows, String key, String value) {
        LOGGER.info("Silver upserted table={} batchId={} rows={} key={} value={}",
                safeToken(table), safeToken(batchId), safeToken(rows),
                safeToken(key), safeToken(value));
    }

    /** Records a replay that was correctly not allowed to overwrite newer state. */
    static void staleReplayIgnored(String table, String batchId, String key, String heldValue) {
        LOGGER.info("Stale replay ignored table={} batchId={} key={} valueHeld={}",
                safeToken(table), safeToken(batchId), safeToken(key), safeToken(heldValue));
    }

    /** Records a quality run's shape: how many rules ran and how many failed. */
    static void qualityRunRecorded(String runId, String table, String recorded, String failed) {
        LOGGER.info("Quality run recorded runId={} table={} rulesRecorded={} rulesFailed={}",
                safeToken(runId), safeToken(table), safeToken(recorded), safeToken(failed));
    }

    /** Records one rule's verdict with the detail it was measured from. */
    static void qualityRuleOutcome(String runId, String checkName, String status, String detail) {
        LOGGER.info("Quality rule outcome runId={} check={} status={}",
                safeToken(runId), safeToken(checkName), safeToken(status));
        LOGGER.debug("Quality rule detail runId={} check={} detail={}",
                safeToken(runId), safeToken(checkName), safeToken(detail, 1024));
    }

    /** Records the gate's verdict and the status code it exited with. */
    static void promotionDecided(String runId, String table, String verdict, int exitCode) {
        LOGGER.info("Promotion decided runId={} table={} verdict={} exitCode={}",
                safeToken(runId), safeToken(table), safeToken(verdict), exitCode);
    }

    /** Records an override, which is never silent and never edits the verdict it overrides. */
    static void overrideRecorded(String runId, String principal, String failuresRemaining) {
        LOGGER.info("Promotion override recorded runId={} principal={} originalFailuresIntact={}",
                safeToken(runId), safeToken(principal), safeToken(failuresRemaining));
    }

    /** Records what a maintenance operation changed, measured either side of it. */
    static void maintenanceOutcome(String table, String operation, String before, String after,
                                   String rows) {
        LOGGER.info("Maintenance outcome table={} operation={} before={} after={} rows={}",
                safeToken(table), safeToken(operation), safeToken(before), safeToken(after),
                safeToken(rows));
    }

    /**
     * Records a table's deployed write configuration. INFO carries the settings
     * the architecture names; DEBUG carries the whole map, because a property
     * nobody asserted on is exactly the one that will have drifted.
     */
    static void tablePropertiesInspected(String table, Map<String, String> properties) {
        LOGGER.info("Table properties inspected table={} count={} mergeMode={} formatVersion={}",
                safeToken(table), properties.size(),
                safeToken(properties.getOrDefault("write.merge.mode", "unset")),
                safeToken(properties.getOrDefault("format-version", "unset")));
        LOGGER.debug("Table properties detail table={} properties={}",
                safeToken(table), safeToken(new TreeMap<>(properties).toString(), 2048));
    }

    /** Records what remains under an object prefix, which is how cleanup is evidenced. */
    static void objectPrefixListed(String prefix, String listing) {
        LOGGER.info("Object prefix listed prefix={} empty={}",
                safeToken(prefix), listing == null || listing.isBlank());
        LOGGER.debug("Object prefix detail prefix={} listing={}",
                safeToken(prefix), safeToken(listing == null ? "" : listing, 2048));
    }

    /**
     * Replaces the value of any argument carrying credential material. The
     * whole token is replaced rather than the part after {@code =}, because an
     * argument may be a bare secret with no key at all.
     */
    static List<String> redact(List<String> argv) {
        return argv.stream().map(SparkVerificationLogging::redactArgument).toList();
    }

    private static String redactArgument(String argument) {
        String lower = argument.toLowerCase(Locale.ROOT);
        for (String marker : SECRET_MARKERS) {
            if (lower.contains(marker)) {
                int separator = argument.indexOf('=');
                return separator < 0 ? REDACTED : argument.substring(0, separator + 1) + REDACTED;
            }
        }
        return safeToken(argument, 512);
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
     * Renders console records in the operational level vocabulary: the JDK
     * backend names the diagnostic level FINE, but operators configure DEBUG,
     * so transcripts say DEBUG. One line per record with a UTC timestamp; an
     * attached exception renders in full so failure context is never lost.
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

    private static String safeToken(String value) {
        return safeToken(value, 256);
    }

    /**
     * Flattens a value onto one line and bounds its length. A job's output runs
     * to thousands of lines, and a transcript that reproduces all of it stops
     * being readable — which is the same as not being kept.
     */
    private static String safeToken(String value, int maxLength) {
        String singleLine = value.replace('\r', '_').replace('\n', '_').replace('\t', '_');
        return singleLine.length() <= maxLength ? singleLine : singleLine.substring(0, maxLength);
    }
}
