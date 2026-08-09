// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Offline checks on the parts of the quality and promotion path that decide
 * where a result is filed and what a verdict means. The measurements
 * themselves need real tables and are proven by
 * {@code SparkPipelineVerificationTest} against the live cluster.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
@Tag("unit")
final class QualityCheckIdentifierTest {

    @Test
    void splitsAFullyQualifiedIdentifier() {
        // The namespace is the zone, and the zone is a partition column of
        // the results table — a wrong split files results under the wrong
        // zone and they stop being findable.
        assertArrayEquals(new String[] {"stratus", "bronze", "customers"},
                QualityCheckJob.splitIdentifier("stratus.bronze.customers"));
    }

    @Test
    void refusesAnIdentifierThatIsNotCatalogNamespaceTable() {
        for (String identifier : new String[] {"customers", "bronze.customers", "a.b.c.d"}) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> QualityCheckJob.splitIdentifier(identifier),
                    "must refuse: " + identifier);
            assertTrue(failure.getMessage().contains(identifier), failure.getMessage());
        }
    }

    @Test
    void readsCheckDefinitionsFromEitherThePlainOrTheEncodedArgument() {
        String json = "[{\"type\":\"row_count_min\",\"minRows\":1}]";
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(json, QualityCheckJob.checkDefinitions(
                JobArguments.parse("--checks", json)));
        assertEquals(json, QualityCheckJob.checkDefinitions(
                JobArguments.parse("--checksBase64", encoded)));
    }

    @Test
    void refusesBothOrNeitherFormOfTheCheckDefinitions() {
        // Preferring one silently would run a set of rules nobody chose.
        assertThrows(IllegalArgumentException.class,
                () -> QualityCheckJob.checkDefinitions(JobArguments.parse("--targetTable", "a.b.c")));
        assertThrows(IllegalArgumentException.class,
                () -> QualityCheckJob.checkDefinitions(
                        JobArguments.parse("--checks", "[]", "--checksBase64", "W10=")));
    }

    @Test
    void refusesCheckDefinitionsThatAreNotValidBase64() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> QualityCheckJob.checkDefinitions(
                        JobArguments.parse("--checksBase64", "not base64 at all!")));

        assertTrue(failure.getMessage().contains("base64"), failure.getMessage());
    }

    @Test
    void aBlockedDecisionNamesTheFailingRules() {
        var decision = new PromotionDecision("run-1", "stratus.bronze.customers", true, 4,
                List.of("customer_id_unique"), List.of("email_mostly_present"));

        assertEquals("BLOCK", decision.outcome());
        assertTrue(decision.describe().contains("customer_id_unique"),
                "whoever is paged must not have to re-derive the reason: " + decision.describe());
        assertTrue(decision.describe().contains("email_mostly_present"),
                "warnings belong in the record too: " + decision.describe());
    }

    @Test
    void aPromotedDecisionSaysSoWithoutFailingRules() {
        var decision = new PromotionDecision("run-2", "stratus.bronze.customers", false, 4,
                List.of(), List.of());

        assertEquals("PROMOTE", decision.outcome());
        assertTrue(decision.describe().contains("failing=none"), decision.describe());
    }

    @Test
    void aDecisionCopiesItsRuleListsSoTheyCannotChangeUnderIt() {
        var failing = new java.util.ArrayList<>(List.of("customer_id_unique"));
        var decision = new PromotionDecision("run-3", "stratus.bronze.customers", true, 1,
                failing, List.of());

        failing.add("added_after_the_fact");

        assertEquals(1, decision.failingChecks().size(),
                "a verdict must not change after it is taken");
    }
}
