<#
.SYNOPSIS
    Automated emulator gate for robot-reset-app: boots the AVD, installs
    RobotReset + FakeDriverStation, enables the accessibility service, fires
    the debug-only DEBUG_COMMAND broadcast trigger via adb, and asserts on
    the resulting FakeDriverStation logcat markers.

.DESCRIPTION
    See docs/e2e-harness.md for what each test case proves and known
    limitations. Run from the repo root after sourcing tools/env.ps1:

        . .\tools\env.ps1
        .\tools\e2e.ps1

    ===========================================================================
    WHY THIS HARNESS DOES NOT FIRE REAL KEY-EVENT CHORDS (read before touching
    Send-Chord below)
    ===========================================================================
    This harness used to fire chords with
    `adb shell input keycombination <ctrl> <alt> <Fn>` (and, before that,
    plain `adb shell input keyevent`). Neither ever produced a single
    onKeyEvent log line, even with the accessibility service correctly bound
    and FLAG_REQUEST_FILTER_KEY_EVENTS confirmed active
    (`adb shell dumpsys accessibility` showed
    `Enabled features of Display [0] = [KeyboardInterceptor]`).

    Root cause: `adb shell input` injects events via
    `InputManager.injectInputEvent`. That path is synthetic input injection,
    not a real hardware input device, and the accessibility
    KeyboardInterceptor stage that feeds onKeyEvent only observes real
    hardware input devices. This is true on an emulator AND on real hardware
    -- it is not an emulator quirk. `sendevent`/uinput were considered and
    rejected as out of scope and fragile (they'd require injecting at the
    kernel evdev layer, faking a whole USB HID keyboard's report descriptor).

    Consequence: the Ctrl+Alt+F1..F8 chord trigger path itself -- a real USB
    HID keyboard producing a real onKeyEvent callback -- is UNTESTABLE via
    adb, full stop. It is covered ONLY by the brief's "Step 0" real-hardware
    bench check (docs/robot-reset-app-brief.md section 7), not by this
    script, not ever by this script.

    What IS fully testable on the emulator -- and the reason this harness
    still exists and is worth running constantly -- is everything downstream
    of chord decoding: element resolution, the clickable-ancestor walk
    (Gotcha 2), the disabled-node no-op (Gotcha 3), and the OpMode dropdown
    open/wait/scroll/match state machine. That is the biggest and most
    gotcha-prone part of this app. To exercise it without a real key event,
    RobotResetService exposes a debug-build-only broadcast receiver,
    DebugCommandReceiver (RobotReset/src/debug/), which forwards directly
    into the SAME handleCommand(ChordDecoder.Decoded) that onKeyEvent calls
    for a real chord -- not a reimplementation of it. That receiver is
    compiled only into debug builds (AGP's `debug` source set) and is
    verifiably absent from the release manifest; see docs/e2e-harness.md for
    the merged-manifest evidence. This harness's Send-Chord function below
    fires that broadcast, despite the name (kept for call-site continuity;
    it no longer sends a KeyEvent of any kind).
    ===========================================================================

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

# Deliberately NOT "Stop". Windows PowerShell 5.1 wraps any native executable's
# stderr output in a NativeCommandError ErrorRecord, so with "Stop" a perfectly
# successful `adb pull` (which reports transfer progress on stderr) aborts the
# whole script. Every operation whose failure actually matters checks
# $LASTEXITCODE explicitly below and exits non-zero itself.
$ErrorActionPreference = "Continue"

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

# DEBUG_COMMAND broadcast action + extras (see RobotReset/src/debug/DebugCommandReceiver.java).
$DebugCommandAction = "com.next2026.robotreset.DEBUG_COMMAND"

# KC_HOME is a real Android keycode (not a chord) used only to navigate the
# emulator away from FakeDriverStation for the "not foregrounded" negative
# control below -- `adb shell input keyevent` is perfectly fine for that,
# since it isn't standing in for the untestable HID chord path.
$KC_HOME = 3

$script:FailCount = 0
$script:Results = @()

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

function Test-EmulatorRunning {
    $devices = & $adb devices
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
        $boot = "$(& $adb shell getprop sys.boot_completed)".Trim()
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
    # Despite the name (kept because every call site below reads naturally as
    # "send the chord that would select/press X"), this does NOT send a
    # KeyEvent of any kind. It fires the debug-only DEBUG_COMMAND broadcast
    # that DebugCommandReceiver forwards straight into
    # RobotResetService.handleCommand(...) -- the same method a real
    # Ctrl+Alt+Fn chord's onKeyEvent calls. See the big comment block at the
    # top of this script for why `adb shell input` cannot be used instead.
    #
    # -p targets the package explicitly so the broadcast can't be picked up
    # by anything else. --include-stopped-packages is harmless insurance
    # (BR_include-stopped) in case the process ever isn't already running;
    # it's not expected to matter since the accessibility service keeps the
    # process alive, but costs nothing to include.
    param(
        [string]$Command,        # ChordDecoder.Command name: INIT, START, STOP, OPMODE_SLOT
        [int]$Slot = -1           # only meaningful when $Command -eq "OPMODE_SLOT"
    )
    $args = @(
        "shell", "am", "broadcast",
        "-a", $DebugCommandAction,
        "-p", $RobotResetPkg,
        "--include-stopped-packages",
        "--es", "cmd", $Command
    )
    if ($Command -eq "OPMODE_SLOT") {
        $args += @("--ei", "slot", "$Slot")
    }
    & $adb @args | Out-Null
}

function Get-FdsLogcat {
    # -d dumps and exits (does not block); -s filters by tag.
    $lines = & $adb logcat -d -s "$FdsLogTag`:I"
    return ($lines -join "`n")
}

function Reset-Fds {
    # Force-stop + relaunch FakeDriverStation so every test case starts from
    # a known, cold state: INIT disabled, no OpMode selected, list not open
    # or populated. MainActivity (FakeDriverStation/.../MainActivity.java)
    # keeps all of that as plain in-memory Activity state with no
    # persistence, so force-stop genuinely resets it -- this isn't
    # cosmetic.
    #
    # Why this exists: the suite used to report 7/7 PASS with a real bug in
    # OpModeSelector present (it gave up permanently the instant a
    # content-changed event arrived before the dropdown had inflated, which
    # is the NORMAL ordering, not an edge case). It only passed because an
    # earlier case had already opened the OpMode dropdown once, leaving the
    # ListView populated and visible, so by the time the off-screen-slot
    # case ran, a scrollable node already existed on screen and papered over
    # the bug. From a genuinely cold start it failed. Isolating every case
    # behind a force-stop closes that hole so no future case can pass by
    # accident on another case's leftover state.
    & $adb shell am force-stop $FdsPkg | Out-Null
    & $adb shell am start -n $FdsActivity | Out-Null
    Start-Sleep -Seconds 2
}

function Wait-ForFdsMarker {
    # Polls FakeDriverStation's logcat (against the buffer, not a live
    # stream) until $Marker appears or $TimeoutSec elapses. Used to make a
    # test case's own precondition-setup step (e.g. "select an OpMode
    # first") deterministic instead of guessing a fixed sleep -- consistent
    # with this whole project's "don't sleep blindly, react to the actual
    # event/outcome" rule.
    param([string]$Marker, [int]$TimeoutSec = 6)
    $elapsedMs = 0
    $stepMs = 300
    while ($elapsedMs -lt ($TimeoutSec * 1000)) {
        $log = Get-FdsLogcat
        if ($log -match [regex]::Escape($Marker)) {
            return $true
        }
        Start-Sleep -Milliseconds $stepMs
        $elapsedMs += $stepMs
    }
    return $false
}

function Get-NodeEnabled {
    # Pulls a fresh uiautomator dump and returns $true/$false for the
    # enabled="..." attribute of the first node with the given visible text,
    # or $null if no such node is present at all.
    param([string]$Text)
    $remotePath = "/sdcard/window_dump_e2e.xml"
    & $adb shell uiautomator dump $remotePath | Out-Null
    $localPath = Join-Path $env:TEMP "robotreset_e2e_dump.xml"
    & $adb pull $remotePath $localPath | Out-Null
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
        [scriptblock]$Setup,        # runs before firing the trigger broadcast (e.g. press Home)
        [string]$Command = "",      # ChordDecoder.Command name, or "" to skip firing a trigger
        [int]$Slot = -1,            # only meaningful when $Command -eq "OPMODE_SLOT"
        [int]$WaitSeconds,
        [string[]]$ExpectPresent,   # markers that MUST appear
        [string[]]$ExpectAbsent,    # markers that MUST NOT appear
        [scriptblock]$ExtraCheck    # optional; returns $true/$false, appended to pass/fail
    )

    # Isolation: every case starts from a known, cold FakeDriverStation state
    # (force-stop + relaunch) rather than trusting whatever a previous case
    # left behind. See Reset-Fds's doc comment for why this matters -- it is
    # what closes the test-ordering hole that let a real OpModeSelector bug
    # hide behind a false 7/7 PASS.
    Reset-Fds

    & $adb logcat -c

    if ($Setup) {
        & $Setup
    }

    if ($Command -ne "") {
        Send-Chord -Command $Command -Slot $Slot
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
# Every Invoke-TestCase call also force-stops + relaunches FakeDriverStation
# itself (see Reset-Fds) before its own logic runs, so this step is not the
# only thing standing between cases -- it just leaves the device in a sane
# state before the loop starts and if this install/enable sequence is
# somehow broken, failing here is more diagnosable than inside case 1.

Write-Host "Launching FakeDriverStation..."
& $adb shell am start -n $FdsActivity | Out-Null
Start-Sleep -Seconds 2

# ---------------------------------------------------------------------------
# 5-6. Test cases
# ---------------------------------------------------------------------------

Write-Host ""
Write-Host "=== Running test cases ==="
Write-Host ""

# Test 1: INIT trigger while INIT is still disabled (no OpMode selected yet).
# Fail-safe property (Gotcha 3): clicking a disabled node must be a no-op.
Invoke-TestCase -Name "INIT trigger while disabled -> no click" `
    -Command "INIT" -WaitSeconds 2 `
    -ExpectAbsent @("INIT_CLICKED")

# Test 2: OPMODE_SLOT 0 selects OpMode slot 0 (visible on first screen), and
# INIT should become enabled as a result.
Invoke-TestCase -Name "OpMode slot 0 select -> OPMODE_SELECTED + INIT enabled" `
    -Command "OPMODE_SLOT" -Slot 0 -WaitSeconds 3 `
    -ExpectPresent @("OPMODE_SELECTED:OpMode Slot 0") `
    -ExtraCheck { Get-NodeEnabled -Text "INIT" }

# Test 3: OPMODE_SLOT 3 selects a slot placed off-screen in FakeDriverStation's
# list (see MainActivity.OPMODE_ENTRIES) so this only succeeds if the
# resolver's scroll-and-match path actually scrolls.
# Long wait: scroll-and-match needs the list-populate delay (~900ms) PLUS
# however many bounded ACTION_SCROLL_FORWARD + rescan cycles it takes.
Invoke-TestCase -Name "OpMode slot 3 (off-screen) select -> scroll-and-match" `
    -Command "OPMODE_SLOT" -Slot 3 -WaitSeconds 6 `
    -ExpectPresent @("OPMODE_SELECTED:OpMode Slot 3")

# Test 4: INIT trigger again, now that an OpMode has been selected and INIT
# should be enabled.
#
# This case genuinely depends on an OpMode having been selected first --
# that's what enables INIT in FakeDriverStation (see MainActivity.
# onOpModeSelected). Before per-case isolation existed, this silently rode
# on test 2's leftover selection. Now that Reset-Fds force-stops
# FakeDriverStation before every case (including this one), that
# precondition no longer holds implicitly, so it is established explicitly
# here: -Setup fires OPMODE_SLOT 0 and polls logcat (Wait-ForFdsMarker, not
# a blind sleep) until FakeDriverStation confirms OPMODE_SELECTED, before
# Invoke-TestCase goes on to fire the real INIT trigger under test. The
# selection marker is asserted present too, so a silent precondition
# failure shows up as a specific missing marker rather than a confusing
# INIT_CLICKED miss.
Invoke-TestCase -Name "INIT trigger while enabled -> INIT_CLICKED" `
    -Setup {
        Send-Chord -Command "OPMODE_SLOT" -Slot 0
        $selected = Wait-ForFdsMarker -Marker "OPMODE_SELECTED:OpMode Slot 0" -TimeoutSec 6
        if (-not $selected) {
            Write-Host "       WARNING: precondition OpMode selection did not confirm within timeout"
        }
    } `
    -Command "INIT" -WaitSeconds 2 `
    -ExpectPresent @("OPMODE_SELECTED:OpMode Slot 0", "INIT_CLICKED")

# Test 5: START.
Invoke-TestCase -Name "START trigger -> START_CLICKED" `
    -Command "START" -WaitSeconds 2 `
    -ExpectPresent @("START_CLICKED")

# Test 6: STOP.
Invoke-TestCase -Name "STOP trigger -> STOP_CLICKED" `
    -Command "STOP" -WaitSeconds 2 `
    -ExpectPresent @("STOP_CLICKED")

# Test 7: negative control. Press Home so FakeDriverStation is NOT the
# foreground app, then fire a trigger. Nothing should happen at all -- the
# purest form of the fail-safe property: absent target, nothing happens.
# (This still exercises real resolution code against a real absent target --
# it is not weakened by the trigger mechanism change. The only thing not
# covered here or anywhere in this harness is whether a real hardware chord
# reaches onKeyEvent in the first place; see the top-of-file comment block.)
Invoke-TestCase -Name "Trigger while FakeDriverStation not foregrounded -> nothing happens" `
    -Setup { & $adb shell input keyevent $KC_HOME | Out-Null; Start-Sleep -Seconds 1 } `
    -Command "START" -WaitSeconds 2 `
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
