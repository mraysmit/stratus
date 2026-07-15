$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
if (Test-Path -LiteralPath (Join-Path $script:HarnessDir '.env')) {
    Import-HarnessEnvironmentFile
    Invoke-HarnessCompose --profile verification down --remove-orphans
} else {
    Invoke-HarnessComposeTeardown down --remove-orphans
}
