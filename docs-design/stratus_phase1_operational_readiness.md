# Stratus Phase 1 Operational Acceptance and Production Readiness

## 1. Purpose

This document defines the operational acceptance gate for Stratus Phase 1.

It is not Increment 8. It does not introduce another platform component. It verifies that the capabilities delivered by Increments 1 through 7 are complete, integrated, supportable, recoverable, observable, and secure enough to operate as a production-ready foundation.

Phase 1 is accepted only when the platform can prove three things:

1. The end-to-end batch lakehouse path works after identity and security hardening.
2. Operators can observe, recover, and support the platform using documented runbooks.
3. Security, governance, and quality controls remain enforced during normal operations and failure scenarios.

Reference:
- [on_prem_data_fabric_architecture.md](stratus_on_prem_data_fabric_architecture.md)
- [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)

---

## 2. Assumptions and Prerequisites

Before this readiness review begins:

- Increment 1 through Increment 7 completion gates have passed.
- All verification suites from Increments 1 through 7 have current passing results.
- The selected open source versions and container image tags are recorded in the Phase 1 version matrix.
- No platform service uses an unpinned `latest` image tag.
- Airflow is deployed using the approved Airflow 3.x topology from Increment 4 and hardened by Increment 7.
- FreeIPA and Keycloak are the active identity sources for users, groups, certificates, and OIDC flows.
- Ranger policies reference approved FreeIPA groups, not local lab placeholder users.
- Ceph RGW, Polaris, Spark, Airflow, Trino, Atlas, Ranger, FreeIPA, and Keycloak are reachable over approved encrypted protocols.
- The permanent Phase 1 verification dataset exists and can be safely recreated.
- Runbooks and operational ownership are assigned before signoff.

### Reference documentation audit

Reference baseline: 2026-07-05.

This readiness gate depends on the same upstream projects referenced by the increment documents. Before production signoff, the platform team must confirm that the approved version matrix still matches current project documentation and release notes, or record an explicit reason for any pinned older version.

At minimum, review current documentation for:

- Ceph, Ceph RGW, and the approved S3-compatible client used by operators
- Apache Iceberg
- Apache Polaris
- Apache Spark
- Apache Airflow 3.x
- Trino
- Apache Atlas
- Apache Ranger
- FreeIPA
- Keycloak
- Prometheus, Grafana, and Loki

Current Phase 1 target baseline as of 2026-07-05:

| Component | Target |
|---|---|
| Apache Polaris | 1.5.0 |
| Apache Iceberg | 1.11.0 |
| Apache Spark | 4.1.2 with Scala 2.13 |
| Apache Airflow | 3.2.2 |
| Airflow Spark provider | 6.2.0 |
| Airflow Amazon provider | 9.31.0 |
| boto3 | 1.43.40 |
| Trino | 482 |
| Keycloak | 26.6.4 |
| Ceph RGW | latest supported release approved for the environment, pinned by image digest or package version |
| Apache Atlas / Ranger | latest approved Apache releases, built as pinned internal images after plugin/database compatibility review |
| FreeIPA | latest supported package stream from the selected Linux distribution or vendor-supported IdM documentation |

Spark 4.2.0 is treated as preview and is not the Phase 1 production target until it becomes a stable release and the Iceberg runtime, Airflow Spark provider, Trino connector, and verification suites are updated together.

---

## 3. Scope

This readiness gate covers:

- integrated functional regression across Increments 1 through 7
- service, protocol, and dependency validation
- version, artifact, and configuration signoff
- observability, alerting, and log correlation
- backup and restore verification
- identity, certificate, credential, and authorization posture
- data quality, promotion gate, and override readiness
- governance, lineage, classification, audit, and policy enforcement
- operational runbooks and ownership
- incident drill evidence
- performance and capacity smoke checks
- release and change-control readiness

Out of scope:

- Kafka, Kafka Connect, Debezium, Flink, and streaming ingestion
- Firebolt Core or other serving acceleration layers
- large-scale multi-environment promotion automation
- self-service data marketplace workflows
- replacing the Phase 1 architecture with alternate components

