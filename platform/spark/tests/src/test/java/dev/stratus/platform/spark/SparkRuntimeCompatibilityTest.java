// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Offline JVM classpath-ownership contract for the Spark runtime dependencies used by tests.
 *
 * <h2>Rationale</h2>
 *
 * <p>Iceberg deliberately relocates its Amazon SDK while Hadoop owns the public AWS package. A
 * duplicate or incorrectly sourced class may compile and still fail only inside a live Spark job.
 * This fast test detects that packaging defect before an environment is started.
 *
 * <h2>Maintenance and promotion</h2>
 *
 * <p>Resource names describe stable API ownership; versions come from
 * {@link SparkRuntimeBaseline}. Update both only as part of a reviewed Spark/Hadoop dependency
 * upgrade, then run the offline harness checks and live cluster conformance suite. Passing this
 * class is developer build evidence only. UAT and production must use the same immutable image
 * digest proven through the live semantic, security, and operational gates.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-08
 * @version 1.1.0
 */
@Tag("unit")
final class SparkRuntimeCompatibilityTest {

    private static final String ICEBERG_S3_FILE_IO_RESOURCE =
            "org/apache/iceberg/aws/s3/S3FileIO.class";
    private static final String RELOCATED_AWS_S3_CLIENT_RESOURCE =
            "dev/stratus/thirdparty/iceberg/amazon/awssdk/services/s3/S3Client.class";
    private static final String PUBLIC_ACCELERATOR_RESOURCE =
            "software/amazon/s3/analyticsaccelerator/request/Constants.class";

    @Test
    void icebergAndItsRelocatedAmazonLibrariesComeFromTheIsolatedRuntime() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        var iceberg = loader.getResource(ICEBERG_S3_FILE_IO_RESOURCE);
        var aws = loader.getResource(RELOCATED_AWS_S3_CLIENT_RESOURCE);
        var publicAccelerator = Collections.list(
                loader.getResources(PUBLIC_ACCELERATOR_RESOURCE));

        assertNotNull(iceberg, "the isolated runtime must carry S3FileIO");
        assertNotNull(aws, "the isolated runtime must carry Iceberg's relocated AWS SDK");
        assertTrue(iceberg.toString().contains(
                                SparkRuntimeBaseline.ISOLATED_ICEBERG_RUNTIME_ARTIFACT)
                        && aws.toString().contains(
                                SparkRuntimeBaseline.ISOLATED_ICEBERG_RUNTIME_ARTIFACT),
                "both resources must come from the isolated runtime: " + iceberg + ", " + aws);
        assertEquals(1, publicAccelerator.size(),
                "only Hadoop's analytics accelerator may own its public package: " + publicAccelerator);
        assertTrue(publicAccelerator.get(0).toString().contains(
                        SparkRuntimeBaseline.analyticsAcceleratorJar()),
                "Hadoop " + SparkRuntimeBaseline.HADOOP_VERSION
                        + "'s exact accelerator must own Constants: " + publicAccelerator);
    }
}
