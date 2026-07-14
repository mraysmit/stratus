#!/usr/bin/env bash
set -euo pipefail

if command -v openssl >/dev/null 2>&1; then
  printf 'OpenSSL is already installed: %s\n' "$(openssl version)"
  exit 0
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
