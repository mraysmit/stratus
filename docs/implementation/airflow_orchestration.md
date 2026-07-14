# Stratus Increment 4 — Apache Airflow Orchestration

## 1. Purpose

This document is the technical implementation plan for Increment 4 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 4 delivers Apache Airflow as the orchestration and control-plane layer for the batch platform created in Increment 3. Airflow schedules and coordinates Spark jobs, data quality checks, promotion gates, and Iceberg table maintenance. When this increment is complete, the bronze, silver, and gold pipeline runs as managed DAGs with retries, failure visibility, structured run metadata, and deterministic promotion blocking. A Java verification suite uses the Airflow REST API and the table layer to confirm the orchestration layer works end to end.

**Prerequisites:**
- Increment 1 complete — Ceph RGW cluster running, all buckets and service accounts in place
- Increment 2 complete — Polaris running, all namespaces and the `platform.quality_check_results` table created, all Increment 2 gate tests passing
- Increment 3 complete — Spark cluster running, Spark jobs implemented, all Increment 3 gate tests passing

**Track rule:** Developer work requires the developer gates of Increments 1-3. Increment 4 production acceptance requires their production gates, except final security-dependent checks close after Increment 7 as defined by the Phase 1 plan.

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 5.8.2 installed on the Airflow host, or a newer approved stable patch after regression testing
- JDK 25 and Maven 3.9.16 on the approved build worker; development hosts may use the same toolchain, while verification hosts require only the approved container runtime and verifier runtime inputs
- DNS resolution: `airflow.stratus.local` resolves to the Airflow host
- Airflow host can reach:
  - Spark master on port 7077
  - Spark master web UI on port 8080
  - Ceph RGW on port 443
  - Polaris on port 8181
- `svc-airflow` Ceph RGW credentials from Increment 1 are available
- `svc-spark` Ceph RGW credentials and Polaris principal credentials from Increment 3 are available
- The Stratus application fat JAR from Increment 3 is available to the Airflow runtime

### Reference documentation audit

Reference baseline: 2026-07-10.

The current Apache Airflow stable documentation line is Airflow 3.x. Stratus standardizes this increment on Airflow 3.3.0 with Python 3.14 and uses the Airflow 3 service split: API server, DAG processor, scheduler, triggerer, init task, and PostgreSQL metadata database. The Spark 6.2.0 and Amazon 9.31.0 providers support this Python line. The custom image also targets the current stable Spark 4.1 client. This avoids carrying an obsolete Airflow 2.x webserver topology, stale Spark 3.x client, or unnecessarily old Python runtime into the design.

Airflow 3.3.0 is tested with PostgreSQL 13-17. Stratus therefore pins PostgreSQL 17.10, the latest patch in the newest supported major, rather than using PostgreSQL 18 before Airflow adds it to the tested support matrix.

The single-host Podman commands are the developer profile. Production uses the same DAGs, providers, images, public API, and verification suite, but requires an external durable metadata database, durable remote logs, managed secrets, trusted HTTPS/OIDC, scheduler and DAG-processor availability appropriate to the selected executor, backup/restore, and failure drills. Local volumes, bootstrap credentials, and a one-host service split cannot pass the production gate.

Airflow's official Docker Compose quickstart is not a production deployment recommendation. The Podman commands here are a lab topology and must be hardened before production use.

---

## 3. Topology

Airflow runs on a dedicated host using Podman containers. PostgreSQL is used as the Airflow metadata database. For Increment 4, Airflow uses `LocalExecutor` with one API server, one DAG processor, one scheduler, and one triggerer. A distributed Celery or Kubernetes executor is deferred until operational scale requires it.

```text
airflow.stratus.local
┌──────────────────────────────────────────────┐
│  Podman: airflow-api-server                  │
│  Airflow UI / REST API :8088                 │
├──────────────────────────────────────────────┤
│  Podman: airflow-dag-processor               │
│  DAG parsing                                 │
├──────────────────────────────────────────────┤
│  Podman: airflow-scheduler                   │
│  Scheduling and task execution               │
├──────────────────────────────────────────────┤
│  Podman: airflow-triggerer                   │
│  Deferrable task triggers                    │
├──────────────────────────────────────────────┤
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
Spark workers → Polaris REST catalog → Ceph RGW Iceberg tables
```

Airflow is not a compute engine. DAG tasks submit Spark jobs, run lightweight control-plane checks, evaluate promotion gates, and schedule maintenance. Heavy transformations remain in Spark.

### Production profile overlay

