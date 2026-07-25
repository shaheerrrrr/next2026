# Runtime Procedures

This runbook is for operating the deployed fleet. It assumes the Uno, three
Feathers, two Fable ESP32-C3 boards, Raspberry Pi Pico, Android phones, and REV
Control Hubs are already programmed and wired.

For board targets, source placement, wiring, or redeployment, use
[Deployment Reference](deployment.md).

## What You Need

- A Mac or Windows computer with this repository
- Python 3 and the packages in `requirements.txt`
- A paired PS4/DS4 controller
- The driver-side Arduino Uno and RFM95W connected by USB
- The driver-side Fable M5Stamp C3 connected by USB when using Fable navigation
- Antennas attached to every LoRa radio before transmission
- The required robots powered, with each phone connected to its own Feather by
  USB OTG and to its own REV Control Hub Wi-Fi network

The dashboard can start while hardware is absent, but it cannot control a robot
until the required links are healthy.

## Normal Startup Order

This order makes status interpretation easiest, although the desktop app will
retry missing devices if the order differs.

1. Attach all LoRa antennas.
2. Power the robots that will be used.
3. Confirm each Android phone is connected to its Feather by USB OTG.
4. Confirm each phone is connected to its robot's REV Control Hub Wi-Fi.
5. Open the FTC Driver Station app on each phone.
6. Connect the driver-side Uno to the computer.
7. Connect the driver-side Fable navigation C3 to the computer.
8. Pair or wake the PS4/DS4 controller.
9. Identify both serial ports.
10. Start `driver_station_flask.py` with both ports.
11. Open `http://127.0.0.1:8765`.
12. Confirm the dashboard indicators before moving a robot.

## Find Serial Ports

The same command works on macOS and Windows after Python dependencies are
installed:

```bash
python -m serial.tools.list_ports -v
```

Disconnect and reconnect one board if the device names are ambiguous. Run the
command before and after reconnecting and compare the list.

### macOS Port Names

Typical ports look like:

```text
/dev/cu.usbmodem11201
/dev/cu.usbmodem11301
```

Use `/dev/cu.*` for outbound serial connections. The Uno and M5Stamp C3 will
normally appear as two different entries.

You can also inspect likely devices with:

```bash
ls /dev/cu.*
```

### Windows Port Names

Typical ports look like:

```text
COM5
COM7
```

Windows Device Manager also lists them under **Ports (COM & LPT)**. Match each
COM port to the Uno or M5Stamp by reconnecting one board at a time.

## One-Time Python Setup

Repeat this section only when setting up a new computer, replacing the virtual
environment, or changing dependencies.

### macOS Setup

```bash
cd ~/next2026
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

If the repository is elsewhere, replace `~/next2026` with its path.

### Windows PowerShell Setup

```powershell
cd C:\path\to\next2026
py -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
```

If PowerShell blocks virtual-environment activation, allow scripts only for the
current terminal and activate again:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\.venv\Scripts\Activate.ps1
```

## Start The Driver Station

The first serial port is the Uno tele-op bridge. `--fable-nav-port` is the
driver-side M5Stamp C3 used for Fable GPS telemetry and target commands. Both
links run at 115200 baud by default.

### macOS Command

```bash
cd ~/next2026
source .venv/bin/activate
python driver_station_flask.py \
  --port /dev/cu.usbmodem11201 \
  --fable-nav-port /dev/cu.usbmodem11301 \
  --hz 20
```

Replace both example ports with the values found on your computer.

### Windows PowerShell Command

```powershell
cd C:\path\to\next2026
.\.venv\Scripts\Activate.ps1
python .\driver_station_flask.py `
  --port COM5 `
  --fable-nav-port COM7 `
  --hz 20
