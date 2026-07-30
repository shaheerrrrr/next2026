<#
.SYNOPSIS
    Automated emulator gate for robot-reset-app: boots the AVD, installs
    RobotReset + FakeDriverStation, enables the accessibility service, fires
    the trigger chords via adb, and asserts on the resulting FakeDriverStation
    logcat markers.

.DESCRIPTION
    See docs/e2e-harness.md for what each test case proves and known
    limitations. Run from the repo root after sourcing tools/env.ps1:

        . .\tools\env.ps1
        .\tools\e2e.ps1

    IMPORTANT: at the time this script was written, RobotResetService's
    onKeyEvent only logs matched chords (see RobotResetService.java's Phase 1
    doc comment) — it does not yet call any resolver to actually click
    anything. That wiring is a pending integration step owned by the
    orchestrator, done once all four lanes land. Until that lands, every test
    case here that asserts a click/selection marker WILL fail, by design —
    that is not a bug in this harness. The "no marker while disabled" and
    "no marker when not foregrounded" negative-control cases will pass
    regardless, since they assert absence.

.PARAMETER AvdName
    Name of the AVD to boot if not already running. Default: RobotResetTest.

.PARAMETER SkipBuild
    Skip the gradlew assembleDebug step (use previously built APKs).

.PARAMETER SkipInstall
    Skip adb install (use whatever's already installed on the device).

.PARAMETER SkipEmulatorBoot
    Skip the emulator boot/wait step (use whatever device adb already sees).

.PARAMETER BootTimeoutSec
    Max seconds to wait for the emulator to report sys.boot_completed=1.
#>
param(
    [string]$AvdName = "RobotResetTest",
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$SkipEmulatorBoot,
    [int]$BootTimeoutSec = 240
)

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Setup
# ---------------------------------------------------------------------------

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

if (-not $env:ANDROID_SDK_ROOT) {
    Write-Error "ANDROID_SDK_ROOT not set. Run '. .\tools\env.ps1' first."
    exit 1
}
if (-not $env:JAVA_HOME) {
    Write-Error "JAVA_HOME not set. Run '. .\tools\env.ps1' first."
    exit 1
}

$adb = Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"
$emulatorExe = Join-Path $env:ANDROID_SDK_ROOT "emulator\emulator.exe"

$RobotResetPkg = "com.next2026.robotreset"
$RobotResetServiceComponent = "$RobotResetPkg/$RobotResetPkg.RobotResetService"
$FdsPkg = "com.next2026.fakedriverstation"
$FdsActivity = "$FdsPkg/.MainActivity"
$FdsLogTag = "FakeDriverStation"

$RobotResetApk = Join-Path $RepoRoot "RobotReset\build\outputs\apk\debug\RobotReset-debug.apk"
$FdsApk = Join-Path $RepoRoot "FakeDriverStation\build\outputs\apk\debug\FakeDriverStation-debug.apk"

# HID keycodes per docs/robot-reset-app-brief.md's chord table.
$KC_CTRL_LEFT = 113
$KC_ALT_LEFT = 57
$KC_F1 = 131
$KC_F2 = 132
$KC_F3 = 133
$KC_F5 = 135
$KC_F6 = 136
$KC_F7 = 137
$KC_F8 = 138
$KC_HOME = 3

$script:FailCount = 0
$script:Results = @()

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

function Test-EmulatorRunning {
    $devices = & $adb devices 2>$null
    foreach ($line in $devices) {
        if ($line -match "^emulator-\d+\s+device\s*$") {
            return $true
        }
    }
    return $false
}

function Wait-ForBoot {
    param([int]$TimeoutSec)
    & $adb wait-for-device
    $elapsed = 0
    while ($true) {
        $boot = "$(& $adb shell getprop sys.boot_completed 2>$null)".Trim()
        if ($boot -eq "1") {
            return $true
        }
        Start-Sleep -Seconds 3
        $elapsed += 3
        if ($elapsed -ge $TimeoutSec) {
            return $false
        }
    }
}

function Send-Chord {
    param([int]$TargetKeyCode)
    & $adb shell input keycombination $KC_CTRL_LEFT $KC_ALT_LEFT $TargetKeyCode | Out-Null
}

function Get-FdsLogcat {
    # -d dumps and exits (does not block); -s filters by tag.
    $lines = & $adb logcat -d -s "$FdsLogTag`:I" 2>$null
    return ($lines -join "`n")
}

function Get-NodeEnabled {
    # Pulls a fresh uiautomator dump and returns $true/$false for the
    # enabled="..." attribute of the first node with the given visible text,
    # or $null if no such node is present at all.
    param([string]$Text)
    $remotePath = "/sdcard/window_dump_e2e.xml"
    & $adb shell uiautomator dump $remotePath 2>$null | Out-Null
    $localPath = Join-Path $env:TEMP "robotreset_e2e_dump.xml"
    & $adb pull $remotePath $localPath 2>$null | Out-Null
    if (-not (Test-Path $localPath)) {
        return $null
    }
    try {
        [xml]$xml = Get-Content $localPath
    } catch {
        return $null
    }
    $node = $xml.SelectSingleNode("//node[@text='$Text']")
    if (-not $node) {
        return $null
    }
    return ($node.enabled -eq "true")
}

function Invoke-TestCase {
    param(
        [string]$Name,
        [scriptblock]$Setup,        # runs before firing the chord (e.g. press Home)
        [int]$ChordKey,             # target key code, or 0 to skip sending a chord
        [int]$WaitSeconds,
        [string[]]$ExpectPresent,   # markers that MUST appear
        [string[]]$ExpectAbsent,    # markers that MUST NOT appear
        [scriptblock]$ExtraCheck    # optional; returns $true/$false, appended to pass/fail
    )

    & $adb logcat -c

    if ($Setup) {
        & $Setup
    }

    if ($ChordKey -ne 0) {
        Send-Chord -TargetKeyCode $ChordKey
    }

    Start-Sleep -Seconds $WaitSeconds

    $log = Get-FdsLogcat

    $pass = $true
    $detail = @()

    foreach ($marker in $ExpectPresent) {
        $found = $log -match [regex]::Escape($marker)
        if ($found) {
            $detail += "OK: found '$marker'"
        } else {
            $pass = $false
            $detail += "MISSING: expected '$marker'"
        }
    }

    foreach ($marker in $ExpectAbsent) {
        $found = $log -match [regex]::Escape($marker)
        if ($found) {
            $pass = $false
            $detail += "UNEXPECTED: found '$marker' (should be absent)"
        } else {
            $detail += "OK: absent '$marker'"
        }
    }

    if ($ExtraCheck) {
        $extraResult = & $ExtraCheck
        if ($extraResult) {
            $detail += "OK: extra check passed"
        } else {
            $pass = $false
            $detail += "FAILED: extra check"
        }
    }

    $result = [PSCustomObject]@{
        Name   = $Name
        Pass   = $pass
        Detail = ($detail -join "; ")
    }
    $script:Results += $result
    if (-not $pass) {
        $script:FailCount++
    }

    $status = if ($pass) { "PASS" } else { "FAIL" }
    Write-Host "[$status] $Name"
    Write-Host "       $($result.Detail)"

    return $result
}

# ---------------------------------------------------------------------------
# 1. Boot AVD
# ---------------------------------------------------------------------------

if (-not $SkipEmulatorBoot) {
    if (Test-EmulatorRunning) {
        Write-Host "Emulator already running."
    } else {
        Write-Host "Booting AVD '$AvdName' ..."
        Start-Process -FilePath $emulatorExe `
            -ArgumentList @("-avd", $AvdName, "-no-snapshot-save", "-no-boot-anim") `
            -WindowStyle Hidden
        $booted = Wait-ForBoot -TimeoutSec $BootTimeoutSec
        if (-not $booted) {
            Write-Error "Emulator did not report sys.boot_completed=1 within $BootTimeoutSec s."
            exit 1
        }
        Write-Host "Emulator booted."
        # A few extra seconds: boot_completed=1 can still race package manager
        # / settings provider readiness for the install/settings steps below.
        Start-Sleep -Seconds 5
    }
} else {
    Write-Host "Skipping emulator boot (per -SkipEmulatorBoot)."
}

# ---------------------------------------------------------------------------
# 2. Build + install
# ---------------------------------------------------------------------------

if (-not $SkipBuild) {
    Write-Host "Building RobotReset + FakeDriverStation debug APKs..."
    & "$RepoRoot\gradlew.bat" ":RobotReset:assembleDebug" ":FakeDriverStation:assembleDebug"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Gradle build failed (exit $LASTEXITCODE)."
        exit 1
    }
} else {
    Write-Host "Skipping build (per -SkipBuild)."
}

