$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$envFile = Join-Path $script:HarnessDir '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    function New-RandomHex([int]$ByteCount) {
        $buffer = [byte[]]::new($ByteCount)
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($buffer)
        [System.Convert]::ToHexString($buffer).ToLowerInvariant()
    }
    $lines = Get-Content -LiteralPath (Join-Path $script:HarnessDir '.env.template')
    $lines = $lines -replace '^CEPH_RGW_ACCESS_KEY=.*', "CEPH_RGW_ACCESS_KEY=stratus-local-$(New-RandomHex 6)"
    $lines = $lines -replace '^CEPH_RGW_SECRET_KEY=.*', "CEPH_RGW_SECRET_KEY=$(New-RandomHex 20)"
    $lines = $lines -replace '^CEPH_DENIED_ACCESS_KEY=.*', "CEPH_DENIED_ACCESS_KEY=stratus-denied-$(New-RandomHex 6)"
    $lines = $lines -replace '^CEPH_DENIED_SECRET_KEY=.*', "CEPH_DENIED_SECRET_KEY=$(New-RandomHex 20)"
    Set-Content -LiteralPath $envFile -Value $lines
    Write-HarnessLog "Generated $envFile with per-machine disposable credentials"
}
# Idempotent: generates on first run, renews when a certificate nears expiry.
& (Join-Path $PSScriptRoot 'generate-lab-certificates.ps1')
Import-HarnessEnvironment
Assert-HarnessSubnetFree
New-Item -ItemType Directory -Force -Path (Join-Path $script:HarnessDir 'evidence') | Out-Null
Invoke-HarnessCompose config --quiet
Invoke-HarnessCompose up --detach --remove-orphans --wait
Invoke-HarnessCompose ps
