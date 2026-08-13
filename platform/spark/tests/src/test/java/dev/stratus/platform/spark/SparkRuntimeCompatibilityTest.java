// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Offline classpath checks for failures that otherwise surface only inside a live job. */
@Tag("unit")
final class SparkRuntimeCompatibilityTest {

    @Test
    void icebergAndItsRelocatedAmazonLibrariesComeFromTheIsolatedRuntime() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        var iceberg = loader.getResource("org/apache/iceberg/aws/s3/S3FileIO.class");
        var aws = loader.getResource(
                "dev/stratus/thirdparty/iceberg/amazon/awssdk/services/s3/S3Client.class");
        var publicAccelerator = Collections.list(loader.getResources(
                "software/amazon/s3/analyticsaccelerator/request/Constants.class"));

        assertNotNull(iceberg, "the isolated runtime must carry S3FileIO");
        assertNotNull(aws, "the isolated runtime must carry Iceberg's relocated AWS SDK");
        assertTrue(iceberg.toString().contains("stratus-iceberg-aws-runtime")
                        && aws.toString().contains("stratus-iceberg-aws-runtime"),
                "both resources must come from the isolated runtime: " + iceberg + ", " + aws);
        assertEquals(1, publicAccelerator.size(),
                "only Hadoop's analytics accelerator may own its public package: " + publicAccelerator);
        assertTrue(publicAccelerator.get(0).toString().contains("analyticsaccelerator-s3-1.3.1"),
                "Hadoop 3.4.3's exact accelerator must own Constants: " + publicAccelerator);
    }
}