if (-not $SkipInstall) {
    if (-not (Test-Path $RobotResetApk)) {
        Write-Error "RobotReset APK not found at $RobotResetApk. Build first (omit -SkipBuild)."
        exit 1
    }
    if (-not (Test-Path $FdsApk)) {
        Write-Error "FakeDriverStation APK not found at $FdsApk. Build first (omit -SkipBuild)."
        exit 1
    }
    Write-Host "Installing RobotReset..."
    & $adb install -r $RobotResetApk
    if ($LASTEXITCODE -ne 0) {
        Write-Error "adb install RobotReset failed (exit $LASTEXITCODE)."
        exit 1
    }
    Write-Host "Installing FakeDriverStation..."
    & $adb install -r $FdsApk
    if ($LASTEXITCODE -ne 0) {
        Write-Error "adb install FakeDriverStation failed (exit $LASTEXITCODE)."
        exit 1
    }
} else {
    Write-Host "Skipping install (per -SkipInstall)."
}

# ---------------------------------------------------------------------------
# 3. Enable accessibility service via shell-UID trick
# ---------------------------------------------------------------------------

Write-Host "Enabling RobotResetService as the accessibility service..."
& $adb shell settings put secure enabled_accessibility_services $RobotResetServiceComponent
& $adb shell settings put secure accessibility_enabled 1
Start-Sleep -Seconds 2

