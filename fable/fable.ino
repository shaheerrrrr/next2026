#include <SPI.h>
#include <RH_RF95.h>
#include "Adafruit_TinyUSB.h"

#define ROBOT_ID 2
#define ROBOT_NAME "Fable"
#define ROBOT_ALL 255

#define RFM95_CS 8
#define RFM95_RST 4
#define RFM95_INT 3

#define RF95_FREQ 915.0
#define FRAME_LEN 21
#define PROTOCOL_VERSION 3
#define LED 13

#define BTN_CROSS    0x0001
#define BTN_CIRCLE   0x0002
#define BTN_SQUARE   0x0004
#define BTN_TRIANGLE 0x0008
#define BTN_OPTIONS  0x0010
#define BTN_L1       0x0020
#define BTN_R1       0x0040
#define BTN_UI_CMD   0x0800

#define STALE_MS 250
#define HID_SEND_INTERVAL_MS 20

// Driver Station command chord (INIT/START/STOP). When BTN_UI_CMD is set in a
// received frame, lx carries (modifier_byte << 8) | hid_usage_id instead of
// stick data. This board is a dumb chord emitter: the chord table lives on
// the desktop (driver_station_flask.py / ui_commands.json), not here.
#define UI_CMD_HOLD_MS     40   // two HID intervals: a discrete down/up, well
                                 // short of Android's ~400ms auto-repeat delay
#define UI_CMD_COOLDOWN_MS 600  // exceeds the 300ms host pulse + 250ms STALE_MS
                                 // (550ms), so a straggler frame cannot double-fire

RH_RF95 rf95(RFM95_CS, RFM95_INT);

uint8_t const desc_hid_report[] = {
  0x05, 0x01,
  0x09, 0x05,
  0xA1, 0x01,

  0x05, 0x09,
  0x19, 0x01,
  0x29, 0x10,
  0x15, 0x00,
  0x25, 0x01,
  0x75, 0x01,
  0x95, 0x10,
  0x81, 0x02,

  0x05, 0x01,
  0x09, 0x30,
  0x09, 0x31,
  0x09, 0x32,
  0x09, 0x35,
  0x15, 0x81,
  0x25, 0x7F,
  0x75, 0x08,
  0x95, 0x04,
  0x81, 0x02,

  0x05, 0x02,
  0x09, 0xC5,
  0x09, 0xC4,
  0x15, 0x00,
  0x26, 0xFF, 0x00,
  0x75, 0x08,
  0x95, 0x02,
  0x81, 0x02,

  0xC0
};

typedef struct __attribute__((packed)) {
  uint16_t buttons;
  int8_t x;
  int8_t y;
  int8_t z;
  int8_t rz;
  uint8_t brake;
  uint8_t accelerator;
} lora_gamepad_report_t;

// Second USB HID interface: a standard boot-layout keyboard, no report ID.
// This leaves desc_hid_report[] above and usb_hid.sendReport(0, ...) below
// byte-for-byte untouched, so the FTC Driver Station app's gamepad mapping
// cannot change. CFG_TUD_HID is 2 on the Adafruit SAMD core, so a second
// Adafruit_USBD_HID instance is supported; usb_hid.begin() runs first so the
// gamepad keeps interface/instance 0.
uint8_t const desc_hid_keyboard_report[] = {
  0x05, 0x01,        // Usage Page (Generic Desktop)
  0x09, 0x06,        // Usage (Keyboard)
  0xA1, 0x01,        // Collection (Application)

  0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
  0x19, 0xE0,        //   Usage Minimum (224, Left Control)
  0x29, 0xE7,        //   Usage Maximum (231, Right GUI)
  0x15, 0x00,        //   Logical Minimum (0)
  0x25, 0x01,        //   Logical Maximum (1)
  0x95, 0x08,        //   Report Count (8)
  0x75, 0x01,        //   Report Size (1)
  0x81, 0x02,        //   Input (Data, Variable, Absolute)   -> modifier byte

  0x95, 0x01,        //   Report Count (1)
  0x75, 0x08,        //   Report Size (8)
  0x81, 0x01,        //   Input (Constant)                   -> reserved byte

  0x05, 0x08,        //   Usage Page (LED)
  0x19, 0x01,        //   Usage Minimum (Num Lock)
  0x29, 0x05,        //   Usage Maximum (Kana)
  0x95, 0x05,        //   Report Count (5)
  0x75, 0x01,        //   Report Size (1)
  0x91, 0x02,        //   Output (Data, Variable, Absolute)
  0x95, 0x01,        //   Report Count (1)
  0x75, 0x03,        //   Report Size (3)
  0x91, 0x01,        //   Output (Constant)                  -> LED padding

  0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
  0x19, 0x00,        //   Usage Minimum (0)
  0x2A, 0xFF, 0x00,  //   Usage Maximum (255)
  0x15, 0x00,        //   Logical Minimum (0)
  0x26, 0xFF, 0x00,  //   Logical Maximum (255)
  0x95, 0x06,        //   Report Count (6)
  0x75, 0x08,        //   Report Size (8)
  0x81, 0x00,        //   Input (Data, Array, Absolute)      -> 6 keycodes

  0xC0               // End Collection
};

