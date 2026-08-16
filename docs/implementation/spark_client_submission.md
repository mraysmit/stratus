# Spark Client Submission — the gap between what is verified and what is used

- Status: External client and scenario-suite conversion implemented; live revalidation in progress.
- Raised: 2026-08-09
- Affects: `platform/spark/tests`, `docs/implementation/spark_compute.md` §10

## 1. The problem in one sentence

The Spark verification suite runs its commands *inside* the cluster, so the
only thing a tenant actually does — submit work from outside, as itself — is
the one thing no test covers.

## 2. What the design says

`spark_compute.md` §10 specifies the verification suite plainly:

> The verification suite submits real Spark jobs to the live cluster and
> confirms the full batch pipeline works end to end. **It uses the Spark Java
> API for job submission.**

The listing under it builds a `SparkSession` in the verifier's own JVM,
pointed at the master over the cluster protocol, carrying its own catalog
credential:

```java
SparkSession.builder()
    .master(System.getenv("STRATUS_SPARK_MASTER"))          // spark://spark-master…:7077
    .config("spark.sql.catalog.stratus.credential", clientId + ":" + clientSecret)
    …
```

That is an external client. It reaches the master over the network, resolves
Polaris over TLS, and authenticates as a named principal.

## 3. What is actually built

`LiveSparkCluster` runs every command through the container runtime:

```java
docker compose --project-name stratus-spark-local exec -T spark-master \
    /opt/spark/bin/spark-submit --master spark://spark-master.stratus.local:7077 …
```

The driver starts *inside* the master container and uses the ambient identity
baked into that container's `spark-defaults.conf`.

This was a deliberate choice, recorded in the runner script: submitting from
inside the cluster needs no workstation hosts-file entry and no CA truststore,
because the harness containers already carry both lab CAs. That reasoning is
true, and it is why the approach was taken. What it does not do is state the
cost, and the cost is the whole point of the suite.

**Needing no truststore is the symptom, not the benefit.** A client that needs
no truststore is a client that never validated the endpoint, because it never
crossed the boundary a real client crosses.

## 4. What this leaves unproven

| Property | Status today |
| --- | --- |
| A client outside the cluster can reach the master | never exercised |
| A client can resolve Polaris over TLS from outside | never exercised |
| A client can write through S3A to Ceph from outside | never exercised |
| A named principal's own credential works | only in the negative — the forged-secret check |
| Two clients with different principals see different things | cannot be expressed |
| Two clients can run at the same time | structurally blocked, see §5 |

Every passing test runs as the container's `svc-spark`. The suite's one
credential-bearing test, `refusesToResolveTheCatalogWithAForgedPrincipalSecret`,
works precisely *because* it overrides the ambient configuration — which is the
clearest evidence that nothing else does.

## 5. Why concurrency is blocked, not merely absent

Two symptoms, both previously misread:

**The Derby collision.** Two sessions inside one container share
`/opt/spark/work-dir/metastore_db`, and the second fails with `Another instance
of Derby may have already booted the database`. This was read as an operational
rule — "run one suite at a time" — and written into the runbook as such. The
correct reading is that the harness models one client, not N. Independent
external drivers each have their own working directory and never collide.

**Configuration lives in process globals.** `STRATUS_SPARK_INTEGRATION` and
`STRATUS_LOG_LEVEL` are read from `System.getenv()`, which is per-JVM. A test
holding two clients with different principals, or different log levels, cannot
express that at all. Per-client configuration has to be a value passed to a
client, not state read from the process.

## 6. Development and production — what actually differs

This gap was first filed against the production tasks `P1-3.1-P1` and
`P1-3.5-V1`. That was wrong, and the distinction matters enough to state:

**Behaviour must be identical in development.** Client submission from outside
the cluster, per-client credentials, concurrent independent clients, and the
isolation between them are software behaviour. They run on one workstation
against one Compose cluster. Nothing about them requires production hardware,
and a developer harness that cannot do them is not a reduced version of the
platform — it is a different platform.

**Physical topology genuinely differs.** Separate failure domains, real
capacity figures, and worker-loss drills across hosts cannot be proven on a
single workstation, because a single workstation is a single failure domain.
Those belong to the production tasks, and the promotion manifest in
`spark_compute.md` §13 already says never to claim a capacity or failover
posture from this topology.

The error was filing a behavioural gap in the physical bucket. Concurrency is
behaviour.

## 7. The fix

Replace the container-exec transport with a real client, per §10.

1. **A client is an object, not the process.** Introduce a configuration value
   carrying the master URL, catalog name, principal id and secret, and storage
   credentials. A test constructs as many as it needs. Nothing is read from
   `System.getenv()` below the point where a test chooses which client to use.
2. **Submission is a `SparkSession` in the test JVM**, built from that value,
   `.master(spark://…)`. This is the documented design and the path a tenant
   uses.
