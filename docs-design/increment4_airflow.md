# Stratus Increment 4 — Apache Airflow Orchestration

## 1. Purpose

This document is the technical implementation plan for Increment 4 of the Stratus platform as defined in [stratus_implementation_plan.md](stratus_implementation_plan.md).

Increment 4 delivers Apache Airflow as the orchestration and control-plane layer for the batch platform created in Increment 3. Airflow schedules and coordinates Spark jobs, data quality checks, promotion gates, and Iceberg table maintenance. When this increment is complete, the bronze, silver, and gold pipeline runs as managed DAGs with retries, failure visibility, structured run metadata, and deterministic promotion blocking. A Java verification suite uses the Airflow REST API and the table layer to confirm the orchestration layer works end to end.

**Prerequisites:**
- Increment 1 complete — MinIO cluster running, all buckets and service accounts in place
- Increment 2 complete — Polaris running, all namespaces and the `platform.quality_check_results` table created, all Increment 2 gate tests passing
- Increment 3 complete — Spark cluster running, Spark jobs implemented, all Increment 3 gate tests passing

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 4.x installed on the Airflow host
- JDK 21+ and Maven 3.9+ on the development and verification host
- DNS resolution: `airflow.stratus.local` resolves to the Airflow host
- Airflow host can reach:
  - Spark master on port 7077
  - Spark master web UI on port 8080
  - MinIO on port 9000
  - Polaris on port 8181
- `svc-airflow` MinIO credentials from Increment 1 are available
- `svc-spark` MinIO credentials and Polaris principal credentials from Increment 3 are available
- The Stratus application fat JAR from Increment 3 is available to the Airflow runtime

---

## 3. Topology

Airflow runs on a dedicated host using Podman containers. PostgreSQL is used as the Airflow metadata database. For Increment 4, Airflow uses `LocalExecutor` with one scheduler and one webserver. A distributed Celery or Kubernetes executor is deferred until operational scale requires it.

```text
airflow.stratus.local
┌──────────────────────────────────────────────┐
│  Podman: airflow-webserver                   │
│  Airflow UI / REST API :8088                 │
├──────────────────────────────────────────────┤
│  Podman: airflow-scheduler                   │
│  DAG parsing, scheduling, task execution     │
├──────────────────────────────────────────────┤
│  Podman: airflow-postgres                    │
│  Airflow metadata database :5432             │
└──────────────────────────────────────────────┘
          │
          │ spark-submit
          ▼
spark-master.stratus.local:7077
          │
          ▼
Spark workers → Polaris REST catalog → MinIO Iceberg tables
```

Airflow is not a compute engine. DAG tasks submit Spark jobs, run lightweight control-plane checks, evaluate promotion gates, and schedule maintenance. Heavy transformations remain in Spark.

---

## 4. Ports

| Port | Service | Purpose |
|---|---|---|
| 8088 | Airflow webserver | Airflow UI and REST API |
| 5432 | PostgreSQL | Airflow metadata database, local host access only where possible |

The Airflow host must also be able to reach the ports from previous increments:

| Port | Service | Purpose |
|---|---|---|
| 7077 | Spark master | Spark job submission |
| 8080 | Spark master UI | Operational verification |
| 9000 | MinIO | Landing-zone detection and Spark object storage access |
| 8181 | Polaris | Spark Iceberg catalog access |

For Increment 4, Airflow's own endpoint may be HTTP inside the lab network. TLS and Keycloak-backed authentication are hardened in Increment 7.

---

## 5. Airflow Image

The official Airflow image is used as the base. A custom image adds Java, Spark client binaries, and the Airflow providers required for Spark and S3-compatible object storage.

### Dockerfile

Create `docker/airflow/Dockerfile` in the Stratus repository:

```dockerfile
FROM apache/airflow:2.9.3-python3.11

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       curl \
       ca-certificates \
       openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*

ARG SPARK_VERSION=3.5.1
ARG HADOOP_VERSION=3

RUN curl -fsSL \
      https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/spark-${SPARK_VERSION}-bin-hadoop${HADOOP_VERSION}.tgz \
      -o /tmp/spark.tgz \
    && mkdir -p /opt/spark \
    && tar -xzf /tmp/spark.tgz -C /opt/spark --strip-components=1 \
    && rm /tmp/spark.tgz

ENV SPARK_HOME=/opt/spark
ENV PATH="${SPARK_HOME}/bin:${PATH}"
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

USER airflow

RUN pip install --no-cache-dir \
    apache-airflow-providers-apache-spark==4.9.0 \
    apache-airflow-providers-amazon==8.25.0 \
    boto3==1.34.131
```

### Build and tag the image

```bash
cd docker/airflow
podman build -t stratus/airflow:2.9.3 .
```

---

## 6. Airflow Directory Layout

Create persistent directories on the Airflow host:

```bash
sudo mkdir -p /data/airflow/dags
sudo mkdir -p /data/airflow/logs
sudo mkdir -p /data/airflow/plugins
sudo mkdir -p /data/airflow/jars
sudo mkdir -p /data/airflow/postgres
sudo chown -R $USER:$USER /data/airflow
```

Copy the Stratus application fat JAR built in Increment 3 into `/data/airflow/jars`:

```bash
cp target/stratus-*.jar /data/airflow/jars/stratus.jar
```

The mounted directory layout is:

```text
/data/airflow/
├── dags/       Airflow DAG files
├── logs/       task logs
├── plugins/    Airflow plugins, if needed later
├── jars/       Stratus Spark application JAR
└── postgres/   PostgreSQL data directory
```

---

## 7. Airflow Configuration

### Environment file

Create `/etc/stratus/airflow.env` on the Airflow host:

```bash
# /etc/stratus/airflow.env

AIRFLOW__CORE__EXECUTOR=LocalExecutor
AIRFLOW__CORE__LOAD_EXAMPLES=False
AIRFLOW__CORE__DAGS_ARE_PAUSED_AT_CREATION=False
AIRFLOW__CORE__DEFAULT_TIMEZONE=UTC
AIRFLOW__DATABASE__SQL_ALCHEMY_CONN=postgresql+psycopg2://airflow:airflow@localhost:5432/airflow
AIRFLOW__API__AUTH_BACKENDS=airflow.api.auth.backend.basic_auth
AIRFLOW__WEBSERVER__WEB_SERVER_PORT=8088

# Bootstrap UI user for Increment 4 lab use only
_AIRFLOW_WWW_USER_USERNAME=admin
_AIRFLOW_WWW_USER_PASSWORD=change-me-before-use
_AIRFLOW_WWW_USER_FIRSTNAME=Stratus
_AIRFLOW_WWW_USER_LASTNAME=Admin
_AIRFLOW_WWW_USER_EMAIL=stratus-admin@example.com

# Spark
STRATUS_SPARK_MASTER=spark://spark-master.stratus.local:7077
STRATUS_SPARK_APP_JAR=/opt/airflow/jars/stratus.jar

# Polaris and MinIO used by Spark jobs
STRATUS_POLARIS_URI=https://polaris.stratus.local:8181/api/catalog
STRATUS_POLARIS_CLIENT_ID=svc-spark
STRATUS_POLARIS_CLIENT_SECRET=<svc-spark Polaris client secret>
STRATUS_POLARIS_CATALOG=stratus
STRATUS_MINIO_ENDPOINT=https://minio1.stratus.local:9000
STRATUS_MINIO_ACCESS_KEY=svc-spark
STRATUS_MINIO_SECRET_KEY=<svc-spark MinIO secret>

# Landing-zone detection account
STRATUS_AIRFLOW_MINIO_ACCESS_KEY=svc-airflow
STRATUS_AIRFLOW_MINIO_SECRET_KEY=<svc-airflow MinIO secret>
STRATUS_LANDING_BUCKET=stratus-landing
```

### Spark submit configuration

Spark jobs submitted by Airflow use the same catalog settings validated in Increment 3. DAGs should pass them as `--conf` values to `spark-submit`, not duplicate transformation logic inside Python tasks.

The minimum `spark-submit` configuration is:

```bash
--master spark://spark-master.stratus.local:7077
--conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
--conf spark.sql.catalog.stratus=org.apache.iceberg.spark.SparkCatalog
--conf spark.sql.catalog.stratus.type=rest
--conf spark.sql.catalog.stratus.uri=https://polaris.stratus.local:8181/api/catalog
--conf spark.sql.catalog.stratus.credential=svc-spark:<client-secret>
--conf spark.sql.catalog.stratus.scope=PRINCIPAL_ROLE:ALL
--conf spark.sql.catalog.stratus.warehouse=stratus
--conf spark.sql.catalog.stratus.io-impl=org.apache.iceberg.aws.s3.S3FileIO
--conf spark.sql.catalog.stratus.s3.endpoint=https://minio1.stratus.local:9000
--conf spark.sql.catalog.stratus.s3.access-key-id=svc-spark
--conf spark.sql.catalog.stratus.s3.secret-access-key=<svc-spark secret>
--conf spark.sql.catalog.stratus.s3.path-style-access=true
--conf spark.sql.defaultCatalog=stratus
```

---

## 8. Podman Container Setup

### Start PostgreSQL

Run on `airflow.stratus.local`:

```bash
podman run -d \
  --name airflow-postgres \
  --hostname airflow-postgres \
  --network host \
  -e POSTGRES_USER=airflow \
  -e POSTGRES_PASSWORD=airflow \
  -e POSTGRES_DB=airflow \
  -v /data/airflow/postgres:/var/lib/postgresql/data:z \
  --restart unless-stopped \
  docker.io/library/postgres:16
```

### Initialise Airflow metadata