| Concern | Production requirement |
|---|---|
| Metadata state | external PostgreSQL service over TLS with a scoped Airflow database role, monitored storage, backups, point-in-time recovery where required, and a tested restore before upgrade |
| Service placement | API server, DAG processor, scheduler, and triggerer run as separately managed services; each has a documented restart policy and the selected executor has an accepted availability/RTO design |
| Executor | retain `LocalExecutor` for the initial control-plane workload only when capacity and host-recovery evidence support it; move to a distributed executor through a separate dependency and capacity decision, not an undocumented configuration toggle |
| DAG delivery | immutable DAG revision is delivered by the build/release process; production services do not mount a developer working tree |
| Task logs | remote logs are written to `s3://stratus-platform/airflow/logs/` through the Ceph RGW connection and remain readable after loss of an Airflow host |
| User/API ingress | trusted HTTPS with Keycloak-backed Airflow 3 auth-manager integration; internal port `8088` is not directly exposed to users |
| Secrets | connections, variables, database credentials, OIDC secrets, Ceph RGW credentials, and Spark submission credentials use the approved secret backend/injection path and rotation procedure |
| Downstream protocols | `spark-submit` to Spark's internal master endpoint, Iceberg REST over HTTPS to Polaris, and S3-compatible HTTPS to Ceph RGW; trust validation is never disabled |

The production environment records at least these effective settings, with secret values omitted from evidence:

```bash
AIRFLOW__CORE__EXECUTOR=LocalExecutor
AIRFLOW__DATABASE__SQL_ALCHEMY_CONN=<TLS PostgreSQL URI from secret injection>
AIRFLOW__LOGGING__REMOTE_LOGGING=True
AIRFLOW__LOGGING__REMOTE_BASE_LOG_FOLDER=s3://stratus-platform/airflow/logs
AIRFLOW__LOGGING__REMOTE_LOG_CONN_ID=ceph_rgw_logs
```

Increment 7 supplies the production certificate and OIDC configuration. The Increment 4 production gate closes only after metadata restore, remote-log continuity, authenticated API access, scheduler/DAG-processor restart, and the unchanged DAG verification suite pass on this overlay.

---

## 4. Ports

| Port | Service | Purpose |
|---|---|---|
| 8088 | Airflow API server | Airflow UI and REST API |
| 5432 | PostgreSQL | Airflow metadata database, local host access only where possible |

The Airflow host must also be able to reach the ports from previous increments:

| Port | Service | Purpose |
|---|---|---|
| 7077 | Spark master | Spark job submission |
| 8080 | Spark master UI | Operational verification |
| 443 | Ceph RGW | Landing-zone detection and Spark object storage access |
| 8181 | Polaris | Spark Iceberg catalog access |

For Increment 4, Airflow's own endpoint may be HTTP inside the lab network. TLS and Keycloak-backed authentication are hardened in Increment 7.

---

## 5. Airflow Image

The official Airflow image is used as the base. A custom image adds Java, Spark client binaries, and the Airflow providers required for Spark and S3-compatible object storage.

### Dockerfile

Create `platform/airflow/image/Dockerfile` in the Stratus repository:

```dockerfile
ARG TEMURIN_21_IMAGE=eclipse-temurin:21-jre
ARG AIRFLOW_BASE_IMAGE=apache/airflow:3.3.0-python3.14

FROM ${TEMURIN_21_IMAGE} AS java21
FROM ${AIRFLOW_BASE_IMAGE}

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       curl \
       ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=java21 /opt/java/openjdk /opt/java/openjdk

# The build system downloads the pinned Spark distribution, verifies its
# recorded checksum, and places it in the build context before this build.
COPY artifacts/spark-4.1.2-bin-hadoop3.tgz /tmp/spark.tgz
RUN mkdir -p /opt/spark \
    && tar -xzf /tmp/spark.tgz -C /opt/spark --strip-components=1 \
    && rm /tmp/spark.tgz

ENV SPARK_HOME=/opt/spark
ENV PATH="${SPARK_HOME}/bin:${PATH}"
ENV JAVA_HOME=/opt/java/openjdk

USER airflow

RUN pip install --no-cache-dir \
    "apache-airflow-providers-apache-spark[pyspark]==6.2.0" \
    apache-airflow-providers-amazon==9.31.0 \
    boto3==1.43.40
```

The Airflow image carries Java 21 only as the Spark 4.1 client runtime because Spark 4.1 supports Java 17/21 and not Java 25. Java is copied from a Temurin JRE image rather than assumed to exist in the Airflow base distribution's package repository. The Spark provider's `pyspark` extra is explicit because provider 6.x no longer includes it by default for `spark-submit` style connections. The lock must resolve PySpark/Spark client 4.1.2 consistently with the copied Spark distribution. Stratus application and verifier builds use JDK 25; Spark-submitted job artifacts use the compatible `--release 17` target defined by Increment 3.

