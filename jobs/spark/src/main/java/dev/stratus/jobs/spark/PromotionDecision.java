// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.List;

/**
 * The promotion gate's verdict for one quality run.
 *
 * <p>The failing rule names travel with the verdict. A gate that reports only
 * "blocked" forces whoever is on call to re-derive the reason from the results
 * table at the moment they are least able to.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public record PromotionDecision(String runId, String targetTable, boolean blocked,
                                int checksExamined, List<String> failingChecks,
                                List<String> warningChecks) {

    public PromotionDecision {
        failingChecks = List.copyOf(failingChecks);
        warningChecks = List.copyOf(warningChecks);
    }

    public String outcome() {
        return blocked ? "BLOCK" : "PROMOTE";
    }

    public String describe() {
        return String.format(
                "PROMOTION %s runId=%s table=%s checksExamined=%d failing=%s warnings=%s",
                outcome(), runId, targetTable, checksExamined,
                failingChecks.isEmpty() ? "none" : String.join(",", failingChecks),
                warningChecks.isEmpty() ? "none" : String.join(",", warningChecks));
    }
}