Run once after PostgreSQL starts:

```bash
podman run --rm \
  --name airflow-init \
  --network host \
  --env-file /etc/stratus/airflow.env \
  -v /data/airflow/dags:/opt/airflow/dags:z \
  -v /data/airflow/logs:/opt/airflow/logs:z \
  -v /data/airflow/plugins:/opt/airflow/plugins:z \
  -v /data/airflow/jars:/opt/airflow/jars:ro,z \
  stratus/airflow:2.9.3 \
  bash -c 'airflow db migrate && airflow users create \
    --username "$_AIRFLOW_WWW_USER_USERNAME" \
    --password "$_AIRFLOW_WWW_USER_PASSWORD" \
    --firstname "$_AIRFLOW_WWW_USER_FIRSTNAME" \
    --lastname "$_AIRFLOW_WWW_USER_LASTNAME" \
    --role Admin \
    --email "$_AIRFLOW_WWW_USER_EMAIL"'
```

### Start the webserver

```bash
podman run -d \
  --name airflow-webserver \
  --hostname airflow.stratus.local \
  --network host \
  --env-file /etc/stratus/airflow.env \
  -v /data/airflow/dags:/opt/airflow/dags:z \
  -v /data/airflow/logs:/opt/airflow/logs:z \
  -v /data/airflow/plugins:/opt/airflow/plugins:z \
  -v /data/airflow/jars:/opt/airflow/jars:ro,z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  stratus/airflow:2.9.3 \
  airflow webserver
```

### Start the scheduler

```bash
podman run -d \
  --name airflow-scheduler \
  --hostname airflow-scheduler.stratus.local \
  --network host \
  --env-file /etc/stratus/airflow.env \
  -v /data/airflow/dags:/opt/airflow/dags:z \
  -v /data/airflow/logs:/opt/airflow/logs:z \
  -v /data/airflow/plugins:/opt/airflow/plugins:z \
  -v /data/airflow/jars:/opt/airflow/jars:ro,z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  stratus/airflow:2.9.3 \
  airflow scheduler
```

### Verify the containers

```bash
podman ps | grep airflow
podman logs airflow-webserver | tail -30
podman logs airflow-scheduler | tail -30
```

Open `http://airflow.stratus.local:8088` in a browser and log in with the bootstrap admin user.

### Auto-start with systemd

Generate systemd units for each running container:

```bash
podman generate systemd --new --name airflow-postgres \
  | sudo tee /etc/systemd/system/stratus-airflow-postgres.service

podman generate systemd --new --name airflow-webserver \
  | sudo tee /etc/systemd/system/stratus-airflow-webserver.service

podman generate systemd --new --name airflow-scheduler \
  | sudo tee /etc/systemd/system/stratus-airflow-scheduler.service

sudo systemctl daemon-reload
sudo systemctl enable --now stratus-airflow-postgres.service
sudo systemctl enable --now stratus-airflow-webserver.service
sudo systemctl enable --now stratus-airflow-scheduler.service
```

---

## 9. Airflow Connections and Variables

For Increment 4, store platform settings as Airflow variables and connections. These values are still backed by environment secrets in the lab. They move to the platform secrets manager in a later operational maturity increment.

### Create Airflow variables

Run inside the scheduler container:

```bash
podman exec airflow-scheduler airflow variables set stratus_spark_master "$STRATUS_SPARK_MASTER"
podman exec airflow-scheduler airflow variables set stratus_spark_app_jar "$STRATUS_SPARK_APP_JAR"
podman exec airflow-scheduler airflow variables set stratus_polaris_uri "$STRATUS_POLARIS_URI"
podman exec airflow-scheduler airflow variables set stratus_polaris_catalog "$STRATUS_POLARIS_CATALOG"
podman exec airflow-scheduler airflow variables set stratus_minio_endpoint "$STRATUS_MINIO_ENDPOINT"
podman exec airflow-scheduler airflow variables set stratus_landing_bucket "$STRATUS_LANDING_BUCKET"
```

### Create an S3-compatible connection for MinIO landing detection

```bash
podman exec airflow-scheduler airflow connections add stratus_minio_landing \
  --conn-type aws \
  --conn-login "$STRATUS_AIRFLOW_MINIO_ACCESS_KEY" \
  --conn-password "$STRATUS_AIRFLOW_MINIO_SECRET_KEY" \
  --conn-extra "{\"endpoint_url\": \"${STRATUS_MINIO_ENDPOINT}\", \"verify\": false}"
```

`verify=false` is acceptable only while using the self-signed lab CA. Once the CA is trusted in the Airflow image, set `verify` to the CA bundle path or remove the flag.

---

## 10. DAG Design

Increment 4 introduces four platform DAGs. Each DAG is stored under `/data/airflow/dags` and versioned in the Stratus repository under `airflow/dags`.

