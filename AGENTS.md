# Agent Maintenance Guide

This file is the repository-level operating contract for Codex, coding agents,
and LLM-assisted contributors. Read it before modifying code or documentation.
Human operators should begin with `README.md` and `docs/procedures.md`.

## Mission And Current Scope

This repository implements long-range control of three robots through an
addressed LoRa gamepad pipeline. Flash and Fable share the standard FTC-style
HID control mapping. Sol uses the same transport with a smaller control profile
that includes D-pad, right trigger, sticks, and selected face buttons.

Only Fable currently has GPS point-to-point navigation. Do not document Flash
as navigation-capable until the implementation is actually deployed and the
user asks to merge that architecture.

## Source Of Truth

Use this precedence order when facts conflict:

1. Deployed code in the current worktree and the relevant robot branch.
2. Hardware behavior confirmed by the user.
3. Current documentation.
4. Git history and old discussion.

Code is ground truth, but stale documentation is still a defect. Correct it in
the same change whenever behavior, wiring, protocol, commands, or ownership
changes.

Do not restore retired files or protocol descriptions merely because they exist
in Git history. In particular, the current tele-op contract is protocol `v3`,
`21` bytes, and addressed by numeric robot ID. The old single-robot `v2`,
`19`-byte protocol is not part of the current documentation.

## Branch Ownership

| Branch | Primary contents |
| --- | --- |
| `ftc-lora` | Desktop driver station, Uno and Feather firmware, Fable navigation sidecar firmware, and documentation. |
| `flash` | Flash REV Control Hub robot project. |
| `fable` | Fable REV Control Hub robot project and navigation consumer. |
| `sol` | Sol REV Control Hub robot project. |

When inspecting another branch, prefer read-only commands such as
`git show fable:path/to/file` or a separate worktree. Do not switch branches in
a dirty worktree. If a branch switch is unavoidable, return to the original
branch and verify `git status` before making edits.

## Repository Map

| Area | Owner |
| --- | --- |
| `driver_station_flask.py` | Desktop controller input, packet assembly, dashboard, serial lifecycle, Fable map, and navigation commands. |
| `driverstation/driverstation.ino` | Transparent validated serial-to-LoRa forwarding. |
| `flash/flash.ino` | Flash LoRa filtering and TinyUSB HID output. |
| `fable/fable.ino` | Fable LoRa filtering, TinyUSB gamepad HID output, and TinyUSB keyboard HID output for Driver Station command chords. |
| `sol/sol.ino` | Sol LoRa filtering and AVR HID output. |
| `fable_driver_nav_esp32c3/` | Driver-side serial/ESP-NOW navigation bridge and visible status LED. |
| `fable_robot_nav_esp32c3/` | Fable GPS ingestion, target state, telemetry, and Pico snapshot producer. |
| `fable_navigation_bridge/` | Pico UART parser and I2C slave. |
| `docs/` | Current operational, architectural, deployment, and protocol references. |

## Non-Negotiable Invariants

- LoRa tele-op frames are protocol `v3` and exactly `21` bytes.
- Robot IDs are Flash `1`, Fable `2`, Sol `3`, and broadcast `255`.
- Robot names are for UI and logs; only the one-byte numeric ID is transmitted.
- All LoRa radios use `915.0 MHz` in the current deployment.
- Desktop serial links use `115200 baud` unless every endpoint and document is
  deliberately changed together.
- The Uno forwards a validated frame unchanged. It does not interpret controls.
- Flash and Fable Feather firmware use a custom analog-trigger HID descriptor.
- Sol runs on an Adafruit Feather 32u4 RFM95 and has a distinct HID report.
- Every robot must neutralize HID output after `250 ms` without a valid,
  addressed frame.
- Fable navigation is a separate ESP-NOW/UART/I2C data plane. Do not put GPS
  telemetry into the high-rate LoRa gamepad frame without an explicit redesign.
- Fable's navigation I2C address is `0x42` and its ESP-NOW channel is `1`.
- Antennas must be attached before intentional LoRa transmission.
- Fable and Flash present separate gamepad and keyboard HID interfaces. Sol's
  AVR HID implementation carries gamepad and keyboard reports on one interface.
- `BTN_UI_CMD` (`0x0800`) frames carry a Driver Station command chord (a HID
  modifier/key pair) in `lx`, not stick data. All three current receiver
  firmwares handle it, but adding another receiver to the UI-command allowlist
  is unsafe until that receiver has matching handling and has been reflashed.

## Coupled Changes

Some edits cannot be made safely in one file.

### Tele-Op Frame Layout

If the frame size, field offsets, version, checksum, robot ID, or button bitmask
changes, inspect and usually update all of these:

- `driver_station_flask.py`
- `driverstation/driverstation.ino`
- `flash/flash.ino`
- `fable/fable.ino`
- `sol/sol.ino`
- `docs/protocol.md`
- `docs/architecture.md`
- `docs/deployment.md` when board behavior or compatibility changes

The Uno usually needs no semantic button change while the frame remains 21
bytes, but it must still be checked because it validates the version, length,
magic, and checksum.

### HID Mapping

If a controller control is added or remapped, update the desktop packet source,
the affected robot's HID report, and the dashboard visualization together.
Confirm the Android Driver Station interpretation, not only a generic Android
gamepad tester. Analog FTC trigger fields require analog HID usages.

### Driver Station Command Chords

If the `BTN_UI_CMD` bit, the chord word encoding, the HID modifier/key tables,
the default chords, the pulse/cooldown timing, or the keyboard HID report
layout changes, inspect all affected layers:

- `driver_station_flask.py` (chord parsing/encoding, `SharedState`, the
  transmitter injection, and the `/api/ui-command*` routes)
- `ui_commands.json` (the on-disk shape it is validated against; do not commit
  this file itself -- it is gitignored operator state)
- `fable/fable.ino`
- `docs/protocol.md`
- `docs/procedures.md`
- `docs/multi-robot.md`
- The `RobotReset` AccessibilityService app on branch `robot-reset-app` in the
  sibling `next2026` repository, which decodes the resulting key event and is
  not in this worktree. Its `ChordDecoder` chord table is independent of this
  repo's `ui_commands.json` defaults; the two are expected to be edited to
  match, not kept structurally coupled.

### Fable Navigation Transport

If an ESP-NOW packet, serial command, telemetry field, FNAV snapshot field, CRC,
or freshness rule changes, inspect all affected layers:

- `driver_station_flask.py`
- `fable_driver_nav_esp32c3/fable_driver_nav_esp32c3.ino`
- `fable_robot_nav_esp32c3/fable_robot_nav_esp32c3.ino`
- `fable_navigation_bridge/main.c`
- Fable Control Hub navigation code on branch `fable`
- `docs/protocol.md`
- `docs/fable-navigation.md`
- `docs/deployment.md`

Never change a packed C/C++ struct without checking exact byte size, field
offsets, endianness, alignment attributes, and CRC coverage at every endpoint.

### Dashboard State

The Fable autonomous badge is desktop-side state, not authoritative Control Hub
feedback. A UI change must preserve that distinction. Current short-term
behavior clears the dashboard autonomous state when a meaningful Fable tele-op
drive or exit command is sent. Do not claim this proves the Control Hub has
stopped autonomous execution.

Sol's flywheel target indicator is also desktop-side state. It mirrors the
deployed `Shooter.java` default (`3000 RPM`), step (`100 RPM`), zero clamp,
D-pad edge handling, and Square/X reset, but receives no Sol telemetry. Keep
those constants synchronized with the `sol` branch and describe the Android
Driver Station telemetry as authoritative. Successful Sol command-chord
requests reset the estimate because the OpMode is expected to restart.

## Documentation Ownership

Each document has one job:

| Document | Ownership |
| --- | --- |
| `README.md` | Entry point, documentation index, quick finds, source map, headline invariants. |
| `docs/architecture.md` | End-to-end system design, data flow, component boundaries, and failure model. |
| `docs/procedures.md` | Runtime-only operator instructions for already-flashed hardware. |
| `docs/deployment.md` | Firmware and software placement, wiring, board targets, and libraries. |
| `docs/protocol.md` | Exact current wire and API contracts. No legacy section. |
| `docs/multi-robot.md` | Addressing, selection, registration, and simultaneous operation. |
| `docs/fable-navigation.md` | Fable-specific navigation behavior and operator semantics. |
| `docs/driver-navigation-led.md` | Driver C3 LED states. |
| `AGENTS.md` | Agent workflow, coupling rules, validation, and documentation policy. |

When code changes, update the smallest complete set of documents. Do not paste
the same long explanation into every file. Put exact bytes in `protocol.md`,
board placement in `deployment.md`, runtime commands in `procedures.md`, and
link between them.

Documentation must describe the current deployed system in present tense.
Future ideas belong in an explicitly requested design document, not in the
current protocol or operator procedures.

## Change Workflow

1. Read `git status` and preserve user changes.
2. Read the implementation at every affected endpoint before proposing a wire
   or HID change.
3. Identify coupled files using the lists above.
4. Make the smallest coherent implementation.
5. Update documentation in the same worktree.
6. Run syntax, build, or static checks available without hardware.
7. Review the diff for accidental protocol, pin, board, or branch drift.
8. Report what was verified locally and what still requires physical hardware.

Do not revert unrelated user changes. Do not replace deployed code with an old
example sketch. Do not silently alter radio frequency, pins, robot IDs,
freshness windows, safety neutralization, or board targets.

## Validation Checklist

Use the checks that apply to the change:

```bash
python3 -m py_compile driver_station_flask.py
```

```bash
python3 driver_station_flask.py --help
```

```bash
rg -n "FRAME_LEN|PROTOCOL_VERSION|ROBOT_ID|RF95_FREQ|STALE_MS" \
  driverstation flash fable sol driver_station_flask.py
```

For Pico changes, configure and build with a working Pico SDK as described in
`docs/deployment.md`. For Arduino changes, compile against the exact board
target and libraries in the deployment matrix. A successful host syntax check
does not validate USB HID descriptors, Android mappings, ESP-NOW radio behavior,
GPS reception, I2C electrical wiring, or motor safety.

After documentation changes:

- Search for retired `v2`, `19 byte`, and old filename references.
- Check every relative Markdown link.
- Confirm Mac and Windows commands use current filenames and both serial ports.
- Confirm protocol sizes and offsets against source, not memory.
- Confirm only Fable is described as navigation-capable.

## Safety And Handoff

Loss of LoRa frames neutralizes Feather HID reports after 250 ms. That protects
tele-op commands. It does not guarantee that Control Hub autonomous code stops,
because autonomy can continue from the last valid GPS target without HID input.

Before ending work that could affect motion:

- State whether the change affects tele-op, autonomous behavior, or both.
- State which boards or branches must be redeployed.
- State which checks passed.
- State which checks require bench or outdoor hardware testing.
- Call out any temporary behavior, such as Fable's current roller-only
  `ChudIntake`, when it affects test interpretation.

The goal is that the next contributor can resume from the repository alone,
without reconstructing assumptions from chat history.
