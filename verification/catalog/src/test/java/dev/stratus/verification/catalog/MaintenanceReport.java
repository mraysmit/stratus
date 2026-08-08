// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

import java.util.List;

/**
 * Every maintenance decision taken for one table in a single pass.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
record MaintenanceReport(String table, List<MaintenanceDecision> decisions) {

    MaintenanceReport {
        decisions = List.copyOf(decisions);
    }

    MaintenanceDecision decision(String category) {
        return decisions.stream()
                .filter(decision -> decision.category().equals(category))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No decision for category '" + category + "' in: " + categories()));
    }

    List<String> categories() {
        return decisions.stream().map(MaintenanceDecision::category).toList();
    }

    List<String> recommendedActions() {
        return decisions.stream()
                .filter(MaintenanceDecision::actionRecommended)
                .map(MaintenanceDecision::category)
                .toList();
    }
}
