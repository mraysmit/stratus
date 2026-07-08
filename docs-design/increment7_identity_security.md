# Stratus Increment 7 — FreeIPA, Keycloak, and Security Hardening

## 1. Purpose

This document is the technical implementation plan for Increment 7 of the Stratus platform as defined in [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md).

Increment 7 hardens the working platform from Increments 1 through 6. It introduces FreeIPA as the Linux-native identity, Kerberos, LDAP, DNS, and PKI foundation; Keycloak as the OIDC broker for REST-facing services; FreeIPA-issued certificates for TLS; and group-based identity propagation into Ranger, Atlas, Trino, Airflow, and Polaris. When this increment is complete, the platform no longer depends on lab-local users, self-signed certificates, or shared bootstrap credentials for normal operation. A Java verification suite confirms that identity and security hardening did not break the storage, catalog, query, orchestration, or governance contracts already proven in earlier increments.

This is a migration and hardening increment, not a greenfield install. The existing platform must continue to work after identity is made real.

**Prerequisites:**
- Increment 1 complete — Ceph RGW storage running with all buckets and service credentials
- Increment 2 complete — Polaris and Iceberg tables operational
- Increment 3 complete — Spark jobs and quality gates operational
- Increment 4 complete — Airflow DAGs operational
- Increment 5 complete — Trino query plane operational
- Increment 6 complete — Atlas metadata and Ranger policy enforcement operational

---

## 2. Assumptions and Prerequisites

- Linux hosts only (RHEL 9 / Rocky 9 / Ubuntu 22.04 or later)
- Podman 4.x installed on the identity and Keycloak hosts
- JDK 21+ and Maven 3.9+ on the verification host
- DNS resolution:
  - `ipa.stratus.local`
  - `keycloak.stratus.local`
  - `object-store.stratus.local`
  - `polaris.stratus.local`
  - `airflow.stratus.local`
  - `trino-coordinator.stratus.local`
  - `atlas.stratus.local`
  - `ranger.stratus.local`
- FreeIPA manages the `stratus.local` DNS zone for lab identity hosts, or delegates for the relevant service names are configured
- A production-like deployment should use persistent volumes and backed-up PostgreSQL databases for Keycloak and governance services
- Flink is not part of the Increment 7 verification path unless a Flink increment has already been implemented

### Reference Documentation Audit

Reference baseline: 2026-07-05.

The current FreeIPA documentation page points users toward Red Hat Enterprise Linux Identity Management documentation for maintained operational guidance. Keycloak's current documentation line is 26.6.4 and its container guide recommends optimized images, HTTPS in production mode, health and metrics endpoints, and PostgreSQL for production-like deployments. Trino 482 documentation confirms that OAuth2 client authentication is configured on the coordinator, requires TLS and a shared secret, and does not require the same client-auth changes on workers.

Airflow is standardized on Airflow 3.2.2 in Increment 4. Increment 7 hardens that Airflow 3.x topology using the Airflow 3 public API and auth-manager model rather than the retired Airflow 2.x webserver/Flask AppBuilder assumption.

---

## 3. Topology

Increment 7 adds identity services and then migrates existing platform services to use them.

```text
identity host
┌──────────────────────────────────────────────┐
│  FreeIPA                                     │
│  Kerberos KDC / LDAP / DNS / Dogtag PKI      │
│  HTTPS UI / API :443                         │
│  Kerberos :88, LDAP :389/636, DNS :53        │
└──────────────────────────────────────────────┘
             │
             │ LDAP, Kerberos, certificates
             ▼
keycloak host
┌──────────────────────────────────────────────┐
│  Keycloak                                    │
│  OIDC broker backed by FreeIPA LDAP          │
│  HTTPS :8443, health/metrics :9000           │
│  PostgreSQL metadata database                │
└──────────────────────────────────────────────┘
             │
             │ OIDC tokens and group claims
             ▼
REST-facing services
Polaris / Trino / Airflow / future APIs

FreeIPA LDAP and groups
        │
        ├── Ranger usersync → group-based policies
        ├── Atlas LDAP authentication
        └── Linux hosts via SSSD
```

The hardened authentication model is:

| Area | Target |
|---|---|
| Linux host login and service identities | FreeIPA + SSSD |
| Service principals | FreeIPA Kerberos principals and keytabs where Kerberos is used |
| REST-facing user authentication | Keycloak OIDC backed by FreeIPA |
| Ranger users and groups | FreeIPA LDAP usersync |
| Atlas users | FreeIPA LDAP |
| Trino clients | Keycloak/OIDC to coordinator over HTTPS |
| Trino internal trust | TLS and, where supported by the selected release, Kerberos or shared-secret secure internal communication |
| Certificates | FreeIPA Dogtag-issued certificates trusted by all hosts and containers |

---

## 4. Service and Protocol Interaction Model

Increment 7 is primarily a protocol integration increment. The platform succeeds only if each protocol has a clear job and no service silently falls back to lab-local authentication.

### Protocol responsibilities

| Protocol | Used by | Purpose |
|---|---|---|
| DNS | all hosts and clients | stable service names, Kerberos canonical host lookup, certificate SAN alignment |
| Kerberos | Linux hosts, service accounts, optional service-to-service flows | ticket-based authentication for host and service identities |
| LDAP / LDAPS | Keycloak, Ranger usersync, Atlas, SSSD | user and group lookup from FreeIPA |
| OIDC / OAuth2 | browser users, CLI clients, Trino, Airflow, Polaris where supported | token-based authentication for REST-facing services |
| HTTPS / TLS | every service endpoint | transport encryption and server identity verification |
| S3 API over HTTPS | Spark, Trino, Polaris, Airflow, verification tools | object access to Ceph RGW-managed Iceberg data and platform objects |
| Iceberg REST catalog over HTTPS | Spark, Trino, verification tools | namespace and table metadata resolution through Polaris |
| Ranger plugin policy REST | Trino coordinator to Ranger Admin | policy download and refresh |
| Ranger audit | Trino/Ranger plugin to audit destination | allowed and denied access audit records |
| JDBC over HTTPS | analysts, BI tools, verification suite | SQL access through Trino |

