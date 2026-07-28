# Current Protocol Reference

This document describes only the protocol currently implemented and deployed.
It is a wire-level reference for maintainers. Historical frame formats belong
in Git history, not in this running specification.

## Conventions

- All multibyte integers are little-endian.
- Signed values use two's-complement representation.
- `E7` coordinates are signed degrees multiplied by `10,000,000`.
- Packed ESP32 structures are transmitted without compiler padding.
- Tele-op uses an XOR checksum.
- Fable navigation binary messages use CRC-16/CCITT-FALSE.

## Tele-Op LoRa Frame

### Transport

| Property | Value |
| --- | --- |
| Protocol version | `3` |
| Frame length | `21` bytes |
| Desktop-to-Uno serial | `115200 baud`, raw binary |
| LoRa frequency | `915.0 MHz` |
| Normal frame rate | `20 Hz` |
| Python format without checksum | `<2sBBHHhhhhHH` |

The Python process sends the same 21 bytes that the Uno transmits over LoRa.
The Uno does not wrap, escape, or reinterpret a valid frame.

### Byte Layout

| Offset | Size | Field | Type | Meaning |
| --- | --- | --- | --- | --- |
| `0` | 2 | `magic` | bytes | `0xA5 0x5A` |
| `2` | 1 | `version` | `uint8` | Must be `3` |
| `3` | 1 | `target_robot` | `uint8` | Numeric robot address |
| `4` | 2 | `seq` | `uint16` | Sequence number, wraps modulo 65536 |
| `6` | 2 | `buttons` | `uint16` | Controller button bitmask |
| `8` | 2 | `lx` | `int16` | Left stick X, `-1000..1000` |
| `10` | 2 | `ly` | `int16` | Left stick Y, `-1000..1000` |
| `12` | 2 | `rx` | `int16` | Right stick X, `-1000..1000` |
| `14` | 2 | `ry` | `int16` | Right stick Y, `-1000..1000` |
| `16` | 2 | `lt` | `uint16` | Left trigger, `0..1000` |
| `18` | 2 | `rt` | `uint16` | Right trigger, `0..1000` |
| `20` | 1 | `checksum` | `uint8` | XOR of bytes `2..19` inclusive |

### Checksum

The two magic bytes and final checksum byte are excluded:

```text
checksum = frame[2] XOR frame[3] XOR ... XOR frame[19]
```

Python implements this as:

```python
frame_without_checksum + bytes([checksum(frame_without_checksum[2:])])
```

Receivers reject a frame with the wrong total length, magic, version, or
checksum.

### Robot Addresses

| Value | Robot |
| --- | --- |
| `1` | Flash |
| `2` | Fable |
| `3` | Sol |
| `255` | All robots / broadcast address |

Robot names are never sent in the binary frame. `target=Fable` in the dashboard
log is a human-readable rendering of byte value `2`.

Each Feather accepts its own ID or `255`. A valid frame for another robot is not
applied as gamepad state.

### Button Bitmask

| Mask | Protocol name | PS4 label | FTC-style label |
| --- | --- | --- | --- |
| `0x0001` | `BTN_CROSS` | Cross | A |
| `0x0002` | `BTN_CIRCLE` | Circle | B |
| `0x0004` | `BTN_SQUARE` | Square | X |
| `0x0008` | `BTN_TRIANGLE` | Triangle | Y |
| `0x0010` | `BTN_OPTIONS` | Options | Start/registration usage |
| `0x0020` | `BTN_L1` | L1 | Left bumper |
| `0x0040` | `BTN_R1` | R1 | Right bumper |
| `0x0080` | `BTN_DPAD_UP` | D-pad up | D-pad up |
| `0x0100` | `BTN_DPAD_DOWN` | D-pad down | D-pad down |
| `0x0200` | `BTN_DPAD_LEFT` | D-pad left | D-pad left |
| `0x0400` | `BTN_DPAD_RIGHT` | D-pad right | D-pad right |

Unused upper button bits must be transmitted as zero until assigned.

### Desktop Controller Normalization

The default Pygame mapping is:

| Input | Default index |
| --- | --- |
| Left stick X | Axis `0` |
| Left stick Y | Axis `1` |
| Right stick X | Axis `2` |
| Right stick Y | Axis `3` |
| Left trigger | Axis `4` |
| Right trigger | Axis `5` |
| Cross | Button `0` |
| Circle | Button `1` |
| Square | Button `2` |
| Triangle | Button `3` |
| L1 | Button `9` |
| R1 | Button `10` |

Sticks receive a `0.06` deadband, are clamped to `-1.0..1.0`, and are rounded
after multiplication by 1000.