The build system resolves the Python packages from the approved package repository using a locked, hash-verified dependency set. The abbreviated `pip install` fragment above shows the required contents, not permission to resolve mutable dependencies on a runtime host.

### Build-system image publication

The following container build is a build-pipeline step. It runs on an approved build worker, followed by tests, scanning, registry publication, and digest recording. Do not build the image on Airflow runtime hosts.

```bash
cd docker/airflow
podman build \
  --build-arg 'AIRFLOW_BASE_IMAGE=apache/airflow:3.3.0-python3.14@sha256:<approved-digest>' \
  --build-arg 'TEMURIN_21_IMAGE=eclipse-temurin:21-jre@sha256:<approved-digest>' \
  -t stratus/airflow:3.3.0 .
```

The build pipeline resolves the current Java 21 patch image, verifies both base-image digests, and records them in the SBOM/version matrix. Floating defaults are acceptable only for local Dockerfile parsing; release builds must pass immutable digest-qualified arguments.

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

The deployment pipeline retrieves the accepted Increment 3 application JAR from the approved artifact repository, verifies its recorded checksum, and stages it at `/data/airflow/jars/stratus.jar`. It must not copy from a local `target/` directory or build on the Airflow host. The deployment record captures the artifact coordinates, checksum, source repository, and deployed path.

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
AIRFLOW__DATABASE__SQL_ALCHEMY_CONN=postgresql+psycopg2://airflow:<airflow-db-secret>@localhost:5432/airflow
AIRFLOW__API__BASE_URL=http://airflow.stratus.local:8088

# Bootstrap UI/API user for Increment 4 bootstrap only.
# Increment 7 replaces this with Keycloak-backed authentication.
_AIRFLOW_WWW_USER_USERNAME=admin
_AIRFLOW_WWW_USER_PASSWORD=<bootstrap secret from approved secret store>
_AIRFLOW_WWW_USER_FIRSTNAME=Stratus
_AIRFLOW_WWW_USER_LASTNAME=Admin
_AIRFLOW_WWW_USER_EMAIL=stratus-admin@example.com

# Spark
STRATUS_SPARK_MASTER=spark://spark-master.stratus.local:7077
STRATUS_SPARK_APP_JAR=/opt/airflow/jars/stratus.jar

# Polaris and Ceph RGW used by Spark jobs
STRATUS_POLARIS_URI=https://polaris.stratus.local:8181/api/catalog
STRATUS_POLARIS_CLIENT_ID=svc-spark
STRATUS_POLARIS_CLIENT_SECRET=<svc-spark Polaris client secret>
STRATUS_POLARIS_CATALOG=stratus
CEPH_RGW_ENDPOINT=https://object-store.stratus.local
CEPH_RGW_ACCESS_KEY=svc-spark
CEPH_RGW_SECRET_KEY=<svc-spark Ceph RGW secret>

# Landing-zone detection account
STRATUS_AIRFLOW_S3_ACCESS_KEY=svc-airflow
STRATUS_AIRFLOW_S3_SECRET_KEY=<svc-airflow Ceph RGW secret>
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
--conf spark.sql.catalog.stratus.s3.endpoint=https://object-store.stratus.local
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
export STRATUS_AIRFLOW_DB_PASSWORD=<airflow-db secret from approved secret store>

podman run -d \
  --name airflow-postgres \
  --hostname airflow-postgres \
  --network host \
  -e POSTGRES_USER=airflow \
  -e POSTGRES_PASSWORD="$STRATUS_AIRFLOW_DB_PASSWORD" \
  -e POSTGRES_DB=airflow \
  -v /data/airflow/postgres:/var/lib/postgresql/data:z \
  --restart unless-stopped \
  docker.io/library/postgres:17.10
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
  stratus/airflow:3.3.0 \
  bash -c 'airflow db migrate && airflow users create \
    --username "$_AIRFLOW_WWW_USER_USERNAME" \
    --password "$_AIRFLOW_WWW_USER_PASSWORD" \
    --firstname "$_AIRFLOW_WWW_USER_FIRSTNAME" \
    --lastname "$_AIRFLOW_WWW_USER_LASTNAME" \
    --role Admin \
    --email "$_AIRFLOW_WWW_USER_EMAIL"'
```

### Start the API server

```bash
podman run -d \
  --name airflow-api-server \
  --hostname airflow.stratus.local \
  --network host \
  --env-file /etc/stratus/airflow.env \
  -v /data/airflow/dags:/opt/airflow/dags:z \
  -v /data/airflow/logs:/opt/airflow/logs:z \
  -v /data/airflow/plugins:/opt/airflow/plugins:z \
  -v /data/airflow/jars:/opt/airflow/jars:ro,z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  stratus/airflow:3.3.0 \
  airflow api-server --port 8088