DNS, certificates, and Kerberos are coupled. The hostname used by a client must resolve consistently, appear in the service certificate SAN, and match the service principal where Kerberos is used. Increment 7 should treat mismatches here as platform defects, not as client-side workarounds.

### Interaction map

| Flow | Protocols | Description |
|---|---|---|
| Host enrollment | HTTPS, LDAP, Kerberos, DNS | platform hosts join FreeIPA, receive identity configuration, and resolve users through SSSD |
| User login to Trino | HTTPS, OIDC, LDAP | user authenticates to Keycloak; Keycloak reads FreeIPA user/groups; Trino accepts OIDC-authenticated session |
| Trino query authorization | JDBC/HTTPS, Ranger REST, LDAP-derived groups | Trino executes the query only after Ranger policy permits the authenticated user and group set |
| Trino table resolution | Iceberg REST over HTTPS | Trino resolves schemas, tables, and snapshots from Polaris, not from raw Ceph RGW paths |
| Trino data read | S3 API over HTTPS | Trino reads Iceberg data and metadata files from Ceph RGW using the approved service credential |
| Spark job execution | Kerberos where enabled, Iceberg REST, S3 API | Airflow submits Spark jobs under the approved service identity; Spark resolves tables through Polaris and reads/writes Ceph RGW |
| Airflow operator access | HTTPS, OIDC | operators authenticate through Keycloak before viewing DAGs, task logs, and API state |
| Ranger user sync | LDAPS | Ranger imports FreeIPA users and groups; policies target groups, not local users |
| Atlas user access | LDAPS, HTTPS | Atlas authenticates users through FreeIPA and serves metadata over HTTPS |
| Certificate renewal | FreeIPA CA, HTTPS/TLS | service certificates are issued and renewed from FreeIPA Dogtag and trusted by all clients |

### Trust boundaries

| Boundary | Rule |
|---|---|
| Browser or BI client to platform | always HTTPS; user identity comes from Keycloak OIDC |
| Platform service to identity provider | LDAPS or HTTPS only; no plaintext LDAP binds across the network |
| Compute engine to Polaris | HTTPS; catalog access must authenticate and must not bypass Polaris |
| Compute/query engine to Ceph RGW | HTTPS S3 API; no direct filesystem access to object data |
| Trino to Ranger | HTTPS in the hardened target state; policy refresh failure should fail closed for protected resources |
| Container to host trust store | containers must mount or bake the FreeIPA CA; insecure flags are not acceptable |

### Identity propagation

Identity flows through the platform in three different forms:

| Identity form | Where it is authoritative | How it is consumed |
|---|---|---|
| FreeIPA user and group | FreeIPA LDAP | Keycloak federation, Ranger usersync, Atlas LDAP, SSSD |
| Kerberos principal | FreeIPA KDC | service account ticket acquisition where Kerberos is implemented |
| OIDC token claims | Keycloak realm `stratus` | Trino, Airflow, Polaris where supported, verification tools |

The key contract is that FreeIPA group membership becomes the policy input everywhere. Keycloak must emit group claims for OIDC consumers, and Ranger must import the same groups through usersync. A user added to `restricted-data-users` in FreeIPA should become eligible for PII access without hand-editing service-local user lists.

### Data-plane versus control-plane credentials

Increment 7 does not collapse every credential into one mechanism. That would be tidy on a whiteboard and very fragile in reality.

| Plane | Credential model |
|---|---|
| Human access to UIs and SQL | Keycloak OIDC backed by FreeIPA |
| Linux host and service identity | FreeIPA, SSSD, Kerberos principals, keytabs where used |
| Iceberg catalog access | Polaris-supported OAuth2/OIDC or approved Polaris principal model |
| Object storage access | Ceph RGW service credentials (RGW users) unless the selected Ceph deployment supports a verified external identity integration |
| Ranger policy administration | FreeIPA-backed users/groups and Ranger admin roles |
| Atlas metadata access | FreeIPA-backed LDAP users/groups |

The verification suite should prove the full chain, not just individual services: FreeIPA group membership must flow into Keycloak tokens and Ranger policies, then change what a user can query through Trino.

---

## 5. Ports

| Port | Service | Purpose |
|---|---|---|
| 53 | FreeIPA DNS | DNS service where FreeIPA manages the lab zone |
| 80 | FreeIPA HTTP | HTTP redirect / certificate enrollment helper |
| 88 | FreeIPA Kerberos | Kerberos authentication |
| 464 | FreeIPA kpasswd | Kerberos password changes |
| 389 | FreeIPA LDAP | LDAP users/groups |
| 636 | FreeIPA LDAPS | LDAP over TLS |
| 443 | FreeIPA HTTPS | FreeIPA UI/API and CA services |
| 5432 | Keycloak PostgreSQL | Keycloak metadata database |
| 8443 | Keycloak HTTPS | OIDC issuer and admin UI |
| 9000 | Keycloak management | health and metrics endpoint |

Existing service ports from earlier increments remain in place, but their TLS and authentication posture changes:

