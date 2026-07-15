// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.verification.storage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Implementation of CephRgwIntegrationTest functionality.
 *
 * This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-07-15
 * @version 1.0.0
 */
@Tag("ceph-integration")
class CephRgwIntegrationTest {
    @Test
    void verifiesLiveCephRgwContract() {
        if (Boolean.getBoolean("ceph.integration.required")) {
            assertTrue(Boolean.parseBoolean(System.getenv("CEPH_RGW_INTEGRATION")),
                    "CEPH_RGW_INTEGRATION=true is required by the selected Maven profile");
        }
        assumeTrue(Boolean.parseBoolean(System.getenv("CEPH_RGW_INTEGRATION")),
                "Set CEPH_RGW_INTEGRATION=true to run against a live Ceph RGW endpoint");
        var config = StorageVerifierConfig.from(System.getenv());

        try (var client = S3ObjectStorageClient.create(config)) {
            var report = new StorageVerifier(client, Clock.systemUTC())
                    .verify(config.requiredBuckets(), config.probeBucket());
            assertTrue(report.success(), report.toJson());
        }
    }
}
