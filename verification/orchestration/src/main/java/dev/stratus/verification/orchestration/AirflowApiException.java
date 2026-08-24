// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.orchestration;

/** A redacted Airflow HTTP or response-contract failure. */
public final class AirflowApiException extends RuntimeException {

    private final int statusCode;

    AirflowApiException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    AirflowApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public int statusCode() {
        return statusCode;
    }
}
