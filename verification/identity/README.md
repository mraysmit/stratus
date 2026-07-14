# Identity Verification

Verifies that FreeIPA and Keycloak are deployed and providing authentication and authorisation across all platform services. Verification covers Kerberos ticket acquisition via service account keytab, LDAP group import into Ranger usersync, OIDC token issuance from Keycloak backed by FreeIPA, and token-based authentication to Polaris. Service-level checks confirm that Spark jobs with a valid keytab succeed and jobs without one are rejected, that Airflow redirects unauthenticated users to Keycloak, and that Ranger group policies take effect within minutes of a FreeIPA group change. TLS certificate replacement with FreeIPA Dogtag-issued certificates and MinIO server-side encryption for the gold and platform buckets are also verified. No service may use a shared password.

Prerequisite: `governance` verification passed against a live cluster.