| Service | Hardened behavior |
|---|---|
| Ceph RGW | TLS chain trusted; encryption-at-rest enabled on sensitive buckets |
| Polaris | HTTPS with trusted certificate; OIDC integration where supported |
| Airflow | HTTPS and Keycloak-backed UI/API auth for Airflow 3.2.2 |
| Trino | HTTPS coordinator endpoint; Keycloak/OIDC client auth |
| Atlas | HTTPS and FreeIPA LDAP authentication |
| Ranger | HTTPS, FreeIPA LDAP usersync, group-based policies |

---

## 6. Identity Service Images and Packages

FreeIPA is commonly installed as host packages on RHEL-compatible systems because it manages DNS, Kerberos, LDAP, certificates, and host enrollment. For the lab, use the platform-approved operating-system packages rather than an unreviewed container image unless the infrastructure team has an approved FreeIPA container pattern.

Keycloak runs as a pinned container image with PostgreSQL:

| Image / package | Purpose |
|---|---|
| FreeIPA server packages | Kerberos, LDAP, DNS, Dogtag PKI |
| MIT Kerberos client packages | Kerberos client tools on platform hosts |
| SSSD packages | Linux host integration with FreeIPA |
| `quay.io/keycloak/keycloak:26.6.4` | Keycloak OIDC broker |
| `docker.io/library/postgres:16` | Keycloak metadata database |

Do not use `latest` tags. If another Keycloak release is approved, update this document and the verification suite expectations together.

---

## 7. FreeIPA Foundation

### Server installation contract

Install FreeIPA on `ipa.stratus.local` using the selected distribution's supported package flow. The implementation runbook must record:

- FreeIPA package versions
- realm name, e.g. `STRATUS.LOCAL`
- DNS domain, e.g. `stratus.local`
- CA configuration
- backup and restore procedure
- admin account bootstrap procedure

Example target values:

| Setting | Value |
|---|---|
| Realm | `STRATUS.LOCAL` |
| Domain | `stratus.local` |
| Server | `ipa.stratus.local` |
| CA | FreeIPA integrated Dogtag CA |
| DNS | FreeIPA-managed lab DNS or delegated subdomain |

### Platform groups

Create the platform groups defined in the architecture:

```text
platform-admins
platform-engineers
data-stewards-crm
analysts-crm
restricted-data-users
consumers-gold
svc-spark
svc-airflow
svc-polaris
svc-trino
svc-atlas
svc-ranger
```

Create `svc-flink` only as a reserved service group unless Flink has already been deployed.

### Platform users

Create lab users for policy verification:

| User | Groups |
|---|---|
| `platform_admin` | `platform-admins` |
| `platform_engineer` | `platform-engineers` |
| `analyst_crm` | `analysts-crm` |
| `analyst_restricted` | `analysts-crm`, `restricted-data-users` |

### Service principals

Create service principals for implemented services:

```text
svc-spark/stratus.local@STRATUS.LOCAL
svc-airflow/stratus.local@STRATUS.LOCAL
svc-polaris/polaris.stratus.local@STRATUS.LOCAL
svc-trino/trino-coordinator.stratus.local@STRATUS.LOCAL
svc-atlas/atlas.stratus.local@STRATUS.LOCAL
svc-ranger/ranger.stratus.local@STRATUS.LOCAL
```

Generate keytabs only for services that actually use Kerberos in this increment. Store keytabs on the relevant hosts with owner-only permissions and record the rotation procedure.

### Host enrollment

Enroll platform hosts as FreeIPA clients:

```bash
ipa-client-install \
  --server=ipa.stratus.local \
  --domain=stratus.local \
  --realm=STRATUS.LOCAL \
  --mkhomedir
```

Every enrolled host must be able to:

```bash
getent passwd platform_admin
id analyst_crm
kinit platform_admin
klist
```

---

## 8. Certificate and Trust Chain Hardening

FreeIPA Dogtag PKI replaces the lab self-signed CA used by earlier increments.

### Certificate inventory

Issue certificates for:

| Hostname | Service |
|---|---|
| `object-store.stratus.local` | Ceph RGW S3 endpoint (already issued in Increment 1 — see [increment1_ceph.md](increment1_ceph.md)) |
| `polaris.stratus.local` | Polaris REST catalog |
| `airflow.stratus.local` | Airflow UI/API |
| `trino-coordinator.stratus.local` | Trino coordinator UI/JDBC |
| `atlas.stratus.local` | Atlas UI/API |
| `ranger.stratus.local` | Ranger Admin |
| `keycloak.stratus.local` | Keycloak OIDC issuer |

### Trust distribution

All hosts and containers must trust the FreeIPA CA:

- Linux hosts trust the CA through the OS trust store
- JVM services receive the CA in the JVM truststore or mounted truststore
- Python services receive the CA bundle path through environment/configuration
- Trino, Spark, Airflow, Atlas, Ranger, Polaris, and Keycloak containers include or mount the CA chain

### TLS verification

Each service must pass:

```bash
curl --fail https://<service-host>:<port>/...
openssl s_client -connect <service-host>:<port> -servername <service-host>
```

Do not sign off this increment while clients require `-k`, `--insecure`, or disabled certificate verification for normal operation.

---

## 9. Keycloak Configuration

Keycloak is the OIDC broker for REST-facing services. It is backed by FreeIPA LDAP so FreeIPA remains the source of truth for users and groups.

### Persistent directories

Create directories on `keycloak.stratus.local`:

```bash
sudo mkdir -p /data/keycloak/postgres
sudo mkdir -p /etc/stratus/keycloak
sudo mkdir -p /etc/stratus/certs
sudo chown -R $USER:$USER /data/keycloak /etc/stratus/keycloak
```

### Start PostgreSQL

```bash
podman run -d \
  --name keycloak-postgres \
  --hostname keycloak-postgres \
  --network host \
  -e POSTGRES_USER=keycloak \
  -e POSTGRES_PASSWORD=<keycloak-db-password> \
  -e POSTGRES_DB=keycloak \
  -v /data/keycloak/postgres:/var/lib/postgresql/data:z \
  --restart unless-stopped \
  docker.io/library/postgres:16
```

### Build optimized Keycloak image

Create `docker/keycloak/Containerfile`:

```dockerfile
FROM quay.io/keycloak/keycloak:26.6.4 AS builder

ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
ENV KC_DB=postgres

WORKDIR /opt/keycloak
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.6.4
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

Build:

```bash
podman build -t stratus/keycloak:26.6.4 docker/keycloak
```

### Start Keycloak

```bash
podman run -d \
  --name keycloak \
  --hostname keycloak.stratus.local \
  --network host \
  -e KC_DB=postgres \
  -e KC_DB_URL=jdbc:postgresql://localhost:5432/keycloak \
  -e KC_DB_USERNAME=keycloak \
  -e KC_DB_PASSWORD=<keycloak-db-password> \
  -e KC_HOSTNAME=https://keycloak.stratus.local:8443 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin \
  -e KC_BOOTSTRAP_ADMIN_PASSWORD=<bootstrap-password> \
  -v /etc/stratus/certs:/etc/stratus/certs:ro,z \
  --restart unless-stopped \
  stratus/keycloak:26.6.4 \
  start --optimized \
    --https-certificate-file=/etc/stratus/certs/keycloak.crt \
    --https-certificate-key-file=/etc/stratus/certs/keycloak.key \
    --https-port=8443 \
    --http-enabled=false
```

### Realm and LDAP federation

Create realm:

```text
stratus
```

Configure LDAP federation:

| Setting | Value |
|---|---|
| Vendor | Red Hat Directory Server / generic LDAP as supported by selected Keycloak version |
| Connection URL | `ldaps://ipa.stratus.local:636` |
| Users DN | FreeIPA users base DN |
| Bind DN | Keycloak LDAP bind service account |
| Edit mode | Read-only for initial increment |
| Import users | Enabled |
| Sync registrations | Disabled |
| Group mapper | Enabled for FreeIPA groups |

Group claims must appear in OIDC tokens so Trino, Polaris, and future APIs can reason over FreeIPA group membership.

Minimum token claims:

| Claim | Purpose |
|---|---|
| `sub` | stable user identity |
| `preferred_username` | human-readable username used in audit records |
| `email` | optional operator contact and UI display |
| `groups` or mapped equivalent | Ranger/Trino/Polaris policy decisions |
| `aud` | target client validation |
| `iss` | realm issuer validation |

Trino, Airflow, and Polaris should validate issuer, audience, expiration, and signature. Do not accept unsigned tokens, wildcard audiences, or tokens issued by a non-Stratus realm.

### OIDC clients

Create clients:

| Client | Type | Purpose |
|---|---|---|
| `trino` | confidential | Trino coordinator login and JDBC OAuth flow |
| `airflow` | confidential | Airflow 3 UI/API authentication |
| `polaris` | confidential or service client | Polaris REST catalog authentication if supported by selected Polaris release |
| `stratus-cli` | confidential or public by policy | verification utilities and operator CLI flows |

Client secrets must be stored outside source control and rotated by procedure.

---

## 10. Ranger and Atlas Identity Migration

### Ranger usersync

Replace Increment 6 local usersync with FreeIPA LDAP usersync.

Required behavior:

- users from FreeIPA appear in Ranger
- FreeIPA groups appear in Ranger
- existing policies are migrated to group names, not local users
- policy refresh does not break the Increment 6 Trino allow/deny behavior

Baseline policy mapping:

| Policy | FreeIPA group |
|---|---|
| platform admins | `platform-admins` |
| platform engineers | `platform-engineers` |
| CRM analysts | `analysts-crm` |
| restricted PII users | `restricted-data-users` |
| quality visibility | `platform-engineers`, `analysts-crm` |

Ranger protocol flow:

```text
FreeIPA LDAP/LDAPS
      │ users and groups
      ▼
Ranger usersync
      │ synchronized identities
      ▼
Ranger Admin policy database
      │ policy download REST
      ▼
Trino Ranger plugin
      │ allow / deny decision
      ▼
Trino query execution
```

Ranger does not authenticate the browser user to Trino. Trino authenticates the user, then Ranger authorizes the action using the user and group context available to the Trino Ranger plugin.

### Atlas LDAP authentication

Configure Atlas to authenticate users through FreeIPA LDAP or LDAPS. Atlas metadata ownership and stewardship values should use FreeIPA usernames and groups rather than Increment 6 local placeholders.

Atlas must still:

- search verification table entities
- show lineage
- show classifications
- update quality metadata after Airflow or Spark runs

Atlas protocol flow:

```text
User browser ──HTTPS──► Atlas
Atlas ──LDAPS──► FreeIPA
Spark/Airflow metadata publisher ──HTTPS REST──► Atlas
```

Atlas remains the metadata and classification system. It does not enforce Trino data access directly; Ranger policies enforce access, optionally using tags or classifications that mirror Atlas governance decisions.

---

## 11. Trino, Airflow, and Polaris Authentication

### Trino OIDC

