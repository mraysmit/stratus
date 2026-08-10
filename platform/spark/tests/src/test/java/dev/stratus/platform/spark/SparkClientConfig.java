// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.util.Map;

/**
 * Everything one client needs to use the platform, as a value.
 *
 * <p>This is a record and not a set of environment variables for one reason:
 * two clients. Configuration read from the process cannot differ between two
 * clients in the same JVM, so a suite built on {@code System.getenv} can never
 * express a second principal, and therefore can never test whether principals
 * are separated at all. Everything the suite needs to vary lives here.
 *
 * @param applicationName the name the cluster shows for this client's work
 * @param masterUrl the standalone master, reached over its published port
 * @param catalogName the Iceberg catalog, which is also the warehouse
 * @param catalogUri the Polaris catalog API
 * @param principalCredential {@code clientId:clientSecret} for the catalog
 * @param storageEndpoint the object store
 * @param storageAccessKey the object-store identity
 * @param storageSecretKey the object-store secret
 * @param driverHost the address executors dial back on
 * @param driverPort the driver's RPC port, fixed so it is knowable in advance
 * @param blockManagerPort the driver's block transfer port, fixed for the same reason
 *
 * This record is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
record SparkClientConfig(
        String applicationName,
        String masterUrl,
        String catalogName,
        String catalogUri,
        String principalCredential,
        String storageEndpoint,
        String storageAccessKey,
        String storageSecretKey,
        String driverHost,
        int driverPort,
        int blockManagerPort) {

    /**
     * Executors run in containers and connect back to this process. On the
     * developer runtime that address is the one the container runtime publishes
     * for the workstation; there is no route from the container bridge to a
     * host address without it.
     */
    static final String DEVELOPER_DRIVER_HOST = "host.docker.internal";

    /**
     * The master over its published loopback port. No hosts-file entry is
     * needed: the port is published on loopback, and this was proven against
     * the live cluster before the suite was built on it.
     */
    static final String DEVELOPER_MASTER = "spark://127.0.0.1:7077";

    SparkClientConfig {
        if (principalCredential == null || !principalCredential.contains(":")) {
            throw new IllegalArgumentException(
                    "The catalog credential must be clientId:clientSecret");
        }
        if (driverPort == blockManagerPort) {
            throw new IllegalArgumentException(
                    "The driver and block manager need separate ports, both were " + driverPort);
        }
    }

    /**
     * The default client: the {@code svc-spark} identity the harness
     * provisions, discovered from the providers' published settings.
     *
     * <p>Ports are supplied by the caller because two clients cannot share
     * them. A second client binding the first client's port fails at startup,
     * which is a clearer failure than two drivers quietly competing.
     */
    static SparkClientConfig serviceIdentity(String applicationName, int driverPort,
                                             int blockManagerPort) {
        Map<String, String> storage = HarnessConnection.objectStorageCredentials("svc-spark");
        return new SparkClientConfig(
                applicationName,
                DEVELOPER_MASTER,
                HarnessConnection.polarisCatalogName(),
                HarnessConnection.polarisCatalogUri(),
                HarnessConnection.sparkPolarisCredential(),
                HarnessConnection.cephEndpoint(),
                storage.get("accessKey"),
                storage.get("secretKey"),
                DEVELOPER_DRIVER_HOST,
                driverPort,
                blockManagerPort);
    }

    /** The same platform, reached as a different catalog principal. */
    SparkClientConfig asPrincipal(String applicationName, String credential, int driverPort,
                                  int blockManagerPort) {
        return new SparkClientConfig(applicationName, masterUrl, catalogName, catalogUri,
                credential, storageEndpoint, storageAccessKey, storageSecretKey,
                driverHost, driverPort, blockManagerPort);
    }

    /** The principal this client authenticates as, without its secret. */
    String principalId() {
        return principalCredential.substring(0, principalCredential.indexOf(':'));
    }
}
