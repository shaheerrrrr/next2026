# Troubleshooting

## Dashboard Shows Controller Movement, But Robot Does Not Respond

Likely causes:

- Uno is not receiving serial frames.
- Uno radio is not transmitting.
- Feather is not receiving valid LoRa frames.
- Feather is connected to the wrong Android device state or Driver Station has not registered it as a controller.

Check the dashboard first. If sticks/buttons move there, the Mac controller path is working.

## Feather Prints `alive, waiting for LoRa frames`

This means the Feather sketch and USB serial are alive, but it is not receiving valid LoRa controller frames.

Check:

- Both radios are on 915.0 MHz.
- Both antennas are attached.
- Uno RFM95 wiring is correct.
- Uno sketch is flashed and running.
- Python is using the correct serial port.
- Frame length is 19 bytes on all components.

## Uno LoRa Init Fails

If a debug sketch or modified Uno sketch reports LoRa initialization failure, suspect Uno-side wiring, SPI, chip select, reset, IRQ, power, or pin mismatch before looking at Python or HID.

Known Uno pins:

```cpp
#define RFM95_CS 10
#define RFM95_RST 9
#define RFM95_INT 2
```

## Intermittent or Chopped Packets

Confirm all components use:

```cpp
#define FRAME_LEN 19
```

The packet is 19 bytes, not 18:

```text
2 magic + 1 version + 2 seq + 1 buttons + 8 sticks + 4 triggers + 1 checksum = 19
```

## Triggers Do Not Work in FTC Robot Code

FTC reads triggers as analog fields:

```java
gamepad1.left_trigger
gamepad1.right_trigger
```

The Feather must expose triggers as analog HID axes. The current code uses Brake and Accelerator axes for this.

Do not debug this by only checking button values. A phone tester showing trigger button presses does not guarantee FTC analog trigger fields will work.

## Driver Station Controller Registration Does Not Work

Use the dashboard `Driver 1: Start + A` button.

The current Feather code maps the protocol Options/Start bit to HID button bit 11 because that was empirically found to work for Driver Station registration.

## Python Crashes With L1/R1 Mapping Issues

The deployed Python code passes L1/R1 indexes into:

```python
read_controller_state(joystick, args.l1_button, args.r1_button)
```

If a different OS/controller mapping reports different button indexes, use:

```bash
--l1-button <index> --r1-button <index>
```

## Browser Shows Connection Reset Noise

If the Python process exits or crashes while the dashboard has an open event stream, the browser/server may show `ConnectionResetError` noise. Treat this as a symptom of the app stopping, not as the primary failure.

