param([switch]$Force)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
if (-not $Force) {
    $answer = Read-Host 'This permanently deletes the local Ceph containers and ALL cluster configuration and data volumes. Type yes to continue'
    if ($answer -ne 'yes') { throw 'Reset cancelled' }
}
if (Test-Path -LiteralPath (Join-Path $script:HarnessDir '.env')) {
    Import-HarnessEnvironmentFile
    Invoke-HarnessCompose --profile verification down --volumes --remove-orphans
} else {
    Invoke-HarnessComposeTeardown down --volumes --remove-orphans
}
Write-HarnessLog 'Removed the disposable local Ceph containers, network, configuration volume, and data volume.'
