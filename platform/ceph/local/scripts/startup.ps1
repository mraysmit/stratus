$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$envFile = Join-Path $script:HarnessDir '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $script:HarnessDir '.env.template') -Destination $envFile
}
if (-not (Test-Path -LiteralPath (Join-Path $script:HarnessDir 'certs\stratus-ca.crt'))) {
    & (Join-Path $PSScriptRoot 'generate-lab-certificates.ps1')
}
Import-HarnessEnvironment
New-Item -ItemType Directory -Force -Path (Join-Path $script:HarnessDir 'evidence') | Out-Null
Invoke-HarnessCompose config --quiet
Invoke-HarnessCompose up --detach --remove-orphans --wait
Invoke-HarnessCompose ps
