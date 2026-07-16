$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/common.ps1')
Import-HarnessEnvironment
foreach ($name in 'CEPH_RGW_DENIED_BUCKET', 'CEPH_DENIED_UID') {
    if (-not [Environment]::GetEnvironmentVariable($name, 'Process')) {
        throw "$name is required; update .env from .env.template"
    }
}
foreach ($bucket in 'stratus-landing', 'stratus-bronze', 'stratus-silver', 'stratus-gold', 'stratus-platform') {
    Invoke-HarnessCompose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt mkdir "cephrgw:$bucket"
    Write-HarnessLog "READY bucket=$bucket"
}
Invoke-HarnessCompose exec -T s3client rclone --ca-cert /certs/stratus-ca.crt mkdir "deniedowner:$env:CEPH_RGW_DENIED_BUCKET"
Write-HarnessLog "READY isolated-policy-bucket=$env:CEPH_RGW_DENIED_BUCKET owner=$env:CEPH_DENIED_UID"
