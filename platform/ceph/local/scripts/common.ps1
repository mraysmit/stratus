$ErrorActionPreference = 'Stop'
$script:HarnessDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:RepoDir = (Resolve-Path (Join-Path $script:HarnessDir '..\..\..')).Path

# All harness status output carries an ISO-8601 UTC timestamp.
function Write-HarnessLog {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "$((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')) $Message"
}

# Loads .env without validating certificates or endpoints. Teardown paths use
# this so a half-configured harness can still be shut down or reset.
function Import-HarnessEnvironmentFile {
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
}

function Import-HarnessEnvironment {
    Import-HarnessEnvironmentFile
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

function Get-HarnessComposeInvocation {
    $implementation = if ($env:COMPOSE_IMPLEMENTATION) { $env:COMPOSE_IMPLEMENTATION } else { 'auto' }
    $baseArgs = @('compose', '--project-directory', $script:HarnessDir, '--env-file', (Join-Path $script:HarnessDir '.env'), '-f', (Join-Path $script:HarnessDir 'compose.yaml'))
    if (($implementation -eq 'docker') -or ($implementation -eq 'auto' -and (Get-Command docker -ErrorAction SilentlyContinue))) {
        return @{ Runtime = 'docker'; BaseArgs = $baseArgs }
    }
    if (($implementation -eq 'podman') -or ($implementation -eq 'auto')) {
        if (-not (Get-Command podman -ErrorAction SilentlyContinue)) { throw 'Neither Docker Compose nor Podman is available' }
        return @{ Runtime = 'podman'; BaseArgs = $baseArgs }
    }
    throw 'COMPOSE_IMPLEMENTATION must be auto, docker, or podman'
}

function Invoke-HarnessCompose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArgs)
    $invocation = Get-HarnessComposeInvocation
    & $invocation.Runtime @($invocation.BaseArgs) @ComposeArgs
    if ($LASTEXITCODE -ne 0) { throw "Compose command failed with exit code $LASTEXITCODE" }
}

# The harness pins its network to 172.28.0.0/24. A foreign network on that
# subnet (for example a cluster left running under an old project name) makes
# 'compose up' fail with a cryptic pool-overlap error; fail early and name it.
function Assert-HarnessSubnetFree {
    $runtime = (Get-HarnessComposeInvocation).Runtime
    foreach ($network in (& $runtime network ls --format '{{.Name}}')) {
        if ($network -like 'stratus-ceph-local_*') { continue }
        $subnets = & $runtime network inspect $network --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' 2>$null
        if ("$subnets" -match '172\.28\.0\.0/24') {
            throw "Network '$network' already uses the harness subnet 172.28.0.0/24. Tear down whatever owns it (for example: $runtime compose -p <old-project> down) and retry."
        }
    }
}

# Tears down by compose project name alone, so it works even when .env is
# missing and the compose file's required variables cannot be interpolated.
function Invoke-HarnessComposeTeardown {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArgs)
    $invocation = Get-HarnessComposeInvocation
    & $invocation.Runtime compose --project-name stratus-ceph-local @ComposeArgs
    if ($LASTEXITCODE -ne 0) { throw "Compose command failed with exit code $LASTEXITCODE" }
}
