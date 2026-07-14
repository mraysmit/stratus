$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Import-HarnessEnvironment

$evidenceDir = Join-Path $script:HarnessDir 'evidence'
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')

$authEvidence = Join-Path $evidenceDir "storage-invalid-credentials-$timestamp.json"
$authArgs = @('run', '--rm', '--no-deps', '-T', '-e', 'STRATUS_VERIFICATION_MODE=AUTH_FAILURE',
    '-e', 'CEPH_RGW_SECRET_KEY=deliberately-invalid-local-secret', 'verifier',
    'java', '-jar', '/opt/stratus/storage-contract-verifier.jar')
Invoke-HarnessCompose @authArgs | Tee-Object -FilePath $authEvidence

$policyEvidence = Join-Path $evidenceDir "storage-cross-identity-denial-$timestamp.json"
$policyArgs = @('run', '--rm', '--no-deps', '-T', '-e', 'STRATUS_VERIFICATION_MODE=ACCESS_DENIED',
    'verifier', 'java', '-jar', '/opt/stratus/storage-contract-verifier.jar')
Invoke-HarnessCompose @policyArgs | Tee-Object -FilePath $policyEvidence

$implementation = if ($env:COMPOSE_IMPLEMENTATION) { $env:COMPOSE_IMPLEMENTATION } else { 'auto' }
$runtime = if ($implementation -eq 'podman') { 'podman' } elseif (Get-Command docker -ErrorAction SilentlyContinue) { 'docker' } else { 'podman' }
$baseArgs = @('compose', '--project-directory', $script:HarnessDir, '--env-file',
    (Join-Path $script:HarnessDir '.env'), '-f', (Join-Path $script:HarnessDir 'compose.yaml'))
$tlsEvidence = Join-Path $evidenceDir "storage-untrusted-tls-$timestamp.log"
$tlsOutput = & $runtime @baseArgs run --rm --no-deps -T verifier-untrusted java -jar /opt/stratus/storage-contract-verifier.jar 2>&1
$tlsExit = $LASTEXITCODE
$tlsOutput | Tee-Object -FilePath $tlsEvidence
if ($tlsExit -ne 2) { throw "Expected untrusted TLS verifier exit code 2 but received $tlsExit" }
if (($tlsOutput -join "`n") -notmatch 'PKIX|SSLHandshake|certification path') {
    throw 'Verifier failed, but not because Java rejected the untrusted TLS certificate'
}

Write-Host "PASS invalid-credentials evidence=$authEvidence"
Write-Host "PASS cross-identity-denial evidence=$policyEvidence"
Write-Host "PASS untrusted-tls evidence=$tlsEvidence"
