$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
Import-HarnessEnvironment
foreach ($bucket in 'stratus-landing', 'stratus-bronze', 'stratus-silver', 'stratus-gold', 'stratus-platform') {
    Invoke-HarnessCompose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt mkdir "cephrgw:$bucket"
    Write-Host "READY bucket=$bucket"
}
Invoke-HarnessCompose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt mkdir "deniedowner:$env:CEPH_RGW_DENIED_BUCKET"
Write-Host "READY isolated-policy-bucket=$env:CEPH_RGW_DENIED_BUCKET owner=$env:CEPH_DENIED_UID"
