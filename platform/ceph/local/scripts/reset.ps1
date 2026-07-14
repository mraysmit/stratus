$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Import-HarnessEnvironment
Invoke-HarnessCompose --profile verification down --volumes --remove-orphans
Write-Host 'Removed the disposable local Ceph containers, network, configuration volume, and data volume.'
