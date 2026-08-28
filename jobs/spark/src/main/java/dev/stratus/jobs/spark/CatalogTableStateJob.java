// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.nio.file.Path;
import java.util.Set;

/** Verifies catalog table existence without creating a Spark application. */
public final class CatalogTableStateJob {

    static final Set<String> ARGUMENTS = Set.of(
            "table", "expectedState", "catalogProperties", "runId");

    private CatalogTableStateJob() {
    }

    public static void main(String... argv) {
        JobArguments arguments = JobArguments.parse(argv).rejectUnknown(ARGUMENTS);
        String table = arguments.require("table");
        String expectedState = arguments.require("expectedState");
        String runId = arguments.require("runId");
        if (!Set.of("present", "absent").contains(expectedState)) {
            throw new IllegalArgumentException("expectedState must be present or absent");
        }

        try (var context = JobTelemetry.openContext(runId)) {
            var settings = CatalogPromotionGateJob.CatalogSettings.load(
                    Path.of(arguments.require("catalogProperties")));
            var catalog = settings.openCatalog();
            try {
                var identifier = CatalogPromotionGateJob.tableIdentifier(
                        settings.catalogName(), table);
                boolean present = JobTelemetry.measure("CATALOG_STATE", "table_exists", runId,
                        table, () -> catalog.tableExists(identifier));
                boolean expectedPresent = "present".equals(expectedState);
                if (present != expectedPresent) {
                    throw new IllegalStateException("Catalog table state mismatch table=" + table
                            + " expected=" + expectedState + " actual="
                            + (present ? "present" : "absent"));
                }
                System.out.printf(
                        "CATALOG TABLE STATE VERIFIED table=%s expected=%s actual=%s%n",
                        table, expectedState, expectedState);
            } finally {
                if (catalog instanceof AutoCloseable closeable) {
                    closeable.close();
                }
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not close the catalog state verifier", failure);
        }
    }
}
