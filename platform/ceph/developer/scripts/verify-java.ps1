$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Import-HarnessEnvironment
$evidenceDir = Join-Path $script:HarnessDir 'evidence'
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
$evidence = Join-Path $evidenceDir "storage-verification-$timestamp.json"
Invoke-HarnessCompose exec -T verifier java -jar /opt/stratus/storage-contract-verifier.jar | Tee-Object -FilePath $evidence
Write-Host "Evidence: $evidence"