Those items belong to later phases or operational maturity work after the Phase 1 foundation is accepted.

---

## 4. Acceptance Topology

The accepted Phase 1 topology is the integrated stack delivered by the increment documents:

```text
Users / operators
  | HTTPS / OIDC
  v
Keycloak  <----LDAPS---->  FreeIPA
  |
  | OIDC tokens
  v
Trino / Airflow / supported REST clients
  |
  | JDBC / REST / service protocols
  v
Ranger policy checks     Atlas metadata and lineage
  |                       ^
  |                       | HTTPS REST lineage publication
  v                       |
Trino ----Iceberg REST----> Polaris ----S3 API----> Ceph RGW
  ^                          ^
  |                          |
  | JDBC verification        | Iceberg REST
  |                          |
Airflow ----spark-submit----> Spark ----S3 API----> Ceph RGW
```

Acceptance requires proving the system behavior, not merely proving that each container starts. Every readiness check should identify which service, protocol, credential, and data contract it exercises.

---

## 5. Service and Protocol Acceptance

| Service | Protocols used | Required acceptance evidence |
|---|---|---|
| Ceph RGW | HTTPS S3 API, Ceph Dashboard HTTPS | buckets exist, TLS validates without insecure flags, RGW user policies isolate landing/bronze/silver/gold/platform zones |
| Apache Polaris | HTTPS REST catalog API, JDBC or metadata database connection if externalized | `stratus` catalog resolves namespaces and tables, service principals are scoped, Iceberg metadata locations point to approved Ceph RGW paths |
| Apache Spark | Spark cluster protocol, Iceberg REST, HTTPS S3 API, JVM truststore | ingestion, transform, materialisation, quality, and maintenance jobs run through Polaris and Ceph RGW |
| Apache Airflow 3.x | HTTPS UI/API, Airflow public API, metadata database protocol, `spark-submit` | API server, DAG processor, scheduler, and triggerer are healthy; DAGs run, retry, alert, and enforce quality gates |
| Trino | HTTPS client protocol/JDBC, Iceberg REST, Ranger plugin REST, HTTPS S3 API through connector | curated SQL queries match Spark outputs; unauthorized queries are denied; Trino does not bypass Polaris |
| Apache Atlas | HTTPS REST API, embedded or configured graph/search stores | metadata entities, lineage, classifications, ownership, and quality attributes are searchable and current |
| Apache Ranger | HTTPS Admin API/UI, usersync LDAP/LDAPS, policy plugin REST, audit storage | FreeIPA groups sync, policies apply through Trino, allow and deny events are audited |
| FreeIPA | Kerberos, LDAP/LDAPS, Dogtag CA, DNS where used | users, groups, hosts, service principals, keytabs, and certificates are issued and recoverable |
| Keycloak | HTTPS/OIDC, LDAPS federation, PostgreSQL protocol | realm, clients, token claims, issuer validation, group federation, and key rotation are verified |
| Observability stack | Prometheus scrape endpoints, Grafana dashboards, Loki log ingestion | dashboards, alerts, log search, and run correlation work across platform services |

No production readiness signoff should pass while routine operational commands require `-k`, `--insecure`, disabled certificate validation, shared human credentials, or local lab users.

---

## 6. Version and Artifact Signoff

The Phase 1 readiness evidence bundle must include a version matrix with:

| Artifact | Required details |
|---|---|
| Container images | registry, repository, tag, digest, build date, owner |
| Java artifacts | Maven coordinates, version, repository source, checksum where practical |
| Python packages | package name, version, source index, lockfile reference |
| Operating system packages | package name, version, distribution repository |
| Configuration files | source repository path, deployed path, checksum or revision |
| Certificates | subject, issuer, SANs, expiry date, issuing procedure |
| Secrets | owner, storage location, rotation procedure, last rotation date; do not record secret values |

Acceptance checks:

- [ ] No service uses floating image tags.
- [ ] Custom images have a reproducible Dockerfile or Containerfile.
- [ ] Airflow uses the approved 3.x image and service split.
- [ ] Trino, Spark, and Iceberg dependency versions are mutually compatible.
- [ ] Ranger plugin and Trino version compatibility is documented.
- [ ] Keycloak and FreeIPA versions are supported by their upstream projects or the selected Linux distribution.
- [ ] The deployed configuration matches the reviewed configuration in source control.

