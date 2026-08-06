// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Conformance suite for the permanent {@code platform.quality_check_results}
 * table (Increment 2, P1-2.4): the harness bootstrap must have provisioned it
 * in the governed platform zone with exactly the documented schema, the zone
 * and checked-at-day partitioning, and the append-only marker, and it must
 * accept a quality result record and serve it back through the catalog.
 *
 * <p>The record appended here is a genuine quality result of this
 * conformance run, not a probe object: the table is an append-only audit
 * trail, so the record is deliberately retained rather than cleaned up.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-06
 * @version 1.0.0
 */
@Tag("catalog-integration")
final class QualityCheckResultsConformanceTest {

    private static final TableIdentifier QUALITY_TABLE = TableIdentifier.of(
            Namespace.of(QualityCheckResultsTableDefinition.NAMESPACE),
            QualityCheckResultsTableDefinition.TABLE_NAME);

    private RESTCatalog catalog;

    @BeforeEach
    void connectToTheLiveCatalog() {
        catalog = LiveCatalog.connect();
    }

    @AfterEach
    void releaseTheCatalog() {
        if (catalog == null) {
            return;
        }
        try {
            catalog.close();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to release the REST catalog client", exception);
        }
    }

    @Test
    void existsWithTheDocumentedSchemaInTheGovernedPlatformLocation() {
        assertTrue(catalog.tableExists(QUALITY_TABLE),
                "platform.quality_check_results must be provisioned by the catalog bootstrap");

        Table table = catalog.loadTable(QUALITY_TABLE);
        assertTrue(table.location().startsWith("s3://stratus-platform/platform/"),
                "the table must live inside the governed platform zone location, got: " + table.location());

        var deployed = table.schema().columns();
        var documented = QualityCheckResultsTableDefinition.columns();
        assertEquals(documented.size(), deployed.size(),
                "the table must carry exactly the fourteen documented columns, found: "
                        + deployed.stream().map(Types.NestedField::name).toList());
        for (int index = 0; index < documented.size(); index++) {
            var expected = documented.get(index);
            var actual = deployed.get(index);
            assertEquals(expected.name(), actual.name(),
                    "column " + (index + 1) + " must match the documented order");
            assertEquals(expected.typeName(), actual.type().toString(),
                    "column " + actual.name() + " must have the documented type");
            assertEquals(expected.required(), actual.isRequired(),
                    "column " + actual.name() + " must have the documented nullability");
        }
        CatalogVerificationLogging.tableDefinitionValidated(QUALITY_TABLE.toString(),
                "schema", "columns=" + deployed.size());
        CatalogVerificationLogging.tableAttributesInspected(QUALITY_TABLE.toString(),
                table.location(),
                table.currentSnapshot() == null ? null : table.currentSnapshot().snapshotId(),
                deployed.stream()
                        .map(column -> column.name() + " " + column.type()
                                + (column.isRequired() ? " required" : ""))
                        .toList(),
                table.spec().fields().stream()
                        .map(field -> table.schema().findColumnName(field.sourceId())
                                + " " + field.transform())
                        .toList()
                        .toString(),
                table.properties());
    }

    @Test
    void isPartitionedByZoneAndCheckedAtDay() {
        Table table = catalog.loadTable(QUALITY_TABLE);

        var deployedFields = table.spec().fields();
        var documentedFields = QualityCheckResultsTableDefinition.partitionFields();
        assertEquals(documentedFields.size(), deployedFields.size(),
                "the partition spec must carry exactly the documented fields, found: " + table.spec());
        for (int index = 0; index < documentedFields.size(); index++) {
            var expected = documentedFields.get(index);
            var actual = deployedFields.get(index);
            assertEquals(expected.sourceColumn(), table.schema().findColumnName(actual.sourceId()),
                    "partition field " + (index + 1) + " must derive from the documented column");
            assertEquals(expected.transform(), actual.transform().toString(),
                    "partition field " + (index + 1) + " must apply the documented transform");
        }
        CatalogVerificationLogging.tableDefinitionValidated(QUALITY_TABLE.toString(),
                "partitioning", table.spec().toString());
    }

