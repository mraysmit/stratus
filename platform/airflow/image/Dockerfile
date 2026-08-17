# syntax=docker/dockerfile:1
# Stratus Airflow client runtime. All remote inputs are resolved and verified by
# scripts/build/airflow-image-resolve-artifacts.sh before this build starts.
ARG TEMURIN_21_IMAGE=eclipse-temurin:21-jre@sha256:371da296b8cb74c7e53fbe7083d5374befc0011b493231d97d45fa789915e434
ARG AIRFLOW_BASE_IMAGE=apache/airflow:3.3.1-python3.14@sha256:d4ed2a3cf0103b52b69f5ee57cfaefd4274dca56b546c4396b83ccaf93057539

FROM ${TEMURIN_21_IMAGE} AS temurin
FROM ${AIRFLOW_BASE_IMAGE}

USER root

COPY --from=temurin /opt/java/openjdk /opt/java/openjdk
COPY artifacts/spark-4.1.3-bin-hadoop3.tgz /tmp/spark-4.1.3-bin-hadoop3.tgz

RUN tar -xzf /tmp/spark-4.1.3-bin-hadoop3.tgz -C /opt \
    && mv /opt/spark-4.1.3-bin-hadoop3 /opt/spark \
    && rm /opt/spark/jars/derby-10.16.1.1.jar \
    && rm -f /usr/bin/docker \
       /home/airflow/.local/bin/uv \
       /home/airflow/.local/bin/uvx \
    && rm /tmp/spark-4.1.3-bin-hadoop3.tgz \
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
# hashes identify the PyPI inputs; the pinned Airflow base supplies transitive
# dependencies and the build tools needed to install PySpark's source archive.
RUN python -m pip install \
      --no-cache-dir \
      --no-index \
      --find-links=/opt/stratus/wheelhouse \
      --no-deps \
      --no-build-isolation \
      --require-hashes \
      --requirement /opt/stratus/requirements.lock \
    && python -m pip uninstall --yes litellm ray \
    && PYSPARK_PACKAGE="$(python -c 'import pathlib, pyspark; print(pathlib.Path(pyspark.__file__).parent)')" \
    && rm -rf "${PYSPARK_PACKAGE}/jars" \
    && rm -rf /opt/stratus/wheelhouse
