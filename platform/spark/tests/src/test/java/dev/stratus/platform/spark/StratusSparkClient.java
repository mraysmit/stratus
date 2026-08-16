// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.apache.spark.SparkStatusTracker;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * A client of the Stratus platform: a Spark driver in this JVM, submitting to
 * the standalone cluster as a named catalog principal.
 *
 * <p>This is what a tenant does, and it is what the design specifies
 * ({@code spark_compute.md} §10). The suite it replaces ran
 * {@code spark-submit} inside the master container, which exercised the
 * cluster's own copy of Spark and nothing about reaching it — no submission
 * path, no per-principal identity, and no way to hold two clients at once.
 *
 * <p>Each instance owns its session, so a test can hold several. Sessions are
 * built rather than shared for the same reason the configuration is a value:
 * {@code SparkSession.builder().getOrCreate()} returns the JVM's existing
 * session if there is one, which would silently hand a second client the first
 * client's identity — a test for principal separation that quietly used one
 * principal would pass while proving nothing.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
final class StratusSparkClient implements AutoCloseable {

    /**
     * Cores this client asks the cluster for, leaving the rest for the platform
     * jobs a test submits alongside it. See the {@code spark.cores.max} note in
     * {@link #connect}.
     */
    private final SparkClientConfig config;
    private final SparkSession session;
    private final SparkExecutionMetrics executionMetrics;

    private StratusSparkClient(SparkClientConfig config, SparkSession session,
                               SparkExecutionMetrics executionMetrics) {
        this.config = config;
        this.session = session;
        this.executionMetrics = executionMetrics;
    }

    static StratusSparkClient connect(SparkClientConfig config) {
        try (var observed = SparkTelemetry.start("client_connect", "spark.client.connect",
                "client=" + SparkLogSanitizer.token(config.applicationName())
                        + " principal=" + SparkLogSanitizer.token(config.principalId())
                        + " master=" + SparkLogSanitizer.token(config.masterUrl()))) {
            try {
                return connectObserved(config, observed);
            } catch (RuntimeException failure) {
                observed.failed(failure, "");
                throw failure;
            }
        }
    }