```

Replace `COM5` and `COM7` with the Uno and navigation C3 ports.

The terminal should print the dashboard address. Open:

```text
http://127.0.0.1:8765
```

Keep the terminal running for the entire operating session. Use one instance of
the application at a time so two processes do not compete for serial ports.

## Interpret Startup Indicators

The indicators at the top-right of the dashboard report independent links:

| Indicator | Healthy meaning | If unhealthy |
| --- | --- | --- |
| Gamepad | Pygame has an active controller | Wake/pair the DS4 and wait for rediscovery |
| Serial | The Uno tele-op serial port is open | Check the Uno USB cable and selected `--port` |
| Fable Nav | The navigation C3 serial port is open so navigation messages can be exchanged | Check the M5Stamp USB cable, `--fable-nav-port`, and ESP-NOW sidecars |
| Dashboard | Flask and the browser event stream are live | Reload the page or inspect the terminal |

The application intentionally stays running if a specified port is absent or
the controller is disconnected. It retries those connections. This allows the
dashboard to explain the failure instead of exiting, but red or waiting status
still means that function is unavailable.

For the navigation C3's physical LED meanings, see
[Driver Navigation LED](driver-navigation-led.md).

## Register Each Robot

The Android FTC Driver Station keeps controller registration per phone. Repeat
this for every robot used in the session:

1. Select the robot in the dashboard.
2. Confirm its phone is showing the FTC Driver Station app.
3. Click **Register Driver 1**.
4. Confirm the phone shows the USB controller assigned to Driver 1.

The dashboard briefly sends the known working Options plus Cross HID
combination only to the selected robot. Registering Flash does not register
Fable or Sol.

## Tele-Op Operation

1. Select Flash, Fable, or Sol from the robot selector.
2. Move a controller input while the robot is safely lifted or disabled.
3. Confirm the dashboard visualization and selected robot's transmit log.
4. Confirm the Android Driver Station shows a connected gamepad.
5. Start the correct tele-op OpMode on that robot.
6. Test at low power before driving at distance.

Press Space while the web page has keyboard focus to cycle:

```text
Flash -> Fable -> Sol -> Flash
```

Do not use Space while typing in a text field or while a modal is handling
keyboard input. The selector card shows which robot currently receives tele-op
frames.

## Fable Navigation Operation

Use this only after Fable tele-op is registered and the Fable navigation link
is receiving fresh GPS telemetry.

1. Select Fable in the dashboard.
2. Confirm **Current Position** is populated.
3. Confirm GPS quality is `GOOD`, with a recent update and an advancing
   navigation sequence.
4. Confirm the Fable Control Hub OpMode reports a valid, fresh navigation
   snapshot.
5. Calibrate the field if the saved four-corner calibration is not correct for
   the current site.
6. Click the main map to preview a target. Review its coordinate and route line.
7. Click **Send Target**. This sends and stores the target; it does not by itself
   command the motors to move.
8. Confirm the target becomes valid in Fable telemetry.
9. With Fable selected, press PS4 Cross. It appears to FTC as `gamepad1.a` and
   arms the current Fable navigation routine.
10. Watch the robot, map, GPS quality, and Fable status continuously.

After Fable is running autonomously, the driver may select Flash or Sol and
tele-operate it. Fable's navigation command is being executed locally by its
Control Hub, so it does not require Fable to remain the selected LoRa target.

To take Fable back to tele-op, select Fable and provide a meaningful manual
drive command or press the configured exit control. Current dashboard logic
also clears its autonomous badge when that Fable tele-op override is sent.

The complete target lifecycle, calibration behavior, and status caveats are in
[Fable Navigation](fable-navigation.md).

## Field Calibration

Field calibration is stored by the browser on the driver computer.

1. Select Fable.
2. Click **Calibrate Field**.
3. Populate each of the four corners by typing coordinates, clicking the
   calibration map, or using Fable's current coordinate when available.
4. Review the corner order and polygon preview.
5. Save the calibration.
6. Confirm the main map fits the field outline with a small buffer.

Calibration does not open automatically. It is not transmitted to the Control
Hub and is not a geofence. Clearing browser site data can remove the saved
corners.

Street and satellite map tiles require internet access. Coordinate telemetry
and transport do not depend on the visual tile provider, but target selection
is much easier with the map loaded.

## Recover From A Disconnection

### Controller

1. Wake or reconnect the DS4.
2. Watch the Gamepad indicator.
3. Verify every control in the dashboard before moving a robot.

### Uno

1. Stop robot motion safely.
2. Reconnect the Uno USB cable.
3. Confirm the same serial device name still exists.
4. Wait for the Serial indicator to recover.
5. Restart the application with the new `--port` if the operating system
   assigned a different device name.

### Fable Navigation C3

1. Keep Fable stopped or under direct supervision.
2. Reconnect the M5Stamp USB cable.
3. Wait for the Fable Nav indicator and physical LED to recover.
4. Restart with the new `--fable-nav-port` if its port changed.
5. Confirm GPS telemetry is fresh before sending another target.

### Browser

Reload `http://127.0.0.1:8765`. Reloading the browser does not restart the
Python controller or serial threads. A browser reload can reconstruct dashboard
state from the server, but map calibration comes from that browser's local
storage.

## Safe Shutdown

1. Stop or cancel Fable autonomy if it was armed.
2. Stop each active FTC OpMode, or otherwise make every robot mechanically safe.
3. Confirm drive, intake, turret, shooter, and dump mechanisms are stopped.
4. Press `Ctrl-C` in the driver-station terminal.
5. Power down robots and radios.
6. Disconnect USB hardware if required.

Do not treat `Ctrl-C`, closing the browser, losing LoRa, or unplugging the Uno
as an autonomous emergency stop. The Feather neutral timeout protects HID
tele-op state, while Fable autonomous motion is controlled by the Control Hub.