Configure Trino coordinator for OAuth2/OIDC through Keycloak over HTTPS. The worker nodes do not need the same client authentication settings because Trino client authentication terminates at the coordinator.

Coordinator configuration intent:

```properties
http-server.https.enabled=true
http-server.https.port=8443
http-server.authentication.type=oauth2
http-server.authentication.oauth2.issuer=https://keycloak.stratus.local:8443/realms/stratus
http-server.authentication.oauth2.client-id=trino
http-server.authentication.oauth2.client-secret=<trino-client-secret>
web-ui.authentication.type=oauth2
```

Trino also requires a configured shared secret for secure internal communication when OAuth2 is enabled. Keep this secret outside source control.

Verification must prove:

- unauthenticated Trino UI/API access is rejected
- `analyst_crm` can authenticate through Keycloak
- Ranger policies still use FreeIPA group membership to allow and deny queries

End-to-end Trino flow:

```text
Analyst / BI tool
      │ HTTPS + OIDC login
      ▼
Trino coordinator
      │ policy check
      ▼
Ranger plugin ──HTTPS REST──► Ranger Admin
      │ allowed
      ▼
Trino Iceberg connector ──HTTPS REST──► Polaris
      │ table metadata
      ▼
Trino workers ──HTTPS S3 API──► Ceph RGW
```

The user identity used for authorization is the authenticated Trino user. The Ceph RGW credential used for object reads is still the approved Trino service credential unless a future object-storage identity integration replaces it. This distinction is important: Ranger governs user access at the query layer; Ceph RGW bucket policy governs service-level object access.

### Airflow authentication

Airflow hardening targets the Airflow 3.2.2 topology from Increment 4. Use the Airflow 3 public API and selected auth-manager configuration, with Keycloak as the OIDC identity provider. If a reverse proxy is used in front of Airflow, it must preserve the same authentication and authorization contracts and must not create a second identity source.

Verification must prove:

- unauthenticated UI access redirects to Keycloak or is rejected by the chosen auth layer
- an authenticated platform operator can view DAGs
- API clients obtain valid tokens through the Airflow 3 auth path
- existing Increment 4 DAG verification still passes

Airflow protocol flow:

```text
Operator browser ──HTTPS/OIDC──► Airflow UI
Airflow scheduler ──local metadata DB protocol──► Airflow PostgreSQL
Airflow task ──spark-submit / cluster protocol──► Spark
Spark ──HTTPS REST──► Polaris
Spark ──HTTPS S3 API──► Ceph RGW
```

Airflow authenticates operators and API clients. It should not become the source of data authorization. Data access still flows through Spark, Polaris, Ceph RGW credentials, Ranger/Trino for query, and Atlas/Ranger governance metadata.

### Polaris authentication

Polaris OIDC integration is release-sensitive. The implementation must verify the selected Polaris release's supported authentication model before replacing the Increment 2 OAuth2 client credential bootstrap.

Target behavior:

- REST catalog requests without valid credentials fail with `401`
- service clients use named Keycloak clients or approved Polaris principals
- no shared root bootstrap credential is used for normal compute-engine access
- Spark and Trino still resolve tables through the same Polaris catalog

Polaris protocol flow:

```text
Spark / Trino / verification client
      │ HTTPS + approved catalog credential
      ▼
Polaris REST catalog
      │ table metadata locations
      ▼
Ceph RGW object storage
```

Polaris is the catalog control point. It decides which catalog, namespace, and table metadata a principal can resolve. It is not the object store and should not hold Ceph RGW root credentials.

---

## 12. Ceph RGW Encryption and Credential Posture

Increment 7 does not replace Ceph RGW S3 access keys unless the selected Ceph deployment supports an approved external identity integration for the platform. It does harden their posture:

- RGW TLS certificates (`object-store.stratus.local`) are FreeIPA-issued and trusted
- RGW users (`svc-spark`, `svc-polaris`, `svc-airflow`, `svc-trino`) remain named per service
- credentials have owners and rotation procedures
- `stratus-gold` and `stratus-platform` use the approved Ceph/RGW encryption-at-rest model
- verification confirms Spark and Trino still read/write/query expected Iceberg tables after TLS and encryption changes

Do not imply Kerberos replaces S3 access keys unless that integration is explicitly implemented and verified.

Ceph RGW protocol flow:

```text
Spark / Trino / Polaris / Airflow
      │ HTTPS S3 API
      ▼
Ceph RGW
      │ bucket policy + RGW user credential
      ▼
Iceberg data, metadata, manifests, platform objects
```

Ceph RGW enforces object-level, per-RGW-user policy. It does not know the human analyst who submitted a Trino query unless a future integration explicitly carries that identity into object-storage authorization. Human-level data authorization in this phase is enforced at Trino/Ranger and Polaris/catalog layers.

---

## 13. Credential Retirement and Secret Handling

Increment 7 must retire lab bootstrap credentials from normal operation.

| Credential | Target |
|---|---|
| FreeIPA admin bootstrap password | emergency admin only; stored in sealed secret process |
| Keycloak bootstrap admin | emergency admin only; not used by services |
| Keycloak client secrets | stored in secret manager or protected host files |
| Kerberos keytabs | owner-only permissions; rotation procedure |
| Ceph RGW service credential secrets | named owner and rotation procedure |
| Polaris bootstrap principal | disabled or locked away after service principals exist |
| Airflow admin bootstrap user | replaced by Keycloak-authenticated platform admins |
| Ranger/Atlas local lab users | removed or disabled after LDAP migration |

