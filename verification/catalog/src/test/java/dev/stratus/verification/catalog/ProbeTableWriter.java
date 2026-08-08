// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.data.GenericAppenderFactory;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.IcebergGenerics;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.deletes.EqualityDeleteWriter;
import org.apache.iceberg.encryption.EncryptedFiles;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.parquet.Parquet;

/**
 * Writes real files into a real probe table through the production writers.
 *
 * <p>Shared by the maintenance and orphan-detection suites so both produce
 * their fixtures the same way: every file here is written by the Iceberg
 * writer into live object storage, and the only difference between a healthy
 * file and an orphan is whether the commit that would reference it happens.
 * Nothing in this class stands in for the storage layer or the catalog.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
final class ProbeTableWriter {

    private ProbeTableWriter() {
    }

    static List<Record> probeRows(Table table, long firstId, long lastId) {
        var rows = new ArrayList<Record>();
        for (long id = firstId; id <= lastId; id++) {
            var record = GenericRecord.create(table.schema());
            record.setField("id", id);
            record.setField("note", "probe-" + id);
            rows.add(record);
        }
        return rows;
    }

    /** Writes a data file and commits it, producing one snapshot. */
    static void appendRows(Table table, String fileName, List<Record> rows) {
        String dataPath = writeDataFile(table, fileName, rows);
        var input = table.io().newInputFile(dataPath);
        table.newAppend().appendFile(DataFiles.builder(table.spec())
                .withPath(dataPath)
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(input.getLength())
                .withRecordCount(rows.size())
                .build()).commit();
    }

    /**
     * Writes a real data file and never commits it — what an interrupted or
     * failed write leaves in the bucket. Returns its location.
     */
    static String writeDataFileWithoutCommitting(Table table, String fileName) {
        return writeDataFile(table, fileName, probeRows(table, 99, 99));
    }

    /** Writes and commits an equality-delete file for one id. */
    static void writeAndCommitEqualityDelete(Table table, long deletedId) {
        Schema deleteSchema = table.schema().select("id");
        var factory = new GenericAppenderFactory(table.schema(), table.spec(),
                new int[] {table.schema().findField("id").fieldId()}, deleteSchema, null);
        var deletePath = table.locationProvider().newDataLocation(
                FileFormat.PARQUET.addExtension("equality-delete-" + UUID.randomUUID()));
        EqualityDeleteWriter<Record> writer = factory.newEqDeleteWriter(
                EncryptedFiles.plainAsEncryptedOutput(table.io().newOutputFile(deletePath)),
                FileFormat.PARQUET, null);
        try (writer) {
            Record delete = GenericRecord.create(deleteSchema);
            delete.setField("id", deletedId);
            writer.write(delete);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to write the equality-delete file", exception);
        }
        table.newRowDelta().addDeletes(writer.toDeleteFile()).commit();
    }

    static String writeDataFile(Table table, String fileName, List<Record> rows) {
        var dataPath = table.locationProvider().newDataLocation(
                FileFormat.PARQUET.addExtension(fileName + "-" + UUID.randomUUID()));
        var outputFile = table.io().newOutputFile(dataPath);
        FileAppender<Record> appender;
        try {
            // zstd matches the Iceberg engine default; the bare builder falls
            // back to gzip, whose Hadoop codec drags in the winutils probe on
            // Windows workstations.
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
        return dataPath;
    }

    static List<Record> readAll(Table table) {
        var rows = new ArrayList<Record>();
        try (var records = IcebergGenerics.read(table).build()) {
            records.forEach(rows::add);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read the probe table back", exception);
        }
        return rows;
    }
}