// Byte-identical to hid_keyboard_report_t / the HID boot-keyboard layout,
// which is why setBootProtocol(HID_ITF_PROTOCOL_KEYBOARD) below is safe.
typedef struct __attribute__((packed)) {
  uint8_t modifier;
  uint8_t reserved;
  uint8_t keycode[6];
} lora_keyboard_report_t;

Adafruit_USBD_HID usb_hid;
lora_gamepad_report_t gp;

Adafruit_USBD_HID keyboard_hid;
lora_keyboard_report_t kb;
bool keyboardHidReady = false;

unsigned long lastFrameMs = 0;
unsigned long lastHidSendMs = 0;

int16_t latestLx = 0;
int16_t latestLy = 0;
int16_t latestRx = 0;
int16_t latestRy = 0;
uint16_t latestLt = 0;
uint16_t latestRt = 0;
uint16_t latestButtons = 0;

// Driver Station command chord state.
uint8_t uiCmdModifier = 0;
uint8_t uiCmdKey = 0;
bool uiCmdPrevBitSet = false;     // BTN_UI_CMD in the previously applied frame
bool uiCmdKeyDown = false;        // press report sent, release still pending
unsigned long uiCmdPressedMs = 0;
unsigned long uiCmdLastFireMs = 0;

uint8_t xorChecksum(const uint8_t *data, uint8_t len) {
  uint8_t c = 0;
  for (uint8_t i = 0; i < len; i++) {
    c ^= data[i];
  }
  return c;
}

bool frameIsValid(const uint8_t *buf, uint8_t len) {
  if (len != FRAME_LEN) return false;
  if (buf[0] != 0xA5 || buf[1] != 0x5A) return false;
  if (buf[2] != PROTOCOL_VERSION) return false;

  uint8_t expected = xorChecksum(buf + 2, FRAME_LEN - 3);
  return expected == buf[FRAME_LEN - 1];
}

bool frameIsForThisRobot(const uint8_t *buf) {
  uint8_t targetRobot = buf[3];
  return targetRobot == ROBOT_ID || targetRobot == ROBOT_ALL;
}

uint16_t readU16(const uint8_t *buf, uint8_t offset) {
  return (uint16_t)buf[offset] | ((uint16_t)buf[offset + 1] << 8);
}

int16_t readI16(const uint8_t *buf, uint8_t offset) {
  return (int16_t)readU16(buf, offset);
}

int8_t stickToHid(int16_t value) {
  value = constrain(value, -1000, 1000);
  return (int8_t)map(value, -1000, 1000, -127, 127);
}

uint8_t triggerToHid(uint16_t value) {
  value = constrain(value, 0, 1000);
  return (uint8_t)map(value, 0, 1000, 0, 255);
}

void resetRadio() {
  digitalWrite(RFM95_RST, LOW);
  delay(10);
  digitalWrite(RFM95_RST, HIGH);
  delay(10);
}

void neutralizeControls() {
  latestLx = 0;
  latestLy = 0;
  latestRx = 0;
  latestRy = 0;
  latestLt = 0;
  latestRt = 0;
  latestButtons = 0;
}

