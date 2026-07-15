#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Adds SPDX Apache-2.0 license headers to Java files in the Stratus project.

.DESCRIPTION
    Inserts a two-line SPDX license header at the very top of every Java file
    under the repository root:

        // Copyright <year> <author>
        // SPDX-License-Identifier: Apache-2.0

    Preserves each file's original line endings (CRLF/LF) and
    trailing-newline state, so diffs contain only the header change.

    Excluded directories: target, .history, node_modules, .git.

.PARAMETER Path
    Optional file or directory to process. A single .java file is processed
    directly; a directory is scanned recursively (with the standard
    exclusions). Defaults to the repository root when omitted.

.PARAMETER DryRun
    Shows what changes would be made without actually modifying files.

.PARAMETER Force
    Re-processes files that already have a license header: every existing
    SPDX header (and any legacy full Apache-2.0 block from an earlier
    convention) is removed and a single fresh SPDX header is inserted at the
    top.

.PARAMETER CopyrightYear
    Year used in the copyright line. Defaults to 2026.

.PARAMETER Verbose
    Shows per-file processing and skip messages.

.EXAMPLE
    .\add-license-headers.ps1 -DryRun
    Shows what changes would be made without modifying files.

.EXAMPLE
    .\add-license-headers.ps1 -Force
    Normalizes all files to a single SPDX header at the top of the file.

.EXAMPLE
    .\add-license-headers.ps1 -Path verification\storage\src\main\java\dev\stratus\verification\storage\StorageVerifier.java -Force
    Normalizes the license header of a single file.
#>

param(
    [string]$Path,
    [switch]$DryRun,
    [switch]$Force,
    [string]$CopyrightYear = "2026",
    [switch]$Verbose
)

# Configuration
$AUTHOR_NAME = "Mark Andrew Ray-Smith Cityline Ltd"
$EXCLUDED_DIRS = '\\(target|\.history|node_modules|\.git)\\'

# SPDX Apache-2.0 header template (two line comments at the top of the file)
$LICENSE_HEADER = @"
// Copyright $CopyrightYear $AUTHOR_NAME
// SPDX-License-Identifier: Apache-2.0
"@

# Function to detect the file's predominant line ending
function Get-LineEnding {
    param([string]$Content)

    if ($Content -match "`r`n") { return "`r`n" }
    return "`n"
}

# Function to check if file already has an SPDX license header
function Test-HasLicenseHeader {
    param([string]$Content)

    return $Content -match "SPDX-License-Identifier:"
}

# Function to remove every existing license block, wherever it appears: the
# current SPDX two-line header, a stray SPDX line, and any legacy full
# Apache-2.0 block from the earlier convention.
function Remove-ExistingLicense {
    param([string]$Content)

    # Legacy '/* ... Licensed under the Apache License ... */' block. The lazy
    # (?!\*/) guards keep the match inside a single comment block.
    $Content = [regex]::Replace($Content,
        '(?s)[ \t]*/\*(?:(?!\*/).)*?Licensed under the Apache License(?:(?!\*/).)*?\*/[ \t]*(\r?\n)*',
        '')

    # SPDX header: a Copyright line immediately followed by an SPDX line.
    $Content = [regex]::Replace($Content,
        '(?m)^[ \t]*//[ \t]*Copyright\b.*\r?\n[ \t]*//[ \t]*SPDX-License-Identifier:.*\r?\n?',
        '')

    # Any remaining stray SPDX line on its own.
    $Content = [regex]::Replace($Content,
        '(?m)^[ \t]*//[ \t]*SPDX-License-Identifier:.*\r?\n?',
        '')

    return $Content
}

