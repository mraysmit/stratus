// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.util.List;
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

    private final SparkClientConfig config;
    private final SparkSession session;

    private StratusSparkClient(SparkClientConfig config, SparkSession session) {
        this.config = config;
        this.session = session;
    }

    static StratusSparkClient connect(SparkClientConfig config) {
        // Before the session: the trust manager a TLS client is born with is
        // the one it keeps, and the catalog client is created during startup.
        HarnessTruststore.installed();

        String catalog = "spark.sql.catalog." + config.catalogName();
        SparkSession session = SparkSession.builder()
                .appName(config.applicationName())
                .master(config.masterUrl())
                // Identity. This is the whole point: the credential belongs to
                // the client, not to the cluster's configuration file.
                .config(catalog, "org.apache.iceberg.spark.SparkCatalog")
                .config(catalog + ".type", "rest")
                .config(catalog + ".uri", config.catalogUri())
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
                // The raw-object path, configured separately from the catalog's
                // own storage client because it is a separate client: a working
                // catalog says nothing about whether S3A works.
                .config("spark.hadoop.fs.s3a.endpoint", config.storageEndpoint())
                .config("spark.hadoop.fs.s3a.access.key", config.storageAccessKey())
                .config("spark.hadoop.fs.s3a.secret.key", config.storageSecretKey())
                .config("spark.hadoop.fs.s3a.path.style.access", "true")
                .config("spark.hadoop.fs.s3a.connection.ssl.enabled", "true")
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

        var client = new StratusSparkClient(config, session);
        SparkVerificationLogging.clientConnected(config.applicationName(), config.principalId(),
                client.applicationId(), config.masterUrl());
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
        return session.sql(statement).collectAsList();
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
        session.stop();
    }
}