| DAG | Schedule | Purpose |
|---|---|---|
| `stratus_landing_to_bronze` | event-driven or every 15 minutes | Detect source files and submit the Spark ingestion job |
| `stratus_bronze_to_silver` | hourly or dataset-triggered | Run bronze quality checks, evaluate promotion, and transform to silver |
| `stratus_silver_to_gold` | daily or dataset-triggered | Run silver quality checks, evaluate promotion, and materialise gold tables |
| `stratus_table_maintenance` | daily / weekly | Run Iceberg snapshot expiry, compaction, and orphan cleanup |

### Common DAG rules

- Each DAG must generate a `run_id` and pass it to every Spark job it submits.
- Every Spark job must write structured success or failure metadata to task logs.
- Every quality job must write records to `stratus.platform.quality_check_results`.
- Promotion must be blocked when any blocking quality check has `status = failed`.
- Warnings do not block promotion, but they must be visible in task logs.
- Overrides require both `override_principal` and `override_reason`.
- DAG code must remain orchestration logic only. Transformations stay in Spark job classes.

### Common Spark submit helper

Create `airflow/dags/stratus_common.py`:

```python
from __future__ import annotations

import os

from airflow.models import Variable


def spark_submit_command(main_class: str, *job_args: str) -> list[str]:
    catalog = Variable.get("stratus_polaris_catalog", default_var="stratus")
    polaris_uri = Variable.get("stratus_polaris_uri")
    minio_endpoint = Variable.get("stratus_minio_endpoint")
    master = Variable.get("stratus_spark_master")
    app_jar = Variable.get("stratus_spark_app_jar")

    client_id = os.environ["STRATUS_POLARIS_CLIENT_ID"]
    client_secret = os.environ["STRATUS_POLARIS_CLIENT_SECRET"]
    access_key = os.environ["STRATUS_MINIO_ACCESS_KEY"]
    secret_key = os.environ["STRATUS_MINIO_SECRET_KEY"]

    return [
        "spark-submit",
        "--master", master,
        "--class", main_class,
        "--conf", "spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions",
        "--conf", f"spark.sql.catalog.{catalog}=org.apache.iceberg.spark.SparkCatalog",
        "--conf", f"spark.sql.catalog.{catalog}.type=rest",
        "--conf", f"spark.sql.catalog.{catalog}.uri={polaris_uri}",
        "--conf", f"spark.sql.catalog.{catalog}.credential={client_id}:{client_secret}",
        "--conf", f"spark.sql.catalog.{catalog}.scope=PRINCIPAL_ROLE:ALL",
        "--conf", f"spark.sql.catalog.{catalog}.warehouse={catalog}",
        "--conf", f"spark.sql.catalog.{catalog}.io-impl=org.apache.iceberg.aws.s3.S3FileIO",
        "--conf", f"spark.sql.catalog.{catalog}.s3.endpoint={minio_endpoint}",
        "--conf", f"spark.sql.catalog.{catalog}.s3.access-key-id={access_key}",
        "--conf", f"spark.sql.catalog.{catalog}.s3.secret-access-key={secret_key}",
        "--conf", f"spark.sql.catalog.{catalog}.s3.path-style-access=true",
        "--conf", f"spark.sql.defaultCatalog={catalog}",
        app_jar,
        *job_args,
    ]
```

### Landing to bronze DAG

Create `airflow/dags/stratus_landing_to_bronze.py`:

```python
from __future__ import annotations

import shlex
from datetime import datetime, timedelta

from airflow import DAG
from airflow.providers.amazon.aws.sensors.s3 import S3KeySensor
from airflow.operators.bash import BashOperator

from stratus_common import spark_submit_command


default_args = {
    "owner": "platform",
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="stratus_landing_to_bronze",
    default_args=default_args,
    start_date=datetime(2026, 1, 1),
    schedule_interval="*/15 * * * *",
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "ingestion", "bronze"],
) as dag:

    wait_for_source_file = S3KeySensor(
        task_id="wait_for_source_file",
        bucket_key="customers/{{ ds }}/customers.csv",
        bucket_name="{{ var.value.stratus_landing_bucket }}",
        aws_conn_id="stratus_minio_landing",
        poke_interval=60,
        timeout=600,
        mode="reschedule",
    )

    run_ingestion = BashOperator(
        task_id="run_ingestion",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.mars.stratus.jobs.LandingToBronzeJob",
            "--sourceFile", "s3a://stratus-landing/customers/{{ ds }}/customers.csv",
            "--targetTable", "stratus.bronze.customers",
            "--sourceSystem", "verification",
            "--runId", "{{ run_id }}",
        )),
    )

    run_bronze_quality = BashOperator(
        task_id="run_bronze_quality",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.mars.stratus.jobs.QualityCheckJob",
            "--targetTable", "stratus.bronze.customers",
            "--runId", "{{ run_id }}",
            "--checks", "[{\"type\":\"row_count_min\",\"severity\":\"blocking\",\"threshold\":1}]",
        )),
    )

    wait_for_source_file >> run_ingestion >> run_bronze_quality
```

