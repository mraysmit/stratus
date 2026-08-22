# syntax=docker/dockerfile:1
# Stratus Airflow client runtime. Spark and Java come from a pinned official OCI
# source stage; small Python inputs are resolved and verified before this build.
ARG SPARK_SOURCE_IMAGE=apache/spark:4.1.3-scala2.13-java21-python3-ubuntu@sha256:bf9d035a7c32a8ca46aa58d6348182ffd7d2dff6409206ecfbb3915ff1fef211
ARG AIRFLOW_BASE_IMAGE=apache/airflow:3.3.1-python3.14@sha256:d4ed2a3cf0103b52b69f5ee57cfaefd4274dca56b546c4396b83ccaf93057539

FROM ${SPARK_SOURCE_IMAGE} AS spark
FROM ${AIRFLOW_BASE_IMAGE}

USER root

COPY --from=spark /opt/java/openjdk /opt/java/openjdk
COPY --from=spark /opt/spark /opt/spark

RUN rm /opt/spark/jars/derby-10.16.1.1.jar \
    && rm -f /usr/bin/docker \
       /home/airflow/.local/bin/uv \
       /home/airflow/.local/bin/uvx \
    && mkdir -p /opt/stratus/wheelhouse \
    && chown -R airflow:0 /opt/spark /opt/stratus

COPY --chown=airflow:0 artifacts/wheelhouse/ /opt/stratus/wheelhouse/
COPY --chown=airflow:0 requirements.lock /opt/stratus/requirements.lock
COPY --chown=airflow:0 artifact-lock.properties /opt/stratus/artifact-lock.properties

ENV JAVA_HOME=/opt/java/openjdk \
    SPARK_HOME=/opt/spark \
    PATH=/opt/java/openjdk/bin:/opt/spark/bin:${PATH}

USER airflow

# No index, dependency traversal, or build isolation is permitted here. The
# hashes identify the PyPI inputs; the pinned Airflow base supplies the declared
# transitive dependencies except the explicitly locked zstandard wheel.
RUN python -m pip install \
      --no-cache-dir \
      --no-index \
      --find-links=/opt/stratus/wheelhouse \
      --no-deps \
      --no-build-isolation \
      --require-hashes \
      --requirement /opt/stratus/requirements.lock \
    && python -m pip uninstall --yes litellm ray apache-airflow-providers-google \
    && rm -rf /opt/stratus/wheelhouse