# ---------------------------------------------------------------------------
# 4. Foreground FakeDriverStation
# ---------------------------------------------------------------------------

Write-Host "Launching FakeDriverStation..."
& $adb shell am start -n $FdsActivity | Out-Null
Start-Sleep -Seconds 2

# ---------------------------------------------------------------------------
# 5-6. Test cases
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== Running test cases ==="
Write-Host ""

# Test 1: Ctrl+Alt+F1 while INIT is still disabled (no OpMode selected yet).
# Fail-safe property (Gotcha 3): clicking a disabled node must be a no-op.
Invoke-TestCase -Name "INIT chord while disabled -> no click" `
    -ChordKey $KC_F1 -WaitSeconds 2 `
    -ExpectAbsent @("INIT_CLICKED")

# Test 2: Ctrl+Alt+F5 selects OpMode slot 0 (visible on first screen), and
# INIT should become enabled as a result.
Invoke-TestCase -Name "OpMode slot 0 (F5) select -> OPMODE_SELECTED + INIT enabled" `
    -ChordKey $KC_F5 -WaitSeconds 3 `
    -ExpectPresent @("OPMODE_SELECTED:OpMode Slot 0") `
    -ExtraCheck { Get-NodeEnabled -Text "INIT" }

# Test 3: Ctrl+Alt+F8 selects OpMode slot 3, which is placed off-screen in
# FakeDriverStation's list (see MainActivity.OPMODE_ENTRIES) so this only
# succeeds if the resolver's scroll-and-match path actually scrolls.
# Long wait: scroll-and-match needs the list-populate delay (~900ms) PLUS
# however many bounded ACTION_SCROLL_FORWARD + rescan cycles it takes.
Invoke-TestCase -Name "OpMode slot 3 (F8, off-screen) select -> scroll-and-match" `
    -ChordKey $KC_F8 -WaitSeconds 6 `
    -ExpectPresent @("OPMODE_SELECTED:OpMode Slot 3")

# Test 4: Ctrl+Alt+F1 again, now that an OpMode has been selected and INIT
# should be enabled.
Invoke-TestCase -Name "INIT chord while enabled -> INIT_CLICKED" `
    -ChordKey $KC_F1 -WaitSeconds 2 `
    -ExpectPresent @("INIT_CLICKED")

# Test 5: START.
Invoke-TestCase -Name "START chord -> START_CLICKED" `
    -ChordKey $KC_F2 -WaitSeconds 2 `
    -ExpectPresent @("START_CLICKED")

# Test 6: STOP.
Invoke-TestCase -Name "STOP chord -> STOP_CLICKED" `
    -ChordKey $KC_F3 -WaitSeconds 2 `
    -ExpectPresent @("STOP_CLICKED")

# Test 7: negative control. Press Home so FakeDriverStation is NOT the
# foreground app, then fire a chord. Nothing should happen at all -- the
# purest form of the fail-safe property: absent target, nothing happens.
Invoke-TestCase -Name "Chord while FakeDriverStation not foregrounded -> nothing happens" `
    -Setup { & $adb shell input keyevent $KC_HOME | Out-Null; Start-Sleep -Seconds 1 } `
    -ChordKey $KC_F2 -WaitSeconds 2 `
    -ExpectAbsent @("INIT_CLICKED", "START_CLICKED", "STOP_CLICKED", "OPMODE_SELECTED")

# Re-foreground FakeDriverStation so the device is left in a sane state for
# manual follow-up inspection.
& $adb shell am start -n $FdsActivity | Out-Null

# ---------------------------------------------------------------------------
# 7. Summary
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== Summary ==="
$script:Results | Format-Table -AutoSize -Property Name, Pass, Detail | Out-String -Width 200 | Write-Host

$total = $script:Results.Count
$passed = ($script:Results | Where-Object { $_.Pass }).Count
Write-Host "$passed / $total test cases passed."

if ($script:FailCount -gt 0) {
    Write-Host "RESULT: FAIL ($script:FailCount failing case(s))"
    exit 1
} else {
    Write-Host "RESULT: PASS"
    exit 0
}
