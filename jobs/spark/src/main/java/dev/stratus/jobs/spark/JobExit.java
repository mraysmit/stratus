// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.jobs.spark;

/**
 * The status codes a platform job exits with.
 *
 * <p>The orchestrator reads the code, not the log. A job that reported every
 * refusal as a generic failure would leave Airflow unable to tell "the data was
 * refused promotion" — a business outcome that should page nobody — from "the
 * job could not run at all", so each refusal a caller might act on differently
 * gets its own code.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-09
 * @version 1.0.0
 */
public final class JobExit {

    /** The job did what it was asked. */
    public static final int SUCCESS = 0;

    /** The arguments were wrong, or the job could not complete. */
    public static final int FAILURE = 1;

    /** A blocking quality check failed, so nothing was written. */
    public static final int PROMOTION_BLOCKED = 2;

    /** The incoming schema conflicts with the table's, so nothing was written. */
    public static final int SCHEMA_DRIFT = 3;

    private JobExit() {
    }
}
