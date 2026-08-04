// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Stratus catalog conformance suite, proven against a live Iceberg REST
 * catalog (Polaris) over live object storage (Ceph RGW). It verifies the
 * chain the compute engines will rely on: namespaces resolve, a table can be
 * created, written, read back, and dropped through the catalog, the files
 * land inside the governed zone location, and a forged principal is refused.
 *
 * <p>The endpoint, principal, and storage binding come from the environment,
 * so the suite runs unchanged against any conforming REST catalog
 * deployment.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-04
 * @version 1.0.0
 */
@Tag("catalog-integration")
final class IcebergRestCatalogConformanceTest {

    private static final Namespace PLATFORM = Namespace.of("platform");

    private RESTCatalog catalog;
    private TableIdentifier probeTable;

    @BeforeEach
    void requireLiveCatalog() {
        if (Boolean.getBoolean("catalog.integration.required")) {
            assertTrue(Boolean.parseBoolean(System.getenv("STRATUS_CATALOG_INTEGRATION")),
                    "STRATUS_CATALOG_INTEGRATION=true is required by the selected Maven profile");
            for (String name : Set.of(
                    "STRATUS_POLARIS_URI",
                    "STRATUS_POLARIS_CLIENT_ID",
                    "STRATUS_POLARIS_CLIENT_SECRET",
                    "STRATUS_POLARIS_CATALOG",
                    "CEPH_RGW_ENDPOINT",
                    "CEPH_RGW_ACCESS_KEY",
                    "CEPH_RGW_SECRET_KEY")) {
                assertTrue(System.getenv(name) != null && !System.getenv(name).isBlank(),
                        name + " is required by the selected Maven profile");
            }
        }
        assumeTrue(Boolean.parseBoolean(System.getenv("STRATUS_CATALOG_INTEGRATION")),
                "Set STRATUS_CATALOG_INTEGRATION=true to run against a live catalog");

        var config = CatalogVerifierConfig.from(System.getenv());
        catalog = new RESTCatalog();
        catalog.initialize(config.catalogName(), RestCatalogProperties.from(config));
        probeTable = TableIdentifier.of(PLATFORM, "conformance_probe_"
                + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void removeProbeTableAndReleaseTheCatalog() {
        if (catalog == null) {
            return;
        }
        try {
            // Cleanup is unconditional: a failed assertion must not leave a
            // probe table behind for the next run to trip over.
            if (probeTable != null && catalog.tableExists(probeTable)) {
                catalog.dropTable(probeTable, true);
            }
        } finally {
            try {
                catalog.close();
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to release the REST catalog client", exception);
            }
        }
    }

    @Test
    void listsTheFourZoneNamespaces() {
        var namespaces = catalog.listNamespaces();

        for (String zone : new String[] {"bronze", "silver", "gold", "platform"}) {
            assertTrue(namespaces.contains(Namespace.of(zone)),
                    "the catalog must expose the " + zone + " namespace, found: " + namespaces);
        }
    }

    @Test
    void createsWritesReadsAndDropsAProbeTableInTheGovernedZoneLocation() {
        var schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "note", Types.StringType.get()));

        Table table = catalog.createTable(probeTable, schema);
        assertTrue(table.location().startsWith("s3://stratus-platform/platform/"),
                "platform-zone tables must live inside the governed zone location, got: " + table.location());

        var dataPath = table.locationProvider().newDataLocation(
                FileFormat.PARQUET.addExtension("conformance-probe-0"));
        var outputFile = table.io().newOutputFile(dataPath);
        FileAppender<Record> appender;
        try {
            appender = Parquet.write(outputFile)
                    .schema(table.schema())
                    .createWriterFunc(messageType -> GenericParquetWriter.create(table.schema(), messageType))
                    .build();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to open the probe data file for writing", exception);
        }
        try (appender) {
            for (long id = 1; id <= 3; id++) {
                var record = GenericRecord.create(table.schema());
                record.setField("id", id);
                record.setField("note", "probe-" + id);
                appender.add(record);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write the probe data file", exception);
        }
        DataFile dataFile = DataFiles.builder(table.spec())
                .withPath(dataPath)
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(outputFile.toInputFile().getLength())
                .withMetrics(appender.metrics())
                .build();
        table.newAppend().appendFile(dataFile).commit();
        assertNotNull(table.currentSnapshot(), "the append must produce a snapshot");

        var readBack = new ArrayList<Record>();
        try (var records = IcebergGenerics.read(table).build()) {
            records.forEach(readBack::add);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read the probe table back", exception);
        }
        assertEquals(3, readBack.size(), "every appended row must read back through the catalog");
        assertEquals("probe-1", readBack.getFirst().getField("note"));

        assertTrue(catalog.dropTable(probeTable, true), "the probe table must drop with purge");
        assertFalse(catalog.tableExists(probeTable), "the dropped probe table must be gone");
    }

    @Test
    void rejectsAForgedPrincipalCredential() {
        var environment = new HashMap<>(System.getenv());
        environment.put("STRATUS_POLARIS_CLIENT_SECRET", "forged-secret-0000000000000000");
        var forgedConfig = CatalogVerifierConfig.from(environment);

        try (var forged = new RESTCatalog()) {
            Map<String, String> properties = RestCatalogProperties.from(forgedConfig);
            var failure = assertThrows(RuntimeException.class,
                    () -> {
                        forged.initialize(forgedConfig.catalogName(), properties);
                        forged.listNamespaces();
                    },
                    "a forged principal credential must be refused");
            assertFalse(String.valueOf(failure.getMessage()).contains(System.getenv("STRATUS_POLARIS_CLIENT_SECRET")),
                    "the failure must not echo the real credential");
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to release the forged catalog client", exception);
        }
    }
}
