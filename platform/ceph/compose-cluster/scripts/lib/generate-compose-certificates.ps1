$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$harness = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$renewWindowSeconds = 604800

# Same pinned image compose uses; only needed when host OpenSSL is unavailable.
$cephImage = 'quay.io/ceph/ceph:v20.2.2@sha256:6b4b5ae33acd3d736eb26d2a19238bce71a22f9cfb99cca887ba6312d0957644'

# Certificates are regenerated when absent or expiring within seven days. Leaf
# renewal preserves the existing CA; only an expiring CA forces re-trusting.
$generator = @'
set -euo pipefail
umask 077
mkdir -p certs private
renew_window_seconds=604800
ca_key=private/stratus-lab-ca.key
ca_cert=certs/stratus-ca.crt
rgw_key=private/object-store.stratus.local.key
rgw_csr=certs/object-store.stratus.local.csr
rgw_cert=certs/object-store.stratus.local.crt
extensions=private/rgw-extensions.cnf
needs_renewal() {
  { [ -f "$1" ] && [ -f "$2" ]; } || return 0
  openssl x509 -checkend "$renew_window_seconds" -noout -in "$2" >/dev/null 2>&1 && return 1
  return 0
}
key_matches_certificate() {
  { [ -f "$1" ] && [ -f "$2" ]; } || return 1
  cert_public="$(openssl x509 -in "$2" -pubkey -noout 2>/dev/null)" || return 1
  key_public="$(openssl pkey -in "$1" -pubout 2>/dev/null)" || return 1
  [ "$cert_public" = "$key_public" ]
}
if needs_renewal "$ca_key" "$ca_cert" || ! key_matches_certificate "$ca_key" "$ca_cert"; then
  if [ -f "$ca_cert" ]; then
    echo "Existing Compose CA is expiring or does not match its key; regenerating it. Re-import $ca_cert wherever the old CA was trusted." >&2
  fi
  openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 365 \
    -subj "/CN=Stratus Disposable Compose CA" -keyout "$ca_key" -out "$ca_cert"
  rm -f "$rgw_key" "$rgw_cert"
fi
if needs_renewal "$rgw_key" "$rgw_cert" || ! key_matches_certificate "$rgw_key" "$rgw_cert"; then
  openssl req -newkey rsa:3072 -nodes -sha256 -subj "/CN=object-store.stratus.local" \
    -keyout "$rgw_key" -out "$rgw_csr"
  printf 'subjectAltName=DNS:object-store.stratus.local\nextendedKeyUsage=serverAuth\n' >"$extensions"
  openssl x509 -req -sha256 -days 90 -in "$rgw_csr" -CA "$ca_cert" -CAkey "$ca_key" -CAcreateserial \
    -extfile "$extensions" -out "$rgw_cert"
fi
# Public certificates must be readable by non-root client containers; private
# keys remain owner-only even though all files were created under umask 077.
chmod 0644 "$ca_cert" "$rgw_cert"
chmod 0600 "$ca_key" "$rgw_key"
openssl verify -CAfile "$ca_cert" "$rgw_cert"
'@
$generator = $generator.Replace("`r`n", "`n")

if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) {
    $runtime = if (Get-Command docker -ErrorAction SilentlyContinue) { 'docker' }
        elseif (Get-Command podman -ErrorAction SilentlyContinue) { 'podman' }
        else { throw 'OpenSSL, Docker, or Podman is required to generate the disposable Compose certificate.' }
    & $runtime run --rm --volume "${harness}:/work" --workdir /work --entrypoint /bin/bash $cephImage -c $generator
    if ($LASTEXITCODE -ne 0) { throw 'Containerized certificate generation failed' }
    Write-HarnessLog 'Disposable Compose certificate is current (generated via the pinned Ceph image).'
    return
}

$certs = Join-Path $harness 'certs'
$private = Join-Path $harness 'private'
New-Item -ItemType Directory -Force -Path $certs, $private | Out-Null
$caKey = Join-Path $private 'stratus-lab-ca.key'
$caCert = Join-Path $certs 'stratus-ca.crt'
$rgwKey = Join-Path $private 'object-store.stratus.local.key'
$rgwCsr = Join-Path $certs 'object-store.stratus.local.csr'
$rgwCert = Join-Path $certs 'object-store.stratus.local.crt'
$extensions = Join-Path $private 'rgw-extensions.cnf'

function Test-RenewalNeeded([string]$KeyPath, [string]$CertPath) {
    if (-not (Test-Path -LiteralPath $KeyPath) -or -not (Test-Path -LiteralPath $CertPath)) { return $true }
    & openssl x509 -checkend $renewWindowSeconds -noout -in $CertPath *> $null
    return ($LASTEXITCODE -ne 0)
}

function Test-KeyMatchesCertificate([string]$KeyPath, [string]$CertPath) {
    if (-not (Test-Path -LiteralPath $KeyPath) -or -not (Test-Path -LiteralPath $CertPath)) { return $false }
    $certPublic = (& openssl x509 -in $CertPath -pubkey -noout 2>$null) -join "`n"
    if ($LASTEXITCODE -ne 0) { return $false }
    $keyPublic = (& openssl pkey -in $KeyPath -pubout 2>$null) -join "`n"
    return ($LASTEXITCODE -eq 0 -and $certPublic -eq $keyPublic)
}

if ((Test-RenewalNeeded $caKey $caCert) -or -not (Test-KeyMatchesCertificate $caKey $caCert)) {
    if (Test-Path -LiteralPath $caCert) {
        Write-Warning "Existing Compose CA is expiring or does not match its key; regenerating it. Re-import $caCert wherever the old CA was trusted."
    }
    & openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 365 -subj '/CN=Stratus Disposable Compose CA' -keyout $caKey -out $caCert
    if ($LASTEXITCODE -ne 0) { throw 'CA generation failed' }
    Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $rgwKey, $rgwCert
}
if ((Test-RenewalNeeded $rgwKey $rgwCert) -or -not (Test-KeyMatchesCertificate $rgwKey $rgwCert)) {
    & openssl req -newkey rsa:3072 -nodes -sha256 -subj '/CN=object-store.stratus.local' -keyout $rgwKey -out $rgwCsr
    if ($LASTEXITCODE -ne 0) { throw 'RGW key generation failed' }
    @('subjectAltName=DNS:object-store.stratus.local', 'extendedKeyUsage=serverAuth') | Set-Content -LiteralPath $extensions -Encoding ascii
    & openssl x509 -req -sha256 -days 90 -in $rgwCsr -CA $caCert -CAkey $caKey -CAcreateserial -extfile $extensions -out $rgwCert
    if ($LASTEXITCODE -ne 0) { throw 'RGW certificate generation failed' }
}
& openssl verify -CAfile $caCert $rgwCert
if ($LASTEXITCODE -ne 0) { throw 'RGW certificate verification failed' }

# Best-effort: restrict the private-key directory to the current user.
try {
    $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls $private /inheritance:r /grant:r "${currentUser}:(OI)(CI)F" *> $null
} catch { }

Write-HarnessLog "Disposable Compose certificate is current. Apply $rgwCert and its protected key to RGW; clients receive only $caCert."
