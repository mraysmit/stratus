$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Import-HarnessEnvironment
Invoke-HarnessCompose --profile verification down --remove-orphans