Plaintext environment files may remain only as controlled lab bootstrap artifacts. They are not the target runtime secret model.

---

## 14. Java Verification Suite

The verification suite checks identity, authentication, TLS, group propagation, and regression behavior against the live platform.

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
<dependency>
    <groupId>io.trino</groupId>
    <artifactId>trino-jdbc</artifactId>
    <version>482</version>
    <scope>test</scope>
</dependency>
```

### Configuration

| Variable | Description |
|---|---|
| `STRATUS_KEYCLOAK_BASE_URL` | e.g. `https://keycloak.stratus.local:8443` |
| `STRATUS_KEYCLOAK_REALM` | `stratus` |
| `STRATUS_KEYCLOAK_CLIENT_ID` | verification client id |
| `STRATUS_KEYCLOAK_CLIENT_SECRET` | verification client secret |
| `STRATUS_TEST_USERNAME` | `analyst_crm` |
| `STRATUS_TEST_PASSWORD` | test user password |
| `STRATUS_RESTRICTED_USERNAME` | `analyst_restricted` |
| `STRATUS_RESTRICTED_PASSWORD` | restricted test user password |
| `STRATUS_TRINO_JDBC_URL` | e.g. `jdbc:trino://trino-coordinator.stratus.local:8443/stratus?SSL=true` |
| `STRATUS_POLARIS_BASE_URL` | e.g. `https://polaris.stratus.local:8181` |
| `STRATUS_AIRFLOW_BASE_URL` | e.g. `https://airflow.stratus.local` |
| `STRATUS_RANGER_BASE_URL` | e.g. `https://ranger.stratus.local:6080` |
| `STRATUS_ATLAS_BASE_URL` | e.g. `https://atlas.stratus.local:21000` |

### Verification test class

Place in `src/test/java/dev/mars/stratus/security/IdentitySecurityVerificationTest.java`:

```java
package dev.mars.stratus.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentitySecurityVerificationTest {

    static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    static final ObjectMapper JSON = new ObjectMapper();

    static String analystToken;
    static String restrictedToken;

    @Test
    @Order(1)
    void keycloakOidcDiscoveryWorks() throws Exception {
        JsonNode discovery = getJson(keycloakBase()
            + "/realms/" + realm() + "/.well-known/openid-configuration", null);

        assertThat(discovery.get("issuer").asText())
            .as("Keycloak issuer must be the Stratus realm")
            .contains("/realms/" + realm());
    }

    @Test
    @Order(2)
    void analystCanObtainOidcToken() throws Exception {
        analystToken = tokenFor(
            System.getenv("STRATUS_TEST_USERNAME"),
            System.getenv("STRATUS_TEST_PASSWORD"));

        assertThat(analystToken)
            .as("Analyst OIDC token must be issued")
            .isNotBlank();
    }

    @Test
    @Order(3)
    void restrictedUserCanObtainOidcToken() throws Exception {
        restrictedToken = tokenFor(
            System.getenv("STRATUS_RESTRICTED_USERNAME"),
            System.getenv("STRATUS_RESTRICTED_PASSWORD"));

        assertThat(restrictedToken)
            .as("Restricted user OIDC token must be issued")
            .isNotBlank();
    }

    @Test
    @Order(4)
    void polarisRejectsUnauthenticatedRequest() throws Exception {
        HttpResponse<String> response = get(
            System.getenv("STRATUS_POLARIS_BASE_URL") + "/api/catalog/v1/config", null);

        assertThat(response.statusCode())
            .as("Polaris must reject unauthenticated catalog access")
            .isIn(401, 403);
    }

    @Test
    @Order(5)
    void airflowRequiresAuthentication() throws Exception {
        HttpResponse<String> response = get(
            System.getenv("STRATUS_AIRFLOW_BASE_URL") + "/", null);

        assertThat(response.statusCode())
            .as("Airflow UI must not allow anonymous access")
            .isIn(302, 401, 403);
    }

    @Test
    @Order(6)
    void atlasAndRangerUseHttps() {
        assertThat(System.getenv("STRATUS_ATLAS_BASE_URL")).startsWith("https://");
        assertThat(System.getenv("STRATUS_RANGER_BASE_URL")).startsWith("https://");
    }

    @Test
    @Order(7)
    void analystCanQueryAllowedGoldDataThroughTrino() throws Exception {
        long count = queryAs(
            System.getenv("STRATUS_TEST_USERNAME"),
            "SELECT count(*) FROM stratus.gold.verification_customer_summary");

        assertThat(count)
            .as("Analyst must query allowed gold data after OIDC/Ranger migration")
            .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @Order(8)
    void analystCannotQueryPiiDataThroughTrino() {
        assertThatExceptionOfType(SQLException.class)
            .as("Analyst without restricted group must still be denied PII data")
            .isThrownBy(() -> queryAs(
                System.getenv("STRATUS_TEST_USERNAME"),
                "SELECT email FROM stratus.silver.verification_customers"));
    }

    @Test
    @Order(9)
    void restrictedUserCanQueryPiiDataThroughTrino() throws Exception {
        long count = queryAs(
            System.getenv("STRATUS_RESTRICTED_USERNAME"),
            "SELECT count(email) FROM stratus.silver.verification_customers");

        assertThat(count)
            .as("Restricted user must query PII data after FreeIPA group sync")
            .isGreaterThanOrEqualTo(1L);
    }

    static long queryAs(String user, String sql) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("SSL", "true");

        try (var connection = DriverManager.getConnection(
                 System.getenv("STRATUS_TRINO_JDBC_URL"), properties);
             var statement = connection.createStatement();
             var rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as("Query must return a row").isTrue();
            return rs.getLong(1);
        }
    }

    static String tokenFor(String username, String password) throws Exception {
        String form = "grant_type=password"
            + "&client_id=" + enc(System.getenv("STRATUS_KEYCLOAK_CLIENT_ID"))
            + "&client_secret=" + enc(System.getenv("STRATUS_KEYCLOAK_CLIENT_SECRET"))
            + "&username=" + enc(username)
            + "&password=" + enc(password);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(keycloakBase() + "/realms/" + realm() + "/protocol/openid-connect/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
            .as("Token request must succeed: %s", response.body())
            .isBetween(200, 299);

        return JSON.readTree(response.body()).get("access_token").asText();
    }

    static JsonNode getJson(String url, String bearer) throws Exception {
        return JSON.readTree(get(url, bearer).body());
    }

    static HttpResponse<String> get(String url, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET();
        if (bearer != null && !bearer.isBlank()) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String keycloakBase() {
        return System.getenv("STRATUS_KEYCLOAK_BASE_URL");
    }

    static String realm() {
        return System.getenv("STRATUS_KEYCLOAK_REALM");
    }

    static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
```

