$ErrorActionPreference = 'Stop'
$harness = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'OpenSSL or Docker is required to generate the disposable lab certificate.'
    }
    $cephImage = 'quay.io/ceph/ceph:v20.2.2@sha256:6b4b5ae33acd3d736eb26d2a19238bce71a22f9cfb99cca887ba6312d0957644'
    $generator = @'
set -e
mkdir -p certs private
if [ ! -f private/stratus-lab-ca.key ] || [ ! -f certs/stratus-ca.crt ]; then
  openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 365 -subj "/CN=Stratus Disposable Lab CA" -keyout private/stratus-lab-ca.key -out certs/stratus-ca.crt
fi
if [ ! -f private/object-store.stratus.local.key ] || [ ! -f certs/object-store.stratus.local.crt ]; then
  openssl req -newkey rsa:3072 -nodes -sha256 -subj "/CN=object-store.stratus.local" -keyout private/object-store.stratus.local.key -out certs/object-store.stratus.local.csr
  printf "subjectAltName=DNS:object-store.stratus.local\nextendedKeyUsage=serverAuth\n" > private/rgw-extensions.cnf
  openssl x509 -req -sha256 -days 90 -in certs/object-store.stratus.local.csr -CA certs/stratus-ca.crt -CAkey private/stratus-lab-ca.key -CAcreateserial -extfile private/rgw-extensions.cnf -out certs/object-store.stratus.local.crt
fi
openssl verify -CAfile certs/stratus-ca.crt certs/object-store.stratus.local.crt
'@
    & docker run --rm --volume "${harness}:/work" --workdir /work --entrypoint /bin/bash $cephImage -c $generator
    if ($LASTEXITCODE -ne 0) { throw 'Containerized certificate generation failed' }
    Write-Host 'Generated the disposable lab certificate using the pinned Ceph image.'
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
if (-not (Test-Path $caKey) -or -not (Test-Path $caCert)) {
    & openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 365 -subj '/CN=Stratus Disposable Lab CA' -keyout $caKey -out $caCert
    if ($LASTEXITCODE -ne 0) { throw 'CA generation failed' }
}
if (-not (Test-Path $rgwKey) -or -not (Test-Path $rgwCert)) {
    & openssl req -newkey rsa:3072 -nodes -sha256 -subj '/CN=object-store.stratus.local' -keyout $rgwKey -out $rgwCsr
    @('subjectAltName=DNS:object-store.stratus.local', 'extendedKeyUsage=serverAuth') | Set-Content -LiteralPath $extensions -Encoding ascii
    & openssl x509 -req -sha256 -days 90 -in $rgwCsr -CA $caCert -CAkey $caKey -CAcreateserial -extfile $extensions -out $rgwCert
    if ($LASTEXITCODE -ne 0) { throw 'RGW certificate generation failed' }
}
& openssl verify -CAfile $caCert $rgwCert
if ($LASTEXITCODE -ne 0) { throw 'RGW certificate verification failed' }
Write-Host "Generated disposable lab certificate. Apply $rgwCert and its protected key to RGW; clients receive only $caCert."
