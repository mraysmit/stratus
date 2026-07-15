// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

/**
 * Immutable data carrier for VerificationCheck values.
 *
 * This record is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
public record VerificationCheck(String name, boolean passed, String detail) {
    public static VerificationCheck passed(String name, String detail) {
        return new VerificationCheck(name, true, detail);
    }

    public static VerificationCheck failed(String name, Exception exception) {
        var message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return new VerificationCheck(name, false, message);
    }
}