void updateGamepadReport() {
  memset(&gp, 0, sizeof(gp));

  gp.x = stickToHid(latestLx);
  gp.y = stickToHid(latestLy);
  gp.z = stickToHid(latestRx);
  gp.rz = stickToHid(latestRy);
  gp.brake = triggerToHid(latestLt);
  gp.accelerator = triggerToHid(latestRt);

  if (latestButtons & BTN_CROSS) {
    gp.buttons |= (1UL << 0);
  }
  if (latestButtons & BTN_CIRCLE) {
    gp.buttons |= (1UL << 1);
  }
  if (latestButtons & BTN_SQUARE) {
    gp.buttons |= (1UL << 3);
  }
  if (latestButtons & BTN_TRIANGLE) {
    gp.buttons |= (1UL << 4);
  }
  if (latestButtons & BTN_OPTIONS) {
    gp.buttons |= (1UL << 11);
  }
  if (latestButtons & BTN_L1) {
    gp.buttons |= (1UL << 6);
  }
  if (latestButtons & BTN_R1) {
    gp.buttons |= (1UL << 7);
  }
}

void sendUiCommandPress() {
  if (!keyboardHidReady || !keyboard_hid.ready()) return;
  memset(&kb, 0, sizeof(kb));
  kb.modifier = uiCmdModifier;
  kb.keycode[0] = uiCmdKey;
  if (keyboard_hid.sendReport(0, &kb, sizeof(kb))) {
    uiCmdKeyDown = true;
    uiCmdPressedMs = millis();
    uiCmdLastFireMs = uiCmdPressedMs;
    if (Serial) {
      Serial.print(ROBOT_NAME);
      Serial.print(" uicmd fire mod=0x");
      Serial.print(uiCmdModifier, HEX);
      Serial.print(" key=0x");
      Serial.println(uiCmdKey, HEX);
    }
  }
}

// Called unconditionally from loop(), every iteration, regardless of radio or
// link state. A pending key release must never be starved by radio traffic
// and must never be suppressed by neutralizeControls() -- if the link dies
// 10ms after a press, the key must still release at +UI_CMD_HOLD_MS, or it
// would stay latched down on the phone indefinitely.
void serviceUiCommandRelease() {
  if (!uiCmdKeyDown) return;
  if (!TinyUSBDevice.mounted()) {
    // No host to release to; drop the pending state so a later mount is clean.
    uiCmdKeyDown = false;
    return;
  }
  if (millis() - uiCmdPressedMs < UI_CMD_HOLD_MS) return;
  if (!keyboardHidReady || !keyboard_hid.ready()) return;
  memset(&kb, 0, sizeof(kb));
  if (keyboard_hid.sendReport(0, &kb, sizeof(kb))) {
    uiCmdKeyDown = false;
    uiCmdModifier = 0;
    uiCmdKey = 0;
  }
}

void printReceivedFrame(uint16_t seq, uint16_t buttons, int rssi) {
  if (!Serial) return;

  Serial.print(ROBOT_NAME);
  Serial.print(" seq=");
  Serial.print(seq);
  Serial.print(" lx=");
  Serial.print(latestLx);
  Serial.print(" ly=");
  Serial.print(latestLy);
  Serial.print(" rx=");
  Serial.print(latestRx);
  Serial.print(" ry=");
  Serial.print(latestRy);
  Serial.print(" lt=");
  Serial.print(latestLt);
  Serial.print(" rt=");
  Serial.print(latestRt);
  Serial.print(" buttons=0x");
  Serial.print(buttons, HEX);
  Serial.print(" hid_buttons=0x");
  Serial.print(gp.buttons, HEX);
  Serial.print(" rssi=");
  Serial.println(rssi);
}

