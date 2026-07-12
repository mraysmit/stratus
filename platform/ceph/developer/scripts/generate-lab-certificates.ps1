$ErrorActionPreference = 'Stop'
if (-not (Get-Command openssl -ErrorAction SilentlyContinue)) { throw 'openssl is required' }
$harness = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
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