```

### Start the DAG processor

```bash
podman run -d \
  --name airflow-dag-processor \
  --hostname airflow-dag-processor.stratus.local \
  --network host \
  --env-file /etc/stratus/airflow.env \
  -v /data/airflow/dags:/opt/airflow/dags:z \
  -v /data/airflow/logs:/opt/airflow/logs:z \
  -v /data/airflow/plugins:/opt/airflow/plugins:z \
  -v /data/airflow/jars:/opt/airflow/jars:ro,z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  stratus/airflow:3.3.0 \
  airflow dag-processor
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
  stratus/airflow:3.3.0 \
  airflow scheduler
```

### Start the triggerer

```bash
podman run -d \
  --name airflow-triggerer \
  --hostname airflow-triggerer.stratus.local \
  --network host \
  --env-file /etc/stratus/airflow.env \
  -v /data/airflow/dags:/opt/airflow/dags:z \
  -v /data/airflow/logs:/opt/airflow/logs:z \
  -v /data/airflow/plugins:/opt/airflow/plugins:z \
  -v /data/airflow/jars:/opt/airflow/jars:ro,z \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  stratus/airflow:3.3.0 \
  airflow triggerer
```

### Verify the containers

```bash
podman ps | grep airflow
podman logs airflow-api-server | tail -30
podman logs airflow-dag-processor | tail -30
podman logs airflow-scheduler | tail -30
podman logs airflow-triggerer | tail -30
```

Open `http://airflow.stratus.local:8088` in a browser and log in with the bootstrap admin user.

### Auto-start with systemd

Generate systemd units for each running container:

```bash
podman generate systemd --new --name airflow-postgres \
  | sudo tee /etc/systemd/system/stratus-airflow-postgres.service

podman generate systemd --new --name airflow-api-server \
  | sudo tee /etc/systemd/system/stratus-airflow-api-server.service

podman generate systemd --new --name airflow-dag-processor \
  | sudo tee /etc/systemd/system/stratus-airflow-dag-processor.service

podman generate systemd --new --name airflow-scheduler \
  | sudo tee /etc/systemd/system/stratus-airflow-scheduler.service

podman generate systemd --new --name airflow-triggerer \
  | sudo tee /etc/systemd/system/stratus-airflow-triggerer.service

sudo systemctl daemon-reload
sudo systemctl enable --now stratus-airflow-postgres.service
sudo systemctl enable --now stratus-airflow-api-server.service
sudo systemctl enable --now stratus-airflow-dag-processor.service
sudo systemctl enable --now stratus-airflow-scheduler.service
sudo systemctl enable --now stratus-airflow-triggerer.service
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
podman exec airflow-scheduler airflow variables set ceph_rgw_endpoint "$CEPH_RGW_ENDPOINT"
podman exec airflow-scheduler airflow variables set stratus_landing_bucket "$STRATUS_LANDING_BUCKET"
```

### Create an S3-compatible connection for Ceph RGW landing detection

```bash
podman exec airflow-scheduler airflow connections add stratus_landing \
  --conn-type aws \
  --conn-login "$STRATUS_AIRFLOW_S3_ACCESS_KEY" \
  --conn-password "$STRATUS_AIRFLOW_S3_SECRET_KEY" \
  --conn-extra "{\"endpoint_url\": \"${CEPH_RGW_ENDPOINT}\", \"verify\": \"/etc/stratus/pki/stratus-ca.crt\"}"
```

The CA bundle must be mounted read-only into the Airflow containers and must validate the certificate presented by `CEPH_RGW_ENDPOINT`. Do not disable certificate verification, including during routine lab verification. Airflow's connection type and provider package retain the upstream name `aws`; that is the official S3-compatible provider API, not an AWS infrastructure dependency.

---

## 10. DAG Design

Increment 4 introduces four platform DAGs. Each DAG is stored under `/data/airflow/dags` and versioned in the Stratus repository under `airflow/dags`.

| DAG | Schedule | Purpose |
|---|---|---|
| `stratus_landing_to_bronze` | event-driven or every 15 minutes | Detect source files and submit the Spark ingestion job |
| `stratus_bronze_to_silver` | hourly or dataset-triggered | Run bronze quality checks, evaluate promotion, and transform to silver |
| `stratus_silver_to_gold` | daily or dataset-triggered | Run silver quality checks, evaluate promotion, and materialise gold tables |
| `stratus_table_maintenance` | daily / weekly | Inspect Iceberg metadata tables, apply per-table policy, and run snapshot expiry, compaction, or orphan cleanup when thresholds are breached |

### Common DAG rules

