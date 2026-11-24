<#
  Starts the graphical plugin installer on Windows (offline).

  A separate Java installation is NOT required: the Java runtime bundled with
  IntelliJ IDEA (jbr\) is located and used. Nothing is downloaded.

  Usage (or simply double-click plugin-installer.cmd):
    powershell -ExecutionPolicy Bypass -File plugin-installer.ps1
    powershell -ExecutionPolicy Bypass -File plugin-installer.ps1 -IdeaHome "C:\Program Files\JetBrains\IntelliJ IDEA 2026.2"
    powershell -ExecutionPolicy Bypass -File plugin-installer.ps1 -Target "$env:APPDATA\JetBrains\IntelliJIdea2026.2\plugins"
#>
param(
    [string]$IdeaHome = "",
    [string]$Target = "",
    [switch]$Console   # run with java.exe in this window (shows log output) instead of javaw.exe
)

$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jar = Join-Path $Here 'plugin-installer.jar'

function Show-Error([string]$Message) {
    Write-Host "ERROR: $Message" -ForegroundColor Red
    try {
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show($Message, 'Plugin installer', 'OK', 'Error') | Out-Null
    } catch { }
}

function Test-IdeaHome([string]$Dir) {
    return ($Dir -and (Test-Path -LiteralPath (Join-Path $Dir 'product-info.json')) -and
            (Test-Path -LiteralPath (Join-Path $Dir 'jbr\bin\java.exe')))
}

function Get-BuildNumber([string]$Dir) {
    try {
        $info = Get-Content -LiteralPath (Join-Path $Dir 'product-info.json') -Raw | ConvertFrom-Json
        if ($info.productCode -and $info.productCode -notin @('IU', 'IC', 'IE')) { return $null }  # not IntelliJ IDEA
        $parts = ($info.buildNumber -replace '^[A-Z]+-', '') -split '\.'
        return [int]$parts[0] * 100000 + [int]$parts[1]
    } catch { return $null }
}

function Find-IdeaHome {
    foreach ($c in @($IdeaHome, $env:IDEA_HOME)) {
        if (Test-IdeaHome $c) { return $c }
    }
    $candidates = New-Object System.Collections.Generic.List[string]
    $patterns = @(
        "$env:ProgramFiles\JetBrains\IntelliJ IDEA*",
        "${env:ProgramFiles(x86)}\JetBrains\IntelliJ IDEA*",
        "$env:LOCALAPPDATA\Programs\IntelliJ IDEA*",
        "$env:LOCALAPPDATA\JetBrains\Toolbox\apps\intellij-idea*",
        "$env:LOCALAPPDATA\JetBrains\Toolbox\apps\IDEA-*\ch-*\*",
        "C:\JetBrains\IntelliJ IDEA*",
        "D:\JetBrains\IntelliJ IDEA*"
    )
    foreach ($p in $patterns) {
        Get-Item -Path $p -ErrorAction SilentlyContinue | ForEach-Object { $candidates.Add($_.FullName) }
    }
    # installer registry entries
    foreach ($key in @('HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
                       'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
                       'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*')) {
        Get-ItemProperty -Path $key -ErrorAction SilentlyContinue |
            Where-Object { $_.DisplayName -like 'IntelliJ IDEA*' -and $_.InstallLocation } |
            ForEach-Object { $candidates.Add($_.InstallLocation.TrimEnd('\')) }
    }
    # pick the newest IntelliJ IDEA build
    $best = $null; $bestBuild = -1
    foreach ($c in ($candidates | Select-Object -Unique)) {
        if (-not (Test-IdeaHome $c)) { continue }
        $b = Get-BuildNumber $c
        if ($b -ne $null -and $b -gt $bestBuild) { $best = $c; $bestBuild = $b }
    }
    return $best
}

if (-not (Test-Path -LiteralPath $Jar)) { Show-Error "$Jar not found."; exit 1 }

$Idea = Find-IdeaHome
$Java = $null
if ($Idea) {
    $Java = Join-Path $Idea ($(if ($Console) { 'jbr\bin\java.exe' } else { 'jbr\bin\javaw.exe' }))
} elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $Java = Join-Path $env:JAVA_HOME ($(if ($Console) { 'bin\java.exe' } else { 'bin\javaw.exe' }))
} else {
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { $Java = $cmd.Source }
}

if (-not $Java -or -not (Test-Path -LiteralPath $Java)) {
    Show-Error ("IntelliJ IDEA (and its bundled Java) could not be found.`r`n`r`n" +
                "Run with the IDEA installation directory, e.g.:`r`n" +
                "powershell -ExecutionPolicy Bypass -File plugin-installer.ps1 -IdeaHome `"C:\Program Files\JetBrains\IntelliJ IDEA 2026.2`"`r`n`r`n" +
                "To install without a GUI: install-plugins.cmd")
    exit 1
}

Write-Host "Java : $Java"
if ($Idea) { Write-Host "IDEA : $Idea" } else { Write-Host "IDEA : not found (plugin directory will be guessed; you can change it in the window)" }

function Quote([string]$s) { return '"' + $s + '"' }
$argList = @('-jar', (Quote $Jar), '--root', (Quote $Here))
if ($Idea) { $argList += @('--idea-home', (Quote $Idea)) }
if ($Target) { $argList += @('--target', (Quote $Target)) }

if ($Console) {
    & $Java -jar $Jar --root $Here @(if ($Idea) { '--idea-home', $Idea }) @(if ($Target) { '--target', $Target })
} else {
    Start-Process -FilePath $Java -ArgumentList $argList -WorkingDirectory $Here
}
