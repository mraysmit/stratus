// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionKey;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortDirection;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.InternalRecordWrapper;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.DataWriter;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.SupportsPrefixOperations;
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
 * created, written, read back, evolved in place, and dropped through the
 * catalog, the files land inside the governed zone location, and a forged
 * principal is refused.
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
    private final List<Table> createdProbes = new ArrayList<>();

    @BeforeEach
    void requireLiveCatalog() {
        catalog = LiveCatalog.connect();
        probeTable = TableIdentifier.of(PLATFORM, "conformance_probe_"
                + UUID.randomUUID().toString().replace("-", ""));
    }

    @AfterEach
    void removeProbeTablesAndTheirObjectsThenReleaseTheCatalog() {
        if (catalog == null) {
            return;
        }
        try {
            // Cleanup is unconditional: a failed assertion must not leave a
            // probe table behind for the next run to trip over.
            for (Table probe : createdProbes) {
                TableIdentifier identifier = TableIdentifier.of(
                        Namespace.of(probe.name().split("\\.")[1]),
                        probe.name().split("\\.")[2]);
                if (catalog.tableExists(identifier)) {
                    catalog.dropTable(identifier, true);
                }
                // A purge drop removes the catalog entry but leaves the
                // objects in the bucket, so the probe's own S3 client
                // removes them: tests must not leave residue in a governed
                // zone (code_style_rules 7.1).
                if (probe.io() instanceof SupportsPrefixOperations prefixIo) {
                    prefixIo.deletePrefix(probe.location());
                }
            }
        } finally {
            createdProbes.clear();
            try {
                catalog.close();
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to release the REST catalog client", exception);
            }
        }
    }

    /** Creates a probe table and registers it for unconditional cleanup. */
    private Table createProbe(TableIdentifier identifier, Schema schema) {
        Table table = catalog.createTable(identifier, schema);
        createdProbes.add(table);
        return table;
    }

    /** Creates a probe table with a full attribute set, registered for cleanup. */
    private Table createProbe(TableIdentifier identifier, Schema schema, PartitionSpec spec,
                              Map<String, String> properties) {
        Table table = catalog.createTable(identifier, schema, spec, properties);
        createdProbes.add(table);
        return table;
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
    void zoneNamespacesCarryTheGovernedLocationAndZoneProperties() {
        for (String zone : new String[] {"bronze", "silver", "gold", "platform"}) {
            Map<String, String> metadata = catalog.loadNamespaceMetadata(Namespace.of(zone));

            assertEquals("s3://stratus-" + zone + "/" + zone + "/", metadata.get("location"),
                    "the " + zone + " namespace must sit inside its governed zone bucket");
            assertEquals(zone, metadata.get("zone"),
                    "the " + zone + " namespace must carry its zone property");
            CatalogVerificationLogging.namespaceValidated(zone, metadata.get("location"));
        }
    }

    @Test
    void createsWritesReadsAndDropsAProbeTableInTheGovernedZoneLocation() {
        var schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "note", Types.StringType.get()));

        Table table = createProbe(probeTable, schema);
        assertTrue(table.location().startsWith("s3://stratus-platform/platform/"),
                "platform-zone tables must live inside the governed zone location, got: " + table.location());
        CatalogVerificationLogging.tableEvent("create-confirmed", probeTable.toString(),
                table.location(), null, 0, null);

        appendRows(table, "conformance-probe-0", probeRows(table, 1, 3));
        assertNotNull(table.currentSnapshot(), "the append must produce a snapshot");
        // The Parquet bytes stream directly to object storage, so there is no
        // local payload to fingerprint; the snapshot id is the durable evidence.
        CatalogVerificationLogging.tableEvent("append-confirmed", probeTable.toString(),
                table.location(), table.currentSnapshot().snapshotId(), 3, null);

        var readBack = readAll(table);
        assertEquals(3, readBack.size(), "every appended row must read back through the catalog");
        assertEquals("probe-1", readBack.getFirst().getField("note"));
        CatalogVerificationLogging.tableEvent("read-back-confirmed", probeTable.toString(),
                table.location(), table.currentSnapshot().snapshotId(), readBack.size(), null);

        assertTrue(catalog.dropTable(probeTable, true), "the probe table must drop with purge");
        assertFalse(catalog.tableExists(probeTable), "the dropped probe table must be gone");
        CatalogVerificationLogging.tableEvent("purge-drop-confirmed", probeTable.toString(),
                table.location(), null, 0, null);
    }

    @Test
    void evolvesTheProbeTableSchemaWithoutDisturbingExistingRows() {
        var schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "note", Types.StringType.get()));
        Table table = createProbe(probeTable, schema);
        appendRows(table, "evolution-probe-0", probeRows(table, 1, 3));

        table.updateSchema().addColumn("source_system", Types.StringType.get()).commit();

        Table evolved = catalog.loadTable(probeTable);
        assertEquals(3, evolved.schema().columns().size(),
                "the evolved schema must carry the added column after a reload");
        Types.NestedField added = evolved.schema().findField("source_system");
        assertNotNull(added, "the added column must resolve by name after a reload");
        assertTrue(added.isOptional(), "added columns must be optional so existing rows stay valid");
        CatalogVerificationLogging.tableEvent("schema-evolution-confirmed", probeTable.toString(),
                evolved.location(), evolved.currentSnapshot().snapshotId(), 3, null);

        var beforeEvolution = readAll(evolved);
        assertEquals(3, beforeEvolution.size(), "rows written before the evolution must survive it");
        for (Record record : beforeEvolution) {
            assertNull(record.getField("source_system"),
                    "pre-evolution rows must read back with a null in the added column");
        }

        var evolvedRow = GenericRecord.create(evolved.schema());
        evolvedRow.setField("id", 4L);
        evolvedRow.setField("note", "probe-4");
        evolvedRow.setField("source_system", "catalog-conformance");
        appendRows(evolved, "evolution-probe-1", List.of(evolvedRow));

        var afterEvolution = readAll(evolved);
        assertEquals(4, afterEvolution.size(), "the evolved schema must accept new rows");
        assertEquals(1, afterEvolution.stream()
                        .filter(record -> "catalog-conformance".equals(record.getField("source_system")))
                        .count(),
                "the new column must round-trip through the evolved schema");
        CatalogVerificationLogging.tableEvent("evolved-write-confirmed", probeTable.toString(),
                evolved.location(), evolved.currentSnapshot().snapshotId(), afterEvolution.size(), null);
    }

    @Test
    void rejectsARowThatLeavesARequiredColumnNull() {
        var schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "note", Types.StringType.get()));
        Table table = createProbe(probeTable, schema);
        appendRows(table, "enforcement-probe-0", probeRows(table, 1, 2));
        long committedSnapshot = catalog.loadTable(probeTable).currentSnapshot().snapshotId();

        // Positive control. The record is built outside the lambda so the
        // assertion below cannot be satisfied by a failure that happens before
        // the write is attempted: with the construction inside it, the throw
        // proved only that something failed, and the two assertions after it
        // then held vacuously because nothing had been written. Building it
        // here also records which layer enforces — the record API accepts the
        // null, so the refusal comes from the write path.
        var invalid = GenericRecord.create(table.schema());
        invalid.setField("id", null);
        invalid.setField("note", "missing-required-id");
        assertNull(invalid.getField("id"),
                "the record API is expected to accept the null and leave enforcement to the write path; "
                        + "if a release starts enforcing on assignment, this construction belongs inside the lambda");

        var failure = assertThrows(RuntimeException.class,
                () -> appendRows(table, "enforcement-probe-rejected", List.of(invalid)),
                "appending a row that leaves a required column null must be rejected");

        Table reloaded = catalog.loadTable(probeTable);
        assertEquals(committedSnapshot, reloaded.currentSnapshot().snapshotId(),
                "a rejected write must not advance the table snapshot");
        assertEquals(2, readAll(reloaded).size(),
                "a rejected write must not add rows to the table");
        CatalogVerificationLogging.negativeConfirmed("required-column-null",
                "refused with " + failure.getClass().getSimpleName());
    }

    @Test
    void createsWritesReadsAndDropsAProbeTableInEveryDataZone() {
        var schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "note", Types.StringType.get()));

        // The platform zone is covered by the lifecycle and quality-table
        // tests; this proves the catalog's storage binding in the zones the
        // engines will actually write to.
        for (String zone : new String[] {"bronze", "silver", "gold"}) {
            TableIdentifier zoneProbe = TableIdentifier.of(Namespace.of(zone),
                    "conformance_probe_" + UUID.randomUUID().toString().replace("-", ""));
            try {
                Table table = createProbe(zoneProbe, schema);
                assertTrue(table.location().startsWith("s3://stratus-" + zone + "/" + zone + "/"),
                        zone + "-zone tables must live inside the governed zone location, got: "
                                + table.location());
                appendRows(table, "zone-probe-0", probeRows(table, 1, 1));
                assertEquals(1, readAll(table).size(),
                        "the appended row must read back through the " + zone + " zone");
                CatalogVerificationLogging.tableEvent("zone-write-confirmed", zoneProbe.toString(),
                        table.location(), table.currentSnapshot().snapshotId(), 1, null);
                assertTrue(catalog.dropTable(zoneProbe, true),
                        "the " + zone + " probe table must drop with purge");
            } finally {
                if (catalog.tableExists(zoneProbe)) {
                    catalog.dropTable(zoneProbe, true);
                }
            }
        }
    }

    /**
     * A table shape with a deliberate attribute set: mixed nullability, a
     * timestamp and a long, two partition transforms, and explicit table
     * properties. Everything a compute engine would declare when creating a
     * governed dataset.
     */
    private static final Schema ATTRIBUTE_SCHEMA = new Schema(
            Types.NestedField.required(1, "event_id", Types.StringType.get()),
            Types.NestedField.required(2, "zone", Types.StringType.get()),
            Types.NestedField.required(3, "event_time", Types.TimestampType.withoutZone()),
            Types.NestedField.optional(4, "measurement", Types.DoubleType.get()),
            Types.NestedField.optional(5, "source_snapshot_id", Types.LongType.get()));

    private static final Map<String, String> ATTRIBUTE_PROPERTIES = Map.of(
            "write.format.default", "parquet",
            "write.parquet.compression-codec", "zstd",
            "stratus.dataset-classification", "conformance");

    private static PartitionSpec attributeSpec() {
        return PartitionSpec.builderFor(ATTRIBUTE_SCHEMA)
                .identity("zone")
                .day("event_time")
                .build();
    }

    @Test
    void createsATableWithACompleteAttributeSetAndReloadsItFromTheCatalog() {
        PartitionSpec spec = attributeSpec();

        Table created = createProbe(probeTable, ATTRIBUTE_SCHEMA, spec, ATTRIBUTE_PROPERTIES);
        assertTrue(created.location().startsWith("s3://stratus-platform/platform/"),
                "the table must be created inside the governed zone location, got: " + created.location());
        CatalogVerificationLogging.tableEvent("attributed-create-confirmed", probeTable.toString(),
                created.location(), null, 0, null);

        var partitionedRows = List.of(
                attributeRow(created, "event-1", "bronze", "2026-08-01T09:15:00", 1.5d, 11L),
                attributeRow(created, "event-2", "bronze", "2026-08-01T18:45:00", 2.5d, null),
                attributeRow(created, "event-3", "silver", "2026-08-02T04:05:00", 3.5d, 33L));
        appendPartitionedRows(created, partitionedRows);
        assertNotNull(created.currentSnapshot(), "the append must produce a snapshot");

        var readBack = readAll(created);
        assertEquals(3, readBack.size(), "every appended row must read back through the catalog");
        assertEquals(1, readBack.stream()
                        .filter(row -> "event-2".equals(row.getField("event_id")))
                        .filter(row -> row.getField("source_snapshot_id") == null)
                        .count(),
                "an optional column must round-trip as null");
        CatalogVerificationLogging.tableEvent("attributed-write-confirmed", probeTable.toString(),
                created.location(), created.currentSnapshot().snapshotId(), readBack.size(), null);

        // The reload is the point of the test: everything asserted below must
        // come back from Polaris, not from the in-memory handle returned by
        // createTable.
        Table reloaded = catalog.loadTable(probeTable);
        assertAttributesMatch(ATTRIBUTE_SCHEMA, spec, ATTRIBUTE_PROPERTIES, reloaded);
        assertEquals(created.currentSnapshot().snapshotId(), reloaded.currentSnapshot().snapshotId(),
                "the reloaded table must resolve to the committed snapshot");
        assertEquals(3, readAll(reloaded).size(),
                "the reloaded table must serve the rows written before the reload");
        logAttributes(probeTable.toString(), reloaded);
    }

    @Test
    void writesEachPartitionToItsOwnGovernedStoragePath() {
        Table table = createProbe(probeTable, ATTRIBUTE_SCHEMA, attributeSpec(), ATTRIBUTE_PROPERTIES);

        appendPartitionedRows(table, List.of(
                attributeRow(table, "event-1", "bronze", "2026-08-01T09:15:00", 1.5d, null),
                attributeRow(table, "event-2", "silver", "2026-08-02T04:05:00", 2.5d, null)));

        var dataPaths = new ArrayList<String>();
        for (var task : table.newScan().planFiles()) {
            dataPaths.add(task.file().location());
        }
        assertEquals(2, dataPaths.size(), "each partition must land in its own data file");
        assertEquals(1, dataPaths.stream()
                        .filter(path -> path.contains("/zone=bronze/event_time_day=2026-08-01/"))
                        .count(),
                "the partition spec must drive the storage layout, got: " + dataPaths);
        assertEquals(1, dataPaths.stream()
                        .filter(path -> path.contains("/zone=silver/event_time_day=2026-08-02/"))
                        .count(),
                "the partition spec must drive the storage layout, got: " + dataPaths);
        for (String path : dataPaths) {
            assertTrue(path.startsWith(table.location()),
                    "every data file must sit under the governed table location, got: " + path);
        }
        CatalogVerificationLogging.tableEvent("partition-layout-confirmed", probeTable.toString(),
                table.location(), table.currentSnapshot().snapshotId(), dataPaths.size(), null);
    }

    @Test
    void sortOrderSurvivesTheCatalogRoundTrip() {
        SortOrder sortOrder = SortOrder.builderFor(ATTRIBUTE_SCHEMA)
                .desc("event_time")
                .asc("event_id")
                .build();

        // Only the builder API carries a sort order; createTable has no
        // overload for it.
        Table created = catalog.buildTable(probeTable, ATTRIBUTE_SCHEMA)
                .withPartitionSpec(attributeSpec())
                .withProperties(ATTRIBUTE_PROPERTIES)
                .withSortOrder(sortOrder)
                .create();
        createdProbes.add(created);

        Table reloaded = catalog.loadTable(probeTable);
        SortOrder reloadedOrder = reloaded.sortOrder();
        assertEquals(2, reloadedOrder.fields().size(),
                "both sort fields must survive the catalog round trip, got: " + reloadedOrder);
        assertEquals("event_time",
                reloaded.schema().findColumnName(reloadedOrder.fields().get(0).sourceId()));
        assertEquals(SortDirection.DESC, reloadedOrder.fields().get(0).direction());
        assertEquals("event_id",
                reloaded.schema().findColumnName(reloadedOrder.fields().get(1).sourceId()));
        assertEquals(SortDirection.ASC, reloadedOrder.fields().get(1).direction());
        CatalogVerificationLogging.tableDefinitionValidated(probeTable.toString(),
                "sort-order", reloadedOrder.toString());
    }

    /** Asserts a reloaded table carries exactly the requested attribute set. */
    private static void assertAttributesMatch(Schema requestedSchema, PartitionSpec requestedSpec,
                                              Map<String, String> requestedProperties, Table reloaded) {
        var requestedColumns = requestedSchema.columns();
        var reloadedColumns = reloaded.schema().columns();
        assertEquals(requestedColumns.size(), reloadedColumns.size(),
                "the reloaded schema must carry every requested column");
        for (int index = 0; index < requestedColumns.size(); index++) {
            Types.NestedField requested = requestedColumns.get(index);
            Types.NestedField actual = reloadedColumns.get(index);
            assertEquals(requested.name(), actual.name(), "column order must survive the round trip");
            assertEquals(requested.type(), actual.type(),
                    "column " + requested.name() + " must keep its type");
            assertEquals(requested.isRequired(), actual.isRequired(),
                    "column " + requested.name() + " must keep its nullability");
        }

        var reloadedSpec = reloaded.spec();
        assertEquals(requestedSpec.fields().size(), reloadedSpec.fields().size(),
                "the reloaded partition spec must carry every requested field, got: " + reloadedSpec);
        for (int index = 0; index < requestedSpec.fields().size(); index++) {
            assertEquals(requestedSpec.fields().get(index).name(),
                    reloadedSpec.fields().get(index).name(),
                    "partition field " + (index + 1) + " must keep its name");
            assertEquals(requestedSpec.fields().get(index).transform().toString(),
                    reloadedSpec.fields().get(index).transform().toString(),
                    "partition field " + (index + 1) + " must keep its transform");
        }

        requestedProperties.forEach((key, value) ->
                assertEquals(value, reloaded.properties().get(key),
                        "table property " + key + " must survive the round trip"));
    }

    private static void logAttributes(String table, Table reloaded) {
        CatalogVerificationLogging.tableAttributesInspected(table, reloaded.location(),
                reloaded.currentSnapshot() == null ? null : reloaded.currentSnapshot().snapshotId(),
                reloaded.schema().columns().stream()
                        .map(column -> column.name() + " " + column.type()
                                + (column.isRequired() ? " required" : ""))
                        .toList(),
                reloaded.spec().fields().stream()
                        .map(field -> reloaded.schema().findColumnName(field.sourceId())
                                + " " + field.transform())
                        .toList()
                        .toString(),
                reloaded.properties());
    }

    private static Record attributeRow(Table table, String eventId, String zone,
                                       String eventTime, Double measurement, Long sourceSnapshotId) {
        var record = GenericRecord.create(table.schema());
        record.setField("event_id", eventId);
        record.setField("zone", zone);
        record.setField("event_time", LocalDateTime.parse(eventTime));
        record.setField("measurement", measurement);
        record.setField("source_snapshot_id", sourceSnapshotId);
        return record;
    }

    /**
     * Writes one Parquet file per partition and commits them as a single
     * append, so the partition spec drives the storage layout exactly as it
     * would for an engine writing the same rows.
     */
    private static void appendPartitionedRows(Table table, List<Record> rows) {
        var wrapper = new InternalRecordWrapper(table.schema().asStruct());
        var byPartition = new LinkedHashMap<String, List<Record>>();
        for (Record row : rows) {
            var partitionKey = new PartitionKey(table.spec(), table.schema());
            partitionKey.partition(wrapper.wrap(row));
            byPartition.computeIfAbsent(partitionKey.toPath(), path -> new ArrayList<>()).add(row);
        }

        var append = table.newAppend();
        int fileIndex = 0;
        for (var partition : byPartition.entrySet()) {
            var partitionKey = new PartitionKey(table.spec(), table.schema());
            partitionKey.partition(wrapper.wrap(partition.getValue().getFirst()));
            var dataPath = table.locationProvider().newDataLocation(table.spec(), partitionKey,
                    FileFormat.PARQUET.addExtension("attributed-probe-" + fileIndex++));
            DataWriter<Record> writer;
            try {
                writer = Parquet.writeData(table.io().newOutputFile(dataPath))
                        .schema(table.schema())
                        .set("write.parquet.compression-codec", "zstd")
                        .createWriterFunc(messageType ->
                                GenericParquetWriter.create(table.schema(), messageType))
                        .withSpec(table.spec())
                        .withPartition(partitionKey)
                        .build();
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to open the partitioned probe file", exception);
            }
            try (writer) {
                for (Record row : partition.getValue()) {
                    writer.write(row);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("Failed to write the partitioned probe file", exception);
            }
            append.appendFile(writer.toDataFile());
        }
        append.commit();
    }

    @Test
    void expiresOldSnapshotsWhileKeepingTheCurrentOneReadable() {
        var schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(2, "note", Types.StringType.get()));
        Table table = createProbe(probeTable, schema);
        appendRows(table, "expiry-probe-0", probeRows(table, 1, 3));
        appendRows(table, "expiry-probe-1", probeRows(table, 4, 4));
        assertEquals(2, snapshotCount(table), "two appends must produce two snapshots");
        long currentSnapshotId = table.currentSnapshot().snapshotId();

        table.expireSnapshots()
                .expireOlderThan(System.currentTimeMillis())
                .retainLast(1)
                .commit();

        Table reloaded = catalog.loadTable(probeTable);
        assertEquals(1, snapshotCount(reloaded),
                "expiry must remove the superseded snapshot and keep the current one");
        assertEquals(currentSnapshotId, reloaded.currentSnapshot().snapshotId(),
                "the current snapshot must survive expiry");
        assertEquals(4, readAll(reloaded).size(),
                "every row must remain readable through the retained snapshot");
        CatalogVerificationLogging.tableEvent("snapshot-expiry-confirmed", probeTable.toString(),
                reloaded.location(), reloaded.currentSnapshot().snapshotId(), 4, null);
    }

    private static int snapshotCount(Table table) {
        int count = 0;
        for (@SuppressWarnings("unused") var snapshot : table.snapshots()) {
            count++;
        }
        return count;
    }

    private static List<Record> probeRows(Table table, long firstId, long lastId) {
        var rows = new ArrayList<Record>();
        for (long id = firstId; id <= lastId; id++) {
            var record = GenericRecord.create(table.schema());
            record.setField("id", id);
            record.setField("note", "probe-" + id);
            rows.add(record);
        }
        return rows;
    }

    /** Appends the rows to an unpartitioned probe table as one committed Parquet file. */
    private static void appendRows(Table table, String fileName, List<Record> rows) {
        var dataPath = table.locationProvider().newDataLocation(
                FileFormat.PARQUET.addExtension(fileName));
        var outputFile = table.io().newOutputFile(dataPath);
        FileAppender<Record> appender;
        try {
            // zstd matches the Iceberg 1.4+ engine default; the bare builder
            // otherwise falls back to legacy gzip, whose Hadoop codec drags
            // in Shell/DataChecksum and the winutils probe on Windows.
            appender = Parquet.write(outputFile)
                    .schema(table.schema())
                    .set("write.parquet.compression-codec", "zstd")
                    .createWriterFunc(messageType -> GenericParquetWriter.create(table.schema(), messageType))
                    .build();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to open the probe data file for writing", exception);
        }
        try (appender) {
            for (Record record : rows) {
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
    }

    private static List<Record> readAll(Table table) {
        var rows = new ArrayList<Record>();
        try (var records = IcebergGenerics.read(table).build()) {
            records.forEach(rows::add);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read the probe table back", exception);
        }
        return rows;
    }

    @Test
    void rejectsAForgedPrincipalCredential() {
        // Positive control. The refusal below is only evidence about the
        // credential if the catalog is reachable and the real credential is
        // accepted at this moment: an unreachable endpoint or a TLS fault
        // would otherwise satisfy the negative without a credential ever being
        // judged. This client was built by the same path the forged one uses.
        assertFalse(catalog.listNamespaces().isEmpty(),
                "the real credential must reach the catalog, otherwise the refusal below proves nothing");

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
            CatalogVerificationLogging.negativeConfirmed("forged-principal-credential",
                    "refused with " + failure.getClass().getSimpleName());
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to release the forged catalog client", exception);
        }
    }
}
