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
 * Offline repository contract for the Spark developer harness and its verification interface.
 *
 * <h2>Rationale</h2>
 *
 * <p>These static assertions inspect the real harness rather than a simulation. They protect rules
 * such as loopback-only ports, no tracked credentials, provider-owned endpoint configuration,
 * exact dependency replacement, safe focused-test artifacts, shared Spark context ownership,
 * bounded telemetry, and non-transitive provider startup. Many failures are otherwise visible only
 * after a secret leaks or a live environment behaves incorrectly.
 *
 * <h2>Proof boundary and maintenance</h2>
 *
 * <p>This class proves repository structure and source-level controls; the
 * {@code spark-integration} suites prove behavior. Reusable paths use {@code *_PATH}, approved
 * values use {@code EXPECTED_*} or the shared {@link SparkRuntimeBaseline}, mandatory collections
 * use {@code REQUIRED_*}, and prohibited values use {@code SUPERSEDED_*}. Update those contracts
 * with the implementation and documentation in one change, preserving a failing assertion before
 * acceptance.
 *
 * <p>Developer evidence does not authorize UAT or production. Rebuild and scan immutable images,
 * prove the live developer suite, promote the same digest to UAT for semantic, security,
 * performance, and recovery checks, and promote the unchanged UAT-approved digest to production.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.1.0
 */
@Tag("unit")
final class SparkHarnessConformanceTest {

    private static final Path HARNESS_ROOT = LiveSparkCluster.harnessDirectory();
    private static final Path COMPOSE_PATH = Path.of("compose.yaml");
    private static final Path ENVIRONMENT_TEMPLATE_PATH = Path.of(".env.template");
    private static final Path SPARK_DEFAULTS_TEMPLATE_PATH = Path.of(
            "config", "spark-defaults.conf.template");
    private static final Path SPARK_CLIENT_SOURCE_PATH = Path.of(
            "..", "tests", "src", "test", "java", "dev", "stratus", "platform", "spark",
            "StratusSparkClient.java");
    private static final Path AWS_RUNTIME_POM_PATH = Path.of("..", "aws-runtime", "pom.xml");
    private static final Path COMMON_SCRIPT_PATH = Path.of(
            "scripts", "lib", "spark-compose-common.sh");
    private static final Path LIVE_TEST_RUNNER_PATH = Path.of(
            "scripts", "tests", "spark-compose-run-live-tests.sh");
    private static final Path TEST_SCRIPTS_PATH = Path.of("scripts", "tests");
    private static final List<String> REQUIRED_TEST_ENTRY_POINTS = List.of(
            "spark-compose-prepare-focused-tests.sh",
            "spark-compose-run-focused-tests.sh",
            LIVE_TEST_RUNNER_PATH.getFileName().toString());

    private static String read(Path relative) {
        try {
            return Files.readString(HARNESS_ROOT.resolve(relative));
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to read " + relative + " under " + HARNESS_ROOT, exception);
        }
    }

    private static String read(String relative) {
        return read(Path.of(relative));
    }