DS4 triggers normally report `-1.0` released and `+1.0` fully pressed. They are
converted with:

```text
trigger = round(((clamp(axis, -1, 1) + 1) / 2) * 1000)
```

The D-pad is read from hat `0` when available. Buttons `11`, `12`, `13`, and
`14` are also checked as fallback up, down, left, and right inputs.

### Driver Registration Injection

Clicking **Register Driver 1** causes the desktop to set `BTN_OPTIONS` and
`BTN_CROSS` for approximately 0.7 seconds in frames addressed to the selected
robot. The Feather mappings below produce the empirically verified Android FTC
Driver Station registration combination.

## Robot USB HID Reports

The LoRa frame is shared, but HID conversion is local to each robot. These HID
reports are part of the deployed compatibility contract because Android and FTC
interpret specific usages and button positions.

### Flash And Fable HID Report

Flash and Fable use the same packed eight-byte report on Feather M0 boards:

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

| Report field | HID usage | Source |
| --- | --- | --- |
| `x` | X | `lx`, mapped `-1000..1000` to `-127..127` |
| `y` | Y | `ly`, mapped `-1000..1000` to `-127..127` |
| `z` | Z | `rx`, mapped `-1000..1000` to `-127..127` |
| `rz` | Rz | `ry`, mapped `-1000..1000` to `-127..127` |
| `brake` | Brake | `lt`, mapped `0..1000` to `0..255` |
| `accelerator` | Accelerator | `rt`, mapped `0..1000` to `0..255` |

The custom Brake and Accelerator usages are required for FTC analog trigger
fields. Mapping triggers to buttons does not populate
`gamepad1.left_trigger` or `gamepad1.right_trigger`.

| Protocol input | HID button bit set |
| --- | --- |
| Cross | `0` |
| Circle | `1` |
| Square | `3` |
| Triangle | `4` |
| L1 | `6` |
| R1 | `7` |
| Options | `11` |

The descriptor exposes 16 buttons followed by X, Y, Z, Rz, Brake, and
Accelerator.

### Sol HID Report

Sol uses a packed nine-byte payload on the Feather 32u4. The USB HID transport
also uses report ID `1`.

```cpp
typedef struct __attribute__((packed)) {
  uint16_t buttons;
  int8_t x;
  int8_t y;
  int8_t z;
  int8_t rz;
  uint8_t brake;
  uint8_t accelerator;
  uint8_t hat;
} lora_gamepad_report_t;
```

Sol maps all four stick fields as above, forces `brake` to zero, and maps only
the right trigger to `accelerator`.

| Protocol input | Sol HID output |
| --- | --- |
| Cross | HID button bit `0` |
| Square | HID button bit `3` |
| Options | HID button bit `11` |
| Right trigger | Accelerator `0..255` |
| D-pad | Hat value below |

| D-pad direction | Hat value |
| --- | --- |
| Up | `0` |
| Up-right | `1` |
| Right | `2` |
| Down-right | `3` |
| Down | `4` |
| Down-left | `5` |
| Left | `6` |
| Up-left | `7` |
| Neutral | `8` in the report payload |

### HID Timing And Neutralization

Every receiver attempts a HID report every 20 ms. If no valid addressed frame
has been applied for more than 250 ms, all controls are reset to neutral before
the next report.

## Fable Driver C3 Serial Protocol

The desktop and driver-side M5Stamp C3 communicate over USB serial at 115200
baud. Commands and responses are UTF-8/ASCII text terminated by newline. This
serial protocol is independent of the binary LoRa frame.

### Desktop-To-C3 Commands

| Command | Meaning |
| --- | --- |
| `TARGET <lat_e7> <lon_e7>` | Send a target in signed integer E7 coordinates |
| `CLEAR` | Clear the current target |
| `TARGET_DEG <lat> <lon>` | Firmware-supported decimal-degree target command; the Flask app currently uses `TARGET` |

Examples:

```text
TARGET 388578089 -773292513
CLEAR
```

Input lines longer than 96 characters are discarded and produce an error JSON
response.

### C3-To-Desktop JSON

Startup success:

```json
{"type":"status","ok":true,"mac":"AA:BB:CC:DD:EE:FF"}
```

Send acceptance:

```json
{"type":"send","ok":true}
```

Send or parse failure:

```json
{"type":"send","ok":false,"error":"description"}
```

Telemetry output contains:

```json
{
  "type": "telemetry",
  "nav_seq": 123,
  "target_seq": 45,
  "lat_e7": 388578089,
  "lon_e7": -773292513,
  "target_lat_e7": 388579000,
  "target_lon_e7": -773291000,
  "flags": 31,
  "fix": 2,
  "satellites": 9,
  "hdop_x100": 85,
  "gps_age_ms": 120,
  "command_age_ms": 210,
  "uptime_ms": 123456
}
```