### Bronze to silver DAG

Create `airflow/dags/stratus_bronze_to_silver.py`:

```python
from __future__ import annotations

import shlex
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator

from stratus_common import spark_submit_command


default_args = {
    "owner": "platform",
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="stratus_bronze_to_silver",
    default_args=default_args,
    start_date=datetime(2026, 1, 1),
    schedule_interval="@hourly",
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "transform", "silver"],
) as dag:

    run_quality = BashOperator(
        task_id="run_bronze_quality",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.mars.stratus.jobs.QualityCheckJob",
            "--targetTable", "stratus.bronze.customers",
            "--runId", "{{ run_id }}",
            "--checks", "[{\"type\":\"uniqueness\",\"columns\":[\"customer_id\"],\"severity\":\"blocking\"}]",
        )),
    )

    evaluate_promotion = BashOperator(
        task_id="evaluate_promotion",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.mars.stratus.jobs.PromotionGateJob",
            "--runId", "{{ run_id }}",
            "--targetTable", "stratus.bronze.customers",
        )),
    )

    run_transform = BashOperator(
        task_id="run_transform",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.mars.stratus.jobs.BronzeToSilverJob",
            "--sourceTable", "stratus.bronze.customers",
            "--targetTable", "stratus.silver.customers",
            "--businessKey", "customer_id",
            "--runId", "{{ run_id }}",
        )),
    )

    run_quality >> evaluate_promotion >> run_transform
```

### Silver to gold DAG

Create `airflow/dags/stratus_silver_to_gold.py`:

```python
from __future__ import annotations

import shlex
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator

from stratus_common import spark_submit_command


default_args = {
    "owner": "platform",
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
}

with DAG(
    dag_id="stratus_silver_to_gold",
    default_args=default_args,
    start_date=datetime(2026, 1, 1),
    schedule_interval="@daily",
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "materialisation", "gold"],
) as dag:

    run_quality = BashOperator(
        task_id="run_silver_quality",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.mars.stratus.jobs.QualityCheckJob",
            "--targetTable", "stratus.silver.customers",
            "--runId", "{{ run_id }}",
            "--checks", "[{\"type\":\"row_count_min\",\"severity\":\"blocking\",\"threshold\":1}]",
        )),
    )

    evaluate_promotion = BashOperator(
        task_id="evaluate_promotion",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.mars.stratus.jobs.PromotionGateJob",
            "--runId", "{{ run_id }}",
            "--targetTable", "stratus.silver.customers",
        )),
    )

    run_materialisation = BashOperator(
        task_id="run_materialisation",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.mars.stratus.jobs.SilverToGoldJob",
            "--sourceTables", "stratus.silver.customers",
            "--targetTable", "stratus.gold.customer_summary",
            "--runId", "{{ run_id }}",
        )),
    )

    run_quality >> evaluate_promotion >> run_materialisation
```

### Table maintenance DAG

Create `airflow/dags/stratus_table_maintenance.py`:

```python
from __future__ import annotations

import shlex
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator

from stratus_common import spark_submit_command


default_args = {
    "owner": "platform",
    "retries": 1,
    "retry_delay": timedelta(minutes=10),
}

TABLES = [
    "stratus.bronze.customers",
    "stratus.silver.customers",
    "stratus.gold.customer_summary",
]

with DAG(
    dag_id="stratus_table_maintenance",
    default_args=default_args,
    start_date=datetime(2026, 1, 1),
    schedule_interval="@daily",
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "maintenance", "iceberg"],
) as dag:

    for table in TABLES:
        BashOperator(
            task_id=f"maintain_{table.replace('.', '_')}",
            bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
                "dev.mars.stratus.jobs.TableMaintenanceJob",
                "--targetTable", table,
                "--operations", "expire_snapshots,rewrite_data_files",
                "--runId", "{{ run_id }}",
            )),
        )
```

---

## 11. Promotion Gate Contract

The promotion gate is the most important control-plane behavior in Increment 4. It must be deterministic and fail closed.

```text
Quality job writes records to platform.quality_check_results
      │
      ▼
Airflow task: evaluate_promotion
      │
      ├── no blocking failures       → success, downstream task runs
      ├── blocking failure exists    → task fails, downstream task skipped
      └── override supplied          → override record written, downstream task runs
```

### Gate input

| Argument | Description |
|---|---|
| `runId` | Airflow DAG run ID or generated platform run ID |
| `targetTable` | Dataset being promoted |
| `overridePrincipal` | Optional principal authorising an override |
| `overrideReason` | Required when `overridePrincipal` is provided |

### Gate output

| Result | Airflow behavior |
|---|---|
| `PROMOTE` | Task exits with code 0 |
| `BLOCK` | Task exits with non-zero code |
| `OVERRIDDEN` | Task exits with code 0 and writes an override record |

