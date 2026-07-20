$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/common.ps1')
Import-HarnessEnvironment

$evidenceDir = Join-Path $script:HarnessDir 'evidence'
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')

Write-HarnessLog '=== NEGATIVE TEST 1/3: invalid credentials — authentication failures below are EXPECTED ==='
$authEvidence = Join-Path $evidenceDir "storage-invalid-credentials-$timestamp.json"
$authArgs = @('run', '--rm', '--no-deps', '-T', '-e', 'STRATUS_VERIFICATION_MODE=AUTH_FAILURE',
    '-e', 'CEPH_RGW_SECRET_KEY=deliberately-invalid-local-secret',
    '-e', "STRATUS_EVIDENCE_FILE=/evidence/storage-invalid-credentials-$timestamp.json", 'verifier',
    'java', '-jar', '/opt/stratus/storage-verifier.jar')
Invoke-HarnessCompose @authArgs
if (-not (Test-Path -LiteralPath $authEvidence) -or
    -not (Select-String -LiteralPath $authEvidence -Pattern '"name":"invalid-credentials-rejected","passed":true' -SimpleMatch -Quiet)) {
    throw "Verifier exited successfully but the evidence does not show invalid credentials being rejected: $authEvidence"
}

Write-HarnessLog '=== NEGATIVE TEST 2/3: cross-identity access — access-denied errors below are EXPECTED ==='
$policyEvidence = Join-Path $evidenceDir "storage-cross-identity-denial-$timestamp.json"
$policyArgs = @('run', '--rm', '--no-deps', '-T', '-e', 'STRATUS_VERIFICATION_MODE=ACCESS_DENIED',
    '-e', "STRATUS_EVIDENCE_FILE=/evidence/storage-cross-identity-denial-$timestamp.json",
    'verifier', 'java', '-jar', '/opt/stratus/storage-verifier.jar')
Invoke-HarnessCompose @policyArgs
if (-not (Test-Path -LiteralPath $policyEvidence) -or
    -not (Select-String -LiteralPath $policyEvidence -Pattern '"name":"cross-identity-access-denied","passed":true' -SimpleMatch -Quiet)) {
    throw "Verifier exited successfully but the evidence does not show the cross-identity denial: $policyEvidence"
}

Write-HarnessLog '=== NEGATIVE TEST 3/3: untrusted TLS — PKIX certificate errors below are EXPECTED ==='
$invocation = Get-HarnessComposeInvocation
$tlsEvidence = Join-Path $evidenceDir "storage-untrusted-tls-$timestamp.log"
$tlsOutput = & $invocation.Runtime @($invocation.BaseArgs) run --rm --no-deps -T verifier-untrusted java -jar /opt/stratus/storage-verifier.jar 2>&1
$tlsExit = $LASTEXITCODE
$tlsHeader = "$((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')) Untrusted TLS negative-test capture: output of a verifier run WITHOUT the Compose CA; the PKIX failure below is the expected, asserted result."
@($tlsHeader) + $tlsOutput | Tee-Object -FilePath $tlsEvidence
if ($tlsExit -ne 2) { throw "Expected untrusted TLS verifier exit code 2 but received $tlsExit" }
if (($tlsOutput -join "`n") -notmatch 'PKIX|SSLHandshake|certification path') {
    throw 'Verifier failed, but not because Java rejected the untrusted TLS certificate'
}

Write-HarnessLog '=== NEGATIVE TESTS COMPLETE: all three failures occurred as required and were asserted ==='
Write-HarnessLog "PASS invalid-credentials evidence=$authEvidence"
Write-HarnessLog "PASS cross-identity-denial evidence=$policyEvidence"
Write-HarnessLog "PASS untrusted-tls evidence=$tlsEvidence"
