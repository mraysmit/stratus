$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

# Behavior self-test for the harness scripts. Verifies what the static
# guardrails in testing/repo-guardrails cannot: certificate renewal, rejection
# of a vacuous verifier, and teardown from broken states. Requires Docker or
# Podman and a fully stopped harness with no preserved cluster volumes.

$runtime = (Get-HarnessComposeInvocation).Runtime
$cephImage = 'quay.io/ceph/ceph:v20.2.2@sha256:6b4b5ae33acd3d736eb26d2a19238bce71a22f9cfb99cca887ba6312d0957644'
$fakeImage = 'stratus/selftest-vacuous-verifier'
$envFile = Join-Path $script:HarnessDir '.env'

if (& $runtime ps -q --filter label=com.docker.compose.project=stratus-ceph-local) {
    throw 'Stop the harness before running the self-test (scripts\shutdown.ps1)'
}
if (& $runtime volume ls -q --filter label=com.docker.compose.project=stratus-ceph-local) {
    throw 'Cluster volumes exist and the self-test exercises destructive reset. Run scripts\reset.ps1 -Force first if losing them is intended.'
}
Assert-HarnessSubnetFree

function Invoke-OpenSsl {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    if (Get-Command openssl -ErrorAction SilentlyContinue) {
        Push-Location $script:HarnessDir
        try { & openssl @Arguments 2>$null } finally { Pop-Location }
    } else {
        & $runtime run --rm --volume "$($script:HarnessDir):/work" --workdir /work --entrypoint openssl $cephImage @Arguments 2>$null
    }
    $script:OpenSslExitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $LASTEXITCODE -ne 0) { throw "openssl $($Arguments -join ' ') failed" }
}