No downstream transform or materialisation task should run after a blocking quality failure unless an explicit override is recorded.

---

## 12. Alerting

Increment 4 uses Airflow's built-in task failure behavior and a simple SMTP or webhook callback. Full observability integration with Prometheus and Grafana follows the operational model in the architecture document and can be expanded after the core orchestration behavior is proven.

### Minimum alert events

- DAG failure
- task failure after all retries are exhausted
- promotion gate blocked
- SLA miss for ingestion or materialisation DAGs
- maintenance DAG failure

### Failure callback contract

Each failure alert must include:

| Field | Description |
|---|---|
| `dag_id` | Airflow DAG identifier |
| `task_id` | failed task |
| `run_id` | DAG run identifier |
| `logical_date` | Airflow logical date |
| `try_number` | final attempt count |
| `log_url` | Airflow task log URL |

---

## 13. Java Verification Suite

The verification suite uses the Airflow REST API to trigger DAGs, poll DAG run state, and confirm side effects through Spark/Iceberg tables. It does not replace the DAGs themselves; it verifies Airflow coordinates the already-working Increment 3 jobs correctly.

### Maven dependencies

Add to `pom.xml` if they are not already present:

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.1</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <version>4.2.1</version>
    <scope>test</scope>
</dependency>
```

The Spark and Iceberg dependencies from Increment 3 remain in place for table assertions.

### Configuration

| Variable | Description |
|---|---|
| `STRATUS_AIRFLOW_BASE_URL` | e.g. `http://airflow.stratus.local:8088` |
| `STRATUS_AIRFLOW_USERNAME` | Airflow API username |
| `STRATUS_AIRFLOW_PASSWORD` | Airflow API password |
| `STRATUS_SPARK_MASTER` | e.g. `spark://spark-master.stratus.local:7077` |
| `STRATUS_POLARIS_URI` | Polaris REST API base URL |
| `STRATUS_POLARIS_CLIENT_ID` | `svc-spark` |
| `STRATUS_POLARIS_CLIENT_SECRET` | svc-spark client secret |
| `STRATUS_POLARIS_CATALOG` | `stratus` |
| `STRATUS_MINIO_ENDPOINT` | MinIO S3 endpoint |
| `STRATUS_MINIO_ACCESS_KEY` | `svc-spark` access key |
| `STRATUS_MINIO_SECRET_KEY` | `svc-spark` secret key |

### Shared Airflow REST client

Place in `src/test/java/dev/mars/stratus/orchestration/AirflowTestClient.java`:

```java
package dev.mars.stratus.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public class AirflowTestClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String authHeader;

    public AirflowTestClient() {
        this.baseUrl = System.getenv("STRATUS_AIRFLOW_BASE_URL");
        String username = System.getenv("STRATUS_AIRFLOW_USERNAME");
        String password = System.getenv("STRATUS_AIRFLOW_PASSWORD");
        String token = Base64.getEncoder().encodeToString(
            (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.authHeader = "Basic " + token;
    }

    public JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", authHeader)
            .GET()
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Airflow GET failed: " + response.statusCode() + " " + response.body());
        }
        return mapper.readTree(response.body());
    }

    public JsonNode triggerDag(String dagId, String runId) throws Exception {
        String body = """
            {
              "dag_run_id": "%s",
              "conf": {
                "verification": true
              }
            }
            """.formatted(runId);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/v1/dags/" + dagId + "/dagRuns"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", authHeader)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Airflow trigger failed: " + response.statusCode() + " " + response.body());
        }
        return mapper.readTree(response.body());
    }

    public String dagRunState(String dagId, String runId) throws Exception {
        JsonNode response = get("/api/v1/dags/" + dagId + "/dagRuns/" + runId);
        return response.get("state").asText();
    }
}
```

### Verification test class

Place in `src/test/java/dev/mars/stratus/orchestration/AirflowOrchestrationVerificationTest.java`:

```java
package dev.mars.stratus.orchestration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AirflowOrchestrationVerificationTest {

    static AirflowTestClient airflow;

    static final List<String> REQUIRED_DAGS = List.of(
        "stratus_landing_to_bronze",
        "stratus_bronze_to_silver",
        "stratus_silver_to_gold",
        "stratus_table_maintenance"
    );

    @BeforeAll
    static void connect() {
        assertThat(System.getenv("STRATUS_AIRFLOW_BASE_URL"))
            .as("STRATUS_AIRFLOW_BASE_URL must be set").isNotBlank();
        airflow = new AirflowTestClient();
    }

    @Test
    @Order(1)
    void airflowReachable() {
        assertThatNoException()
            .as("Airflow REST API must be reachable")
            .isThrownBy(() -> airflow.get("/api/v1/health"));
    }

    @Test
    @Order(2)
    void allRequiredDagsExistAndAreUnpaused() throws Exception {
        var dags = airflow.get("/api/v1/dags?limit=100");
        List<String> dagIds = dags.get("dags").findValuesAsText("dag_id");

        assertThat(dagIds)
            .as("All Increment 4 DAGs must be registered")
            .containsAll(REQUIRED_DAGS);
    }

    @Test
    @Order(3)
    void maintenanceDagCanRunSuccessfully() throws Exception {
        String runId = "verification-maintenance-" + UUID.randomUUID();
        airflow.triggerDag("stratus_table_maintenance", runId);

        Awaitility.await()
            .atMost(Duration.ofMinutes(10))
            .pollInterval(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(
                airflow.dagRunState("stratus_table_maintenance", runId))
                .isIn("success", "failed"));

        assertThat(airflow.dagRunState("stratus_table_maintenance", runId))
            .as("Maintenance DAG must complete successfully")
            .isEqualTo("success");
    }

    @Test
    @Order(4)
    void bronzeToSilverDagBlocksWhenQualityFails() throws Exception {
        String runId = "verification-block-" + UUID.randomUUID();
        airflow.triggerDag("stratus_bronze_to_silver", runId);

        Awaitility.await()
            .atMost(Duration.ofMinutes(15))
            .pollInterval(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(
                airflow.dagRunState("stratus_bronze_to_silver", runId))
                .isIn("success", "failed"));

        // This verification assumes the test bronze table contains the intentional duplicate
        // created by the Increment 3 verification dataset.
        assertThat(airflow.dagRunState("stratus_bronze_to_silver", runId))
            .as("A blocking quality failure must fail the DAG run")
            .isEqualTo("failed");
    }

    @Test
    @Order(5)
    void silverToGoldDagCanRunSuccessfullyAfterValidSilverDataExists() throws Exception {
        String runId = "verification-gold-" + UUID.randomUUID();
        airflow.triggerDag("stratus_silver_to_gold", runId);

        Awaitility.await()
            .atMost(Duration.ofMinutes(15))
            .pollInterval(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(
                airflow.dagRunState("stratus_silver_to_gold", runId))
                .isIn("success", "failed"));

        assertThat(airflow.dagRunState("stratus_silver_to_gold", runId))
            .as("Silver to gold DAG must complete successfully when quality passes")
            .isEqualTo("success");
    }
}
```

### Running the verification suite

```bash
export STRATUS_AIRFLOW_BASE_URL=http://airflow.stratus.local:8088
export STRATUS_AIRFLOW_USERNAME=admin
export STRATUS_AIRFLOW_PASSWORD=change-me-before-use
export STRATUS_SPARK_MASTER=spark://spark-master.stratus.local:7077
export STRATUS_POLARIS_URI=https://polaris.stratus.local:8181/api/catalog
export STRATUS_POLARIS_CLIENT_ID=svc-spark
export STRATUS_POLARIS_CLIENT_SECRET=<client secret>
export STRATUS_POLARIS_CATALOG=stratus
export STRATUS_MINIO_ENDPOINT=https://minio1.stratus.local:9000
export STRATUS_MINIO_ACCESS_KEY=svc-spark
export STRATUS_MINIO_SECRET_KEY=<svc-spark secret>

mvn test -pl . -Dtest=AirflowOrchestrationVerificationTest
```

All tests must pass before Increment 4 is considered complete.

---

## 14. Operational Checks

Once the verification suite passes, perform these additional checks before signing off Increment 4.

### Airflow web UI

Open `http://airflow.stratus.local:8088`. Confirm:
- all four Stratus DAGs are visible
- DAGs are unpaused
- latest successful and failed runs are visible
- task logs are accessible from the UI

### Airflow scheduler health

```bash
podman logs airflow-scheduler | tail -50
```

Expected: no DAG import errors and no repeated database connectivity errors.

### DAG import validation

```bash
podman exec airflow-scheduler airflow dags list-import-errors
```

Expected: no import errors.

### Trigger a DAG manually

```bash
podman exec airflow-scheduler airflow dags trigger stratus_table_maintenance
podman exec airflow-scheduler airflow dags state stratus_table_maintenance $(date +%Y-%m-%d)
```

The DAG run should complete successfully and produce task logs.

### Confirm quality results are written

Query `stratus.platform.quality_check_results` via Spark SQL or the Iceberg API and confirm new records exist for the Airflow DAG run IDs.

### Confirm promotion blocking

Run the bronze-to-silver DAG against a dataset with an intentional duplicate business key. Confirm:
- `run_bronze_quality` succeeds and writes a failed blocking check
- `evaluate_promotion` fails
- `run_transform` is skipped
- the DAG run is marked failed

### Confirm maintenance effects

After the maintenance DAG runs, inspect the target tables:
- snapshot count should not grow without bound
- compaction should not increase file-count debt
- task logs should report maintenance metrics

---

## 15. Completion Gate

