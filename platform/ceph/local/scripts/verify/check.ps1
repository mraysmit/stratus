$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/common.ps1')
Import-HarnessEnvironment
foreach ($bucket in 'stratus-landing', 'stratus-bronze', 'stratus-silver', 'stratus-gold', 'stratus-platform') {
    Invoke-HarnessCompose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt lsf "cephrgw:$bucket/" | Out-Null
    Write-HarnessLog "PASS bucket=$bucket"
}
