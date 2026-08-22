// Copyright 2026 Mark Andrew Ray-Smith Cityline Ltd
// SPDX-License-Identifier: Apache-2.0

package dev.stratus.platform.spark;

import java.util.List;

/**
 * Independent test oracle for the Spark runtime classpath and image replacement contract.
 *
 * <h2>Rationale</h2>
 *
 * <p>The Spark image, offline harness checks, live cluster checks, and JVM classpath checks all
 * enforce different views of one runtime. Keeping their versions in unrelated assertions allows a
 * partial upgrade to pass one layer while leaving another layer stale. This package-local baseline
 * gives those tests one deliberately reviewed vocabulary without exposing test policy to product
 * code.
 *
 * <h2>Maintenance and promotion</h2>
 *
 * <p>For an upgrade, change this oracle and the BOM/image inputs in one reviewable change, observe
 * the offline and live assertions fail before completing the implementation, and verify the whole
 * replacement set rather than mixing Hadoop patches. Rebuild and scan an immutable Spark image,
 * prove its class ownership and live Ceph/Polaris behavior in developer, promote the same digest to
 * UAT for semantic, security, performance, and recovery validation, and promote the unchanged
 * UAT-approved digest to production. Never update these values solely to match an unexpected
 * runtime classpath.
 *
 * <p>This class is part of the Stratus on-premises data fabric platform.
 *
 * @author Mark Andrew Ray-Smith Cityline Ltd
 * @since 2026-08-22
 * @version 1.0.0
 */
final class SparkRuntimeBaseline {

    static final String HADOOP_VERSION = "3.4.3";
    static final String BASE_HADOOP_VERSION = "3.4.2";
    static final List<String> SUPERSEDED_HADOOP_VERSIONS = List.of(BASE_HADOOP_VERSION, "3.4.1");
    static final String AWS_SDK_BUNDLE_VERSION = "2.35.4";
    static final String ANALYTICS_ACCELERATOR_VERSION = "1.3.1";
    static final String ISOLATED_ICEBERG_RUNTIME_ARTIFACT = "stratus-iceberg-aws-runtime";
    static final String HADOOP_AWS_ARTIFACT = "hadoop-aws";

    private SparkRuntimeBaseline() {
    }

    static String hadoopJar(String artifact) {
        return jar(artifact, HADOOP_VERSION);
    }

    static String baseHadoopJar(String artifact) {
        return jar(artifact, BASE_HADOOP_VERSION);
    }

    static String awsSdkBundleJar() {
        return jar("bundle", AWS_SDK_BUNDLE_VERSION);
    }

    static String analyticsAcceleratorJar() {
        return "analyticsaccelerator-s3-" + ANALYTICS_ACCELERATOR_VERSION;
    }

    private static String jar(String artifact, String version) {
        return artifact + "-" + version + ".jar";
    }
}
