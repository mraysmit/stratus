// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void catalogAuthenticationIsExplicitRatherThanInferred() {
        String template = read("config/spark-defaults.conf.template");

        assertTrue(template.contains(".rest.auth.type") && template.contains("oauth2"),
                "the REST catalog must declare OAuth2 instead of relying on credential inference");
        assertTrue(template.contains(".oauth2-server-uri")
                        && template.contains("__POLARIS_ENDPOINT__/api/catalog/v1/oauth/tokens"),
                "the OAuth token endpoint must be rendered explicitly");
    }

    @Test
    void everyContainerGetsPrivateScratchAndApplicationsHaveResourceCeilings() {
        String compose = read("compose.yaml");
        String defaults = read("config/spark-defaults.conf.template");

        assertFalse(compose.contains("spark-scratch:/opt/spark/scratch"),
                "workers must not share a named volume for Spark local scratch");
        assertTrue(compose.contains("tmpfs:") && compose.contains("/opt/spark/scratch"),
                "each Spark container must receive private ephemeral scratch");
        assertTrue(defaults.contains("spark.cores.max")
                        && defaults.contains("spark.executor.cores"),
                "developer applications need an explicit core ceiling and executor size");
    }

    @Test
    void imageBuildReplacesHadoopAsOneVersionAndRelocatesIcebergsAwsSdk() {
        String imagePom = read("../image/pom.xml");
        String awsRuntimePom = read("../aws-runtime/pom.xml");
        String dockerfile = read("../image/Dockerfile");

        assertTrue(imagePom.contains("<hadoop.aws.version>3.4.3</hadoop.aws.version>"),
                "Hadoop 3.4.3 contains the newer-JDK Subject compatibility fix");
        assertTrue(awsRuntimePom.contains("<pattern>software.amazon</pattern>"),
                "all Iceberg-bundled Amazon libraries must be relocated away from Hadoop S3A");
        assertTrue(dockerfile.contains("hadoop-client-api-3.4.2.jar")
                        && dockerfile.contains("hadoop-client-runtime-3.4.2.jar"),
                "the base Hadoop clients must be replaced, never mixed with the selected version");
    }

    @Test
    void s3aUploadsDoNotRequireWindowsNativeUtilities() {
        String defaults = read("config/spark-defaults.conf.template");
        String client = read("../tests/src/test/java/dev/stratus/platform/spark/StratusSparkClient.java");

        assertTrue(defaults.contains("spark.hadoop.fs.s3a.fast.upload.buffer    bytebuffer")
                        && client.contains("spark.hadoop.fs.s3a.fast.upload.buffer\", \"bytebuffer"),
                "client-mode S3A uploads must not depend on winutils.exe for local disk buffering");
    }

    @Test
    void onlyThePackagedSubmissionSmokeTestStartsAFreshSparkDriver() {
        Path tests = HARNESS.resolve("../tests/src/test/java/dev/stratus/platform/spark").normalize();
        try (Stream<Path> sources = Files.list(tests)) {
            List<Path> submitters = sources
                    .filter(path -> path.toString().endsWith("Test.java"))
                    .filter(path -> !path.getFileName().toString()
                            .equals("SparkHarnessConformanceTest.java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("LiveSparkCluster.submitJob(");
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    })
                    .toList();

            assertEquals(List.of(tests.resolve("SparkPipelineVerificationTest.java")), submitters,
                    "scenario tests must reuse their driver; only the packaged submission smoke test may start one");
            String clusterCommands = Files.readString(tests.resolve("LiveSparkCluster.java"));
            assertFalse(clusterCommands.contains("/opt/spark/bin/spark-sql")
                            || clusterCommands.contains("static CommandResult sparkSql"),
                    "the Hive-oriented SQL CLI must not return to the Polaris client suite");
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to inspect Spark integration tests", exception);
        }
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
    void providerSettingsRemainSourceableAfterAWindowsCheckout() {
        String common = read("scripts/lib/spark-compose-common.sh");

        assertTrue(common.contains("sed 's/\\r$//'"),
                "the Bash harness must strip CRLF before sourcing tracked connection files");
        assertTrue(common.contains("cmd.exe /d /c mvnw.cmd"),
                "Git Bash must invoke the Windows Maven wrapper without rewriting it");
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