- Each DAG must generate a `run_id` and pass it to every Spark job it submits.
- Every Spark job must write structured success or failure metadata to task logs.
- Every quality job must write records to `stratus.platform.quality_check_results`.
- Promotion must be blocked when any blocking quality check has `status = failed`.
- Warnings do not block promotion, but they must be visible in task logs.
- Overrides require both `override_principal` and `override_reason`.
- DAG code must remain orchestration logic only. Transformations stay in Spark job classes.
- Maintenance DAGs must emit the metadata signals they used for decisions, including file count, average file size, snapshot count, manifest count, delete-file count, and orphan cleanup result.

### Common Spark submit helper

Create `airflow/dags/stratus_common.py`:

```python
from __future__ import annotations

import os

from airflow.models import Variable


def spark_submit_command(main_class: str, *job_args: str) -> list[str]:
    catalog = Variable.get("stratus_polaris_catalog", default_var="stratus")
    polaris_uri = Variable.get("stratus_polaris_uri")
    s3_endpoint = Variable.get("ceph_rgw_endpoint")
    master = Variable.get("stratus_spark_master")
    app_jar = Variable.get("stratus_spark_app_jar")

    client_id = os.environ["STRATUS_POLARIS_CLIENT_ID"]
    client_secret = os.environ["STRATUS_POLARIS_CLIENT_SECRET"]
    access_key = os.environ["CEPH_RGW_ACCESS_KEY"]
    secret_key = os.environ["CEPH_RGW_SECRET_KEY"]

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
        "--conf", f"spark.sql.catalog.{catalog}.s3.endpoint={s3_endpoint}",
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
    schedule="*/15 * * * *",
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "ingestion", "bronze"],
) as dag:

    wait_for_source_file = S3KeySensor(
        task_id="wait_for_source_file",
        bucket_key="customers/{{ ds }}/customers.csv",
        bucket_name="{{ var.value.stratus_landing_bucket }}",
        aws_conn_id="stratus_landing",
        poke_interval=60,
        timeout=600,
        mode="reschedule",
    )

    run_ingestion = BashOperator(
        task_id="run_ingestion",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.stratus.jobs.spark.LandingToBronzeJob",
            "--sourceFile", "s3a://stratus-landing/customers/{{ ds }}/customers.csv",
            "--targetTable", "stratus.bronze.customers",
            "--sourceSystem", "verification",
            "--runId", "{{ run_id }}",
        )),
    )

    run_bronze_quality = BashOperator(
        task_id="run_bronze_quality",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.stratus.jobs.spark.QualityCheckJob",
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
    schedule="@hourly",
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "transform", "silver"],
) as dag:

    run_quality = BashOperator(
        task_id="run_bronze_quality",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.stratus.jobs.spark.QualityCheckJob",
            "--targetTable", "stratus.bronze.customers",
            "--runId", "{{ run_id }}",
            "--checks", "[{\"type\":\"uniqueness\",\"columns\":[\"customer_id\"],\"severity\":\"blocking\"}]",
        )),
    )

    evaluate_promotion = BashOperator(
        task_id="evaluate_promotion",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.stratus.jobs.spark.PromotionGateJob",
            "--runId", "{{ run_id }}",
            "--targetTable", "stratus.bronze.customers",
        )),
    )

    run_transform = BashOperator(
        task_id="run_transform",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.stratus.jobs.spark.BronzeToSilverJob",
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
    schedule="@daily",
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "materialisation", "gold"],
) as dag:

    run_quality = BashOperator(
        task_id="run_silver_quality",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.stratus.jobs.spark.QualityCheckJob",
            "--targetTable", "stratus.silver.customers",
            "--runId", "{{ run_id }}",
            "--checks", "[{\"type\":\"row_count_min\",\"severity\":\"blocking\",\"threshold\":1}]",
        )),
    )

    evaluate_promotion = BashOperator(
        task_id="evaluate_promotion",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.stratus.jobs.spark.PromotionGateJob",
            "--runId", "{{ run_id }}",
            "--targetTable", "stratus.silver.customers",
        )),
    )

    run_materialisation = BashOperator(
        task_id="run_materialisation",
        bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
            "dev.stratus.jobs.spark.SilverToGoldJob",
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
    {
        "name": "stratus.bronze.customers",
        "policy": "bronze-default-v1",
    },
    {
        "name": "stratus.silver.customers",
        "policy": "silver-default-v1",
    },
    {
        "name": "stratus.gold.customer_summary",
        "policy": "gold-default-v1",
    },
]

with DAG(
    dag_id="stratus_table_maintenance",
    default_args=default_args,
    start_date=datetime(2026, 1, 1),
    schedule="@daily",
    catchup=False,
    max_active_runs=1,
    tags=["stratus", "maintenance", "iceberg"],
) as dag:

    for table in TABLES:
        BashOperator(
            task_id=f"maintain_{table['name'].replace('.', '_')}",
            bash_command=" ".join(shlex.quote(part) for part in spark_submit_command(
                "dev.stratus.jobs.spark.TableMaintenanceJob",
                "--targetTable", table["name"],
                "--policy", table["policy"],
                "--decisionMode", "metadata_table_thresholds",
                "--runId", "{{ run_id }}",
            )),
        )
```