### Running the verification suite

```bash
export STRATUS_KEYCLOAK_BASE_URL=https://keycloak.stratus.local:8443
export STRATUS_KEYCLOAK_REALM=stratus
export STRATUS_KEYCLOAK_CLIENT_ID=stratus-verifier
export STRATUS_KEYCLOAK_CLIENT_SECRET=<client secret>
export STRATUS_TEST_USERNAME=analyst_crm
export STRATUS_TEST_PASSWORD=<password>
export STRATUS_RESTRICTED_USERNAME=analyst_restricted
export STRATUS_RESTRICTED_PASSWORD=<password>
export STRATUS_TRINO_JDBC_URL='jdbc:trino://trino-coordinator.stratus.local:8443/stratus?SSL=true'
export STRATUS_POLARIS_BASE_URL=https://polaris.stratus.local:8181
export STRATUS_AIRFLOW_BASE_URL=https://airflow.stratus.local
export STRATUS_RANGER_BASE_URL=https://ranger.stratus.local:6080
export STRATUS_ATLAS_BASE_URL=https://atlas.stratus.local:21000

mvn test -pl . -Dtest=IdentitySecurityVerificationTest
```

The verification suite should be extended with command-line Kerberos checks in the implementation repository if the build host can execute `kinit`, `klist`, and `kvno`.

---

## 15. Operational Checks

### FreeIPA

```bash
ipa user-find analyst_crm
ipa group-show analysts-crm
kinit platform_admin
klist
```

Expected: users and groups exist, Kerberos tickets can be issued, and host enrollment resolves FreeIPA users through SSSD.

### Keycloak

```bash
curl --fail https://keycloak.stratus.local:8443/realms/stratus/.well-known/openid-configuration
curl --fail https://keycloak.stratus.local:9000/health/ready
```

Expected: OIDC discovery works and the health endpoint reports ready.

### Ranger group sync

```bash
curl -s -u admin:<password> \
  https://ranger.stratus.local:6080/service/xusers/users/userName/analyst_crm
```

Expected: user exists in Ranger and has groups imported from FreeIPA.

### Trino authenticated query

```bash
trino \
  --server https://trino-coordinator.stratus.local:8443 \
  --user analyst_crm \
  --external-authentication \
  --execute "SELECT count(*) FROM stratus.gold.verification_customer_summary"
```

Expected: authenticated query succeeds for allowed data, and denied PII query still fails for a user without `restricted-data-users`.

### TLS posture

Run `curl` without insecure flags against every HTTPS endpoint. No normal operational command should require `-k`, `--insecure`, or disabled certificate validation.

### Earlier increment regression

Re-run the core verification suites:

```bash
mvn test -pl . -Dtest=TrinoQueryVerificationTest
mvn test -pl . -Dtest=GovernanceVerificationTest
mvn test -pl . -Dtest=AirflowOrchestrationVerificationTest
```

If Airflow or Polaris authentication changes require updated test clients, update the test clients and keep the behavioral assertions unchanged.

---

## 16. Completion Gate

Increment 7 is complete when all of the following are true:

- [ ] FreeIPA server is deployed, backed up, and reachable at `ipa.stratus.local`
- [ ] DNS names used by clients resolve consistently and match certificate SANs
- [ ] Kerberos realm `STRATUS.LOCAL` is operational
- [ ] FreeIPA LDAP and LDAPS are operational
- [ ] FreeIPA Dogtag CA issues service certificates
- [ ] Platform hosts are enrolled with FreeIPA and SSSD
- [ ] Platform groups exist in FreeIPA
- [ ] Platform users and service principals exist in FreeIPA
- [ ] Required keytabs exist only on intended hosts with owner-only permissions
- [ ] Keycloak is deployed with PostgreSQL and HTTPS
- [ ] Keycloak realm `stratus` exists
- [ ] Keycloak federates users and groups from FreeIPA LDAP
- [ ] Keycloak tokens include the group claims needed by platform services
- [ ] Keycloak tokens validate issuer, audience, signature, and expiration for all OIDC consumers
- [ ] OIDC clients exist for Trino, Airflow, Polaris where supported, and verification utilities
- [ ] Ranger usersync imports FreeIPA users and groups
- [ ] Ranger policies are migrated to FreeIPA groups
- [ ] Atlas authenticates against FreeIPA LDAP
- [ ] Trino coordinator uses HTTPS and Keycloak/OIDC for client access
- [ ] Airflow UI/API authentication is hardened for Airflow 3.2.2 and Keycloak
- [ ] Polaris rejects unauthenticated catalog access
- [ ] Polaris, Trino, Spark, and Airflow still resolve Iceberg tables only through Polaris
- [ ] FreeIPA-issued certificates replace lab self-signed certificates for platform endpoints
- [ ] Clients no longer require insecure TLS flags for normal operation
- [ ] `stratus-gold` and `stratus-platform` have server-side encryption enabled
- [ ] Bootstrap local users from earlier increments are disabled or removed from normal operation
- [ ] `IdentitySecurityVerificationTest` passes
- [ ] Increment 4, 5, and 6 verification behavior still passes after hardening