`{"type":"send","ok":true}` means the local ESP-NOW API accepted the packet
for transmission. It is not a robot-side acknowledgement. Fresh returned
telemetry is the evidence that the navigation radio path is alive.

## Fable ESP-NOW Protocol

### Radio Settings

| Property | Value |
| --- | --- |
| Channel | `1` |
| Peer address | `FF:FF:FF:FF:FF:FF` broadcast |
| Encryption | Disabled |
| Command interval | Heartbeat every `500 ms`; target/clear on demand |
| Telemetry interval | `100 ms` |

### CRC-16/CCITT-FALSE

Every binary navigation packet uses:

| Parameter | Value |
| --- | --- |
| Polynomial | `0x1021` |
| Initial value | `0xFFFF` |
| Input reflection | False |
| Output reflection | False |
| Final XOR | `0x0000` |

The CRC covers every packet byte except the final two-byte CRC field. The CRC
field itself is encoded little-endian.

### Navigation Command Packet

Length: `20` bytes. Magic: ASCII `FNC1`. Version: `1`.

| Offset | Size | Field | Type |
| --- | --- | --- | --- |
| `0` | 4 | `magic` | `char[4]`, `FNC1` |
| `4` | 1 | `version` | `uint8`, `1` |
| `5` | 1 | `type` | `uint8` |
| `6` | 4 | `seq` | `uint32` |
| `10` | 4 | `targetLatE7` | `int32` |
| `14` | 4 | `targetLonE7` | `int32` |
| `18` | 2 | `crc` | `uint16` over bytes `0..17` |

| Type | Value | Semantics |
| --- | --- | --- |
| Target | `1` | Store coordinates, set target valid, and copy `seq` to target sequence |
| Clear target | `2` | Clear target validity and coordinates; copy `seq` to target sequence |
| Heartbeat | `3` | Refresh driver command age without changing target state |

Any valid packet refreshes `lastDriverCommandMs`. Unknown but CRC-valid types
therefore refresh link age without changing the target; senders should use only
the assigned values.

### Navigation Telemetry Packet

Length: `49` bytes. Magic: ASCII `FNT1`. Version: `1`. Type: `16`.

| Offset | Size | Field | Type | Meaning |
| --- | --- | --- | --- | --- |
| `0` | 4 | `magic` | `char[4]` | `FNT1` |
| `4` | 1 | `version` | `uint8` | `1` |
| `5` | 1 | `type` | `uint8` | `16` |
| `6` | 4 | `navSeq` | `uint32` | Increments when GPS location updates |
| `10` | 4 | `targetSeq` | `uint32` | Sequence of most recent target/clear command |
| `14` | 4 | `latE7` | `int32` | Current latitude |
| `18` | 4 | `lonE7` | `int32` | Current longitude |
| `22` | 4 | `targetLatE7` | `int32` | Stored target latitude |
| `26` | 4 | `targetLonE7` | `int32` | Stored target longitude |
| `30` | 1 | `flags` | `uint8` | Validity flags |
| `31` | 1 | `fixQuality` | `uint8` | `0` none, `1` weak, `2` good |
| `32` | 1 | `satellites` | `uint8` | Saturated at 255 |
| `33` | 2 | `hdopX100` | `uint16` | HDOP scaled by 100 |
| `35` | 4 | `gpsAgeMs` | `uint32` | Age of latest GPS update |
| `39` | 4 | `commandAgeMs` | `uint32` | Age of latest valid command/heartbeat |
| `43` | 4 | `uptimeMs` | `uint32` | Robot C3 uptime from `millis()` |
| `47` | 2 | `crc` | `uint16` | CRC over bytes `0..46` |

If no timestamp has ever been recorded, an age is `0xFFFFFFFF`.

### Navigation Flags

| Mask | Meaning |
| --- | --- |
| `0x01` | GPS position is valid and recent |
| `0x02` | A target is stored and valid |
| `0x04` | Satellite count is valid |
| `0x08` | HDOP is valid |
| `0x10` | A driver command or heartbeat was received within 2000 ms |

Fix quality is computed from a position no older than 2000 ms. A valid count of
four or more satellites produces quality `2`; any other recent valid location
produces `1`.

## Robot C3-To-Pico FNAV Snapshot

### UART Transport

| Property | Value |
| --- | --- |
| UART | `115200 baud`, 8 data bits, no parity, 1 stop bit |
| Producer | Fable robot ESP32-C3 |
| Consumer | Raspberry Pi Pico UART0 |
| Interval | `100 ms` |
| Frame length | `56` bytes |
| Magic | ASCII `FNAV` |
| Version | `1` |

