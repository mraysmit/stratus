# Stratus Spark runtime: the approved Spark base image plus the Iceberg and
# S3A client artifacts the platform contracts require.
#
# The artifacts are resolved into the build context by
# scripts/lib/spark-compose-resolve-artifacts.sh, which uses the repository's
# own Maven toolchain so the versions come from the build system rather than
# from an ad-hoc download inside the image. Nothing is fetched here: a layer
# that reaches the network at build time cannot be reproduced, and P1-0.1
# keeps image assembly out of the harness scripts.
#
# Versions are the Increment 3 approved matrix (spark_compute.md section 5):
#   stratus-iceberg-aws-runtime     Iceberg Spark 4.1 1.11.0, AWS SDK relocated
#   hadoop-client / hadoop-aws      3.4.3  (+ its resolved runtime dependencies)
FROM apache/spark:4.1.2-scala2.13-java17-python3-ubuntu

USER root

# Replace the base image's Hadoop pair as one operation. Keeping either 3.4.2
# jar would be an unsupported mixed Hadoop classpath; adding 3.4.3 under a
# second filename would only make the winner depend on classpath ordering.
RUN rm /opt/spark/jars/hadoop-client-api-3.4.2.jar \
       /opt/spark/jars/hadoop-client-runtime-3.4.2.jar

# One layer, so the selected Hadoop, S3A, and isolated Iceberg set is a single
# reviewable unit.
COPY jars/ /opt/spark/jars/

# The lock manifest travels with the image so a running container can be
# asked what it was built from.
COPY artifact-lock.txt /opt/stratus/artifact-lock.txt

# Trust material for Ceph RGW and the Polaris catalog is mounted at runtime
# by the harness rather than baked in: the disposable lab CAs are per-machine
# and would otherwise pin this image to one developer's cluster.
RUN mkdir -p /opt/stratus/certs && chmod 0755 /opt/stratus/certs

# The event-log and scratch directories must exist here, owned by the runtime
# user, before a named volume is mounted over them. A volume whose mountpoint
# is absent from the image is created root-owned, and Spark then fails at
# startup with "Failed to create any local dir" — the directory exists but the
# spark user cannot write to it.
RUN mkdir -p /opt/spark/events /opt/spark/scratch \
    && chown -R spark:spark /opt/spark/events /opt/spark/scratch

USER spark
