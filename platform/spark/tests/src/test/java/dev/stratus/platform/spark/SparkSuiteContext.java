// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Owns one live Spark context for a complete JUnit launcher run and gives each
 * test class an isolated SQL session over it.
 */
final class SparkSuiteContext implements BeforeAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(SparkSuiteContext.class);
    private static final String RESOURCE_KEY = "suite-spark-context";

    private SharedResource resource;

    @Override
    public void beforeAll(ExtensionContext context) {
        LiveSparkCluster.require();
        resource = context.getRoot().getStore(NAMESPACE).computeIfAbsent(
                RESOURCE_KEY, ignored -> new SharedResource(), SharedResource.class);
    }

    StratusSparkClient client(SparkClientConfig config) {
        if (resource == null) {
            throw new IllegalStateException("The suite Spark context has not been started");
        }
        return resource.client(config);
    }

    private static final class SharedResource implements AutoCloseable {

        private final StratusSparkClient owner = StratusSparkClient.connect(
                SparkClientConfig.serviceIdentity("stratus-live-suite", 17077, 17078)
                        .withApplicationCores(2));

        private StratusSparkClient client(SparkClientConfig config) {
            return owner.asAnotherPrincipal(config);
        }

        @Override
        public void close() {
            owner.close();
        }
    }
}
