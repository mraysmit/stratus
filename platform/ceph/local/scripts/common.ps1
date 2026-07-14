$ErrorActionPreference = 'Stop'
$script:HarnessDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:RepoDir = (Resolve-Path (Join-Path $script:HarnessDir '..\..\..')).Path

function Import-HarnessEnvironment {
    $envFile = Join-Path $script:HarnessDir '.env'
    if (-not (Test-Path -LiteralPath $envFile)) {
        throw "Create $envFile from .env.template"
    }
    foreach ($line in Get-Content -LiteralPath $envFile) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -ne 2) { throw "Invalid .env line: $line" }
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim(), 'Process')
    }
    foreach ($name in 'CEPH_RGW_ENDPOINT', 'CEPH_RGW_ACCESS_KEY', 'CEPH_RGW_SECRET_KEY') {
        if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) { throw "$name is required" }
    }
    foreach ($relativePath in 'certs\stratus-ca.crt', 'certs\object-store.stratus.local.crt', 'private\object-store.stratus.local.key') {
        $requiredPath = Join-Path $script:HarnessDir $relativePath
        if (-not (Test-Path -LiteralPath $requiredPath)) { throw "Missing $requiredPath" }
    }
    if (-not $env:CEPH_RGW_ENDPOINT.StartsWith('https://') -and $env:CEPH_RGW_ALLOW_HTTP -ne 'true') {
        throw 'CEPH_RGW_ENDPOINT must use HTTPS unless CEPH_RGW_ALLOW_HTTP=true'
    }
}

function Invoke-HarnessCompose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArgs)
    $implementation = if ($env:COMPOSE_IMPLEMENTATION) { $env:COMPOSE_IMPLEMENTATION } else { 'auto' }
    $baseArgs = @('compose', '--project-directory', $script:HarnessDir, '--env-file', (Join-Path $script:HarnessDir '.env'), '-f', (Join-Path $script:HarnessDir 'compose.yaml'))
    if (($implementation -eq 'docker') -or ($implementation -eq 'auto' -and (Get-Command docker -ErrorAction SilentlyContinue))) {
        & docker @baseArgs @ComposeArgs
    } elseif (($implementation -eq 'podman') -or $implementation -eq 'auto') {
        if (-not (Get-Command podman -ErrorAction SilentlyContinue)) { throw 'Neither Docker Compose nor Podman is available' }
        & podman @baseArgs @ComposeArgs
    } else {
        throw 'COMPOSE_IMPLEMENTATION must be auto, docker, or podman'
    }
    if ($LASTEXITCODE -ne 0) { throw "Compose command failed with exit code $LASTEXITCODE" }
}