---

## 7. End-to-End Functional Acceptance

The permanent Phase 1 verification dataset must exercise the full integrated path:

```text
landing file
  -> Spark ingestion
  -> bronze Iceberg table
  -> quality checks
  -> Airflow promotion gate
  -> silver Iceberg table
  -> quality checks
  -> Airflow promotion gate
  -> gold Iceberg table
  -> Trino query
  -> Atlas lineage and quality metadata
  -> Ranger audited access decision
```

### Required assertions

| Assertion | Pass condition |
|---|---|
| Landing access | only approved service accounts can read the landing object |
| Bronze fidelity | bronze row count and raw columns match the source file |
| Quality result write | `platform.quality_check_results` records PASS, WARN, and FAIL examples with the correct run id |
| Blocking quality gate | Airflow halts downstream promotion when a blocking quality check fails |
| Silver conformance | silver table contains the expected deduplicated and normalized rows |
| Gold aggregate | gold table contains expected aggregates and business keys |
| Query parity | Trino counts and aggregates match Spark results for the same Iceberg snapshot |
| Catalog discipline | Spark and Trino resolve tables through Polaris, not direct object paths |
| Lineage | Atlas shows landing-to-bronze-to-silver-to-gold lineage for the verification run |
| Classification | Atlas classification metadata exists for any sensitive verification columns |
| Authorization allow | an approved analyst group can query approved gold data through Trino |
| Authorization deny | an unapproved user is denied access to restricted data and Ranger records the denial |
| Audit correlation | Airflow run id, Spark application id, Iceberg snapshot id, Trino query id, and Ranger audit record can be correlated |

The readiness reviewer should keep the command output, test report, screenshots where useful, and log links in the evidence bundle.

---

## 8. Backup and Restore Acceptance

Readiness requires a restore drill, not only a backup configuration.

| Area | Backup requirement | Restore acceptance |
|---|---|---|
| Ceph object data | replicated or erasure-coded pool design for critical buckets plus OSD/MON recovery procedures | verification dataset remains readable after an OSD, MON, or RGW daemon failure drill, or after restoring from the approved backup path |
| Iceberg metadata | table metadata files, manifests, manifest lists, snapshots, and delete files retained according to policy | an older snapshot can be inspected, manifests resolve, and a controlled rollback procedure is documented |
| Polaris | catalog metadata store backup | restored Polaris resolves existing table identifiers to the same Ceph RGW table metadata locations as the accepted restore point |
| Airflow | PostgreSQL metadata backup plus DAG repository backup | restored Airflow lists DAGs, preserves relevant run history, and triggers the verification DAG |
| Spark | job artifact repository and configuration backup | restored job artifacts can be submitted by Airflow |
| Trino | catalog, security, and coordinator configuration backup | restored Trino can query the gold verification table and enforce Ranger policy |
| Atlas | graph/search state backup and configuration backup | restored Atlas shows verification entities, classifications, and lineage |
| Ranger | policy database, usersync configuration, plugin configuration, and audit configuration backup | restored Ranger enforces the approved allow/deny policies |
| FreeIPA | FreeIPA server backup, CA material, DNS/LDAP/Kerberos state | restored FreeIPA preserves users, groups, hosts, principals, and certificate authority identity |
| Keycloak | PostgreSQL backup plus realm/client export where approved | restored Keycloak issues valid tokens with expected claims and signing keys |
| Observability | dashboard, alert rule, and retention configuration backup | restored dashboards and alerts monitor the verification run |

### Restore drill

The readiness drill must prove:

- backups exist at the expected location
- backup age is visible to operators
- restore instructions are executable by the assigned operator
- restored services start cleanly
- restored services can run the end-to-end verification path
- restored identity and policy state does not silently broaden access
- restored Polaris catalog state, Iceberg metadata files, manifests, and object data resolve to the same accepted table snapshot
- restored Polaris table metadata locations match the expected Iceberg `metadata/*.metadata.json` files for the accepted restore point
- restored manifests and manifest lists reference object files that exist in Ceph RGW
- rollback to a known-good Iceberg snapshot is executable after a logical bad write

