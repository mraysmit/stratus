#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-08-01
source "$(dirname "$0")/../lib/ceph-compose-common.sh"

endpoint_hostname='object-store.stratus.local'
address='127.0.0.1'
check_only=false

usage() {
  cat <<'EOF'
Usage: ceph-compose-configure-hostname.sh [--check] [--address <ip>]

Idempotently maps object-store.stratus.local in the workstation hosts file.
Uses a Windows UAC prompt from Git Bash, or sudo on Linux and macOS, when the
system file is not directly writable. Existing conflicting mappings are never
overwritten automatically.
EOF
}

while (( $# > 0 )); do
  case "$1" in
    --check)
      check_only=true
      shift
      ;;
    --address)
      (( $# >= 2 )) || fail "--address requires an IP address"
      address="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "Unknown argument: $1"
      ;;
  esac
done

valid_address() {
  local octet
  if [[ "$address" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
    IFS=. read -r -a octets <<<"$address"
    for octet in "${octets[@]}"; do
      (( 10#$octet <= 255 )) || return 1
    done
    return 0
  fi
  [[ "$address" == *:* && "$address" =~ ^[0-9A-Fa-f:.]+$ && "$address" =~ [0-9A-Fa-f] ]]
}
valid_address || fail "--address must be an IPv4 or IPv6 address"

if [[ -n "${STRATUS_HOSTS_FILE:-}" ]]; then
  hosts_file="$STRATUS_HOSTS_FILE"
  custom_hosts_file=true
elif [[ -n "${MSYSTEM:-}" ]]; then
  command -v cygpath >/dev/null 2>&1 || fail "cygpath is required under Git Bash"
  windows_root="${SYSTEMROOT:-${WINDIR:-C:\\Windows}}"
  hosts_file="$(cygpath -u "$windows_root/System32/drivers/etc/hosts")"
  custom_hosts_file=false
else
  hosts_file='/etc/hosts'
  custom_hosts_file=false
fi

[[ -f "$hosts_file" ]] || fail "Hosts file does not exist: $hosts_file"

host_mappings() {
  awk -v hostname="$endpoint_hostname" '
    $1 !~ /^#/ {
      for (field = 2; field <= NF; field++) {
        if ($field ~ /^#/) break
        if ($field == hostname) print $1
      }
    }
  ' "$hosts_file"
}

mapping_state() {
  local mapping found=false
  while IFS= read -r mapping; do
    [[ -n "$mapping" ]] || continue
    found=true
    if [[ "$mapping" != "$address" ]]; then
      printf 'conflict:%s' "$mapping"
      return
    fi
  done < <(host_mappings)
  if [[ "$found" == true ]]; then
    printf 'configured'
  else
    printf 'missing'
  fi
}

state="$(mapping_state)"
case "$state" in
  configured)
    log "$endpoint_hostname already maps to $address in $hosts_file"
    exit 0
    ;;
  conflict:*)
    fail "$endpoint_hostname already maps to ${state#conflict:} in $hosts_file; resolve the conflicting entry explicitly"
    ;;
  missing)
    [[ "$check_only" == false ]] || fail "$endpoint_hostname is not configured in $hosts_file; run $0"
    ;;
esac

hosts_line="${address}\t${endpoint_hostname}\t# Stratus Ceph Compose"

append_directly() {
  if [[ -s "$hosts_file" && -n "$(tail -c 1 "$hosts_file")" ]]; then
    printf '\n' >>"$hosts_file"
  fi
  printf '%b\n' "$hosts_line" >>"$hosts_file"
}

append_with_windows_uac() {
  command -v powershell.exe >/dev/null 2>&1 || fail "PowerShell is required to request Windows administrator access"
  local helper windows_helper
  helper="$(mktemp "${TMPDIR:-/tmp}/stratus-ceph-hosts.XXXXXX.ps1")"
  trap 'rm -f "${helper:-}"' EXIT
  cat >"$helper" <<EOF
\$ErrorActionPreference = 'Stop'
\$hostsPath = Join-Path \$env:SystemRoot 'System32\\drivers\\etc\\hosts'
\$hostname = '$endpoint_hostname'
\$address = '$address'
\$mappings = foreach (\$rawLine in Get-Content -LiteralPath \$hostsPath) {
    \$content = (\$rawLine -split '#', 2)[0].Trim()
    if (-not \$content) { continue }
    \$parts = \$content -split '\\s+'
    if (\$parts.Count -gt 1 -and \$parts[1..(\$parts.Count - 1)] -contains \$hostname) { \$parts[0] }
}
\$conflict = \$mappings | Where-Object { \$_ -ne \$address } | Select-Object -First 1
if (\$conflict) { throw "\$hostname already maps to \$conflict in \$hostsPath" }
if (\$mappings -contains \$address) { exit 0 }
\$rawHosts = Get-Content -LiteralPath \$hostsPath -Raw
\$prefix = if (\$rawHosts.Length -gt 0 -and -not \$rawHosts.EndsWith("\`n")) { "\`r\`n" } else { '' }
Add-Content -LiteralPath \$hostsPath -NoNewline -Value "\$prefix\$address\`t\$hostname\`t# Stratus Ceph Compose\`r\`n"
Clear-DnsClientCache
EOF
  windows_helper="$(cygpath -w "$helper")"
  MSYS_NO_PATHCONV=1 powershell.exe -NoProfile -NonInteractive -Command \
    "\$ErrorActionPreference='Stop'; \$arguments='-NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"\"$windows_helper\"\"'; \$process=Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ArgumentList \$arguments; exit \$process.ExitCode"
}

if [[ "$custom_hosts_file" == true ]]; then
  [[ -w "$hosts_file" ]] || fail "Custom hosts file is not writable: $hosts_file"
  append_directly
elif [[ -n "${MSYSTEM:-}" ]]; then
  append_with_windows_uac
elif [[ -w "$hosts_file" ]]; then
  append_directly
elif [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  append_directly
elif command -v sudo >/dev/null 2>&1; then
  prefix=''
  [[ ! -s "$hosts_file" || -z "$(tail -c 1 "$hosts_file")" ]] || prefix=$'\n'
  printf '%s%b\n' "$prefix" "$hosts_line" | sudo tee -a "$hosts_file" >/dev/null
else
  fail "Updating $hosts_file requires root privileges or sudo"
fi

[[ "$(mapping_state)" == configured ]] || fail "Hosts file update completed but $endpoint_hostname still does not map to $address"
log "Mapped $endpoint_hostname to $address in $hosts_file"
