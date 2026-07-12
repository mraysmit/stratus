$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Import-HarnessEnvironment
foreach ($bucket in 'stratus-landing', 'stratus-bronze', 'stratus-silver', 'stratus-gold', 'stratus-platform') {
    Invoke-HarnessCompose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt mkdir "cephrgw:$bucket"
    Write-Host "READY bucket=$bucket"
}