### Coupled Iceberg recovery drills

Iceberg recovery must be tested as a coupled catalog, metadata, and object-data workflow. The following drills are required before production signoff:

| Drill | Required evidence |
|---|---|
| Catalog corruption | Restore Polaris metadata and prove existing table identifiers resolve to the expected metadata file locations. |
| Missing metadata file | Remove or quarantine a non-production table metadata file, prove detection, restore it, and validate the table can be read at the accepted snapshot. |
| Missing manifest or manifest list | Remove or quarantine a non-production manifest artifact, prove read/planning failure is detected, restore it, and validate table planning succeeds. |
| Object-store unavailability | Make the Ceph RGW endpoint unavailable in a controlled test and prove Spark, Trino, Polaris, Airflow, and alerts fail predictably without corrupting table state. |
| Logical bad write | Commit a controlled bad write, roll back to a known-good Iceberg snapshot, and prove Spark and Trino read the restored result consistently. |
| Catalog/object mismatch | Restore catalog metadata and object data from intentionally mismatched points in a non-production drill, prove validation fails, then restore both to a consistent point. |

---

## 9. Observability and Alerting Acceptance

Operators must be able to answer four questions quickly:

1. Is the platform healthy?
2. Which dataset run failed?
3. Which control-plane service caused or contributed to the failure?
4. Who or what accessed governed data?

### Required dashboards

| Dashboard | Required signals |
|---|---|
| Platform overview | service health, active incidents, backup age, certificate expiry, failed DAG count |
| Ceph RGW | OSD/MON/MGR/RGW daemon health, pool capacity, RGW request/error rate, bucket growth by zone |
| Polaris and Iceberg | catalog latency, auth failures, table count, metadata growth, failed table resolutions, per-table maintenance policy version, file count, small-file count, average file size, snapshot chain length, manifest count, delete-file count, orphan-file count, last maintenance action, orphan cleanup result |
| Spark | application status, duration, executor failures, failed stages, event log availability |
| Airflow 3.x | API server health, scheduler heartbeat, DAG processor heartbeat, triggerer health, DAG import errors, failed tasks, Deadline Alert breaches |
| Trino | query latency, failed queries, queued queries, worker health, Ranger plugin errors |
| Atlas | entity update freshness, REST errors, search/index health, lineage publication lag |
| Ranger | policy sync health, deny counts, audit volume, plugin policy refresh age |
| FreeIPA and Keycloak | LDAP/LDAPS health, Kerberos health, token issuance failures, federation errors |
| Data quality | failed blocking checks, warnings, override count, check coverage by dataset |

### Required alerts

- Ceph OSD, MON, or RGW daemon unavailable
- Ceph pool/bucket capacity threshold breached
- Polaris unavailable or elevated 5xx response rate
- Polaris table metadata location mismatch after restore validation
- Iceberg file-count or small-file-count threshold breached for a governed table
- Iceberg average file size below table policy threshold
- Iceberg snapshot-chain length above table policy threshold
- Iceberg manifest count or metadata-file growth above table policy threshold
- Iceberg delete-file count above table policy threshold
- Iceberg orphan-file count or orphan cleanup failure above table policy threshold
- Iceberg maintenance skipped because no table-specific policy exists
- Spark job failure rate above threshold
- Airflow scheduler heartbeat stale
- Airflow DAG processor heartbeat stale
- Airflow Deadline Alert breach
- Trino coordinator unavailable
- Ranger plugin cannot refresh policies
- unexpected increase in Ranger deny events
- Atlas lineage publication stalled
- FreeIPA LDAP/LDAPS unavailable
- Keycloak token issuance failure
- certificate expires within the warning window
- backup missing or older than the approved recovery point objective
- blocking quality check failure
- quality gate override executed

### Logging contract

Logs for pipeline and platform activity must preserve these fields where applicable:

- `run_id`
- `dag_id`
- dataset name
- source and target table
- Iceberg snapshot id
- Spark application id
- Trino query id
- authenticated user or service principal
- Ranger policy id for authorization decisions
- failure category and exception class

Logs must not print passwords, access keys, tokens, keytabs, private keys, or full connection strings.

---

## 10. Security and Identity Acceptance

| Control | Required evidence |
|---|---|
| TLS | all browser, API, JDBC, LDAP, and object-storage endpoints use trusted certificates without insecure flags |
| Certificate lifecycle | each certificate has an owner, expiry date, renewal procedure, and monitoring alert |
| FreeIPA identity | users, groups, hosts, service principals, keytabs, and CA state are backed up and documented |
| Keycloak OIDC | tokens validate issuer, audience, signature, expiry, and group claims |
| Ranger authorization | policies reference FreeIPA groups and enforce access through Trino |
| Ceph RGW service policy | service accounts have the minimum object-storage access needed for their platform role |
| Secret handling | secrets are stored in approved protected files or secret manager, not in source code or logs |
| Bootstrap retirement | lab bootstrap users, passwords, and local placeholder accounts are disabled or break-glass only |
| Rotation | keytabs, service account keys, Keycloak client secrets, and Ceph RGW credentials have tested rotation runbooks |
| Audit | privileged access, policy changes, query denies, quality overrides, and restore actions are auditable |

Security acceptance must include both positive and negative tests. A user in an approved group must succeed, and a user outside the approved group must be denied with an auditable reason.

---

## 11. Governance and Quality Acceptance

Governance and data quality are accepted only when they are connected to the operating pipeline.

### Governance evidence

- Atlas contains the verification dataset entities.
- Atlas lineage connects Spark jobs, Iceberg tables, and gold outputs.
- Atlas classifications exist for sensitive verification fields.
- Atlas ownership and stewardship values use approved FreeIPA users or groups.
- Ranger policies enforce access to Trino-visible tables and columns.
- Ranger audit records can be searched by user, table, action, and policy id.
- Policy changes require an approved change record.

### Quality evidence

- `platform.quality_check_results` exists and is queryable.
- Quality checks record PASS, WARN, FAIL, dataset, run id, table, snapshot id, severity, and detail.
- Airflow promotion gates read quality results and block downstream work on blocking failures.
- Quality failure alerts identify the dataset, check, run id, and owner.
- Quality overrides require approval, reason, expiry, and audit record.
- The verification dataset includes at least one passing run and one intentionally blocked run.

---

## 12. Runbooks and Ownership

Every Phase 1 service must have a named owner, escalation path, and runbook. The readiness bundle should include links or repository paths for each runbook.

| Runbook | Required content |
|---|---|
| Backup and restore | backup schedule, restore procedure, validation command, owner |
| Certificate renewal | issuing authority, renewal steps, trust distribution, rollback |
| Keytab rotation | principals affected, rotation steps, validation steps |
| Keycloak client secret rotation | clients affected, rotation order, validation steps |
| Ceph RGW service credential rotation | access key owner, policy mapping, dependent services |
| Airflow DAG failure | triage, retry, backfill, clearing task state, escalation |
| Spark job failure | event log review, failed stage triage, rerun procedure |
| Trino query failure | query id lookup, catalog check, Ranger policy check |
| Polaris outage | health checks, metadata store check, restore path |
| Atlas outage | entity/search health, lineage backlog handling |
| Ranger outage | Admin health, plugin policy cache behavior, emergency policy handling |
| Quality gate failure | dataset owner notification, override process, rerun process |
| Identity outage | FreeIPA and Keycloak triage, break-glass boundaries |
| Incident communication | severity definitions, owner, stakeholder notification |

Runbooks should be executable by someone other than the original implementer. If an operator cannot follow the runbook during the readiness review, the runbook is not accepted.

---

## 13. Operational Drills

The following drills must be run before Phase 1 signoff:

| Drill | Expected outcome |
|---|---|
| End-to-end verification run | landing-to-gold workflow succeeds, Trino query matches Spark output, Atlas and Ranger evidence exists |
| Blocking quality failure | Airflow halts promotion, alert fires, override workflow is documented but not automatically applied |
| Unauthorized Trino query | user is denied, Ranger audit record identifies user, object, action, and policy |
| Certificate validation | endpoint checks succeed without insecure flags; an expired or untrusted certificate is rejected in a controlled test |
| Keycloak token validation | invalid issuer, audience, expired token, or bad signature is rejected |
| FreeIPA group propagation | group membership change reaches Keycloak and Ranger within the approved window |
| Airflow DAG import error | alert fires and the failed DAG does not silently disappear from operational visibility |
| Spark job retry | transient failure retries according to policy and records final status |
| Ceph OSD, MON, or RGW daemon failure | service remains within the approved replication/erasure-coding fault-tolerance target, or restore path is executed |
| Catalog/object-store consistency restore | restored Polaris metadata, Iceberg metadata files, manifests, and Ceph RGW object data resolve to a consistent table snapshot |
| Iceberg failure recovery | catalog corruption, missing metadata file, missing manifest file, accidental object deletion, logical bad write, rollback to known-good snapshot, and object-store unavailability scenarios have documented rollback or restore evidence |
| Control-plane restore | restored Polaris, Airflow, Ranger, Atlas, FreeIPA, and Keycloak support the verification workflow |
| Credential rotation | one non-production service credential is rotated and dependent service validation passes |
| Backup age alert | stale or missing backup condition raises an alert |

Each drill needs a dated evidence record with:

- operator
- environment
- command or test used
- result
- log or dashboard reference
- follow-up issues

---

## 14. Performance and Capacity Smoke Checks

Phase 1 readiness does not require final production sizing, but it does require proving that the foundation has no obvious capacity failure before onboarding real datasets.

| Area | Smoke check |
|---|---|
| Ceph RGW | ingest and read the verification dataset plus a larger synthetic file without error; capacity dashboard updates |
| Ceph RGW metadata-heavy behavior | create, list, read, update, and clean up representative Iceberg metadata/object layouts without elevated error rate or unexpected latency |
| Spark | run the full pipeline with a scaled verification dataset and record duration and resource use |
| Airflow | run concurrent DAGs at the expected initial operating level without scheduler starvation |
| Trino | run concurrent read-only analytical queries over gold data and record latency and failure rate |
| Polaris | resolve tables repeatedly from Spark and Trino without elevated latency or auth failures |
| Concurrent catalog and object-store access | run Spark writes, Trino reads, Polaris table resolutions, and operator S3 listing checks together and record baseline error rate and latency |
| Atlas | publish lineage for repeated verification runs without stale metadata |
| Ranger | evaluate policy decisions under concurrent Trino queries without plugin errors |
| Observability | metrics and logs remain queryable after the smoke test |

The readiness record should capture baseline numbers. These are the first operating benchmarks for later capacity planning.

---

## 15. Change and Release Readiness

Production readiness includes the ability to change the platform safely.

Acceptance checks:

- [ ] configuration changes are reviewed through source control
- [ ] image updates require a version-matrix update
- [ ] policy changes require an owner and approval record
- [ ] schema changes define compatibility, rollback, and quality-check impact
- [ ] DAG changes include import validation and test execution
- [ ] secret and certificate changes include rotation and rollback steps
- [ ] rollback procedures exist for failed platform configuration changes
- [ ] release notes summarize component versions, configuration changes, known risks, and verification evidence

---

## 16. Readiness Evidence Bundle

The final evidence bundle should be stored with the implementation record for the environment and include:

- completed checklist from this document
- passing verification results from Increments 1 through 7
- end-to-end Phase 1 verification run output
- version and artifact matrix
- deployed configuration inventory
- service endpoint inventory
- certificate inventory
- secret and credential inventory without secret values
- backup schedule and restore drill evidence
- dashboard and alert inventory
- incident drill results
- runbook index
- open risk register
- explicit signoff from platform operations, security, data governance, and data engineering owners

Open risks may remain only if they have an owner, severity, mitigation, target date, and explicit acceptance by the accountable owner.

---

## 17. Completion Gate

Phase 1 is production-ready when all of the following are true:

- [ ] Increment 1 through Increment 7 completion gates are complete
- [ ] all inherited verification suites pass after Increment 7 identity and TLS hardening
- [ ] the end-to-end verification dataset completes the full landing-to-gold workflow
- [ ] Trino query results match Spark output for the accepted verification run
- [ ] Atlas lineage, classification, ownership, and quality metadata are current
- [ ] Ranger allow and deny behavior is verified and audited
- [ ] Airflow 3.x API server, DAG processor, scheduler, and triggerer health are monitored
- [ ] Deadline Alert, task failure, quality failure, and backup-age alerts are configured
- [ ] FreeIPA users, groups, hosts, service principals, CA state, and backup are verified
- [ ] Keycloak realm, clients, federation, token claims, and key rotation are verified
- [ ] no normal operational flow requires insecure TLS flags
- [ ] bootstrap credentials and local lab users are removed, disabled, or documented as break-glass only
- [ ] certificate, keytab, Ceph RGW credential, and Keycloak client-secret rotation runbooks exist
- [ ] backup and restore drill proves restored services can run the verification workflow
- [ ] backup and restore drill proves catalog metadata, Iceberg metadata files, manifests, and object data are recovered consistently
- [ ] observability dashboards expose service health, pipeline state, quality state, policy state, and backup age
- [ ] Iceberg metadata health signals are visible for file count, average file size, snapshots, manifests, delete files, and orphan cleanup
- [ ] incident drills are complete and evidence is attached
- [ ] runbooks have named owners and escalation paths
- [ ] open risks are documented, owned, and accepted
- [ ] platform operations, security, data governance, and data engineering sign off

After this gate passes, Phase 1 can onboard controlled production datasets or proceed into Phase 2 planning.

---

## 18. Troubleshooting

### Verification succeeds before Increment 7 but fails after identity hardening

- Confirm all containers and JVM truststores contain the FreeIPA CA chain.
- Confirm Keycloak issuer and audience settings match each OIDC consumer.
- Confirm Ranger usersync imported the expected FreeIPA groups.
- Confirm service principals, keytabs, and client secrets were rotated into the runtime environment.

### Trino can query data but Ranger does not audit the query

- Confirm the Trino Ranger plugin is loaded.
- Confirm the plugin can reach Ranger Admin over HTTPS.
- Confirm policy refresh is current.
- Confirm the query is not using an alternate catalog that bypasses the governed Iceberg catalog.

### Atlas shows tables but not lineage

- Confirm Spark and Airflow metadata publishers are enabled.
- Confirm lineage events include the same dataset and table identifiers used in Polaris.
- Confirm Atlas REST API authentication and TLS trust still work after Increment 7 hardening.

### Restore drill starts services but verification fails

- Confirm restored Polaris metadata resolves tables to the original Ceph RGW paths.
- Confirm restored Airflow DAG code matches the restored metadata database state.
- Confirm restored Ranger policies reference current FreeIPA groups.
- Confirm restored Keycloak signing keys and clients match service expectations.

### Operators cannot find failure cause quickly

- Confirm logs include `run_id`, `dag_id`, table, snapshot id, query id, and user where applicable.
- Confirm dashboards link to the relevant logs.
- Confirm alert annotations include service, dataset, run id, and owner.

---

## 19. References

- Main architecture: [on_prem_data_fabric_architecture.md](stratus_on_prem_data_fabric_architecture.md)
- Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Increment 1 - Ceph RGW: [increment1_ceph.md](increment1_ceph.md)
- Increment 2 - Iceberg and Polaris: [increment2_iceberg_polaris.md](increment2_iceberg_polaris.md)
- Increment 3 - Spark: [increment3_spark.md](increment3_spark.md)
- Increment 4 - Airflow: [increment4_airflow.md](increment4_airflow.md)
- Increment 5 - Trino: [increment5_trino.md](increment5_trino.md)
- Increment 6 - Atlas and Ranger: [increment6_atlas_ranger.md](increment6_atlas_ranger.md)
- Increment 7 - Identity and Security: [increment7_identity_security.md](increment7_identity_security.md)