`TableMaintenanceJob` must query Iceberg metadata tables and emit the observed file count, small-file count, average file size, snapshot-chain length, manifest count, delete-file count, orphan-file count, selected action, and policy version. The DAG is only the trigger; it must not hard-code maintenance operations that bypass table policy.

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

Increment 4 uses Airflow's built-in task failure behavior, Deadline Alerts where timing guarantees are required, and a simple SMTP or webhook callback. Full observability integration with Prometheus and Grafana follows the operational model in the architecture document and can be expanded after the core orchestration behavior is proven.

### Minimum alert events

- DAG failure
- task failure after all retries are exhausted
- promotion gate blocked
- Deadline Alert for ingestion or materialisation DAGs
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

The Java source and Maven dependencies in this section are build inputs only. The approved build system publishes the executable verifier as a pinned container image. Operators execute that image and do not build on the verification host or inside the verification container.

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
| `CEPH_RGW_ENDPOINT` | Ceph RGW S3 endpoint |
| `CEPH_RGW_ACCESS_KEY` | `svc-spark` access key |
| `CEPH_RGW_SECRET_KEY` | `svc-spark` secret key |

### Shared Airflow REST client

Place in `verification/orchestration-contract/src/test/java/dev/stratus/verification/orchestration/AirflowTestClient.java`:

```java
package dev.stratus.verification.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AirflowTestClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String bearerToken;

    public AirflowTestClient() throws Exception {
        this.baseUrl = System.getenv("STRATUS_AIRFLOW_BASE_URL");
        String username = System.getenv("STRATUS_AIRFLOW_USERNAME");
        String password = System.getenv("STRATUS_AIRFLOW_PASSWORD");
        this.bearerToken = fetchToken(username, password);
    }

    private String fetchToken(String username, String password) throws Exception {
        String body = """
            {
              "username": "%s",
              "password": "%s"
            }
            """.formatted(username, password);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/auth/token"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Airflow token request failed: "
                + response.statusCode() + " " + response.body());
        }
        return mapper.readTree(response.body()).get("access_token").asText();
    }

    public JsonNode get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + bearerToken)
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
            .uri(URI.create(baseUrl + "/api/v2/dags/" + dagId + "/dagRuns"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + bearerToken)
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
        JsonNode response = get("/api/v2/dags/" + dagId + "/dagRuns/" + runId);
        return response.get("state").asText();
    }
}
```

### Verification test class

Place in `verification/orchestration-contract/src/test/java/dev/stratus/verification/orchestration/AirflowOrchestrationVerificationTest.java`:

```java
package dev.stratus.verification.orchestration;

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
    static void connect() throws Exception {
        assertThat(System.getenv("STRATUS_AIRFLOW_BASE_URL"))
            .as("STRATUS_AIRFLOW_BASE_URL must be set").isNotBlank();
        airflow = new AirflowTestClient();
    }

    @Test
    @Order(1)
    void airflowReachable() {
        assertThatNoException()
            .as("Airflow REST API must be reachable")
            .isThrownBy(() -> airflow.get("/api/v2/monitor/health"));
    }

    @Test
    @Order(2)
    void allRequiredDagsExistAndAreUnpaused() throws Exception {
        var dags = airflow.get("/api/v2/dags?limit=100");
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
export STRATUS_AIRFLOW_PASSWORD=<bootstrap secret from approved secret store>
export STRATUS_SPARK_MASTER=spark://spark-master.stratus.local:7077
export STRATUS_POLARIS_URI=https://polaris.stratus.local:8181/api/catalog
export STRATUS_POLARIS_CLIENT_ID=svc-spark
export STRATUS_POLARIS_CLIENT_SECRET=<client secret>
export STRATUS_POLARIS_CATALOG=stratus
export CEPH_RGW_ENDPOINT=https://object-store.stratus.local
export CEPH_RGW_ACCESS_KEY=svc-spark
export CEPH_RGW_SECRET_KEY=<svc-spark secret>

export STRATUS_AIRFLOW_ORCHESTRATION_VERIFIER_IMAGE=registry.stratus.local/stratus/airflow-orchestration-verifier:<version>@sha256:<digest>
podman run --rm --env-file /etc/stratus/verifiers/airflow-orchestration.env \
  -v /data/stratus/evidence/increment4:/evidence:z \
  ${STRATUS_AIRFLOW_ORCHESTRATION_VERIFIER_IMAGE}
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
- compaction should reduce file-count debt when policy thresholds are breached
- task logs should report the Iceberg metadata-table metrics that triggered or skipped each action
- skipped actions should be explicit, with the table name, threshold, observed value, and policy version recorded

---

## 15. Implementation Task Track

These tasks execute `P1-4.1` through `P1-4.5`; evidence belongs under `evidence/phase1/increment4/<task-id>/` and IDs remain stable in delivery tooling.

| ID | Parent | Track | Task and definition of done | Owner | Depends on | Deliverable/path | Verification/evidence | Gate | Accepted by | Blocker/risk | Status |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `P1-4.1-S1` | `P1-4.1` | Shared | Lock Airflow image, providers, DAG packaging, constraints, and verifier artifacts. | Build owner | P1-3 developer gate | `platform/airflow/image/`; dependency locks | build, scan, import and provider smoke tests | D1, P1-P3 | Platform owner | Provider compatibility | Not started |
| `P1-4.1-D1` | `P1-4.1` | Developer | Implement idempotent LocalExecutor deployment, local PostgreSQL, startup/reset, and health checks. | Operations owner | `P1-4.1-S1` | `platform/airflow/developer/` | two lifecycle cycles and DB migration output | D1 | Platform owner | Local resources | Not started |
| `P1-4.2-D1` | `P1-4.2` | Developer | Configure Spark submission, Polaris/Ceph trust, protected connections, and immutable DAG delivery. | Data-engineering owner | `P1-4.1-D1` | `platform/airflow/dags/`; `platform/airflow/config/`; `environments/developer/airflow/` | Spark task and secret-redaction evidence | D1 | Security owner | Connection leakage | Not started |
| `P1-4.3-V1` | `P1-4.3` | Developer | Implement and verify ingestion, transforms, quality halt, maintenance, retry, and alert DAGs. | Data-engineering owner | `P1-4.2-D1` | DAG modules/tests | run IDs, pass/fail paths, retry/alert reports | D1-D2 | Data owner | Alert sink availability | Not started |
| `P1-4.1-P1` | `P1-4.1` | Production | Provision external PostgreSQL TLS/backup/restore and production Airflow service placement. | Database and operations owners | `P1-4.1-S1` | `platform/airflow/`; `environments/production/airflow/`; DB runbook | migration, failover/recovery, service restart | P1-P6 | Platform owner | DB HA decision | Not started |
| `P1-4.2-P1` | `P1-4.2` | Production | Apply OIDC/HTTPS, managed secrets, immutable DAG promotion, Ceph remote logs, and restricted administration. | Security owner | `P1-4.1-P1`, Increment 7 controls | `platform/airflow/config/`; `environments/production/airflow/` | auth negative tests, log continuity, rotation | P5-P11 | Operations owner | OIDC integration | Not started |
| `P1-4.5-R1` | `P1-4.5` | Production | Prove scheduler/service failure, DB restore, DAG rollback, retry safety, and alert routing. | Operations owner | `P1-4.2-P1` | `operations/runbooks/airflow/` | timed drills, restored run metadata, alert exercise | P12-P16 | Platform owner | Maintenance window | Not started |
| `P1-4.4-V1` | `P1-4.4` | Production | Run production DAG and quality-gate regression with capacity and observability evidence. | QA owner | `P1-4.5-R1` | production test reports | run IDs, JUnit, metrics, failed promotion proof | P15-P18 | Data owner | Representative schedule load | Not started |
| `P1-4.G-D` | `P1-4` | Developer | Accept D1-D2. | Platform owner | `P1-4.3-V1` | developer gate record | gate/evidence matrix | D1-D2 | Data owner | Open defect | Not started |
| `P1-4.G-P` | `P1-4` | Production | Accept P1-P18 with promotion and readiness evidence. | Platform owner | `P1-4.4-V1` | production gate record | gate/evidence matrix | P1-P18 | Operations owner | Open production defect | Not started |

## 16. Completion Gates

### Developer gate

- [ ] **D1** - Single-host Airflow 3.3.0 starts/stops idempotently and DAG scheduling, Spark submission, retry, failure alert, quality halt, and verifier tests pass.
- [ ] **D2** - Local metadata/log state, bootstrap credentials, local CA, and reduced service availability are recorded in the promotion manifest.

### Production gate

Increment 4 is accepted when all of the following are true:

- [ ] **P1** - PostgreSQL metadata database running and managed by systemd on `airflow.stratus.local`
- [ ] **P2** - Airflow API server running and managed by systemd on `airflow.stratus.local`
- [ ] **P3** - Airflow DAG processor running and managed by systemd
- [ ] **P4** - Airflow scheduler running and managed by systemd on `airflow.stratus.local`
- [ ] **P5** - Airflow triggerer running and managed by systemd
- [ ] **P6** - Airflow UI and public REST API are reachable through trusted HTTPS/OIDC; port 8088, if retained internally, is not an unauthenticated production ingress
- [ ] **P7** - Airflow DAG import check reports no errors
- [ ] **P8** - All four DAGs exist: `stratus_landing_to_bronze`, `stratus_bronze_to_silver`, `stratus_silver_to_gold`, `stratus_table_maintenance`
- [ ] **P9** - Airflow can submit Spark jobs to `spark-master.stratus.local:7077`
- [ ] **P10** - Landing-to-bronze DAG detects a source file and writes a bronze Iceberg table
- [ ] **P11** - Bronze-to-silver DAG runs quality checks and blocks downstream transform when a blocking failure exists
- [ ] **P12** - Silver-to-gold DAG materialises a gold table when quality passes
- [ ] **P13** - Maintenance DAG inspects Iceberg metadata tables, applies per-table policy, and runs or skips snapshot expiry and file rewrite operations with recorded evidence
- [ ] **P14** - Task retries work for a transient failure
- [ ] **P15** - Failure alerts fire when a task exhausts retries
- [ ] **P16** - `AirflowOrchestrationVerificationTest` passes against the live platform
- [ ] **P17** - Airflow task logs include run IDs, target tables, Spark application IDs, and quality gate decisions
- [ ] **P18** - external metadata database and remote logs restore successfully; managed secrets and scheduler/DAG-processor availability meet the approved RTO/RPO design

The developer gate may unblock Increment 5 engineering. Only the production gate marks Increment 4 accepted in the Phase 1 tracker.

---

## 17. Troubleshooting

### Airflow API server cannot connect to metadata database

```bash
podman logs airflow-api-server
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
podman exec airflow-scheduler curl --cacert /etc/stratus/certs/ca.crt \
  https://polaris.stratus.local:8181/api/catalog/v1/config
