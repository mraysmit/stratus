// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;

/** Verifies both silver-to-gold outcomes through Iceberg without creating a Spark application. */
public final class CatalogSilverToGoldVerifierJob {

    private static final int EXPECTED_SOURCE_QUALITY_CHECKS = 2;
    private static final int EXPECTED_GOLD_QUALITY_CHECKS = 2;
    private static final String EXPECTED_BLOCKING_CHECK =
            "silver_customer_is_unique_for_gold";
    private static final Set<String> ARGUMENTS = Set.of(
            "acceptedSourceTable", "acceptedTargetTable", "acceptedQualityRunId",
            "acceptedPipelineRunId", "blockedSourceTable", "blockedTargetTable",
            "blockedQualityRunId", "expectedRows", "expectedCountries", "catalogProperties",
            "runId");

    private CatalogSilverToGoldVerifierJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String acceptedSource = requireSilver(arguments.require("acceptedSourceTable"));
        String acceptedTarget = requireGold(arguments.require("acceptedTargetTable"));
        String acceptedQualityRunId = arguments.require("acceptedQualityRunId");
        String acceptedPipelineRunId = arguments.require("acceptedPipelineRunId");
        String blockedSource = requireSilver(arguments.require("blockedSourceTable"));
        String blockedTarget = requireGold(arguments.require("blockedTargetTable"));
        String blockedQualityRunId = arguments.require("blockedQualityRunId");
        long expectedRows = positive(arguments.require("expectedRows"), "expectedRows");
        Set<String> countries = expectedCountries(arguments.require("expectedCountries"));
        String runId = arguments.optional("runId").orElseGet(() -> UUID.randomUUID().toString());

