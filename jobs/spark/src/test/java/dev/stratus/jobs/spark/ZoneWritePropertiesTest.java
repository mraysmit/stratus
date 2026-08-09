// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Covers the write configuration each zone's tables carry.
 *
 * <p>These are the properties the architecture (§6.4.6) requires to be stated
 * rather than inherited, so what is under test is that they are stated at all
 * and that the zones differ where the architecture says they differ. That the
 * live catalog accepts them is proven against Polaris by the conformance
 * scenario in the live suite.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("unit")
final class ZoneWritePropertiesTest {

    @Test
    void everyZoneStatesTheWriteModesRatherThanInheritingThem() {
        for (Map<String, String> zone : java.util.List.of(
                ZoneWriteProperties.bronze(), ZoneWriteProperties.silver(),
                ZoneWriteProperties.gold())) {
            for (String operation : new String[] {"delete", "update", "merge"}) {
                assertEquals("copy-on-write", zone.get("write." + operation + ".mode"),
                        "write." + operation + ".mode must be stated: " + zone);
                assertEquals("serializable", zone.get("write." + operation + ".isolation-level"),
                        "write." + operation + ".isolation-level must be stated: " + zone);
            }
            assertEquals("parquet", zone.get("write.format.default"), zone.toString());
            assertTrue(zone.containsKey("write.target-file-size-bytes"), zone.toString());
        }
    }

    @Test
    void bronzeAcceptsAnEvolvingSchemaAndSaysItIsAppendOnly() {
        Map<String, String> bronze = ZoneWriteProperties.bronze();

        // Without this property Spark's analyzer refuses a batch carrying a new
        // column before Iceberg's schema merge is ever consulted, and the
        // merge-schema option on the write is silently a no-op.
        assertEquals("true", bronze.get("write.spark.accept-any-schema"));
        assertEquals("true", bronze.get(ZoneWriteProperties.APPEND_ONLY));
    }

    @Test
    void silverDoesNotAcceptAnEvolvingSchemaAndIsNotMarkedAppendOnly() {
        Map<String, String> silver = ZoneWriteProperties.silver();

        // Silver is the conformed zone: a column arriving unreviewed is the
        // thing it exists to prevent, and it is upserted rather than appended.
        assertFalse(silver.containsKey("write.spark.accept-any-schema"), silver.toString());
        assertFalse(silver.containsKey(ZoneWriteProperties.APPEND_ONLY), silver.toString());
        assertEquals("hash", silver.get("write.distribution-mode"), silver.toString());
    }

    @Test
    void theFormatVersionIsPinnedRatherThanLeftToTheRuntime() {
        // An Iceberg upgrade that moved the default format version would change
        // how deletes are stored on every table nobody had pinned.
        assertEquals("2", ZoneWriteProperties.bronze().get("format-version"));
        assertEquals("2", ZoneWriteProperties.silver().get("format-version"));
    }
}
