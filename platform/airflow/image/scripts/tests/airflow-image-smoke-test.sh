#!/usr/bin/env bash
# Runtime compatibility checks for the built Stratus Airflow image.
set -euo pipefail

IMAGE_TAG="${STRATUS_AIRFLOW_IMAGE:-stratus/airflow:dev}"
START_NS="$(date +%s%N)"

log() {
  printf 'timestamp=%s component=airflow-image-smoke level=%s event=%s %s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" "${3:-}"
}

log INFO smoke_started "image=${IMAGE_TAG}"
MSYS_NO_PATHCONV=1 docker run --rm --entrypoint /bin/bash "${IMAGE_TAG}" -euo pipefail -c '
  assert_equal() {
    if [[ "$2" != "$1" ]]; then
      printf "check=%s expected=%s actual=%s result=failed\n" "$3" "$1" "$2" >&2
      return 1
    fi
    printf "check=%s actual=%s result=passed\n" "$3" "$2"
  }

  airflow_version="$(airflow version | tail -n 1)"
  python_version="$(python -c "import sys; print(f'\''{sys.version_info.major}.{sys.version_info.minor}'\'')")"
  assert_equal "3.3.1" "${airflow_version}" airflow_version
  assert_equal "3.14" "${python_version}" python_version
  python - <<'\''PY'\''
from importlib.metadata import PackageNotFoundError, version
from airflow.providers.amazon.aws.sensors.s3 import S3KeySensor
from airflow.providers.apache.spark.operators.spark_submit import SparkSubmitOperator
import boto3
import pyspark
import zstandard

expected = {
    "apache-airflow": "3.3.1",
    "apache-airflow-providers-amazon": "9.34.0",
    "apache-airflow-providers-apache-spark": "6.3.1",
    "aiohttp": "3.14.3",
    "boto3": "1.43.56",
    "pyspark-client": "4.1.3",
    "zstandard": "0.25.0",
}
actual = {distribution: version(distribution) for distribution in expected}
assert actual == expected, f"version mismatch: expected={expected}, actual={actual}"
assert boto3.__version__ == "1.43.56", boto3.__version__
assert pyspark.__version__ == "4.1.3", pyspark.__version__
assert zstandard.__version__ == "0.25.0", zstandard.__version__
assert SparkSubmitOperator.__name__ == "SparkSubmitOperator"
assert S3KeySensor.__name__ == "S3KeySensor"
try:
    version("pyspark")
except PackageNotFoundError:
    pass
else:
    raise AssertionError("full PySpark distribution is installed")
try:
    version("litellm")
except PackageNotFoundError:
    pass
else:
    raise AssertionError("unused LiteLLM distribution is installed")
try:
    version("ray")
except PackageNotFoundError:
    pass
else:
    raise AssertionError("unused Ray distribution is installed")
try:
    version("apache-airflow-providers-google")
except PackageNotFoundError:
    pass
else:
    raise AssertionError("unused Google provider distribution is installed")
print(f"provider_imports=ok versions={actual} litellm=absent ray=absent google_provider=absent pyspark_distribution=absent")
PY
  python -m pip check
  java -version
  spark-submit --version
  test ! -e /opt/spark/jars/derby-10.16.1.1.jar
  test ! -e /usr/bin/docker
  test ! -e /home/airflow/.local/bin/uv
  test ! -e /home/airflow/.local/bin/uvx
  test -r /opt/stratus/artifact-lock.properties
  grep -Fx "airflow.version=3.3.1" /opt/stratus/artifact-lock.properties
'
log INFO smoke_completed "image=${IMAGE_TAG} duration_ms=$(( ($(date +%s%N) - START_NS) / 1000000 ))"
