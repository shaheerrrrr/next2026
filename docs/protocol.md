# Protocol Reference

The current controller frame is 19 bytes.

Keep Python, Uno, and Feather in sync if this protocol changes.

## Frame Layout

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

## Checksum

The checksum is the XOR of every byte after magic and before checksum:

```text
checksum(frame_without_checksum[2:])
```

In other words, the magic bytes are not included in the checksum.

## Button Bitmask

| Bit | Hex | Meaning |
| ---: | --- | --- |
| 0 | `0x01` | Cross / A |
| 1 | `0x02` | Circle / B |
| 2 | `0x04` | Square / X |
| 3 | `0x08` | Triangle / Y |
| 4 | `0x10` | Options / Start registration pulse |
| 5 | `0x20` | L1 / left bumper |
| 6 | `0x40` | R1 / right bumper |

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

L1/R1 can be changed with:

```bash
--l1-button 9 --r1-button 10
```

Triggers are normalized from the usual DS4 axis range `-1.0..1.0` into `0..1000`.

## Feather HID Mapping

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

