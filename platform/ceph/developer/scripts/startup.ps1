$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Import-HarnessEnvironment
New-Item -ItemType Directory -Force -Path (Join-Path $script:HarnessDir 'evidence') | Out-Null
& (Join-Path $script:RepoDir 'mvnw.cmd') -f (Join-Path $script:RepoDir 'pom.xml') --batch-mode --no-transfer-progress `
    --projects verification/storage-contract --also-make clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed with exit code $LASTEXITCODE" }
Invoke-HarnessCompose config --quiet
Invoke-HarnessCompose up --detach --build --remove-orphans
Invoke-HarnessCompose ps
