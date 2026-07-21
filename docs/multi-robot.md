# Multi-Robot Control

The multi-robot stack lets one Mac driver station broadcast controller frames to three robots while only one robot consumes live tele-op control at a time.

## Robots

| Robot | Role | Robot id | Feather sketch |
| --- | --- | ---: | --- |
| Flash | Existing tele-op robot | `1` | [flash/flash.ino](../flash/flash.ino) |
| Fable | Tele-op robot, future GPS autonomy | `2` | [fable/fable.ino](../fable/fable.ino) |
| Sol | Turret/shooter robot, Feather 32u4 RFM95 | `3` | [sol/sol.ino](../sol/sol.ino) |

## Data Flow

```text
DS4 controller
  -> driver_station_flask.py
  -> v3 addressed 21-byte packet
  -> USB serial
  -> driverstation/driverstation.ino on Uno
  -> LoRa broadcast
  -> all robot Feathers receive packet
  -> only matching robot id emits live HID
  -> nonmatching robots emit neutral HID
  -> Android Driver Station phones
  -> REV Control Hubs
```

The driver station broadcasts one packet stream. The active robot id is inside each packet. This keeps radio use close to the single-robot design while allowing dashboard-side robot switching.

## Driver Dashboard

Run:

```bash
cd ~/next2026
source .venv/bin/activate
python driver_station_flask.py --port /dev/cu.usbmodem11301 --hz 20
```

Open:

```text
http://127.0.0.1:8765
```

The top selector switches active control between Flash, Fable, and Sol. The selected robot gets live stick/button/trigger state. The other robots receive the same packets, reject them because the target id does not match, and keep sending neutral HID reports to their phones.

Press Space in the dashboard to cycle control in order:

```text
Flash -> Fable -> Sol -> Flash
```

Fable also has an optional GPS navigation side channel. See [Fable Navigation Link](fable-navigation.md) for the ESP32-C3 GPS/I2C/ESP-NOW firmware and dashboard map behavior.

## Controls

Flash and Fable use the same HID mappings as the deployed single-robot Feather code:

- Left and right sticks
- Left and right triggers
- Cross/A, Circle/B, Square/X, Triangle/Y
- L1/R1
- Options/Start registration pulse

Sol uses:

- Same joystick axes
- Right trigger
- Square/X
- D-pad up/down/left/right
- Options/Start registration pulse

On macOS, DS4 D-pad input may appear as buttons instead of a joystick hat. The Flask driver station reads both hat 0 and fallback buttons `11`, `12`, `13`, and `14` by default. Override those indexes with the `--dpad-*-button` flags if pygame reports a different mapping.

## Safety

Each Feather keeps stale-frame neutralization. If a robot stops receiving valid matching packets for more than 250 ms, it neutralizes sticks, triggers, and buttons.

Switching away from a robot also neutralizes it immediately because its Feather receives valid packets for a different target id.
