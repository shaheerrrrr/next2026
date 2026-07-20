# Driver Navigation LED

The driver-side M5Stamp C3 uses its onboard SK6812 RGB LED as an ambient Fable navigation-link indicator. The LED animation is non-blocking and does not change the serial protocol, ESP-NOW packets, heartbeat timing, or navigation behavior.

## Color Guide

| Appearance | Meaning |
| --- | --- |
| Short rainbow animation | The driver-side navigation board has started. |
| Teal breathing and gently shifting shades | Valid Fable telemetry has been received recently and the navigation link is active. |
| Amber breathing and gently shifting shades | The board is waiting for telemetry, or previously received telemetry is stale. |
| Brief bright green pulse | A valid telemetry packet was received from Fable. |
| Brief bright blue pulse | A driver target or clear command was accepted by ESP-NOW for transmission. |
| Brief bright red pulse | ESP-NOW rejected a driver target or clear command. |
| Continuous red breathing | ESP-NOW failed to initialize. |

The regular 500 ms heartbeat is intentionally excluded from the blue transmit pulse. Showing every heartbeat would create a constant 2 Hz flash and obscure meaningful driver commands.

## Timing

- Startup rainbow: 1.8 seconds.
- Telemetry remains fresh for 2.5 seconds after the most recent valid packet.
- Receive pulse: 140 ms.
- Successful command pulse: 360 ms.
- Failed command pulse: 600 ms.
- LED rendering: 50 frames per second.

## Arduino Dependency

Install the `FastLED` library before compiling [fable_driver_nav_esp32c3.ino](../fable_driver_nav_esp32c3/fable_driver_nav_esp32c3.ino). The sketch uses the M5Stamp C3 onboard SK6812 LED on GPIO 2 with GRB color ordering.

## Interpretation Note

The blue pulse means `esp_now_send()` accepted the command for transmission. It does not prove that the robot application processed the command. The green pulse represents a telemetry packet that passed the driver-side packet length, magic, version, type, and CRC validation.