The UART stream has no separate framing escape. The Pico scans for `FNAV`,
checks version and declared length at byte 6, then validates the full CRC.

### FNAV Layout

| Offset | Size | Field | Type | Meaning |
| --- | --- | --- | --- | --- |
| `0` | 4 | `magic` | bytes | ASCII `FNAV` |
| `4` | 1 | `version` | `uint8` | `1` |
| `5` | 1 | `length` | `uint8` | `56` |
| `6` | 1 | `flags` | `uint8` | Same flag masks as ESP-NOW telemetry |
| `7` | 1 | `fixQuality` | `uint8` | `0` none, `1` weak, `2` good |
| `8` | 4 | `navSeq` | `uint32` | GPS update sequence |
| `12` | 4 | `targetSeq` | `uint32` | Most recent target/clear sequence |
| `16` | 4 | `currentLatE7` | `int32` | Current latitude |
| `20` | 4 | `currentLonE7` | `int32` | Current longitude |
| `24` | 4 | `targetLatE7` | `int32` | Target latitude |
| `28` | 4 | `targetLonE7` | `int32` | Target longitude |
| `32` | 4 | `gpsAgeMs` | `uint32` | GPS update age or `0xFFFFFFFF` |
| `36` | 4 | `commandAgeMs` | `uint32` | Driver command age or `0xFFFFFFFF` |
| `40` | 2 | `hdopX100` | `uint16` | HDOP scaled by 100 |
| `42` | 1 | `satellites` | `uint8` | Satellite count |
| `43` | 1 | reserved | `uint8` | Must currently be zero |
| `44` | 4 | `gpsCharsProcessed` | `uint32` | TinyGPSPlus diagnostic count |
| `48` | 4 | `uptimeMs` | `uint32` | Robot C3 uptime |
| `52` | 2 | reserved | bytes | Must currently be zero |
| `54` | 2 | `crc` | `uint16` | CRC over bytes `0..53` |

The Pico creates a valid startup snapshot with no valid flags and both age
fields set to `0xFFFFFFFF`. This allows the Control Hub to distinguish a live
bridge with no navigation data from malformed I2C bytes.

## Pico I2C Register Protocol

| Property | Value |
| --- | --- |
| Device role | I2C peripheral/slave |
| Seven-bit address | `0x42` |
| Bus | I2C0, `100 kHz` |
| Register range | `0..55` |
| Register contents | One byte of the current FNAV snapshot |

The Control Hub reads the 56-byte snapshot as four transactions:

| Transaction | Start register | Length |
| --- | --- | --- |
| 1 | `0` | `14` |
| 2 | `14` | `14` |
| 3 | `28` | `14` |
| 4 | `42` | `14` |

The first write byte in an I2C transaction sets the register address. Selecting
register `0` copies the latest published UART snapshot into a latched buffer.
All subsequent reads come from that buffer until the next register-zero start,
so the four chunks form one CRC-consistent 56-byte image.

Reads beyond register 55 return zero. Extra write bytes in one transaction are
ignored after the first register byte.

## Dashboard HTTP And SSE Interface

The browser interface is local to the driver computer. It is not exposed to the
robots unless the web host is deliberately changed.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/` | Dashboard HTML |
| `GET` | `/api/status` | Current driver-station snapshot |
| `POST` | `/api/robot/<flash|fable|sol>` | Change active tele-op target |
| `POST` | `/api/driver1` | Start the registration pulse |
| `POST` | `/api/fable/target` | Accept JSON `{lat, lon}` and send an E7 target |
| `POST` | `/api/fable/cancel` | Send `CLEAR` and reset desktop navigation state |
| `GET` | `/events` | Server-Sent Events stream |

SSE event names currently include `status`, `frame`, `fable_nav`, `notice`, and
`ping`. `ping` is emitted after 15 seconds without another event to keep the
stream alive.

## Compatibility Rules

### Changing Tele-Op

Any change to tele-op version, size, offsets, checksum, robot IDs, or button
bits must be reviewed across:

- Desktop Python builder
- Uno framing validator
- Flash receiver
- Fable receiver
- Sol receiver
- Android HID mappings and affected Control Hub code

### Changing Fable Navigation

Any change to packed ESP-NOW or FNAV structures must preserve explicit packing,
little-endian encoding, exact size, and CRC coverage across both endpoints.
Update the driver C3, robot C3, Pico, desktop parser, Fable Control Hub decoder,
and this document as required.

Do not increment a protocol version in only one component. A version change is
a coordinated deployment event, not a local refactor.