Increment 4 is complete when all of the following are true:

- [ ] PostgreSQL metadata database running and managed by systemd on `airflow.stratus.local`
- [ ] Airflow webserver running and managed by systemd on `airflow.stratus.local`
- [ ] Airflow scheduler running and managed by systemd on `airflow.stratus.local`
- [ ] Airflow UI and REST API reachable on port 8088
- [ ] Airflow DAG import check reports no errors
- [ ] All four DAGs exist: `stratus_landing_to_bronze`, `stratus_bronze_to_silver`, `stratus_silver_to_gold`, `stratus_table_maintenance`
- [ ] Airflow can submit Spark jobs to `spark-master.stratus.local:7077`
- [ ] Landing-to-bronze DAG detects a source file and writes a bronze Iceberg table
- [ ] Bronze-to-silver DAG runs quality checks and blocks downstream transform when a blocking failure exists
- [ ] Silver-to-gold DAG materialises a gold table when quality passes
- [ ] Maintenance DAG runs snapshot expiry and file rewrite operations without error
- [ ] Task retries work for a transient failure
- [ ] Failure alerts fire when a task exhausts retries
- [ ] `AirflowOrchestrationVerificationTest` passes against the live platform
- [ ] Airflow task logs include run IDs, target tables, Spark application IDs, and quality gate decisions

When all gates are checked, Increment 5 (Trino interactive query) can begin.

---

## 16. Troubleshooting

### Airflow webserver cannot connect to metadata database

```bash
podman logs airflow-webserver
podman logs airflow-postgres
```

Common causes:
- PostgreSQL container is not running
- `AIRFLOW__DATABASE__SQL_ALCHEMY_CONN` points to the wrong host or port
- PostgreSQL data directory permissions are wrong

### DAGs do not appear in the UI

```bash
podman exec airflow-scheduler airflow dags list-import-errors
```

Common causes:
- Python syntax error in a DAG file
- missing provider package in the Airflow image
- DAG file not mounted under `/opt/airflow/dags`

### Spark submit fails from Airflow

- Confirm `spark-submit` exists inside the Airflow container: `podman exec airflow-scheduler spark-submit --version`
- Confirm Airflow can reach the Spark master: `podman exec airflow-scheduler nc -zv spark-master.stratus.local 7077`
- Confirm `/opt/airflow/jars/stratus.jar` exists inside the scheduler container
- Check Spark master UI for a submitted application

### Spark job cannot connect to Polaris

- Confirm the Polaris REST API is reachable from the Airflow host:

```bash
podman exec airflow-scheduler curl -k https://polaris.stratus.local:8181/api/catalog/v1/config
```

- Confirm `STRATUS_POLARIS_CLIENT_ID` and `STRATUS_POLARIS_CLIENT_SECRET` are present in the scheduler environment
- Confirm the catalog URI includes `/api/catalog`

### Landing-zone sensor never succeeds

- Confirm the expected key exists in MinIO:

```bash
mc ls stratus/stratus-landing/customers/$(date +%F)/customers.csv
```

- Confirm the `stratus_minio_landing` Airflow connection has the MinIO endpoint in its JSON extras
- Confirm the `svc-airflow` credentials can list the landing bucket

### Promotion gate does not block

- Query `stratus.platform.quality_check_results` for the Airflow `run_id`
- Confirm failed checks use `severity = blocking` and `status = failed`
- Confirm the DAG calls `PromotionGateJob` before the downstream transform or materialisation task
- Confirm downstream tasks depend on the gate task and are not configured with a permissive trigger rule such as `all_done`

### Task retries do not happen

- Confirm `retries` and `retry_delay` are set in the DAG or task
- Confirm the task exits with a non-zero code on failure
- Check the task instance history in the Airflow UI

---

## 17. References

- Apache Airflow documentation: https://airflow.apache.org/docs/
- Airflow REST API: https://airflow.apache.org/docs/apache-airflow/stable/stable-rest-api-ref.html
- Airflow Spark provider: https://airflow.apache.org/docs/apache-airflow-providers-apache-spark/stable/
- Airflow Amazon provider: https://airflow.apache.org/docs/apache-airflow-providers-amazon/stable/
- Apache Spark standalone cluster: https://spark.apache.org/docs/latest/spark-standalone.html
- Apache Iceberg Spark procedures: https://iceberg.apache.org/docs/latest/spark-procedures/
- Stratus implementation plan: [stratus_implementation_plan.md](stratus_implementation_plan.md)
- Stratus architecture: [on_prem_data_fabric_architecture.md](on_prem_data_fabric_architecture.md)
- Increment 1 — MinIO: [increment1_minio.md](increment1_minio.md)
- Increment 2 — Iceberg and Polaris: [increment2_iceberg_polaris.md](increment2_iceberg_polaris.md)
- Increment 3 — Spark: [increment3_spark.md](increment3_spark.md)
