$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/common.ps1')
Import-HarnessEnvironment
$evidenceDir = Join-Path $script:HarnessDir 'evidence'
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$evidence = Join-Path $evidenceDir "storage-verification-$timestamp.json"
$environmentEvidence = Join-Path $evidenceDir "environment-$timestamp.json"
# Per-run log name so the verifier log correlates with this run's evidence.
$env:STRATUS_LOG_FILE = "/evidence/storage-verifier-$timestamp.%g.log"
$invocation = Get-HarnessComposeInvocation

# Environment snapshot required by the README evidence contract: runtime,
# resolved image identities, Ceph version, cluster status, and OSD state.
function Get-ImageRef([string]$Image) {
    $ref = & $invocation.Runtime image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{else}}{{.Id}}{{end}}' $Image 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $ref) { return 'unavailable' }
    return ($ref -join '')
}
function Get-ClusterJson {
    param([string[]]$Command)
    try {
        return ((Invoke-HarnessCompose exec -T mon1 @Command) -join "`n" | ConvertFrom-Json)
    } catch {
        return $null
    }
}
$platform = (& $invocation.Runtime version --format '{{.Server.Os}}/{{.Server.Arch}}' 2>$null) -join ''
if ($LASTEXITCODE -ne 0 -or -not $platform) { $platform = 'unknown' }
$runtimeVersion = (& $invocation.Runtime --version 2>$null) -join ' '
$cephVersion = 'unavailable'
try { $cephVersion = (Invoke-HarnessCompose exec -T mon1 ceph version) -join ' ' } catch { }
[ordered]@{
    description = 'Stratus verification environment snapshot: the runtime, images, and Ceph cluster state that produced the storage-verification evidence with the same timestamp'
    timestamp = $timestamp
    rgwEndpoint = $env:CEPH_RGW_ENDPOINT
    composeRuntime = $invocation.Runtime
    runtimeVersion = $runtimeVersion
    platform = $platform
    cephImage = $env:CEPH_IMAGE
    cephImageResolved = Get-ImageRef $env:CEPH_IMAGE
    verifierImage = $env:VERIFIER_IMAGE
    verifierImageResolved = Get-ImageRef $env:VERIFIER_IMAGE
    cephVersion = $cephVersion
    cephStatus = Get-ClusterJson @('ceph', 'status', '--format', 'json')
    osdTree = Get-ClusterJson @('ceph', 'osd', 'tree', '--format', 'json')
} | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath $environmentEvidence
Write-HarnessLog "Environment: $environmentEvidence"

# Probe from the verifier image because a newly created one-off container can
# briefly precede Docker's network DNS registration.
$endpointHost = ([Uri]$env:CEPH_RGW_ENDPOINT).DnsSafeHost
$dnsReady = $false
foreach ($attempt in 1..10) {
    & $invocation.Runtime @($invocation.BaseArgs) run --rm --no-deps -T `
        --entrypoint /bin/sh verifier -c 'getent hosts "$1" >/dev/null 2>&1' _ $endpointHost
    if ($LASTEXITCODE -eq 0) {
        $dnsReady = $true
        break
    }
    Start-Sleep -Seconds 1
}
if (-not $dnsReady) {
    throw "Verifier container could not resolve RGW endpoint host: $endpointHost"
}

& $invocation.Runtime @($invocation.BaseArgs) run --rm --no-deps -T `
    -e "STRATUS_EVIDENCE_FILE=/evidence/storage-verification-$timestamp.json" `
    verifier java -jar /opt/stratus/storage-verifier.jar
$verifierExit = $LASTEXITCODE
if ($verifierExit -ne 0) {
    $failedEvidence = $evidence -replace '\.json$', '-FAILED.json'
    if (Test-Path -LiteralPath $evidence) { Move-Item -LiteralPath $evidence -Destination $failedEvidence -Force }
    throw "Storage verification failed with exit code $verifierExit; evidence: $failedEvidence"
}
Write-HarnessLog "Evidence: $evidence"
Write-HarnessLog "Verifier log: $(Join-Path $evidenceDir "storage-verifier-$timestamp.0.log")"