When all gates are checked, the Phase 1 batch, query, governance, and identity foundation is complete.

---

## 17. Troubleshooting

### Keycloak cannot sync FreeIPA users

- Confirm LDAPS connectivity from Keycloak to `ipa.stratus.local:636`
- Confirm Keycloak trusts the FreeIPA CA
- Confirm the LDAP bind DN and password are correct
- Confirm the users DN and groups DN match the FreeIPA directory layout
- Check Keycloak logs for LDAP mapper errors

### Trino OAuth login fails

- Confirm Trino coordinator uses HTTPS
- Confirm the Keycloak client redirect URI includes `https://trino-coordinator.stratus.local:8443/oauth2/callback`
- Confirm `http-server.authentication.oauth2.issuer` exactly matches the Keycloak realm issuer
- Confirm the Trino shared secret is configured
- Confirm the Trino container trusts the Keycloak certificate chain

### Ranger policies stop applying after usersync migration

- Confirm Ranger usersync imported the expected FreeIPA groups
- Confirm policies reference group names, not removed local users
- Confirm the query user appears in the expected group in Ranger
- Check Trino coordinator logs for Ranger policy refresh errors

### Atlas login fails

- Confirm Atlas LDAP settings point to FreeIPA LDAPS
- Confirm Atlas trusts the FreeIPA CA
- Confirm the user exists in FreeIPA and has required group membership
- Check Atlas logs for bind or user search errors

### Spark jobs fail after certificate replacement

- Confirm the Spark image or mounted truststore includes the FreeIPA CA
- Confirm Polaris and Ceph RGW certificates include correct SANs
- Confirm Spark catalog and S3 client settings no longer disable certificate validation
- Re-run the Increment 3 Spark verification suite

### Kerberos ticket acquisition fails

- Confirm DNS forward and reverse resolution for the host
- Confirm `/etc/krb5.conf` points to `STRATUS.LOCAL`
- Confirm the principal exists in FreeIPA
- Confirm the keytab is readable only by the intended service user
- Use `kinit -kt <keytab> <principal>` and inspect `klist`

### TLS still requires insecure flags

- Confirm the service presents the FreeIPA-issued certificate, not the old lab certificate
- Confirm the certificate SAN includes the hostname clients use
- Confirm the client container or JVM truststore contains the FreeIPA CA
- Remove stale truststore mounts from earlier increments

### User has correct FreeIPA group but Trino still denies access

- Confirm Keycloak token contains the expected group claim
- Confirm Ranger usersync imported the same FreeIPA group
- Confirm the Ranger policy references the group name exactly
- Confirm Trino maps the authenticated OIDC principal to the expected username
- Check Ranger audit records for the policy id that denied the request

### Token is accepted by one service but rejected by another

- Confirm both services use the same Keycloak realm issuer
- Confirm the token audience includes the target client
- Confirm the service container trusts the Keycloak TLS certificate
- Confirm the service clock is synchronized; expired or not-yet-valid tokens often indicate time drift
- Confirm the service validates the current Keycloak signing key after key rotation

---

## 18. References

- FreeIPA documentation: https://www.freeipa.org/page/Documentation
- Red Hat Identity Management documentation: https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/9/html/managing_idm_users_groups_hosts_and_access_control_rules/
- Keycloak documentation: https://www.keycloak.org/documentation
- Keycloak server container guide: https://www.keycloak.org/server/containers
- Keycloak server administration guide: https://www.keycloak.org/docs/latest/server_admin/
- Trino OAuth2 authentication: https://trino.io/docs/current/security/oauth2.html
- Trino Kerberos authentication: https://trino.io/docs/current/security/kerberos.html
- Trino secure internal communication: https://trino.io/docs/current/security/internal-communication.html
- Airflow public API authentication: https://airflow.apache.org/docs/apache-airflow/stable/security/api.html
- Airflow security documentation: https://airflow.apache.org/docs/apache-airflow/stable/security/
- Apache Ranger: https://ranger.apache.org/
- Apache Atlas: https://atlas.apache.org/
- Stratus Phase 1 implementation plan: [stratus_implementation_plan_phase1.md](stratus_implementation_plan_phase1.md)
- Stratus architecture: [on_prem_data_fabric_architecture.md](stratus_on_prem_data_fabric_architecture.md)
- Increment 1 — Ceph object storage foundation: [increment1_ceph.md](increment1_ceph.md)
- Increment 2 — Iceberg and Polaris: [increment2_iceberg_polaris.md](increment2_iceberg_polaris.md)
- Increment 3 — Spark: [increment3_spark.md](increment3_spark.md)
- Increment 4 — Airflow: [increment4_airflow.md](increment4_airflow.md)
- Increment 5 — Trino: [increment5_trino.md](increment5_trino.md)
- Increment 6 — Atlas and Ranger: [increment6_atlas_ranger.md](increment6_atlas_ranger.md)
