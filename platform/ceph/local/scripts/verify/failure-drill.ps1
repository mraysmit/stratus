$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../lib/common.ps1')

# Failure drills against the live cluster: stop real daemons one class at a
# time, prove the S3 contract continues through the outage, and require full
# recovery to HEALTH_OK with every placement group active+clean. These are
# real failures on the real cluster; nothing is simulated. This is a
# single-Docker-host drill: it proves daemon-level failover and recovery,
# not production resilience.

Import-HarnessEnvironment

$running = Invoke-HarnessCompose ps -q
if (-not $running) {
    throw 'The harness is not running. Start it first: scripts\lifecycle\startup.ps1'
}

# 'bash -c' arguments are passed as an explicit array: a bare -c token would
# bind to Invoke-HarnessCompose's -ComposeArgs parameter by prefix.
function Invoke-Mon1Bash {
    param([Parameter(Mandatory = $true)][string]$Command)
    Invoke-HarnessCompose @('exec', '-T', 'mon1', 'bash', '-c', $Command)
}

function Wait-HarnessHealthy {
    param(
        [Parameter(Mandatory = $true)][string]$Context,
        [int]$TimeoutSeconds = 300
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ($true) {
        try {
            Invoke-Mon1Bash 'ceph status --format json | jq -e ".health.status == \"HEALTH_OK\" and ([.pgmap.pgs_by_state[].state_name] == [\"active+clean\"])"' | Out-Null
            break
        } catch {
            if ((Get-Date) -gt $deadline) {
                try { Invoke-HarnessCompose exec -T mon1 ceph status } catch { }
                throw "Cluster did not recover to HEALTH_OK with all placement groups active+clean ($Context)"
            }
            Start-Sleep -Seconds 5
        }
    }
    Write-HarnessLog "PASS recovery context=$Context health=HEALTH_OK pgs=active+clean"
}

function Write-DrillBanner {
    param([Parameter(Mandatory = $true)][string]$Title)
    Write-Host ''
    Write-HarnessLog "=== DRILL: $Title ==="
}

# A failed drill must never leave a daemon stopped; starting a running
# service is a no-op, so this restore is safe on every exit path.
try {

Wait-HarnessHealthy -Context 'baseline before drills' -TimeoutSeconds 60

Write-DrillBanner 'RGW daemon outage: the S3 contract continues through the surviving gateway'
Write-HarnessLog 'EXPECTED-DEGRADATION begin service=rgw1'
Invoke-HarnessCompose stop rgw1 | Out-Null
& (Join-Path $PSScriptRoot 'check.ps1')
Invoke-HarnessCompose start rgw1 | Out-Null
Write-HarnessLog 'EXPECTED-DEGRADATION end service=rgw1'
Wait-HarnessHealthy -Context 'after RGW restart'

Write-DrillBanner 'monitor outage: two of three monitors keep quorum and the S3 contract continues'
Write-HarnessLog 'EXPECTED-DEGRADATION begin service=mon3'
Invoke-HarnessCompose stop mon3 | Out-Null
Invoke-Mon1Bash 'for attempt in $(seq 1 60); do ceph quorum_status --format json | jq -e ".quorum | length == 2" >/dev/null && exit 0; sleep 2; done; echo "quorum did not drop to two monitors" >&2; exit 1' | Out-Null
& (Join-Path $PSScriptRoot 'check.ps1')
Invoke-HarnessCompose start mon3 | Out-Null
Write-HarnessLog 'EXPECTED-DEGRADATION end service=mon3'
Invoke-Mon1Bash 'for attempt in $(seq 1 60); do ceph quorum_status --format json | jq -e ".quorum | length == 3" >/dev/null && exit 0; sleep 2; done; echo "quorum did not return to three monitors" >&2; exit 1' | Out-Null
Wait-HarnessHealthy -Context 'after monitor restart'

Write-DrillBanner 'OSD outage: writes continue degraded, then all placement groups recover'
Write-HarnessLog 'EXPECTED-DEGRADATION begin service=osd1'
Invoke-HarnessCompose stop osd1 | Out-Null
Invoke-Mon1Bash 'for attempt in $(seq 1 60); do ceph osd stat --format json | jq -e ".num_up_osds == 2" >/dev/null && exit 0; sleep 2; done; echo "the stopped OSD was not marked down" >&2; exit 1' | Out-Null
& (Join-Path $PSScriptRoot 'check.ps1')
& (Join-Path $PSScriptRoot 'verify-java.ps1')
Invoke-HarnessCompose start osd1 | Out-Null
Write-HarnessLog 'EXPECTED-DEGRADATION end service=osd1'
Wait-HarnessHealthy -Context 'after OSD restart and backfill' -TimeoutSeconds 600

Write-Host ''
Write-HarnessLog 'FAILURE-DRILL PASS: rgw-failover, monitor-quorum-loss, osd-degraded-write-and-recovery'

} finally {
    try { Invoke-HarnessCompose start rgw1 mon3 osd1 | Out-Null } catch { }
}
