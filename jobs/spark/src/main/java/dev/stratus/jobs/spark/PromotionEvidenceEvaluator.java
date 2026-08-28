// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Evaluates already-read quality evidence without depending on a Spark runtime. */
final class PromotionEvidenceEvaluator {

    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_WARNING = "WARNING";
    static final String STATUS_OVERRIDDEN = "overridden";

    private static final Logger LOGGER = LoggerFactory.getLogger(PromotionEvidenceEvaluator.class);

    private PromotionEvidenceEvaluator() {
    }

    static PromotionDecision evaluate(String runId, String targetTable,
                                      List<Evidence> evidence) {
        var failing = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        boolean overridden = false;

        for (Evidence result : evidence) {
            LOGGER.debug("PROMOTION EVIDENCE runId={} check={} severity={} status={}",
                    runId, result.checkName(), result.severity(), result.status());
            if (STATUS_OVERRIDDEN.equals(result.status())) {
                overridden = true;
            } else if (STATUS_FAILED.equals(result.status())) {
                failing.add(result.checkName());
            } else if (STATUS_WARNING.equals(result.status())) {
                warnings.add(result.checkName());
            }
        }

        boolean blocked = evidence.isEmpty() || (!failing.isEmpty() && !overridden);
        return new PromotionDecision(runId, targetTable, blocked, evidence.size(),
                failing, warnings);
    }

    record Evidence(String checkName, String severity, String status) {
    }
}
