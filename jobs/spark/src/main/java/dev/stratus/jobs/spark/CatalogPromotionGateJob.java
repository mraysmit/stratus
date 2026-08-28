// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.expressions.Expressions;

/**
 * Evaluates a promotion decision directly through Iceberg's REST catalog and file reader.
 *
 * <p>This is the orchestration boundary: it remains an independent Airflow task, but it does not
 * create a Spark driver or acquire executors merely to read a handful of quality-result rows. The
 * governed Spark writer still re-evaluates the same evidence in its own Spark session immediately
 * before writing.
 */
public final class CatalogPromotionGateJob {

    static final String DEFAULT_RESULTS_TABLE = "stratus.platform.quality_check_results";
    static final Set<String> ARGUMENTS = Set.of(
            "runId", "targetTable", "resultsTable", "catalogProperties");

    private CatalogPromotionGateJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String runId = arguments.require("runId");
        String targetTable = arguments.require("targetTable");
        String resultsTable = arguments.optional("resultsTable").orElse(DEFAULT_RESULTS_TABLE);
        Path propertiesFile = Path.of(arguments.require("catalogProperties"));

        try (var context = JobTelemetry.openContext(runId)) {
            CatalogSettings settings = CatalogSettings.load(propertiesFile);
            Catalog catalog = settings.openCatalog();
            try {
                PromotionDecision decision = evaluate(
                        catalog, settings.catalogName(), runId, targetTable, resultsTable);
                System.out.printf("%s resultsTable=%s reader=iceberg-generic%n",
                        decision.describe(), resultsTable);
                if (decision.blocked()) {
                    System.exit(JobExit.PROMOTION_BLOCKED);
                }
            } finally {
                if (catalog instanceof AutoCloseable closeable) {
                    closeable.close();
                }
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not close the promotion catalog", failure);
        }
    }

    static PromotionDecision evaluate(Catalog catalog, String catalogName, String runId,
                                      String targetTable, String resultsTable) {
        TableIdentifier identifier = tableIdentifier(catalogName, resultsTable);
        var evidence = JobTelemetry.measure("CATALOG_PROMOTION", "read_evidence", runId,
                targetTable, () -> {
                    var records = new ArrayList<PromotionEvidenceEvaluator.Evidence>();
                    var table = catalog.loadTable(identifier);
                    try (var rows = IcebergGenerics.read(table)
                            .where(Expressions.equal("run_id", runId))
                            .select("check_name", "severity", "status")
                            .build()) {
                        for (var row : rows) {
                            records.add(new PromotionEvidenceEvaluator.Evidence(
                                    value(row.getField("check_name")),
                                    value(row.getField("severity")),
                                    value(row.getField("status"))));
                        }
                    } catch (IOException exception) {
                        throw new IllegalStateException(
                                "Could not read promotion evidence from " + resultsTable,
                                exception);
                    }
                    return records;
                });
        return PromotionEvidenceEvaluator.evaluate(runId, targetTable, evidence);
    }

    static TableIdentifier tableIdentifier(String catalogName, String table) {
        String prefix = catalogName + ".";
        if (!table.startsWith(prefix) || table.length() == prefix.length()) {
            throw new IllegalArgumentException(
                    "Table must be fully qualified below catalog " + catalogName + ": " + table);
        }
        return TableIdentifier.parse(table.substring(prefix.length()));
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    record CatalogSettings(String catalogName, Map<String, String> properties) {

        static CatalogSettings load(Path file) {
            var source = new Properties();
            try (InputStream input = Files.newInputStream(file)) {
                source.load(input);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read catalog properties: " + file,
                        exception);
            }

            String catalogName = required(source, "spark.sql.defaultCatalog");
            String prefix = "spark.sql.catalog." + catalogName + ".";
            var properties = new LinkedHashMap<String, String>();
            for (String name : source.stringPropertyNames()) {
                if (name.startsWith(prefix)) {
                    properties.put(name.substring(prefix.length()), source.getProperty(name));
                }
            }
            properties.remove("type");
            if (!properties.containsKey("uri") || !properties.containsKey("warehouse")
                    || !properties.containsKey("credential")
                    || !properties.containsKey("io-impl")) {
                throw new IllegalArgumentException(
                        "Catalog properties are missing the REST, warehouse, credential, or I/O binding");
            }
            return new CatalogSettings(catalogName, Map.copyOf(properties));
        }

        Catalog openCatalog() {
            return CatalogUtil.loadCatalog("org.apache.iceberg.rest.RESTCatalog", catalogName,
                    properties, null);
        }

        private static String required(Properties source, String name) {
            String value = source.getProperty(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required catalog property: " + name);
            }
            return value.trim();
        }
    }
}