        try (var context = JobTelemetry.openContext(runId)) {
            var settings = CatalogPromotionGateJob.CatalogSettings.load(
                    Path.of(arguments.require("catalogProperties")));
            Catalog catalog = settings.openCatalog();
            try {
                verifyAccepted(catalog, settings.catalogName(), acceptedSource, acceptedTarget,
                        acceptedQualityRunId, acceptedPipelineRunId, expectedRows, countries, runId);
                verifyBlocked(catalog, settings.catalogName(), blockedSource, blockedTarget,
                        blockedQualityRunId, expectedRows, runId);
            } finally {
                close(catalog);
            }
        }
    }

    private static void verifyAccepted(
            Catalog catalog, String catalogName, String sourceTable, String targetTable,
            String qualityRunId, String pipelineRunId, long expectedRows,
            Set<String> expectedCountries, String verifierRunId) {
        long sourceRows = JobTelemetry.measure("CATALOG_SILVER_TO_GOLD_VERIFY", "count_source",
                verifierRunId, sourceTable, () -> rowCount(catalog, catalogName, sourceTable));
        requireCount(expectedRows, sourceRows, "accepted silver rows");

        PromotionDecision sourceDecision = CatalogPromotionGateJob.evaluate(
                catalog, catalogName, qualityRunId, sourceTable,
                CatalogPromotionGateJob.DEFAULT_RESULTS_TABLE);
        requireAllowed(sourceDecision, EXPECTED_SOURCE_QUALITY_CHECKS, "accepted silver");

        TableIdentifier targetIdentifier = CatalogPromotionGateJob.tableIdentifier(
                catalogName, targetTable);
        if (!catalog.tableExists(targetIdentifier)) {
            throw new IllegalStateException("Accepted promotion did not create " + targetTable);
        }
        var target = catalog.loadTable(targetIdentifier);
        Set<String> columns = target.schema().columns().stream()
                .map(field -> field.name().toLowerCase())
                .collect(Collectors.toUnmodifiableSet());
        if (!columns.equals(Set.of("country", "customer_count"))) {
            throw new IllegalStateException("Unexpected gold summary columns: " + columns);
        }

        Map<String, Long> groups = goldGroups(target);
        if (!groups.keySet().equals(expectedCountries)) {
            throw new IllegalStateException("Unexpected gold countries: " + groups.keySet());
        }
        long total = groups.values().stream().mapToLong(Long::longValue).sum();
        requireCount(expectedRows, total, "gold customer total");
        if (target.currentSnapshot() == null) {
            throw new IllegalStateException("Accepted gold table has no committed snapshot");
        }

        PromotionDecision goldDecision = CatalogPromotionGateJob.evaluate(
                catalog, catalogName, pipelineRunId, targetTable,
                CatalogPromotionGateJob.DEFAULT_RESULTS_TABLE);
        requireAllowed(goldDecision, EXPECTED_GOLD_QUALITY_CHECKS, "accepted gold");
        System.out.printf("CATALOG SILVER TO GOLD VERIFIED sourceTable=%s targetTable=%s "
                        + "sourceRows=%d groups=%d customerTotal=%d sourceQualityChecks=%d "
                        + "goldQualityChecks=%d snapshotId=%d runId=%s%n",
                sourceTable, targetTable, sourceRows, groups.size(), total,
                sourceDecision.checksExamined(), goldDecision.checksExamined(),
                target.currentSnapshot().snapshotId(), verifierRunId);
    }

    private static void verifyBlocked(
            Catalog catalog, String catalogName, String sourceTable, String targetTable,
            String qualityRunId, long expectedRows, String verifierRunId) {
        long sourceRows = JobTelemetry.measure("CATALOG_SILVER_TO_GOLD_VERIFY",
                "count_blocked_source", verifierRunId, sourceTable,
                () -> rowCount(catalog, catalogName, sourceTable));
        requireCount(expectedRows, sourceRows, "blocked silver rows");

        PromotionDecision decision = CatalogPromotionGateJob.evaluate(
                catalog, catalogName, qualityRunId, sourceTable,
                CatalogPromotionGateJob.DEFAULT_RESULTS_TABLE);
        if (!decision.blocked()
                || !Set.copyOf(decision.failingChecks()).equals(Set.of(EXPECTED_BLOCKING_CHECK))) {
            throw new IllegalStateException("Expected the DAG's silver uniqueness failure, got: "
                    + decision.describe());
        }
        TableIdentifier targetIdentifier = CatalogPromotionGateJob.tableIdentifier(
                catalogName, targetTable);
        if (catalog.tableExists(targetIdentifier)) {
            throw new IllegalStateException("Blocked promotion created gold table " + targetTable);
        }
        System.out.printf("CATALOG SILVER TO GOLD BLOCK VERIFIED sourceTable=%s targetTable=%s "
                        + "sourceRows=%d failingChecks=%s checksExamined=%d runId=%s%n",
                sourceTable, targetTable, sourceRows, String.join(",", decision.failingChecks()),
                decision.checksExamined(), verifierRunId);
    }

    private static long rowCount(Catalog catalog, String catalogName, String tableName) {
        var table = catalog.loadTable(
                CatalogPromotionGateJob.tableIdentifier(catalogName, tableName));
        long count = 0;
        try (var rows = IcebergGenerics.read(table).build()) {
            for (var ignored : rows) {
                count++;
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + tableName, failure);
        }
        return count;
    }

    private static Map<String, Long> goldGroups(org.apache.iceberg.Table table) {
        var groups = new LinkedHashMap<String, Long>();
        try (var rows = IcebergGenerics.read(table)
                .select("country", "customer_count").build()) {
            for (var row : rows) {
                String country = String.valueOf(row.getField("country"));
                Object rawCount = row.getField("customer_count");
                if (!(rawCount instanceof Number count)) {
                    throw new IllegalStateException(
                            "Gold customer_count is not numeric for " + country + ": " + rawCount);
                }
                Long previous = groups.put(country, count.longValue());
                if (previous != null) {
                    throw new IllegalStateException("Gold contains duplicate country group "
                            + country);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read gold summary " + table.name(), failure);
        }
        return Map.copyOf(groups);
    }

    private static void requireAllowed(
            PromotionDecision decision, int expectedChecks, String subject) {
        if (decision.blocked() || decision.checksExamined() != expectedChecks) {
            throw new IllegalStateException("Unexpected " + subject + " evidence: "
                    + decision.describe());
        }
    }

    private static void requireCount(long expected, long actual, String subject) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Expected " + expected + " " + subject + " but found " + actual);
        }
    }

    static Set<String> expectedCountries(String value) {
        var countries = Set.copyOf(java.util.Arrays.stream(value.split(",", -1))
                .map(String::trim)
                .filter(country -> !country.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        if (countries.isEmpty()) {
            throw new IllegalArgumentException("expectedCountries must not be empty");
        }
        return countries;
    }

    static long positive(String value, String argument) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) {
                throw new IllegalArgumentException("--" + argument + " must be at least 1");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("--" + argument + " must be an integer: " + value,
                    failure);
        }
    }

    static String requireSilver(String table) {
        return AirflowSilverToGoldVerifierJob.requireIsolatedSilver(table);
    }

    static String requireGold(String table) {
        return AirflowSilverToGoldVerifierJob.requireIsolatedGold(table);
    }

    private static void close(Catalog catalog) {
        if (catalog instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception failure) {
                throw new IllegalStateException("Could not close the catalog verifier", failure);
            }
        }
    }
}