    @Test
    void everyHarnessScriptCarriesTheProductPrefix() {
        try (Stream<Path> scripts = Files.walk(HARNESS_ROOT.resolve("scripts"))) {
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
        String compose = read(COMPOSE_PATH);

        assertTrue(compose.contains("${SPARK_BIND_ADDRESS:-127.0.0.1}"),
                "published ports must default to loopback");
        assertFalse(compose.contains("0.0.0.0:"),
                "no published port may bind every interface by default");
    }

    @Test
    void noCredentialLivesInATrackedFile() {
        // The svc-spark key pair is pulled from OpenBao and the catalog secret
        // is generated into the ignored .env; neither may be committed.
        String template = read(ENVIRONMENT_TEMPLATE_PATH);

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
        String template = read(SPARK_DEFAULTS_TEMPLATE_PATH);

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
        String template = read(SPARK_DEFAULTS_TEMPLATE_PATH);

        assertTrue(template.contains(".rest.auth.type") && template.contains("oauth2"),
                "the REST catalog must declare OAuth2 instead of relying on credential inference");
        assertTrue(template.contains(".oauth2-server-uri")
                        && template.contains("__POLARIS_ENDPOINT__/api/catalog/v1/oauth/tokens"),
                "the OAuth token endpoint must be rendered explicitly");
    }

    @Test
    void everyContainerGetsPrivateScratchAndApplicationsHaveResourceCeilings() {
        String compose = read(COMPOSE_PATH);
        String defaults = read(SPARK_DEFAULTS_TEMPLATE_PATH);

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
        String awsRuntimePom = read(AWS_RUNTIME_POM_PATH);
        String dockerfile = read("../image/Dockerfile");

        assertTrue(imagePom.contains("<hadoop.aws.version>"
                        + SparkRuntimeBaseline.HADOOP_VERSION + "</hadoop.aws.version>"),
                "Hadoop " + SparkRuntimeBaseline.HADOOP_VERSION
                        + " contains the newer-JDK Subject compatibility fix");
        assertTrue(awsRuntimePom.contains("<pattern>software.amazon</pattern>"),
                "all Iceberg-bundled Amazon libraries must be relocated away from Hadoop S3A");
        assertTrue(dockerfile.contains(
                                SparkRuntimeBaseline.baseHadoopJar("hadoop-client-api"))
                        && dockerfile.contains(
                                SparkRuntimeBaseline.baseHadoopJar("hadoop-client-runtime")),
                "the base Hadoop clients must be replaced, never mixed with the selected version");
    }

    @Test
    void dependencyOnlyAwsRuntimeDoesNotInvokeTaggedSurefire() {
        String awsRuntimePom = read(AWS_RUNTIME_POM_PATH);

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

        String client = read(SPARK_CLIENT_SOURCE_PATH);
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
        Path tests = HARNESS_ROOT.resolve(TEST_SCRIPTS_PATH);
        assertTrue(Files.isDirectory(tests),
                "Spark test entry points must live under scripts/tests");

        try (Stream<Path> scripts = Files.list(tests)) {
            assertEquals(REQUIRED_TEST_ENTRY_POINTS,
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
        String defaults = read(SPARK_DEFAULTS_TEMPLATE_PATH);
        String client = read(SPARK_CLIENT_SOURCE_PATH);

        assertTrue(defaults.contains("spark.hadoop.fs.s3a.fast.upload.buffer    bytebuffer")
                        && client.contains("spark.hadoop.fs.s3a.fast.upload.buffer\", \"bytebuffer"),
                "client-mode S3A uploads must not depend on winutils.exe for local disk buffering");
    }

    @Test
    void sqlTimestampsUseUtcInClusterAndClientMode() {
        String defaults = read(SPARK_DEFAULTS_TEMPLATE_PATH);
        String client = read(SPARK_CLIENT_SOURCE_PATH);

        assertTrue(defaults.contains("spark.sql.session.timeZone")
                        && defaults.contains("UTC"),
                "packaged jobs must interpret unzoned SQL timestamps in UTC");
        assertTrue(client.contains("spark.sql.session.timeZone\", \"UTC"),
                "client-mode jobs must interpret unzoned SQL timestamps in UTC");
    }

    @Test
    void onlyThePackagedSubmissionSmokeTestStartsAFreshSparkDriver() {
        Path tests = HARNESS_ROOT.resolve(
                "../tests/src/test/java/dev/stratus/platform/spark").normalize();
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
        assertFalse(read(ENVIRONMENT_TEMPLATE_PATH)
                        .contains("SPARK_IMAGE=stratus/spark-runtime:latest"),
                "the runtime image must be a pinned tag");
        assertTrue(read(ENVIRONMENT_TEMPLATE_PATH).contains("SPARK_IMAGE="),
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
        String common = read(COMMON_SCRIPT_PATH);
        String mavenCommon = read("scripts/lib/spark-compose-maven-common.sh");
        String liveRunner = read(LIVE_TEST_RUNNER_PATH);

        assertTrue(common.contains("sed 's/\\r$//'"),
                "the Bash harness must strip CRLF before sourcing tracked connection files");
        assertTrue(mavenCommon.contains("cmd.exe /d /c mvnw.cmd"),
                "Git Bash must invoke the Windows Maven wrapper without rewriting it");
        assertTrue(liveRunner.contains("-Dstratus.spark.integration=true"),
                "the live opt-in must cross the Git Bash-to-cmd.exe process boundary");
    }

    @Test
    void liveRunsUseOneDeclarativeLoggingConfigurationAndCorrelationId() {
        String compose = read(COMPOSE_PATH);
        String log4j = read("config/log4j2.properties");
        String liveRunner = read(LIVE_TEST_RUNNER_PATH);

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
    void implementationPlanTracksCompletedAndRemainingTelemetryWork() {
        String plan = read("../../../docs/implementation/spark_compute.md");

        for (String completedTask : List.of("P1-3.1-V1", "P1-3.1-V2")) {
            assertTrue(plan.contains("| `" + completedTask + "`")
                            && plan.contains(completedTask + "` **Verified 2026-08-16**"),
                    completedTask + " must record its completed implementation and evidence");
        }
        for (String remainingTask : List.of("P1-3.1-V3", "P1-3.1-V4", "P1-3.6-P2")) {
            assertTrue(plan.contains("| `" + remainingTask + "`")
                            && plan.contains(remainingTask + "` **Not started**"),
                    remainingTask + " must remain visible with an explicit status");
        }

        assertTrue(plan.contains("first executor registration")
                        && plan.contains("Iceberg scan planning and commit")
                        && plan.contains("relative regression thresholds"),
                "the remaining phase-level timing and regression work must be explicit");
        assertTrue(plan.contains("Fast live")
                        && plan.contains("Deep semantic")
                        && plan.contains("Cold packaged submission"),
                "the remaining feedback-tier split must be explicit");
        assertTrue(plan.contains("Prometheus")
                        && plan.contains("Grafana")
                        && plan.contains("Loki"),
                "the production telemetry export targets must be explicit");
    }

    @Test
    void theHarnessNeverStartsAProviderTransitively() {
        // ADR-P1-003: a consumer fails fast with a remediation rather than
        // starting Ceph or Polaris on the operator's behalf. Naming a
        // provider's startup script inside a remediation message is the
        // correct behaviour, so the quoted strings are removed before looking
        // for an invocation — otherwise this check would forbid the very
        // message it wants.
        String common = read(COMMON_SCRIPT_PATH);

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