# Function to process a single Java file
function Add-LicenseHeader {
    param(
        [string]$FilePath,
        [switch]$DryRun,
        [switch]$Force
    )

    if ($Verbose) {
        Write-Host "Processing: $FilePath" -ForegroundColor Cyan
    }

    try {
        $content = Get-Content -Path $FilePath -Raw -Encoding UTF8

        if ([string]::IsNullOrEmpty($content)) {
            if ($Verbose) {
                Write-Host "  Skipping - empty file" -ForegroundColor Yellow
            }
            return $false
        }

        # Check if file already has license header
        if ((Test-HasLicenseHeader -Content $content) -and -not $Force) {
            if ($Verbose) {
                Write-Host "  Skipping - already has license header (use -Force to override)" -ForegroundColor Yellow
            }
            return $false
        }

        $eol = Get-LineEnding -Content $content
        $hadTrailingNewline = $content.EndsWith("`n")

        # In Force mode, strip all existing license blocks first (this also
        # collapses accidental duplicates and migrates legacy Apache blocks)
        if ($Force) {
            $content = Remove-ExistingLicense -Content $content
        }

        $lines = $content -split "`r?`n"
        $headerLines = $LICENSE_HEADER -split "`r?`n"

        # The SPDX header goes at the very top of the file, followed by one
        # blank line and then the file's original content (leading blank lines
        # collapsed away).
        $restStart = 0
        while ($restStart -lt $lines.Length -and $lines[$restStart].Trim() -eq "") {
            $restStart++
        }

        $newLines = @()
        $newLines += $headerLines
        if ($restStart -lt $lines.Length) {
            $newLines += ""
            $newLines += $lines[$restStart..($lines.Length - 1)]
        }

        # Preserve the file's original line endings and trailing-newline state
        $newContent = $newLines -join $eol
        if ($hadTrailingNewline -and -not $newContent.EndsWith($eol)) {
            $newContent += $eol
        }

        if ($DryRun) {
            Write-Host "  Would add license header to: $FilePath" -ForegroundColor Green
        } else {
            Set-Content -Path $FilePath -Value $newContent -Encoding UTF8 -NoNewline
            Write-Host "  Added license header to: $FilePath" -ForegroundColor Green
        }

        return $true

    } catch {
        Write-Error "Error processing $FilePath`: $_"
        return $false
    }
}

# Main execution
Write-Host "License Header Addition Script" -ForegroundColor Magenta
Write-Host "SPDX Apache-2.0" -ForegroundColor Magenta
Write-Host "=============================" -ForegroundColor Magenta
Write-Host ""

if ($DryRun) {
    Write-Host "DRY RUN MODE - No files will be modified" -ForegroundColor Yellow
    Write-Host ""
}

if ($Force) {
    Write-Host "FORCE MODE - Will update files with existing license headers" -ForegroundColor Yellow
    Write-Host ""
}

# Resolve the target: an explicit file or directory, or the repository root
$repoRoot = Split-Path -Parent $PSScriptRoot
if ($Path) {
    if (-not (Test-Path -Path $Path)) {
        Write-Error "Path not found: $Path"
        exit 1
    }
    $targetPath = (Resolve-Path -Path $Path).Path
} else {
    $targetPath = $repoRoot
}

if (Test-Path -Path $targetPath -PathType Leaf) {
    if ($targetPath -notmatch '\.java$') {
        Write-Error "Not a Java file: $targetPath"
        exit 1
    }
    Write-Host "Processing single file: $targetPath" -ForegroundColor Blue
    $javaFiles = @(Get-Item -Path $targetPath)
} else {
    Write-Host "Scanning for Java files under $targetPath ..." -ForegroundColor Blue
    $javaFiles = Get-ChildItem -Path $targetPath -Recurse -Filter "*.java" |
        Where-Object { $_.FullName -notmatch $EXCLUDED_DIRS }
}

Write-Host "Found $($javaFiles.Count) Java files" -ForegroundColor Blue
Write-Host ""

# Process each file
$updatedCount = 0
$skippedCount = 0

foreach ($file in $javaFiles) {
    $result = Add-LicenseHeader -FilePath $file.FullName -DryRun:$DryRun -Force:$Force
    if ($result) {
        $updatedCount++
    } else {
        $skippedCount++
    }
}

# Summary
Write-Host ""
Write-Host "Summary:" -ForegroundColor Magenta
Write-Host "  Files processed: $($javaFiles.Count)" -ForegroundColor White
Write-Host "  Files updated: $updatedCount" -ForegroundColor Green
Write-Host "  Files skipped: $skippedCount" -ForegroundColor Yellow

if ($DryRun) {
    Write-Host ""
    Write-Host "Run without -DryRun to apply changes" -ForegroundColor Cyan
}