    private static StratusSparkClient connectObserved(SparkClientConfig config,
                                                       SparkTelemetry.Operation observed) {
        // Before the session: the trust manager a TLS client is born with is
        // the one it keeps, and the catalog client is created during startup.
        HarnessTruststore.installed();

        // A JVM holds one SparkContext, and getOrCreate hands back whatever
        // session already exists — with its identity, not the one asked for
        // here. Two test classes in one fork would silently share a principal,
        // and a suite that believes it is testing separation would be testing
        // one client twice. Refusing is the only way that failure is visible.
        var active = SparkSession.getActiveSession();
        if (active.isDefined() || SparkSession.getDefaultSession().isDefined()) {
            throw new IllegalStateException("A Spark session already exists in this JVM, and "
                    + config.applicationName() + " would silently inherit its identity. Use "
                    + "asAnotherPrincipal for a second client in the same driver, or run this "
                    + "class in its own fork.");
        }

        String catalog = "spark.sql.catalog." + config.catalogName();
        SparkSession session = SparkSession.builder()
                .appName(config.applicationName())
                .master(config.masterUrl())
                // Identity. This is the whole point: the credential belongs to
                // the client, not to the cluster's configuration file.
                .config(catalog, "org.apache.iceberg.spark.SparkCatalog")
                .config(catalog + ".type", "rest")
                .config(catalog + ".uri", config.catalogUri())
                .config(catalog + ".rest.auth.type", "oauth2")
                .config(catalog + ".oauth2-server-uri", config.catalogOAuth2Uri())
                .config(catalog + ".credential", config.principalCredential())
                .config(catalog + ".scope", "PRINCIPAL_ROLE:ALL")
                .config(catalog + ".warehouse", config.catalogName())
                .config(catalog + ".header.X-Iceberg-Access-Delegation", "none")
                .config(catalog + ".io-impl", "org.apache.iceberg.aws.s3.S3FileIO")
                .config(catalog + ".s3.endpoint", config.storageEndpoint())
                .config(catalog + ".s3.access-key-id", config.storageAccessKey())
                .config(catalog + ".s3.secret-access-key", config.storageSecretKey())
                .config(catalog + ".s3.path-style-access", "true")
                .config("spark.sql.extensions",
                        "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                .config("spark.sql.defaultCatalog", config.catalogName())
                // Timestamp literals without an explicit zone use the SQL
                // session zone. A client launched from Shanghai otherwise
                // sends an expiry boundary eight hours earlier than the same
                // client launched in UTC, so maintenance can report success
                // while expiring nothing.
                .config("spark.sql.session.timeZone", "UTC")
                // A standalone application with no ceiling takes every core in
                // the cluster. The developer cluster has four, so a client that
                // holds its session for a whole test class starves any job that
                // class submits: the job registers and then waits forever for
                // an executor that will never be offered. Observed 2026-08-12,
                // an ingestion job sat at cores=0 state=WAITING for 42 minutes
                // while this client held all four. A client reads counts and
                // metadata, so one core is ample and leaves three for the jobs.
                .config("spark.cores.max", String.valueOf(config.applicationCores()))
                .config("spark.executor.cores", "1")
                // The Apache default is 200 shuffle partitions. On this
                // four-core developer cluster that turned tiny Iceberg cleanup
                // actions into 204 tasks and added tens of seconds of scheduler
                // overhead. Two tasks per cluster core preserves parallelism
                // without manufacturing empty work.
                .config("spark.default.parallelism", "8")
                .config("spark.sql.shuffle.partitions", "8")
                // The raw-object path, configured separately from the catalog's
                // own storage client because it is a separate client: a working
                // catalog says nothing about whether S3A works.
                .config("spark.hadoop.fs.s3a.endpoint", config.storageEndpoint())
                .config("spark.hadoop.fs.s3a.access.key", config.storageAccessKey())
                .config("spark.hadoop.fs.s3a.secret.key", config.storageSecretKey())
                .config("spark.hadoop.fs.s3a.path.style.access", "true")
                .config("spark.hadoop.fs.s3a.connection.ssl.enabled", "true")
                .config("spark.hadoop.fs.s3a.fast.upload", "true")
                .config("spark.hadoop.fs.s3a.fast.upload.buffer", "bytebuffer")
                .config("spark.redaction.regex",
                        "(?i)secret|password|token|access[.]?key|credential")
                .config("spark.sql.redaction.options.regex",
                        "(?i)secret|password|token|access[.]?key|credential")
                // Reachability. Executors are launched inside the container
                // bridge and dial back here; without an address they can route
                // to, the application is accepted and then never progresses.
                .config("spark.driver.host", config.driverHost())
                .config("spark.driver.bindAddress", "0.0.0.0")
                .config("spark.driver.port", String.valueOf(config.driverPort()))
                .config("spark.blockManager.port", String.valueOf(config.blockManagerPort()))
                // The executors' truststore is a path inside their own
                // container, not this workstation's. Omitting it lets the
                // catalog resolve and then fails every write with PKIX.
                .config("spark.executor.extraJavaOptions",
                        "-Djavax.net.ssl.trustStore=" + HarnessTruststore.CONTAINER_PATH)
                .getOrCreate();

        var executionMetrics = new SparkExecutionMetrics();
        session.sparkContext().addSparkListener(executionMetrics);
        var client = new StratusSparkClient(config, session, executionMetrics);
        SparkVerificationLogging.clientConnected(config.applicationName(), config.principalId(),
                client.applicationId(), config.masterUrl());
        observed.succeeded("applicationId=" + client.applicationId()
                + " sparkVersion=" + session.version()
                + " requestedCores=" + config.applicationCores());
        return client;
    }

    /**
     * A second client of the same cluster, authenticating as another principal.
     *
     * <p>A JVM has one {@code SparkContext}, and
     * {@code SparkSession.builder().getOrCreate()} hands back the existing
     * session whatever configuration it is asked for. Calling {@code connect}
     * twice would therefore produce two objects sharing one identity, and a
     * test for principal separation would pass while using a single principal —
     * the worst kind of green.
     *
     * <p>{@code newSession} gives an isolated SQL configuration over the shared
     * context, which is what lets a second catalog client authenticate as
     * someone else. The two share the cluster application, so this proves
     * per-principal authorisation rather than two independent drivers; two
     * drivers means two JVMs, which is a property of the cluster rather than of
     * a client.
     */
    StratusSparkClient asAnotherPrincipal(SparkClientConfig other) {
        try (var observed = SparkTelemetry.start("client_session", "spark.client.session",
                "client=" + SparkLogSanitizer.token(other.applicationName())
                        + " principal=" + SparkLogSanitizer.token(other.principalId()))) {
            try {
                StratusSparkClient client = asAnotherPrincipalObserved(other);
                observed.succeeded("applicationId=" + client.applicationId());
                return client;
            } catch (RuntimeException failure) {
                observed.failed(failure, "");
                throw failure;
            }
        }
    }

    private StratusSparkClient asAnotherPrincipalObserved(SparkClientConfig other) {
        SparkSession isolated = session.newSession();
        String catalog = "spark.sql.catalog." + other.catalogName();
        isolated.conf().set(catalog, "org.apache.iceberg.spark.SparkCatalog");
        isolated.conf().set(catalog + ".type", "rest");
        isolated.conf().set(catalog + ".uri", other.catalogUri());
        isolated.conf().set(catalog + ".rest.auth.type", "oauth2");
        isolated.conf().set(catalog + ".oauth2-server-uri", other.catalogOAuth2Uri());
        isolated.conf().set(catalog + ".credential", other.principalCredential());
        isolated.conf().set(catalog + ".scope", "PRINCIPAL_ROLE:ALL");
        isolated.conf().set(catalog + ".warehouse", other.catalogName());
        isolated.conf().set(catalog + ".header.X-Iceberg-Access-Delegation", "none");
        isolated.conf().set(catalog + ".io-impl", "org.apache.iceberg.aws.s3.S3FileIO");
        isolated.conf().set(catalog + ".s3.endpoint", other.storageEndpoint());
        isolated.conf().set(catalog + ".s3.access-key-id", other.storageAccessKey());
        isolated.conf().set(catalog + ".s3.secret-access-key", other.storageSecretKey());
        isolated.conf().set(catalog + ".s3.path-style-access", "true");

        var client = new StratusSparkClient(other, isolated, executionMetrics);
        SparkVerificationLogging.clientConnected(other.applicationName(), other.principalId(),
                client.applicationId(), other.masterUrl());
        return client;
    }

    SparkSession session() {
        return session;
    }

    SparkClientConfig config() {
        return config;
    }

    /**
     * The cluster's identifier for this client's work.
     *
     * <p>The standalone master issues {@code app-<timestamp>-<n>}; a session
     * that fell back to running in this JVM would say {@code local-…}. Tests
     * assert on the prefix, because every other assertion in a suite passes
     * just as happily against a cluster it never reached.
     */
    String applicationId() {
        return session.sparkContext().applicationId();
    }

    /** Runs a statement and returns its rows. */
    List<Row> sql(String statement) {
        SparkVerificationLogging.statementSubmitted(config.applicationName(),
                config.principalId(), statement);
        String action = sqlAction(statement);
        String fingerprint = SparkLogSanitizer.fingerprint(statement);
        try (var observed = SparkTelemetry.start("sql", "spark.sql." + action.toLowerCase(Locale.ROOT),
                "applicationId=" + applicationId()
                        + " client=" + SparkLogSanitizer.token(config.applicationName())
                        + " principal=" + SparkLogSanitizer.token(config.principalId())
                        + " action=" + action + " statementHash=" + fingerprint)) {
            String operationId = observed.operationId();
            session.sparkContext().setJobGroup(operationId, action + " " + fingerprint, false);
            try {
                List<Row> rows = session.sql(statement).collectAsList();
                SparkWork work = workFor(operationId);
                observed.succeeded("rows=" + rows.size() + ' ' + work.fields() + ' '
                        + settledMetrics(operationId).fields());
                return rows;
            } catch (RuntimeException failure) {
                observed.failed(failure, workFor(operationId).fields() + ' '
                        + settledMetrics(operationId).fields());
                throw failure;
            } finally {
                session.sparkContext().clearJobGroup();
            }
        }
    }

    /** The first column of the first row, as text; the shape most checks want. */
    String scalar(String statement) {
        List<Row> rows = sql(statement);
        if (rows.isEmpty()) {
            throw new IllegalStateException("No rows returned by: " + statement);
        }
        Object value = rows.get(0).get(0);
        return value == null ? null : value.toString();
    }

    @Override
    public void close() {
        try (var observed = SparkTelemetry.start("client_close", "spark.client.close",
                "applicationId=" + applicationId()
                        + " client=" + SparkLogSanitizer.token(config.applicationName()))) {
            try {
                session.sparkContext().removeSparkListener(executionMetrics);
                session.stop();
                observed.succeeded("");
            } catch (RuntimeException failure) {
                observed.failed(failure, "");
                throw failure;
            }
        }
    }

    private static String sqlAction(String statement) {
        String trimmed = statement == null ? "" : statement.stripLeading();
        if (trimmed.isEmpty()) {
            return "UNKNOWN";
        }
        int separator = trimmed.indexOf(' ');
        String first = separator < 0 ? trimmed : trimmed.substring(0, separator);
        return SparkLogSanitizer.token(first.toUpperCase(Locale.ROOT), 32);
    }

    private SparkWork workFor(String operationId) {
        SparkStatusTracker tracker = session.sparkContext().statusTracker();
        int[] jobIds = tracker.getJobIdsForGroup(operationId);
        var stageIds = new LinkedHashSet<Integer>();
        for (int jobId : jobIds) {
            var job = tracker.getJobInfo(jobId);
            if (job.isDefined()) {
                for (int stageId : job.get().stageIds()) {
                    stageIds.add(stageId);
                }
            }
        }
        int tasks = 0;
        int completedTasks = 0;
        int failedTasks = 0;
        for (int stageId : stageIds) {
            var stage = tracker.getStageInfo(stageId);
            if (stage.isDefined()) {
                tasks += stage.get().numTasks();
                completedTasks += stage.get().numCompletedTasks();
                failedTasks += stage.get().numFailedTasks();
            }
        }
        return new SparkWork(jobIds.length, stageIds.size(), tasks, completedTasks, failedTasks);
    }

    private SparkExecutionMetrics.MetricsSnapshot settledMetrics(String operationId) {
        final long timeoutMillis = 5_000L;
        try {
            session.sparkContext().listenerBus().waitUntilEmpty(timeoutMillis);
        } catch (TimeoutException timeout) {
            SparkVerificationLogging.executionMetricsIncomplete(operationId, timeoutMillis);
        }
        return executionMetrics.snapshot(operationId);
    }

    private record SparkWork(int jobs, int stages, int tasks, int completedTasks, int failedTasks) {

        String fields() {
            return "jobs=" + jobs + " stages=" + stages + " tasks=" + tasks
                    + " completedTasks=" + completedTasks + " failedTasks=" + failedTasks;
        }
    }
}
