<#
.SYNOPSIS
    Tails adb logcat filtered to the RobotReset app, for bench debugging.

.DESCRIPTION
    RobotResetService currently has no android.util.Log calls of its own
    (Phase 1 keeps its observability in the in-process KeyEventLog ring
    buffer shown by KeyMonitorActivity, not logcat) — but its process still
    emits framework/crash lines (AndroidRuntime, ActivityManager,
    AccessibilityManagerService, etc.) that matter at the bench, and future
    phases are expected to add real Log calls. So the default mode here
    filters logcat to the RobotReset app's own process (via --pid, resolved
    from `pidof`), which follows whatever the app logs without needing this
    script to hardcode a tag list that may go stale.

    Falls back to an unfiltered stream with a warning if the process isn't
    currently running (e.g. accessibility service not yet enabled) — in
    that case pass -Tag to filter by logcat tag instead once you know one.

.PARAMETER Tag
    Optional: filter by logcat tag(s) instead of by process id, e.g.
    -Tag "RobotReset","AndroidRuntime". Useful once specific tags are known,
    or while the process isn't running yet.

.PARAMETER Serial
    Optional -s <serial> to target a specific device/emulator.

.PARAMETER Clear
    Clear the logcat buffer before tailing.

.EXAMPLE
    . .\tools\env.ps1
    .\tools\logcat-robotreset.ps1
    .\tools\logcat-robotreset.ps1 -Tag FakeDriverStation
    .\tools\logcat-robotreset.ps1 -Clear
#>
param(
    [string[]]$Tag = @(),
    [string]$Serial = "",
    [switch]$Clear
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

$pkg = "com.next2026.robotreset"

if ($Clear) {
    & $adb @adbArgs logcat -c
    Write-Host "Cleared logcat buffer."
}

if ($Tag.Count -gt 0) {
    $tagArgs = @()
    foreach ($t in $Tag) {
        $tagArgs += "$t`:V"
    }
    $tagArgs += "*:S"
    Write-Host "Tailing logcat filtered to tags: $($Tag -join ', ')"
    & $adb @adbArgs logcat @tagArgs
    exit $LASTEXITCODE
}

$appPid = (& $adb @adbArgs shell pidof $pkg 2>$null)
if ($appPid -is [array]) { $appPid = $appPid[0] }
$appPid = "$appPid".Trim()

if ($appPid -match '^\d+$') {
    Write-Host "Tailing logcat for $pkg (pid $appPid). Ctrl+C to stop."
    & $adb @adbArgs logcat "--pid=$appPid"
} else {
    Write-Warning "$pkg is not currently running (accessibility service not started?). Falling back to unfiltered logcat — press Ctrl+C to stop, or pass -Tag once you know a specific tag to filter on."
    & $adb @adbArgs logcat
}