$tmpDir = Join-Path ([IO.Path]::GetTempPath()) ("stratus-selftest-" + [guid]::NewGuid().ToString('n'))
New-Item -ItemType Directory -Path $tmpDir | Out-Null
$createdEnv = $false
$movedEnv = $null
try {
    Write-Host ''
    Write-HarnessLog '=== SELFTEST: near-expiry leaf certificate renews while preserving the CA ==='
    & (Join-Path $PSScriptRoot 'generate-lab-certificates.ps1') | Out-Null
    $caBefore = Invoke-OpenSsl -Arguments @('x509', '-sha256', '-fingerprint', '-noout', '-in', 'certs/stratus-ca.crt')
    [IO.File]::WriteAllText((Join-Path $script:HarnessDir 'private\rgw-extensions.cnf'),
        "subjectAltName=DNS:object-store.stratus.local`nextendedKeyUsage=serverAuth`n")
    Invoke-OpenSsl -Arguments @('req', '-newkey', 'rsa:3072', '-nodes', '-sha256',
        '-subj', '/CN=object-store.stratus.local',
        '-keyout', 'private/object-store.stratus.local.key', '-out', 'certs/object-store.stratus.local.csr')
    Invoke-OpenSsl -Arguments @('x509', '-req', '-sha256', '-days', '3', '-in', 'certs/object-store.stratus.local.csr',
        '-CA', 'certs/stratus-ca.crt', '-CAkey', 'private/stratus-lab-ca.key', '-CAcreateserial',
        '-extfile', 'private/rgw-extensions.cnf', '-out', 'certs/object-store.stratus.local.crt')
    & (Join-Path $PSScriptRoot 'generate-lab-certificates.ps1') | Out-Null
    Invoke-OpenSsl -AllowFailure -Arguments @('x509', '-checkend', '604800', '-noout',
        '-in', 'certs/object-store.stratus.local.crt') | Out-Null
    if ($script:OpenSslExitCode -ne 0) {
        throw 'Leaf certificate was not renewed although it was within the renewal window'
    }
    $caAfter = Invoke-OpenSsl -Arguments @('x509', '-sha256', '-fingerprint', '-noout', '-in', 'certs/stratus-ca.crt')
    if ("$caBefore" -ne "$caAfter") {
        throw 'The CA changed during leaf renewal; renewal must preserve the CA'
    }
    Write-HarnessLog 'PASS certificate-renewal'

    Write-Host ''
    Write-HarnessLog '=== SELFTEST: verify-security rejects a verifier that exits 0 without denial evidence ==='
    if (-not (Test-Path -LiteralPath $envFile)) {
        (Get-Content -LiteralPath (Join-Path $script:HarnessDir '.env.template')) `
            -replace 'generated-at-first-startup', 'selftest-placeholder' | Set-Content -LiteralPath $envFile
        $createdEnv = $true
    }
    [IO.File]::WriteAllText((Join-Path $tmpDir 'java'), "#!/bin/bash`necho `"{}`"`nexit 0`n")
    [IO.File]::WriteAllText((Join-Path $tmpDir 'Dockerfile'), "FROM $cephImage`nCOPY --chmod=0755 java /usr/local/bin/java`n")
    & $runtime build -t $fakeImage $tmpDir | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Failed to build the vacuous verifier image' }
    Copy-Item -LiteralPath $envFile -Destination (Join-Path $tmpDir 'env-original')
    (Get-Content -LiteralPath (Join-Path $tmpDir 'env-original')) `
        -replace '^VERIFIER_IMAGE=.*', "VERIFIER_IMAGE=$fakeImage" | Set-Content -LiteralPath $envFile
    $marker = Get-Date
    $outputFile = Join-Path $tmpDir 'verify-security.out'
    $failedAsExpected = $false
    try {
        & (Join-Path $PSScriptRoot 'verify-security.ps1') *> $outputFile
    } catch {
        $failedAsExpected = $true
        Add-Content -LiteralPath $outputFile -Value $_.Exception.Message
    }
    Copy-Item -LiteralPath (Join-Path $tmpDir 'env-original') -Destination $envFile -Force
    Remove-Item -LiteralPath (Join-Path $tmpDir 'env-original') -Force
    Get-ChildItem (Join-Path $script:HarnessDir 'evidence') -Filter 'storage-*' -File |
        Where-Object { $_.LastWriteTime -gt $marker } | Remove-Item -Force
    if (-not $failedAsExpected) {
        throw 'verify-security accepted a verifier that exits 0 without denial evidence'
    }
    $capturedOutput = Get-Content -LiteralPath $outputFile -Raw
    if ($capturedOutput -notmatch 'does not show invalid credentials being rejected') {
        throw "verify-security failed for an unexpected reason: $capturedOutput"
    }
    Write-HarnessLog 'PASS vacuous-verifier-rejected'

    Write-Host ''
    Write-HarnessLog '=== SELFTEST: shutdown and destructive reset work without .env ==='
    if ($createdEnv) {
        Remove-Item -LiteralPath $envFile -Force
        $createdEnv = $false
    } elseif (Test-Path -LiteralPath $envFile) {
        $movedEnv = Join-Path $tmpDir 'env-backup'
        Move-Item -LiteralPath $envFile -Destination $movedEnv
    }
    & (Join-Path $PSScriptRoot 'shutdown.ps1') | Out-Null
    & (Join-Path $PSScriptRoot 'reset.ps1') -Force | Out-Null
    if ($movedEnv) {
        Move-Item -LiteralPath $movedEnv -Destination $envFile
        $movedEnv = $null
    }
    Write-HarnessLog 'PASS teardown-without-env'

    Write-Host ''
    Write-HarnessLog 'SELFTEST PASS: certificate-renewal, vacuous-verifier-rejected, teardown-without-env'
} finally {
    if (Test-Path -LiteralPath (Join-Path $tmpDir 'env-original')) {
        Copy-Item -LiteralPath (Join-Path $tmpDir 'env-original') -Destination $envFile -Force
    }
    if ($movedEnv -and (Test-Path -LiteralPath $movedEnv)) {
        Move-Item -LiteralPath $movedEnv -Destination $envFile -Force
    }
    if ($createdEnv -and (Test-Path -LiteralPath $envFile)) {
        Remove-Item -LiteralPath $envFile -Force
    }
    & $runtime rmi $fakeImage *> $null
    Remove-Item -Recurse -Force $tmpDir -ErrorAction SilentlyContinue
}
