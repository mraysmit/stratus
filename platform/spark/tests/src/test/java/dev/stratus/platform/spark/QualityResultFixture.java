// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import dev.stratus.jobs.spark.QualityCheckJob;

/** Creates an empty, per-suite clone of the permanent quality audit table. */
final class QualityResultFixture {

    private QualityResultFixture() {
    }

    static void create(StratusSparkClient client, String table) {
        client.sql("CREATE TABLE " + table + " USING iceberg AS SELECT * FROM "
                + QualityCheckJob.RESULTS_TABLE + " WHERE 1 = 0");
    }
}
