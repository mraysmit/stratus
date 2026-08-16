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
        assertTrue(defaults.contains("spark.default.parallelism")
                        && defaults.contains("spark.sql.shuffle.partitions"),
                "the four-core developer cluster must not inherit Spark's 200-way shuffle default");
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
    void dependencyOnlyAwsRuntimeDoesNotInvokeTaggedSurefire() {
        String awsRuntimePom = read("../aws-runtime/pom.xml");

        assertTrue(awsRuntimePom.contains("<artifactId>maven-surefire-plugin</artifactId>")
                        && awsRuntimePom.contains("<skipTests>true</skipTests>"),
                "the testless shading module must not ask Surefire to resolve inherited tag filters");
    }

    @Test
    void focusedLiveRunsRequireExactPreparedSnapshots() {
        String artifacts = read("scripts/lib/spark-compose-focused-test-artifacts.sh");
        String runner = read("scripts/tests/spark-compose-run-focused-tests.sh");

        assertTrue(artifacts.contains("--cached --others --exclude-standard")
                        && artifacts.contains(":stratus-bom,:stratus-iceberg-aws-runtime,:stratus-spark-jobs")
                        && artifacts.contains("focused_test_assert_hash AWS_RUNTIME")
                        && artifacts.contains("focused_test_assert_hash SPARK_JOBS"),
                "the fast path must fingerprint dirty inputs and validate every prepared snapshot");
        assertTrue(runner.contains("focused_test_validate_artifacts")
                        && runner.contains("test -Pspark-integration-tests -pl :stratus-spark-tests")
                        && runner.contains("-Dtest="),
                "the focused runner must validate freshness and pin the direct test lifecycle");
    }

    @Test
    void liveClassesShareOneRootOwnedSparkContextWithIsolatedSessions() {
        String rootPom = read("../../../pom.xml");
        assertTrue(rootPom.contains("<reuseForks>true</reuseForks>"),
                "one Surefire fork must host the suite-scoped Spark context");

        String suiteContext = read("../tests/src/test/java/dev/stratus/platform/spark/"
                + "SparkSuiteContext.java");
        assertTrue(suiteContext.contains("context.getRoot().getStore")
                        && suiteContext.contains("implements AutoCloseable")
                        && suiteContext.contains("withApplicationCores(2)"),
                "the shared two-core context must be owned and closed by JUnit's root store");

        String client = read("../tests/src/test/java/dev/stratus/platform/spark/StratusSparkClient.java");
        assertTrue(client.contains("ownsSparkContext")
                        && client.contains("if (ownsSparkContext)")
                        && client.contains("session.catalog().clearCache()"),
                "class sessions must clear their state without stopping the shared SparkContext");

        for (String testClass : List.of(
                "SparkCatalogBindingConformanceTest.java",
                "SparkClientConformanceTest.java",
                "SparkIncrementalLoadVerificationTest.java",
                "SparkPipelineVerificationTest.java",
                "SparkPrincipalSeparationTest.java")) {
            String source = read("../tests/src/test/java/dev/stratus/platform/spark/" + testClass);
            assertTrue(source.contains("SparkSuiteContext")
                            && source.contains("@RegisterExtension")
                            && source.contains(".client("),
                    testClass + " must obtain an isolated session from the suite-owned context");
            assertFalse(source.contains("StratusSparkClient.connect("),
                    testClass + " must not start its own SparkContext");
        }

        for (String documentation : List.of(
                "../../../docs/reference/maven_test_commands.md",
                "../../../docs/implementation/spark_client_submission.md")) {
            String guidance = read(documentation);
            assertTrue(guidance.contains("suite-scoped")
                            && guidance.contains("isolated")
                            && guidance.contains("reuseForks=true"),
                    documentation + " must describe the shared-context lifecycle");
            assertFalse(guidance.contains("reuseForks=false")
                            || guidance.contains("one fork per class"),
                    documentation + " must not preserve the superseded fork-per-class guidance");
        }
    }

    @Test
    void testEntryPointsHaveTheirOwnClearlyNamedDirectory() {
        Path tests = HARNESS.resolve("scripts/tests");
        assertTrue(Files.isDirectory(tests),
                "Spark test entry points must live under scripts/tests");

        try (Stream<Path> scripts = Files.list(tests)) {
            assertEquals(List.of(
                            "spark-compose-prepare-focused-tests.sh",
                            "spark-compose-run-focused-tests.sh",
                            "spark-compose-run-live-tests.sh"),
                    scripts.filter(path -> path.toString().endsWith(".sh"))
                            .map(path -> path.getFileName().toString())
                            .sorted()
                            .toList(),
                    "scripts/tests must contain the complete Spark test interface");
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to inspect scripts/tests", exception);
        }
    }

    @Test
    void deepSuitesBatchRelatedObservationsInsteadOfStartingOneActionPerAssertion() {
        String incremental = read("../tests/src/test/java/dev/stratus/platform/spark/"
                + "SparkIncrementalLoadVerificationTest.java");
        String pipeline = read("../tests/src/test/java/dev/stratus/platform/spark/"
                + "SparkPipelineVerificationTest.java");

        assertTrue(occurrences(incremental, "scalar(") <= 45,
                "incremental verification must batch related values into observation rows");
        assertTrue(occurrences(pipeline, "client.scalar(") <= 8,
                "pipeline verification must reuse or batch values already observed");
    }

    @Test
    void deepSuitesIsolateQualityHistoryAndPurgeTheirResultTables() {
        String fixture = read("../tests/src/test/java/dev/stratus/platform/spark/"
                + "QualityResultFixture.java");
        assertTrue(fixture.contains("USING iceberg AS SELECT * FROM")
                        && fixture.contains("QualityCheckJob.RESULTS_TABLE")
                        && fixture.contains("WHERE 1 = 0"),
                "isolated stores must derive the deployed canonical schema without copying history");

        for (String testClass : List.of(
                "SparkIncrementalLoadVerificationTest.java",
                "SparkPipelineVerificationTest.java")) {
            String source = read("../tests/src/test/java/dev/stratus/platform/spark/" + testClass);
            assertTrue(source.contains("quality_check_results_")
                            && source.contains("QualityResultFixture.create(client, RESULTS)")
                            && source.contains("new PlatformJobs(client, RESULTS)")
                            && source.contains("new String[] {RESULTS,"),
                    testClass + " must create, use, and purge a unique quality-result table");
        }

        String quality = read("../../../jobs/spark/src/main/java/dev/stratus/jobs/spark/"
                + "QualityCheckJob.java");
        String promotion = read("../../../jobs/spark/src/main/java/dev/stratus/jobs/spark/"
                + "PromotionGate.java");
        assertTrue(quality.contains("writeTo(resultsTable)")
                        && promotion.contains("spark.table(resultsTable)")
                        && promotion.contains("writeTo(resultsTable)"),
                "quality writes, gate reads, and override writes must share the selected table");
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
    void sqlTimestampsUseUtcInClusterAndClientMode() {
        String defaults = read("config/spark-defaults.conf.template");
        String client = read("../tests/src/test/java/dev/stratus/platform/spark/StratusSparkClient.java");

        assertTrue(defaults.contains("spark.sql.session.timeZone")
                        && defaults.contains("UTC"),
                "packaged jobs must interpret unzoned SQL timestamps in UTC");
        assertTrue(client.contains("spark.sql.session.timeZone\", \"UTC"),
                "client-mode jobs must interpret unzoned SQL timestamps in UTC");
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
        String mavenCommon = read("scripts/lib/spark-compose-maven-common.sh");
        String liveRunner = read("scripts/tests/spark-compose-run-live-tests.sh");

        assertTrue(common.contains("sed 's/\\r$//'"),
                "the Bash harness must strip CRLF before sourcing tracked connection files");
        assertTrue(mavenCommon.contains("cmd.exe /d /c mvnw.cmd"),
                "Git Bash must invoke the Windows Maven wrapper without rewriting it");
        assertTrue(liveRunner.contains("-Dstratus.spark.integration=true"),
                "the live opt-in must cross the Git Bash-to-cmd.exe process boundary");
    }

    @Test
    void liveRunsUseOneDeclarativeLoggingConfigurationAndCorrelationId() {
        String compose = read("compose.yaml");
        String log4j = read("config/log4j2.properties");
        String liveRunner = read("scripts/tests/spark-compose-run-live-tests.sh");

        assertTrue(compose.contains("./config/log4j2.properties:/opt/spark/conf/log4j2.properties:ro"),
                "every Spark container must use the tracked Log4j2 configuration");
        assertTrue(compose.contains("STRATUS_LOG_LEVEL: ${STRATUS_LOG_LEVEL:-INFO}"),
                "master and worker daemons must receive the selected Stratus log level");
        assertTrue(log4j.contains("${env:STRATUS_LOG_LEVEL:-INFO}")
                        && log4j.contains("%X{suiteRunId}")
                        && log4j.contains("%X{jobRunId}")
                        && log4j.contains("%X{operationId}"),
                "the runtime logging configuration must support level control and correlation");
        assertTrue(liveRunner.contains("export STRATUS_RUN_ID")
                        && liveRunner.contains("-Dstratus.run.id=\"$run_id\""),
                "one run ID must correlate shell, JUnit, driver, and executor records");

        String clusterCommands = read("../tests/src/test/java/dev/stratus/platform/spark/LiveSparkCluster.java");
        assertTrue(clusterCommands.contains("spark.executorEnv.STRATUS_LOG_LEVEL")
                        && clusterCommands.contains("spark.executorEnv.STRATUS_RUN_ID"),
                "packaged submissions must propagate level and run correlation to executors");
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

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