3. **The workstation needs what a tenant needs**: the hosts-file entry and a
   truststore for the harness CA. `ceph-compose-run-live-tests.sh` already
   builds a truststore for exactly this reason, and
   `ceph-compose-configure-hostname.sh` already supplies the hosts entry — so
   the mechanism exists and is proven, it is simply not used by the Spark
   suite.
4. **Prove concurrency directly**: two clients, two principals, submitting at
   the same time, each seeing only what its principal is entitled to. This is
   the test the current design cannot express, and it is the reason for the
   change.
5. **Keep container exec only where it belongs** — inspecting the cluster from
   the inside, which is a different question from whether a client can use it.

## 7a. What was built, and what building it discovered

An external client exists and is proven against the live platform:
`SparkClientConfig`, `StratusSparkClient`, `HarnessConnection`,
`HarnessTruststore` and `PolarisPrincipals` under `platform/spark/tests`, with
`SparkClientConformanceTest` (3) and `SparkPrincipalSeparationTest` (3).

Six findings, none of which was written down anywhere before, and four of which
cost real time to diagnose:

- **The external driver works, and needs less than expected.** No hosts-file
  entry: `spark://127.0.0.1:7077` is enough, because the master's port is
  published on loopback. No firewall exception either. What it does need is
  `spark.driver.host=host.docker.internal`, `bindAddress=0.0.0.0`, and fixed
  `driver.port` / `blockManager.port`, because executors run inside the bridge
  and dial back.
- **SLF4J and Spark form a logging loop.** This module binds SLF4J to JUL;
  Spark brings the bridge routing JUL back to SLF4J. Together they recurse
  until the stack is exhausted, and it surfaces as a `StackOverflowError`
  inside `SparkSession` creation — which looks like anything but logging.
  `jul-to-slf4j` is excluded in the module's POM.
- **Setting `javax.net.ssl.trustStore` is not enough.** The JVM builds its
  default `SSLContext` once and caches it, and merely constructing an
  `HttpClient` fixes it. A truststore installed after that point is ignored,
  and every TLS call fails PKIX against a store that plainly contains the right
  CA. `HarnessTruststore` replaces the default context outright.
- **The driver and the executors need different paths to the same truststore.**
  The driver's is a workstation path; the executors' is the container path the
  compose file mounts. Setting only the driver's lets the catalog resolve and
  then fails every write.
- **A JVM holds one `SparkContext`.** `getOrCreate()` returns any existing
  session whatever configuration it is asked for, so independently rebuilding
  clients in one fork would silently share an identity. `connect` still
  refuses that unsafe path. The integration profile now sets
  `reuseForks=true`: `SparkSuiteContext` owns one suite-scoped context in
  JUnit's root store and hands every class an isolated `newSession()` with its
  own catalog configuration and identity. Derived clients clear session state
  without stopping the context; only the root owner stops it. Two principals
  in one driver is therefore a test of *authorisation*; two drivers still means
  two processes.
- **The current-JDK failure was Hadoop, not an intrinsic S3A limit.** Hadoop
  3.4.1 called `Subject.getSubject`, which JDK 24 permanently disabled, so the
  raw-object path failed on the workstation while Iceberg's S3 client worked.
  Hadoop 3.4.3 contains HADOOP-19212 and uses the replacement Subject API. The
  verifier now runs that line on Java 26 and the S3A round trip is mandatory;
  the version-based assumption was removed. The Spark containers remain on
  Spark 4.1.2's supported Java 17 component runtime.
- **Polaris refuses rather than filters.** A principal granted nothing does not
  receive an empty list; it receives
  `not authorized for op LIST_NAMESPACES`, naming the principal and the roles
  it presented. That is the stronger behaviour — an empty answer is
  indistinguishable from an empty catalog.

The separation assertions were proven load-bearing by granting the probe
principal `catalog_admin`: both fail with `ALLOWED` where a refusal is
required.

## 8. What the fix does not cover

Per-tenant identity in the full sense — a directory, group membership,
per-tenant policy — arrives with Increment 7 (FreeIPA and Keycloak). The
change above proves that two *named principals* can work concurrently and
independently, which is the Spark-layer property. It does not create a tenancy
model, and this document should not be read as claiming one.

## 9. Consequences for existing records

`P1-3.3-V1` and `P1-3.3-V2` are recorded as `Accepted` on evidence produced
entirely through container exec. That evidence remains valid for what it
measured — the jobs' behaviour, the write modes, the quality and promotion
logic, which are unaffected by how the driver was started. It does not
establish that a client can use the platform, and neither task's record claims
it does. This document is the statement that the claim was never made, so that
nobody later reads "the batch pipeline works end to end" as covering the
submission path.

The long incremental scenario now runs ingestion and maintenance in its one
external driver. One pipeline check deliberately remains a packaged
`spark-submit` smoke test, so jar assembly, argument parsing, process exit and
the real submission boundary are still exercised without paying that startup
cost for every tiny fixture.