```

- Confirm `STRATUS_POLARIS_CLIENT_ID` and `STRATUS_POLARIS_CLIENT_SECRET` are present in the scheduler environment
- Confirm the catalog URI includes `/api/catalog`

### Landing-zone sensor never succeeds

- Confirm the expected key exists in Ceph RGW:

```bash
aws s3 --endpoint-url https://object-store.stratus.local ls s3://stratus-landing/customers/$(date +%F)/customers.csv
```

- Confirm the `stratus_landing` Airflow connection has the Ceph RGW endpoint in its JSON extras
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

### DAG processor is not parsing DAGs

```bash
podman logs airflow-dag-processor | tail -50
podman exec airflow-scheduler airflow dags list-import-errors
```

Common causes:
- DAG directory is not mounted into the DAG processor container
- provider package missing from the Airflow image
- Python import error in a DAG file
- scheduler and DAG processor are not using the same Airflow image and mounted plugin paths

---

## 18. References

- Apache Airflow documentation: https://airflow.apache.org/docs/
- Airflow 3.3 prerequisites and supported databases/Python: https://airflow.apache.org/docs/apache-airflow/stable/installation/prerequisites.html
- Airflow Docker deployment guide: https://airflow.apache.org/docs/apache-airflow/stable/howto/docker-compose/index.html
- Airflow REST API: https://airflow.apache.org/docs/apache-airflow/stable/stable-rest-api-ref.html
- Airflow Spark provider: https://airflow.apache.org/docs/apache-airflow-providers-apache-spark/stable/
- Airflow Spark provider changelog: https://airflow.apache.org/docs/apache-airflow-providers-apache-spark/stable/changelog.html
- Airflow Amazon provider: https://airflow.apache.org/docs/apache-airflow-providers-amazon/stable/
- PostgreSQL 17 release notes: https://www.postgresql.org/docs/17/release.html
- Apache Spark standalone cluster: https://spark.apache.org/docs/latest/spark-standalone.html
- Apache Iceberg Spark procedures: https://iceberg.apache.org/docs/latest/spark-procedures/
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [stratus_on_prem_data_fabric_architecture.md](../architecture/stratus_on_prem_data_fabric_architecture.md)
- Increment 1 — Ceph object storage foundation: [increment1_ceph.md](increment1_ceph.md)
- Increment 2 — Iceberg and Polaris: [increment2_iceberg_polaris.md](increment2_iceberg_polaris.md)
- Increment 3 — Spark: [increment3_spark.md](increment3_spark.md)