void setup() {
  Serial.begin(115200);
  delay(100);

  pinMode(LED, OUTPUT);
  pinMode(RFM95_RST, OUTPUT);
  digitalWrite(RFM95_RST, HIGH);

  if (!TinyUSBDevice.isInitialized()) {
    TinyUSBDevice.begin(0);
  }

  usb_hid.setPollInterval(2);
  usb_hid.setReportDescriptor(desc_hid_report, sizeof(desc_hid_report));
  usb_hid.begin();

  // Second HID interface for Driver Station command chords. Must be begin()'d
  // before the detach/attach below so re-enumeration presents the complete
  // final descriptor set. keyboard_hid.begin() fails soft (returns false)
  // rather than hanging if a future core ships CFG_TUD_HID 1 -- tele-op keeps
  // working and only the chord feature is dead.
  keyboard_hid.setPollInterval(2);
  keyboard_hid.setBootProtocol(HID_ITF_PROTOCOL_KEYBOARD);
  keyboard_hid.setReportDescriptor(desc_hid_keyboard_report, sizeof(desc_hid_keyboard_report));
  keyboardHidReady = keyboard_hid.begin();
  if (!keyboardHidReady && Serial) {
    Serial.println("Keyboard HID unavailable; CFG_TUD_HID may be 1.");
  }

  if (TinyUSBDevice.mounted()) {
    TinyUSBDevice.detach();
    delay(10);
    TinyUSBDevice.attach();
  }

  resetRadio();

  if (!rf95.init()) {
    while (1) {
      digitalWrite(LED, !digitalRead(LED));
      delay(100);
    }
  }

  if (!rf95.setFrequency(RF95_FREQ)) {
    while (1) {
      digitalWrite(LED, !digitalRead(LED));
      delay(250);
    }
  }

  rf95.setTxPower(20, false);
  neutralizeControls();
}

void loop() {
#ifdef TINYUSB_NEED_POLLING_TASK
  TinyUSBDevice.task();
#endif

  // Unconditional and first: a pending key release must never be starved by
  // radio traffic or the gamepad send interval below.
  serviceUiCommandRelease();

  if (rf95.available()) {
    uint8_t buf[RH_RF95_MAX_MESSAGE_LEN];
    uint8_t len = sizeof(buf);

    if (rf95.recv(buf, &len) && frameIsValid(buf, len)) {
      if (frameIsForThisRobot(buf)) {
        uint16_t seq = readU16(buf, 4);

        latestButtons = readU16(buf, 6);
        latestLx = readI16(buf, 8);
        latestLy = readI16(buf, 10);
        latestRx = readI16(buf, 12);
        latestRy = readI16(buf, 14);
        latestLt = readU16(buf, 16);
        latestRt = readU16(buf, 18);

        uint16_t rawButtons = latestButtons;  // pre-neutralize, for the debug print below
        bool uiCmdBit = (latestButtons & BTN_UI_CMD) != 0;
        if (uiCmdBit) {
          uint16_t chord = readU16(buf, 8);  // reinterpret lx as the chord word
          bool cooldownClear = (uiCmdLastFireMs == 0) ||
                               (millis() - uiCmdLastFireMs >= UI_CMD_COOLDOWN_MS);
          if (!uiCmdPrevBitSet && !uiCmdKeyDown && cooldownClear) {
            uiCmdModifier = (uint8_t)(chord >> 8);
            uiCmdKey = (uint8_t)(chord & 0x00FF);
            if (uiCmdKey != 0) {
              sendUiCommandPress();
            }
          }
          // A UI-command frame never carries gamepad state. This is the
          // firmware-side belt to the desktop's own guards: even if a chord
          // word somehow reached this robot's gamepad path, it cannot become
          // stick motion, because the report is forced neutral right here.
          neutralizeControls();
        }
        uiCmdPrevBitSet = uiCmdBit;

        lastFrameMs = millis();
        digitalWrite(LED, HIGH);

        updateGamepadReport();
        printReceivedFrame(seq, rawButtons, rf95.lastRssi());
      } else {
        neutralizeControls();
        uiCmdPrevBitSet = false;
        lastFrameMs = 0;
        digitalWrite(LED, LOW);
      }
    }
  }

  if (lastFrameMs == 0 || millis() - lastFrameMs > STALE_MS) {
    neutralizeControls();
    uiCmdPrevBitSet = false;
    digitalWrite(LED, LOW);
  }

  if (TinyUSBDevice.mounted() && usb_hid.ready()) {
    if (millis() - lastHidSendMs >= HID_SEND_INTERVAL_MS) {
      updateGamepadReport();
      usb_hid.sendReport(0, &gp, sizeof(gp));
      lastHidSendMs = millis();
    }
  }
}

