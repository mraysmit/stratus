$ErrorActionPreference = 'Stop'

if (Get-Command openssl -ErrorAction SilentlyContinue) {
    Write-Host "OpenSSL is already installed: $(& openssl version)"
    exit 0
}

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw @'
OpenSSL is not installed and winget is unavailable.
Install or update Microsoft App Installer, then rerun this script:
https://learn.microsoft.com/windows/package-manager/winget/
'@
}

Write-Host 'Installing OpenSSL Light with winget...'
& winget install --id ShiningLight.OpenSSL.Light --exact --accept-package-agreements --accept-source-agreements
if ($LASTEXITCODE -ne 0) { throw "winget failed with exit code $LASTEXITCODE" }

# winget installers update the persistent PATH, but not always the current process.
$env:Path = @(
    [Environment]::GetEnvironmentVariable('Path', 'Machine')
    [Environment]::GetEnvironmentVariable('Path', 'User')
) -join [IO.Path]::PathSeparator

$openssl = Get-Command openssl -ErrorAction SilentlyContinue
if (-not $openssl) {
    $knownLocations = @(
        (Join-Path $env:ProgramFiles 'OpenSSL-Win64\bin\openssl.exe')
        (Join-Path ${env:ProgramFiles(x86)} 'OpenSSL-Win32\bin\openssl.exe')
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }
    if ($knownLocations.Count -gt 0) {
        $env:Path = "$(Split-Path -Parent $knownLocations[0])$([IO.Path]::PathSeparator)$env:Path"
        $openssl = Get-Command openssl -ErrorAction SilentlyContinue
    }
}

if (-not $openssl) {
    throw 'OpenSSL was installed but is not available in this process. Open a new PowerShell window and run openssl version.'
}

Write-Host "Installed OpenSSL: $(& openssl version)"
