// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.catalog;

/**
 * One maintenance decision: what was observed in a named metadata table, the
 * trigger it was compared against, and whether that comparison recommends
 * action.
 *
 * <p>The observed value and the threshold are both carried because a decision
 * that reports only its verdict cannot be reviewed — an operator cannot tell a
 * correct "no action" from a query that returned nothing.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.0.0
 */
record MaintenanceDecision(String category, String metadataTable, long observed,
                           long threshold, boolean actionRecommended, String detail) {
}
