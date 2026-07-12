$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Import-HarnessEnvironment
Invoke-HarnessCompose down --remove-orphans
