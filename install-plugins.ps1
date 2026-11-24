<#
  Installs all IntelliJ IDEA plugins from this folder without network access (Windows, no GUI).
  For choosing plugins one by one, use plugin-installer.cmd instead.

  Usage (or run install-plugins.cmd):
    powershell -ExecutionPolicy Bypass -File install-plugins.ps1
    powershell -ExecutionPolicy Bypass -File install-plugins.ps1 -Optional
    powershell -ExecutionPolicy Bypass -File install-plugins.ps1 -Target "$env:APPDATA\JetBrains\IntelliJIdea2026.3\plugins"

  Default target: <newest existing %APPDATA%\JetBrains\IntelliJIdea<version> or IdeaIC<version>>\plugins,
  otherwise %APPDATA%\JetBrains\IntelliJIdea2026.2\plugins.
  This script does not check plugin/IDE version compatibility; the graphical installer does.
  IntelliJ IDEA itself disables an incompatible plugin at startup.
  Close IntelliJ IDEA before running.
#>
param(
    [string]$Target = "",
    [switch]$Optional
)

$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not $Target) {
    $data = Join-Path $env:APPDATA 'JetBrains'
    $newest = Get-ChildItem -Path $data -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(IntelliJIdea|IdeaIC)(\d{4})\.(\d)$' } |
        Sort-Object { [int]($_.Name -replace '^\D+(\d{4})\.(\d)$', '$1$2') } |
        Select-Object -Last 1
    $dir = if ($newest) { $newest.FullName } else { Join-Path $data 'IntelliJIdea2026.2' }
    $Target = Join-Path $dir 'plugins'
}

if (Get-Process -Name idea64, idea -ErrorAction SilentlyContinue) {
    Write-Host "WARNING: IntelliJ IDEA appears to be running. Close it and run again." -ForegroundColor Yellow
    exit 1
}

New-Item -ItemType Directory -Force -Path $Target | Out-Null
Write-Host "Target directory: $Target"

$script:Failed = 0
function Install-Zip([string]$Zip) {
    try { Install-ZipCore $Zip }
    catch {
        $script:Failed++
        Write-Host ("  FAILED: {0}: {1}" -f (Split-Path -Leaf $Zip), $_.Exception.Message) -ForegroundColor Red
        if ($_.Exception -is [System.IO.PathTooLongException] -or $_.Exception -is [System.IO.DirectoryNotFoundException]) {
            Write-Host "  Hint: the path is probably longer than 260 characters. Use a shorter -Target or plugin-installer.cmd (not limited)." -ForegroundColor Yellow
        }
    }
}

function Install-ZipCore([string]$Zip) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Zip)
    try { $top = ($archive.Entries[0].FullName -split '/')[0] } finally { $archive.Dispose() }
    if (-not $top) { Write-Host "  SKIPPED (empty zip): $Zip"; return }
    $dest = Join-Path $Target $top
    # remove an older version first so stale jars do not remain
    if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force -Confirm:$false }
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Zip, $Target)
    Write-Host ("  installed: {0}  ->  {1}\" -f (Split-Path -Leaf $Zip), $top)
}

# Platform-specific packages carry the platform in their file name (e.g. ...-linux-x64.zip)
function Test-ForThisPlatform([string]$Name) {
    if ($Name -match '-(linux|windows|mac)-(x64|arm64)\.zip$') {
        $arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'x64' }
        return ($Matches[1] -eq 'windows' -and $Matches[2] -eq $arch)
    }
    return $true
}

# Large plugins are stored as <name>.zip.001, .002, ... (GitHub's 100 MB file limit).
# Join the parts into a temporary zip and verify it against <name>.zip.sha256.
function Join-Parts([string]$Base) {
    $parts = Get-ChildItem -Path "$Base.*" -File | Where-Object { $_.Name -match '\.zip\.\d{3}$' } | Sort-Object Name
    $out = Join-Path ([System.IO.Path]::GetTempPath()) ([System.IO.Path]::GetFileName($Base))
    $stream = [System.IO.File]::Create($out)
    try {
        foreach ($p in $parts) {
            $in = [System.IO.File]::OpenRead($p.FullName)
            try { $in.CopyTo($stream) } finally { $in.Dispose() }
        }
    } finally { $stream.Dispose() }
    $sidecar = "$Base.sha256"
    if (Test-Path -LiteralPath $sidecar) {
        $expected = ((Get-Content -LiteralPath $sidecar -Raw) -split '\s+')[0]
        $actual = (Get-FileHash -LiteralPath $out -Algorithm SHA256).Hash
        if ($expected -ne $actual) {
            Remove-Item -LiteralPath $out -Force -Confirm:$false
            throw "joined parts of $([System.IO.Path]::GetFileName($Base)) are damaged (SHA-256 mismatch)"
        }
    }
    Write-Host ("  joined {0} parts of {1} (SHA-256 verified)" -f $parts.Count, [System.IO.Path]::GetFileName($Base))
    return $out
}

# Real .zip files plus split ones (listed by their .001 part)
function Get-PluginZips([string]$Dir) {
    Get-ChildItem -Path $Dir -File | Where-Object { $_.Name -like '*.zip' -or $_.Name -like '*.zip.001' } |
        ForEach-Object { $_.FullName -replace '\.001$', '' } | Sort-Object
}

function Install-Any([string]$Base) {
    if (Test-Path -LiteralPath $Base) { Install-Zip $Base; return }
    try { $tmp = Join-Parts $Base }
    catch { $script:Failed++; Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red; return }
    try { Install-Zip $tmp } finally { Remove-Item -LiteralPath $tmp -Force -Confirm:$false -ErrorAction SilentlyContinue }
}

Get-PluginZips (Join-Path $Here 'plugins') | ForEach-Object { Install-Any $_ }

if ($Optional) {
    Write-Host "Optional plugins:"
    Get-PluginZips (Join-Path $Here 'plugins\optional') |
        Where-Object { Test-ForThisPlatform ([System.IO.Path]::GetFileName($_)) } |
        ForEach-Object { Install-Any $_ }
}

Write-Host ""
if ($script:Failed -gt 0) { Write-Host "$($script:Failed) plugin(s) failed to install." -ForegroundColor Red }
Write-Host "Done. Start IntelliJ IDEA and check Settings > Plugins > Installed."
Write-Host ("Extra SpotBugs rule jars: " + (Join-Path $Here 'spotbugs-rules\spotbugs-4.8'))
