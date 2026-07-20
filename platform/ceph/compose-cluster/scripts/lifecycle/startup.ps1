$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/common.ps1')
function New-RandomHex([int]$ByteCount) {
    $buffer = [byte[]]::new($ByteCount)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
    [System.Convert]::ToHexString($buffer).ToLowerInvariant()
}
$envFile = Join-Path $script:HarnessDir '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    $lines = Get-Content -LiteralPath (Join-Path $script:HarnessDir '.env.template')
    $lines = $lines -replace '^CEPH_RGW_ACCESS_KEY=.*', "CEPH_RGW_ACCESS_KEY=stratus-local-$(New-RandomHex 6)"
    $lines = $lines -replace '^CEPH_RGW_SECRET_KEY=.*', "CEPH_RGW_SECRET_KEY=$(New-RandomHex 20)"
    $lines = $lines -replace '^CEPH_DENIED_ACCESS_KEY=.*', "CEPH_DENIED_ACCESS_KEY=stratus-denied-$(New-RandomHex 6)"
    $lines = $lines -replace '^CEPH_DENIED_SECRET_KEY=.*', "CEPH_DENIED_SECRET_KEY=$(New-RandomHex 20)"
    $lines = $lines -replace '^CEPH_DASHBOARD_PASSWORD=.*', "CEPH_DASHBOARD_PASSWORD=$(New-RandomHex 20)"
    Set-Content -LiteralPath $envFile -Value $lines
    # Best-effort: restrict the generated secrets to the current user (parity
    # with the bash twin's chmod 600).
    try {
        $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls $envFile /inheritance:r /grant:r "${currentUser}:F" *> $null
    } catch { }
    Write-HarnessLog "Generated $envFile with per-machine disposable credentials"
} elseif (-not (Select-String -LiteralPath $envFile -Pattern '^CEPH_DASHBOARD_PASSWORD=' -Quiet)) {
    # Backfill for .env files generated before the dashboard existed.
    Add-Content -LiteralPath $envFile -Value @(
        '',
        '# Ceph Dashboard (management console) sign-in, added by startup.',
        'CEPH_DASHBOARD_USER=stratus-dashboard',
        "CEPH_DASHBOARD_PASSWORD=$(New-RandomHex 20)"
    )
    Write-HarnessLog "Added generated dashboard credentials to $envFile"
}
# Idempotent: generates on first run, renews when a certificate nears expiry.
& (Join-Path $PSScriptRoot '../lib/generate-compose-certificates.ps1')
Import-HarnessEnvironment
Assert-HarnessSubnetFree
New-Item -ItemType Directory -Force -Path (Join-Path $script:HarnessDir 'evidence') | Out-Null
Invoke-HarnessCompose config --quiet
Invoke-HarnessCompose up --detach --remove-orphans --wait
Invoke-HarnessCompose ps
