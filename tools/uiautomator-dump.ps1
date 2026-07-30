<#
.SYNOPSIS
    Phase-4 real-hardware discovery helper: dumps the current UI hierarchy
    via uiautomator and pulls it to this machine.

.DESCRIPTION
    Thin wrapper around:
        adb shell uiautomator dump /sdcard/window_dump.xml
        adb pull /sdcard/window_dump.xml <out>

    Use this on the bench, with the real FTC Driver Station app (or
    FakeDriverStation) in the foreground, to discover real resource-ids and
    visible text for TargetSpec entries. See docs/robot-reset-app-brief.md
    section 8.

.PARAMETER OutFile
    Local path to pull the dump to. Defaults to .\window_dump.xml in the
    current directory.

.PARAMETER Serial
    Optional -s <serial> to target a specific device/emulator when more than
    one is attached.

.EXAMPLE
    . .\tools\env.ps1
    .\tools\uiautomator-dump.ps1
    .\tools\uiautomator-dump.ps1 -OutFile .\dumps\ds_init_screen.xml
#>
param(
    [string]$OutFile = ".\window_dump.xml",
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"

if (-not $env:ANDROID_SDK_ROOT) {
    Write-Error "ANDROID_SDK_ROOT not set. Run '. .\tools\env.ps1' first."
    exit 1
}

$adb = Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"
$adbArgs = @()
if ($Serial -ne "") {
    $adbArgs += @("-s", $Serial)
}

$remotePath = "/sdcard/window_dump.xml"

Write-Host "Dumping UI hierarchy to device path $remotePath ..."
& $adb @adbArgs shell uiautomator dump $remotePath
if ($LASTEXITCODE -ne 0) {
    Write-Error "uiautomator dump failed (exit $LASTEXITCODE)."
    exit 1
}

Write-Host "Pulling to $OutFile ..."
& $adb @adbArgs pull $remotePath $OutFile
if ($LASTEXITCODE -ne 0) {
    Write-Error "adb pull failed (exit $LASTEXITCODE)."
    exit 1
}

Write-Host "Done: $OutFile"
