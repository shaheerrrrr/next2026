# Protocol Reference

There are now two controller protocols in this repository:

- v2 legacy single-robot protocol: used by [gamepad_lora_tx.py](../gamepad_lora_tx.py), [uno/uno.ino](../uno/uno.ino), and [feather/feather.ino](../feather/feather.ino).
- v3 multi-robot protocol: used by [driver_station_flask.py](../driver_station_flask.py), [driverstation/driverstation.ino](../driverstation/driverstation.ino), and the Flash/Fable/Sol Feather sketches.

Keep Python, Uno, and Feather in sync if this protocol changes.

## v2 Legacy Frame Layout

The legacy single-robot controller frame is 19 bytes.

| Field | Type | Bytes | Notes |
| --- | --- | ---: | --- |
| magic | bytes | 2 | Always `0xA5 0x5A` |
| version | uint8 | 1 | Currently `2` |
| seq | uint16 LE | 2 | Sequence number |
| buttons | uint8 | 1 | Protocol button bitmask |
| lx | int16 LE | 2 | Left stick X, `-1000..1000` |
| ly | int16 LE | 2 | Left stick Y, `-1000..1000` |
| rx | int16 LE | 2 | Right stick X, `-1000..1000` |
| ry | int16 LE | 2 | Right stick Y, `-1000..1000` |
| lt | uint16 LE | 2 | Left trigger, `0..1000` |
| rt | uint16 LE | 2 | Right trigger, `0..1000` |
| checksum | uint8 | 1 | XOR checksum |

Python format before checksum:

```python
PACK_FMT_NO_CHECKSUM = "<2sBHBhhhhHH"
FRAME_LEN = struct.calcsize(PACK_FMT_NO_CHECKSUM) + 1
```

## v3 Multi-Robot Frame Layout

The multi-robot controller frame is 21 bytes.

| Field | Type | Bytes | Notes |
| --- | --- | ---: | --- |
| magic | bytes | 2 | Always `0xA5 0x5A` |
| version | uint8 | 1 | Currently `3` |
| target_robot | uint8 | 1 | `1` Flash, `2` Fable, `3` Sol, `255` broadcast |
| seq | uint16 LE | 2 | Sequence number |
| buttons | uint16 LE | 2 | Protocol button bitmask |
| lx | int16 LE | 2 | Left stick X, `-1000..1000` |
| ly | int16 LE | 2 | Left stick Y, `-1000..1000` |
| rx | int16 LE | 2 | Right stick X, `-1000..1000` |
| ry | int16 LE | 2 | Right stick Y, `-1000..1000` |
| lt | uint16 LE | 2 | Left trigger, `0..1000` |
| rt | uint16 LE | 2 | Right trigger, `0..1000` |
| checksum | uint8 | 1 | XOR checksum |

Python format before checksum:

```python
PACK_FMT_NO_CHECKSUM = "<2sBBHHhhhhHH"
FRAME_LEN = struct.calcsize(PACK_FMT_NO_CHECKSUM) + 1
```

## Checksum

The checksum is the XOR of every byte after magic and before checksum:

```text
checksum(frame_without_checksum[2:])
```

In other words, the magic bytes are not included in the checksum.

## v2 Button Bitmask

| Bit | Hex | Meaning |
| ---: | --- | --- |
| 0 | `0x01` | Cross / A |
| 1 | `0x02` | Circle / B |
| 2 | `0x04` | Square / X |
| 3 | `0x08` | Triangle / Y |
| 4 | `0x10` | Options / Start registration pulse |
| 5 | `0x20` | L1 / left bumper |
| 6 | `0x40` | R1 / right bumper |

## v3 Button Bitmask

| Bit | Hex | Meaning |
| ---: | --- | --- |
| 0 | `0x0001` | Cross / A |
| 1 | `0x0002` | Circle / B |
| 2 | `0x0004` | Square / X |
| 3 | `0x0008` | Triangle / Y |
| 4 | `0x0010` | Options / Start registration pulse |
| 5 | `0x0020` | L1 / left bumper |
| 6 | `0x0040` | R1 / right bumper |
| 7 | `0x0080` | D-pad up |
| 8 | `0x0100` | D-pad down |
| 9 | `0x0200` | D-pad left |
| 10 | `0x0400` | D-pad right |

## Mac Controller Mapping

Current expected DS4 mapping through `pygame`:

| Control | pygame input |
| --- | --- |
| Left stick X | axis 0 |
| Left stick Y | axis 1 |
| Right stick X | axis 2 |
| Right stick Y | axis 3 |
| Left trigger | axis 4 |
| Right trigger | axis 5 |
| Cross | button 0 |
| Circle | button 1 |
| Square | button 2 |
| Triangle | button 3 |
| L1 | button 9 by default |
| R1 | button 10 by default |
| D-pad | hat 0, or buttons 11/12/13/14 by default |

L1/R1 can be changed with:

```bash
--l1-button 9 --r1-button 10
```

D-pad button fallbacks can be changed with:

```bash
--dpad-up-button 11 --dpad-down-button 12 --dpad-left-button 13 --dpad-right-button 14
```

Triggers are normalized from the usual DS4 axis range `-1.0..1.0` into `0..1000`.

## Legacy Feather HID Mapping

The Feather exposes a custom TinyUSB gamepad report:

```cpp
typedef struct __attribute__((packed)) {
  uint16_t buttons;
  int8_t x;
  int8_t y;
  int8_t z;
  int8_t rz;
  uint8_t brake;
  uint8_t accelerator;
} lora_gamepad_report_t;
```

Current HID behavior in [feather/feather.ino](../feather/feather.ino):

| Protocol input | HID output |
| --- | --- |
| Cross / A | button bit 0 |
| Circle / B | button bit 1 |
| Square / X | button bit 3 |
| Triangle / Y | button bit 4 |
| Options / Start | button bit 11 |
| L1 / left bumper | button bit 6 |
| R1 / right bumper | button bit 7 |
| Left stick | X/Y axes |
| Right stick | Z/Rz axes |
| Left trigger | Brake axis |
| Right trigger | Accelerator axis |

The L1/R1 mappings in this document intentionally match the deployed code.

## Multi-Robot Feather Behavior

Each v3 Feather sketch has a fixed robot id:

| Robot | Sketch | Robot id |
| --- | --- | ---: |
| Flash | [flash/flash.ino](../flash/flash.ino) | `1` |
| Fable | [fable/fable.ino](../fable/fable.ino) | `2` |
| Sol | [sol/sol.ino](../sol/sol.ino) | `3` |

All robots receive every LoRa packet. A robot only applies packets whose `target_robot` matches its own id, or packets addressed to `255`.

When a packet targets another robot, the Feather emits neutral HID reports so the connected Android phone keeps seeing a controller with all controls released.

Flash and Fable preserve the deployed button/axis HID mappings from the original Feather sketch. Sol uses the same stick axes, maps right trigger to Accelerator, maps Square/X to the tested X-button output, and maps the protocol D-pad bits to a HID hat switch.
