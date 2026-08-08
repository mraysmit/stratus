// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guardrails on the Spark harness files themselves, run offline in every
 * build. These are static assertions about the real harness, not a simulation
 * of it: the live behaviour is proven by the {@code spark-integration} suites.
 *
 * <p>They exist because the rules they enforce — loopback-only ports, no
 * credential in a tracked file, no provider endpoint copied into a consumer —
 * are invisible at runtime until something is already wrong or already
 * leaked.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
@Tag("unit")
final class SparkHarnessConformanceTest {

    private static final Path HARNESS = LiveSparkCluster.harnessDirectory();

    private static String read(String relative) {
        try {
            return Files.readString(HARNESS.resolve(relative));
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read " + relative + " under " + HARNESS, exception);
        }
    }

    @Test
    void everyHarnessScriptCarriesTheProductPrefix() {
        try (Stream<Path> scripts = Files.walk(HARNESS.resolve("scripts"))) {
            List<String> misnamed = scripts
                    .filter(path -> path.toString().endsWith(".sh"))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !name.startsWith("spark-compose-"))
                    .toList();
            assertTrue(misnamed.isEmpty(),
                    "every Spark harness script must be prefixed spark-compose-, found: " + misnamed);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to walk the Spark harness scripts", exception);
        }
    }

    @Test
    void publishedPortsBindToLoopbackByDefault() {
        String compose = read("compose.yaml");

        assertTrue(compose.contains("${SPARK_BIND_ADDRESS:-127.0.0.1}"),
                "published ports must default to loopback");
        assertFalse(compose.contains("0.0.0.0:"),
                "no published port may bind every interface by default");
    }

    @Test
    void noCredentialLivesInATrackedFile() {
        // The svc-spark key pair is pulled from OpenBao and the catalog secret
        // is generated into the ignored .env; neither may be committed.
        String template = read(".env.template");

        assertFalse(template.contains("SPARK_RGW_ACCESS_KEY="),
                "RGW credentials must come from the secret store, never the template");
        assertFalse(template.contains("SPARK_RGW_SECRET_KEY="),
                "RGW credentials must come from the secret store, never the template");
        assertTrue(template.contains("SPARK_POLARIS_CLIENT_SECRET=\n")
                        || template.contains("SPARK_POLARIS_CLIENT_SECRET=" + System.lineSeparator()),
                "the principal secret field must be empty in the template; startup generates it");
    }

    @Test
    void theRenderedConfigurationIsIgnoredAndItsTemplateHoldsNoProviderEndpoint() {
        // ADR-P1-003: a consumer takes endpoints, network names, and the
        // catalog name from the provider's connection.env. Copying them here
        // would leave two places to change and one of them silently stale.
        String template = read("config/spark-defaults.conf.template");

        assertFalse(template.contains("object-store.stratus.local"),
                "the Ceph endpoint must be rendered from connection.env, not written here");
        assertFalse(template.contains("polaris.stratus.local"),
                "the Polaris endpoint must be rendered from connection.env, not written here");
        assertTrue(template.contains("__CEPH_RGW_ENDPOINT__") && template.contains("__POLARIS_ENDPOINT__"),
                "the template must carry the placeholders the startup script fills");

        assertTrue(read(".gitignore").contains("config/spark-defaults.conf"),
                "the rendered configuration holds the principal secret and must never be committed");
    }

    @Test
    void theImageIsPinnedAndNeverLatest() {
        assertFalse(read(".env.template").contains("SPARK_IMAGE=stratus/spark-runtime:latest"),
                "the runtime image must be a pinned tag");
        assertTrue(read(".env.template").contains("SPARK_IMAGE="),
                "the template must declare the runtime image");
    }

    @Test
    void shutdownNeverRequiresAnEnvironmentFile() {
        // Teardown has to work on a half-configured harness, which is the
        // state an operator is in when they most need it.
        String shutdown = read("scripts/lifecycle/spark-compose-shutdown.sh");

        assertTrue(shutdown.contains("compose_teardown"),
                "shutdown must tear down by project name");
        assertFalse(shutdown.contains("load_environment"),
                "shutdown must not require .env to be loadable");
    }

    @Test
    void theHarnessNeverStartsAProviderTransitively() {
        // ADR-P1-003: a consumer fails fast with a remediation rather than
        // starting Ceph or Polaris on the operator's behalf. Naming a
        // provider's startup script inside a remediation message is the
        // correct behaviour, so the quoted strings are removed before looking
        // for an invocation — otherwise this check would forbid the very
        // message it wants.
        String common = read("scripts/lib/spark-compose-common.sh");

        assertTrue(common.contains("require_provider_harnesses"),
                "the harness must check its providers are up");

        String outsideStrings = common.replaceAll("\"[^\"]*\"", "\"\"");
        assertFalse(outsideStrings.contains("-compose-startup.sh"),
                "the harness must never invoke a provider's startup script; found one outside a message");
    }
}
