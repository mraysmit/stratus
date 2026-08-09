// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Covers the upsert the transform job builds, and the two conditions in it that
 * decide whether silver ends up holding the right version of a record.
 *
 * <p>That the statement parses and behaves as written is proven on the live
 * cluster; what is proven here is that the conditions are in it at all. A
 * dropped sequence comparison still parses, still merges, and still reports
 * success — it just quietly lets an older record win.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("unit")
final class MergeStatementTest {

    private static final String SILVER = "stratus.silver.customers";
    private static final String BRONZE = "stratus.bronze.customers";
    private static final String[] BRONZE_COLUMNS = {
        "customer_id", "email", "country", "updated_at",
        "stratus_batch_id", "stratus_ingested_at", "stratus_source_file"};

    @Test
    void theSequenceComparisonGuardsTheUpdate() {
        String statement = TransformJob.mergeStatement(SILVER, "batch",
                new String[] {"customer_id"}, "updated_at");

        assertTrue(statement.contains("WHEN MATCHED AND (s.`updated_at` > t.`updated_at`"),
                "an update must require the incoming row to be the newer one: " + statement);
        assertTrue(statement.contains("THEN UPDATE SET *"), statement);
        assertTrue(statement.contains("WHEN NOT MATCHED THEN INSERT *"),
                "a key silver has not seen must still be inserted: " + statement);
    }

    @Test
    void aTargetRowWithNoSequenceCanStillBeCorrected() {
        // Comparing anything against null yields null, not true, so without
        // this clause a silver row that arrived without a sequence value could
        // never be updated again by anything.
        String statement = TransformJob.mergeStatement(SILVER, "batch",
                new String[] {"customer_id"}, "updated_at");

        assertTrue(statement.contains("OR t.`updated_at` IS NULL"), statement);
    }

    @Test
    void aCompositeKeyJoinsEveryColumnWithAnd() {
        String statement = TransformJob.mergeStatement(SILVER, "batch",
                new String[] {"region", "customer_id"}, "updated_at");

        assertTrue(statement.contains("ON t.`region` = s.`region` AND t.`customer_id` = s.`customer_id`"),
                "every key column must be in the match condition: " + statement);
    }

    @Test
    void theTargetIsAliasedSoBothSidesOfTheComparisonCanBeNamed() {
        String statement = TransformJob.mergeStatement(SILVER, "batch",
                new String[] {"customer_id"}, "updated_at");

        assertTrue(statement.startsWith("MERGE INTO " + SILVER + " AS t USING (batch) AS s"),
                "without the aliases the sequence comparison cannot be expressed: " + statement);
    }

    @Test
    void theSourceIsASubqueryRatherThanAView() {
        // Found on the cluster: a temporary view registered from a DataFrame
        // plan cannot be a MERGE source — Spark 4.1 fails to plan it with
        // "No plan for TableReference[...]" — while the same query written
        // inline plans and runs.
        String statement = TransformJob.mergeStatement(SILVER,
                TransformJob.batchQuery(BRONZE, BRONZE_COLUMNS, new String[] {"customer_id"},
                        "updated_at", null),
                new String[] {"customer_id"}, "updated_at");

        assertTrue(statement.contains("USING (SELECT "), statement);
        assertTrue(statement.contains("FROM " + BRONZE), statement);
    }

    @Test
    void theSourceQueryDropsTheIngestionAuditColumns() {
        // Silver is rewritten by whichever batch last corrected a row, so
        // carrying the batch that first delivered it forward would state
        // something that stops being true.
        String query = TransformJob.batchQuery(BRONZE, BRONZE_COLUMNS,
                new String[] {"customer_id"}, "updated_at", null);

        assertTrue(query.startsWith("SELECT `customer_id`, `email`, `country`, `updated_at` FROM"),
                query);
        for (String audit : new String[] {"stratus_batch_id", "stratus_ingested_at"}) {
            assertFalse(query.startsWith("SELECT `customer_id`, `email`, `country`, `updated_at`, `"
                    + audit), query);
        }
    }

    @Test
    void theSourceQueryNarrowsToOneBatchWhenOneIsNamed() {
        String query = TransformJob.batchQuery(BRONZE, BRONZE_COLUMNS,
                new String[] {"customer_id"}, "updated_at", "2026-08-02");

        assertTrue(query.contains("WHERE `stratus_batch_id` = '2026-08-02'"), query);
    }

    @Test
    void theSourceQueryReadsEveryBatchWhenNoneIsNamed() {
        String query = TransformJob.batchQuery(BRONZE, BRONZE_COLUMNS,
                new String[] {"customer_id"}, "updated_at", null);

        // The batch column is still in the tie-break hash, which considers the
        // whole row; what must be absent is a filter on it.
        assertFalse(query.contains("WHERE `stratus_batch_id`"),
                "no batch filter must be applied when none was asked for: " + query);
    }

    @Test
    void theDeduplicationOrderingIsTotal() {
        // Ordering by the sequence alone leaves the winner between two rows
        // sharing a key and a timestamp to whichever the engine read first, so
        // the same input could produce a different silver table on the next run.
        String query = TransformJob.batchQuery(BRONZE, BRONZE_COLUMNS,
                new String[] {"customer_id"}, "updated_at", null);

        assertTrue(query.contains("ORDER BY `updated_at` DESC, hash("), query);
        assertTrue(query.contains("hash(`customer_id`, `email`, `country`, `updated_at`, "
                + "`stratus_batch_id`, `stratus_ingested_at`, `stratus_source_file`) ASC"),
                "the tie-break must consider the whole row: " + query);
    }

    @Test
    void aBatchIdCarryingAQuoteIsRefused() {
        // The only caller-supplied value in the statement. A quote would end
        // the literal early and make the rest of the argument part of the query.
        var refused = assertThrows(IllegalArgumentException.class,
                () -> TransformJob.batchQuery(BRONZE, BRONZE_COLUMNS, new String[] {"customer_id"},
                        "updated_at", "2026-08-02' OR '1'='1"));

        assertTrue(refused.getMessage().contains("quote"), refused.getMessage());
    }

    @Test
    void aColumnNameCarryingTheQuotingCharacterIsRefused() {
        // The names reach here after being checked against the source table's
        // own columns, so this is the last thing standing between a hostile
        // name and a statement that means something else.
        var refused = assertThrows(IllegalArgumentException.class,
                () -> TransformJob.mergeStatement(SILVER, "batch",
                        new String[] {"customer_id`, 1=1 --"}, "updated_at"));

        assertTrue(refused.getMessage().contains("backtick"), refused.getMessage());
    }

    @Test
    void everyIdentifierIsQuoted() {
        String statement = TransformJob.mergeStatement(SILVER, "batch",
                new String[] {"order"}, "timestamp");

        // Both of these are reserved words. Unquoted, the statement fails to
        // parse at submission time rather than here.
        assertTrue(statement.contains("t.`order` = s.`order`"), statement);
        assertTrue(statement.contains("s.`timestamp` > t.`timestamp`"), statement);
    }

    @Test
    void theArgumentContractIsTheOneTheJobReads() {
        // The rename from --orderBy is only a real break if the old name is
        // refused; accepted and ignored, every existing submission would keep
        // working and silently stop ordering by anything.
        assertTrue(TransformJob.ARGUMENTS.contains("sequenceColumn"));
        assertEquals(false, TransformJob.ARGUMENTS.contains("orderBy"),
                "the retired argument name must not be accepted");
    }
}
