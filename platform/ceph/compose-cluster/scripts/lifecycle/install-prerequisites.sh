#!/usr/bin/env bash
set -euo pipefail
# Author: Mark Raysmith <raysmith.subs@gmail.com>
# Date: 2026-07-22

# Installs the host OpenSSL CLI used for certificate generation. Optional:
# the certificate script falls back to running openssl inside the pinned Ceph
# image when the host has none, so this exists to make local generation fast
# and to give air-gapped hosts a documented path.

if command -v openssl >/dev/null 2>&1; then
  printf 'OpenSSL is already installed: %s\n' "$(openssl version)"
  exit 0
fi

# Git Bash ships its own openssl, so this branch is rare on Windows; when it
# does trigger, install via winget rather than a Linux package manager.
if [[ -n "${MSYSTEM:-}" ]]; then
  if command -v winget.exe >/dev/null 2>&1; then
    MSYS_NO_PATHCONV=1 winget.exe install --id ShiningLight.OpenSSL.Light --accept-source-agreements --accept-package-agreements
    printf 'OpenSSL installed via winget. Start a new shell so PATH updates apply, then run openssl version.\n'
    exit 0
  fi
  printf 'winget is unavailable. Install OpenSSL from https://slproweb.com/products/Win32OpenSSL.html or reinstall Git for Windows (which bundles openssl).\n' >&2
  exit 1
fi

if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  elevate=()
elif command -v sudo >/dev/null 2>&1; then
  elevate=(sudo)
else
  printf 'OpenSSL installation requires root privileges or sudo.\n' >&2
  exit 1
fi

if command -v apt-get >/dev/null 2>&1; then
  "${elevate[@]}" apt-get update
  "${elevate[@]}" apt-get install -y openssl ca-certificates
elif command -v dnf >/dev/null 2>&1; then
  "${elevate[@]}" dnf install -y openssl ca-certificates
elif command -v yum >/dev/null 2>&1; then
  "${elevate[@]}" yum install -y openssl ca-certificates
elif command -v zypper >/dev/null 2>&1; then
  "${elevate[@]}" zypper --non-interactive install openssl ca-certificates
elif command -v apk >/dev/null 2>&1; then
  "${elevate[@]}" apk add --no-cache openssl ca-certificates
elif command -v pacman >/dev/null 2>&1; then
  "${elevate[@]}" pacman -Sy --noconfirm openssl ca-certificates
else
  printf 'No supported package manager found. Install the OpenSSL CLI with your distribution package manager.\n' >&2
  exit 1
fi

command -v openssl >/dev/null 2>&1 || {
  printf 'The package manager completed, but openssl is not on PATH. Start a new shell and run openssl version.\n' >&2
  exit 1
}
printf 'Installed OpenSSL: %s\n' "$(openssl version)"