    @Test
    void declaresTheAppendOnlyContractInItsTableProperties() {
        Table table = catalog.loadTable(QUALITY_TABLE);

        assertEquals("true",
                table.properties().get(QualityCheckResultsTableDefinition.APPEND_ONLY_PROPERTY),
                "the append-only audit contract must be discoverable from the table metadata");
        CatalogVerificationLogging.tableDefinitionValidated(QUALITY_TABLE.toString(),
                "append-only", QualityCheckResultsTableDefinition.APPEND_ONLY_PROPERTY + "=true");
    }

    @Test
    void acceptsAQualityResultRecordAndServesItBackThroughTheCatalog() {
        Table table = catalog.loadTable(QUALITY_TABLE);
        var schema = table.schema();
        var runId = "catalog-conformance-" + UUID.randomUUID();
        // Iceberg timestamps carry microsecond precision, so the value is
        // truncated before writing to keep the read-back comparison exact.
        var checkedAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var checkedSnapshot = table.currentSnapshot() == null
                ? null : table.currentSnapshot().snapshotId();

        var record = GenericRecord.create(schema);
        record.setField("run_id", runId);
        record.setField("dataset_namespace", QualityCheckResultsTableDefinition.NAMESPACE);
        record.setField("dataset_name", QualityCheckResultsTableDefinition.TABLE_NAME);
        record.setField("zone", "platform");
        record.setField("check_type", "conformance");
        record.setField("check_name", "quality-table-write-path");
        record.setField("severity", "blocking");
        record.setField("status", "passed");
        record.setField("metric_value", 1.0d);
        record.setField("threshold", 1.0d);
        record.setField("failure_detail", null);
        record.setField("pipeline_run_id", null);
        record.setField("checked_at", checkedAt);
        record.setField("iceberg_snapshot_id", checkedSnapshot);

        var partitionKey = new PartitionKey(table.spec(), schema);
        partitionKey.partition(new InternalRecordWrapper(schema.asStruct()).wrap(record));
        var dataPath = table.locationProvider().newDataLocation(table.spec(), partitionKey,
                FileFormat.PARQUET.addExtension(runId));
        DataWriter<Record> writer;
        try {
            // zstd matches the Iceberg 1.4+ engine default; the bare builder
            // otherwise falls back to legacy gzip, whose Hadoop codec drags
            // in Shell/DataChecksum and the winutils probe on Windows.
            writer = Parquet.writeData(table.io().newOutputFile(dataPath))
                    .schema(schema)
                    .set("write.parquet.compression-codec", "zstd")
                    .createWriterFunc(messageType -> GenericParquetWriter.create(schema, messageType))
                    .withSpec(table.spec())
                    .withPartition(partitionKey)
                    .build();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to open the quality result data file for writing", exception);
        }
        try (writer) {
            writer.write(record);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write the quality result record", exception);
        }
        table.newAppend().appendFile(writer.toDataFile()).commit();
        assertNotNull(table.currentSnapshot(), "the append must produce a snapshot");
        CatalogVerificationLogging.tableEvent("quality-result-appended", QUALITY_TABLE.toString(),
                table.location(), table.currentSnapshot().snapshotId(), 1, null);

        var readBack = new ArrayList<Record>();
        try (var rows = IcebergGenerics.read(table)
                .where(Expressions.equal("run_id", runId))
                .build()) {
            rows.forEach(readBack::add);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read the quality result record back", exception);
        }
        assertEquals(1, readBack.size(), "the appended result must be served back by run_id");
        Record served = readBack.getFirst();
        assertEquals("passed", served.getField("status"));
        assertEquals("quality-table-write-path", served.getField("check_name"));
        assertEquals(1.0d, served.getField("metric_value"));
        assertEquals(checkedAt, served.getField("checked_at"),
                "the check execution time must survive the round trip exactly");
        assertNull(served.getField("failure_detail"), "a passing check carries no failure detail");
        assertEquals(checkedSnapshot, served.getField("iceberg_snapshot_id"));
        CatalogVerificationLogging.tableEvent("quality-result-read-back", QUALITY_TABLE.toString(),
                table.location(), table.currentSnapshot().snapshotId(), readBack.size(), null);
    }
}
