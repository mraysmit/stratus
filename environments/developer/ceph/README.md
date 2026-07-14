# Developer Ceph Overlay

The selected developer profile is the disposable genuine Ceph/RGW environment in `platform/ceph/local/compose.yaml`. It runs locally with Docker Desktop or Docker Engine and does not depend on an external RGW endpoint.

Local credentials, generated certificates, private keys, Ceph volumes, and evidence remain in ignored files or Docker-managed volumes. Do not record secret values here.

This profile proves real Ceph RGW/S3 behavior plus container-level quorum, replication, degraded operation/recovery, and RGW failover on one Docker host. Physical-host failure domains, dedicated devices, production durability, capacity, external load-balancer failover, and production recovery remain separate cephadm tasks.

## Verified implementation status

On 2026-07-14 the Docker profile passed:

- pinned Ceph Tentacle 20.2.2 with three-MON quorum, active/standby MGR pair, three BlueStore OSDs `up`/`in`, and two RGWs
- replicated pools across three container-level CRUSH hosts with all placement groups `active+clean`
- trusted HTTPS access through the local RGW proxy
- creation and listing of the five Stratus buckets
- rclone PUT, GET, and DELETE
- Java bucket, object, HEAD/list, multipart, and cleanup verification from a prebuilt image
- rejection of an untrusted client and absence of a published plaintext RGW port
- two-of-three monitor quorum during one-MON loss and recovery to three
- rclone and Java verification while one RGW was offline
- rclone and Java verification during one-OSD degradation, followed by recovery to `HEALTH_OK`
- destructive reset, clean recreation, and re-verification

Warnings during deliberate failure drills remain visible and are not suppressed. Steady state is `HEALTH_OK`; neither result is physical-host or production acceptance evidence.
