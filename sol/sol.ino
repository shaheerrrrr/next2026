#include <SPI.h>
#include <RH_RF95.h>
#include <HID.h>

#define ROBOT_ID 3
#define ROBOT_NAME "Sol"
#define ROBOT_ALL 255

#define RFM95_CS 8
#define RFM95_RST 4
#define RFM95_INT 7

#define RF95_FREQ 915.0
#define FRAME_LEN 21
#define PROTOCOL_VERSION 3
#define LED 13

#define BTN_CROSS      0x0001
#define BTN_SQUARE     0x0004
#define BTN_OPTIONS    0x0010
#define BTN_DPAD_UP    0x0080
#define BTN_DPAD_DOWN  0x0100
#define BTN_DPAD_LEFT  0x0200
#define BTN_DPAD_RIGHT 0x0400
#define BTN_UI_CMD     0x0800

#define STALE_MS 250
#define HID_SEND_INTERVAL_MS 20
#define HID_REPORT_ID 1
#define HID_KEYBOARD_REPORT_ID 2
#define HAT_NEUTRAL 8

// Driver Station command chord (INIT/START/STOP). When BTN_UI_CMD is set in a
// received frame, lx carries (modifier_byte << 8) | hid_usage_id instead of
// stick data. Same wire contract and firmware role as fable/fable.ino: this
// board is a dumb chord emitter -- the chord table lives on the desktop
// (driver_station_flask.py / ui_commands.json), not here. Timing constants
// copied verbatim from fable/fable.ino so behavior is identical across robots.
#define UI_CMD_HOLD_MS     40   // two HID intervals: a discrete down/up, well
                                 // short of Android's ~400ms auto-repeat delay
#define UI_CMD_COOLDOWN_MS 600  // exceeds the 300ms host pulse + 250ms STALE_MS
                                 // (550ms), so a straggler frame cannot double-fire

RH_RF95 rf95(RFM95_CS, RFM95_INT);

static const uint8_t desc_hid_report[] PROGMEM = {
  0x05, 0x01,
  0x09, 0x05,
  0xA1, 0x01,
  0x85, HID_REPORT_ID,

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

  0x05, 0x01,
  0x09, 0x39,
  0x15, 0x00,
  0x25, 0x07,
  0x35, 0x00,
  0x46, 0x3B, 0x01,
  0x65, 0x14,
  0x75, 0x04,
  0x95, 0x01,
  0x81, 0x42,
  0x75, 0x04,
  0x95, 0x01,
  0x81, 0x03,

  0xC0
};

// ---------------------------------------------------------------------------
// Driver Station command chord keyboard, Sol variant.
//
// Sol is a Feather 32u4 (ATmega32u4) on the classic Arduino HID.h/
// PluggableUSB AVR stack, NOT Adafruit_TinyUSB. That stack only exposes ONE
// USB HID interface (see the single global HID_ singleton in HID.h/HID.cpp),
// so fable.ino's approach -- a second, genuinely independent
// Adafruit_USBD_HID instance with its own endpoint and its own
// setBootProtocol(HID_ITF_PROTOCOL_KEYBOARD) -- has no equivalent here.
//
// Instead this multiplexes a second logical HID device onto the SAME
// interface as the existing gamepad, the same way the gamepad descriptor
// above already does it: a distinct Report ID. The gamepad claims
// HID_REPORT_ID (1) via the "0x85, HID_REPORT_ID" tag right after its
// "0xA1, 0x01" (Collection Application); this keyboard collection claims
// HID_KEYBOARD_REPORT_ID (2) the same way. Multiple Report-ID-tagged
// top-level collections appended to one HID_ instance via
// HID().AppendDescriptor() is the standard, well-supported way HID.h/
// PluggableUSB handles composite HID on AVR boards (this is exactly how
// combo mouse+keyboard Arduino sketches work) -- it is a normal pattern,
// just architecturally different from Fable's second-interface approach.
//
// Layout below is byte-for-byte the same boot-keyboard shape as fable.ino's
// desc_hid_keyboard_report[] (modifier byte, reserved byte, 5 LED output bits
// + 3 padding bits, 6-byte keycode array) -- WITH one addition Fable's
// omits: the "0x85, HID_KEYBOARD_REPORT_ID" Report ID tag, required here
// because this collection is multiplexed by Report ID on a shared interface
// instead of being declared as its own full boot-protocol interface.
//
// UNVERIFIED ON REAL HARDWARE. Fable's second-USB-interface keyboard was
// verified end-to-end on real Fable hardware today. This Report-ID-
// multiplexed variant for Sol has NOT been flashed or tested. AVR's HID.h
// does not expose a per-sub-descriptor boot-protocol declaration the way
// TinyUSB's setBootProtocol() does, so whether Android's input stack still
// splits this second Report-ID-tagged top-level collection into its own
// distinct keyboard-class logical input device here -- the way it does for
// two genuinely separate USB interfaces -- is standard, well-supported USB
// HID behavior in general (composite mouse+keyboard-over-one-interface
// devices are extremely common, and hid-generic on Linux/Android splits by
// top-level collection, not strictly by USB interface), but THIS SPECIFIC
// board+library combination is unconfirmed. Do not treat this as working
// until it passes a Step-0-equivalent bring-up pass on real Sol hardware:
// confirm the board enumerates a keyboard-class HID collection, and confirm
// a real chord reaches the phone's onKeyEvent (see docs/bring-up.md on the
// robot-reset-app branch for the Fable version of this test).
static const uint8_t desc_hid_keyboard_report[] PROGMEM = {
  0x05, 0x01,        // Usage Page (Generic Desktop)
  0x09, 0x06,        // Usage (Keyboard)
  0xA1, 0x01,        // Collection (Application)
  0x85, HID_KEYBOARD_REPORT_ID,

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

// Byte-identical to fable.ino's lora_keyboard_report_t / the HID
// boot-keyboard input-report layout (modifier, reserved, 6 keycodes). The
// Report ID prefix byte is NOT part of this struct -- HID_::SendReport()
// prepends the ID byte itself (see HID.cpp: it sends `id` then the payload
// as two separate USB_Send() calls), the same way HID_REPORT_ID is handled
// for lora_gamepad_report_t below.
typedef struct __attribute__((packed)) {
  uint8_t modifier;
  uint8_t reserved;
  uint8_t keycode[6];
} lora_keyboard_report_t;

class SolGamepadHID {
 public:
  SolGamepadHID() : descriptorNode(desc_hid_report, sizeof(desc_hid_report)) {
    HID().AppendDescriptor(&descriptorNode);
  }

  bool sendReport(const lora_gamepad_report_t &report) {
    return HID().SendReport(HID_REPORT_ID, &report, sizeof(report)) >= 0;
  }

 private:
  HIDSubDescriptor descriptorNode;
};

// Second logical HID device multiplexed onto the same HID_ interface as
// SolGamepadHID above, via HID_KEYBOARD_REPORT_ID instead of HID_REPORT_ID.
// See the long comment above desc_hid_keyboard_report[] for why this is the
// AVR/HID.h equivalent of fable.ino's second Adafruit_USBD_HID interface,
// and for the real-hardware-verification caveat that applies to it.
class SolKeyboardHID {
 public:
  SolKeyboardHID() : descriptorNode(desc_hid_keyboard_report, sizeof(desc_hid_keyboard_report)) {
    HID().AppendDescriptor(&descriptorNode);
  }

  bool sendReport(const lora_keyboard_report_t &report) {
    return HID().SendReport(HID_KEYBOARD_REPORT_ID, &report, sizeof(report)) >= 0;
  }

 private:
  HIDSubDescriptor descriptorNode;
};

SolGamepadHID usb_hid;
lora_gamepad_report_t gp;

// Declared after usb_hid so global constructor order (C++ guarantees
// in-translation-unit declaration order) appends the gamepad sub-descriptor
// to HID_'s linked list before the keyboard one -- matching fable.ino's
// gamepad-interface-first, keyboard-interface-second ordering. The Report ID
// byte is what actually distinguishes the two at runtime; descriptor order
// only affects enumeration order, not correctness.
SolKeyboardHID keyboard_hid;
lora_keyboard_report_t kb;

unsigned long lastFrameMs = 0;
unsigned long lastHidSendMs = 0;

int16_t latestLx = 0;
int16_t latestLy = 0;
int16_t latestRx = 0;
int16_t latestRy = 0;
uint16_t latestRt = 0;
uint16_t latestButtons = 0;

// Driver Station command chord state. Mirrors fable.ino's state machine
// exactly (same field names, same semantics, same timing), minus the
// keyboardHidReady flag -- HID.h's HID_::begin() is a no-op that always
// succeeds (see HID.cpp), so there is no equivalent "did this sub-interface
// fail to claim an endpoint" failure mode to track on this stack.
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

uint8_t dpadToHat(uint16_t buttons) {
  bool up = buttons & BTN_DPAD_UP;
  bool down = buttons & BTN_DPAD_DOWN;
  bool left = buttons & BTN_DPAD_LEFT;
  bool right = buttons & BTN_DPAD_RIGHT;

  if (up && right) return 1;
  if (right && down) return 3;
  if (down && left) return 5;
  if (left && up) return 7;
  if (up) return 0;
  if (right) return 2;
  if (down) return 4;
  if (left) return 6;
  return HAT_NEUTRAL;
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
  latestRt = 0;
  latestButtons = 0;
}

void updateGamepadReport() {
  memset(&gp, 0, sizeof(gp));

  gp.x = stickToHid(latestLx);
  gp.y = stickToHid(latestLy);
  gp.z = stickToHid(latestRx);
  gp.rz = stickToHid(latestRy);
  gp.brake = 0;
  gp.accelerator = triggerToHid(latestRt);
  gp.hat = dpadToHat(latestButtons);

  if (latestButtons & BTN_CROSS) {
    gp.buttons |= (1UL << 0);
  }
  if (latestButtons & BTN_SQUARE) {
    gp.buttons |= (1UL << 3);
  }
  if (latestButtons & BTN_OPTIONS) {
    gp.buttons |= (1UL << 11);
  }
}

void sendUiCommandPress() {
  memset(&kb, 0, sizeof(kb));
  kb.modifier = uiCmdModifier;
  kb.keycode[0] = uiCmdKey;
  if (keyboard_hid.sendReport(kb)) {
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
  } else if (Serial) {
    // HID_::SendReport() on this AVR/HID.h stack blocks for up to ~250ms
    // per USB_Send() phase (see USBCore.cpp) with no non-blocking ready()
    // check available (unlike TinyUSB on the M0 boards) -- a false return
    // here means the shared gamepad+keyboard endpoint went unserviced by
    // the host for that whole window and the chord was dropped. This was
    // previously silent; logging it is what lets a real block be told
    // apart from every other reason a chord might not land.
    Serial.print(ROBOT_NAME);
    Serial.print(" uicmd FAILED (host not draining HID endpoint) mod=0x");
    Serial.print(uiCmdModifier, HEX);
    Serial.print(" key=0x");
    Serial.println(uiCmdKey, HEX);
  }
}

// Called unconditionally from loop(), every iteration, regardless of radio or
// link state -- same requirement and same reasoning as fable.ino's
// serviceUiCommandRelease(). A pending key release must never be starved by
// radio traffic and must never be suppressed by neutralizeControls(): if the
// link dies 10ms after a press, the key must still release at
// +UI_CMD_HOLD_MS, or it would stay latched down on the phone indefinitely.
//
// USBDevice.configured() (declared in the AVR core's USBAPI.h, pulled in via
// HID.h -> Arduino.h) is this stack's equivalent of TinyUSBDevice.mounted():
// it reflects whether the host has completed USB enumeration/configuration.
void serviceUiCommandRelease() {
  if (!uiCmdKeyDown) return;
  if (!USBDevice.configured()) {
    // No host to release to; drop the pending state so a later
    // (re-)enumeration starts clean, same as fable.ino's mounted() check.
    uiCmdKeyDown = false;
    return;
  }
  if (millis() - uiCmdPressedMs < UI_CMD_HOLD_MS) return;
  memset(&kb, 0, sizeof(kb));
  if (keyboard_hid.sendReport(kb)) {
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
  Serial.print(" rt=");
  Serial.print(latestRt);
  Serial.print(" buttons=0x");
  Serial.print(buttons, HEX);
  Serial.print(" hat=");
  Serial.print(gp.hat);
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

  // HID().begin() is a no-op stub on this stack (see HID.cpp) -- both
  // sub-descriptors (gamepad + keyboard) were already appended to HID_'s
  // linked list by the SolGamepadHID/SolKeyboardHID global constructors
  // above, before setup() ever runs.
  HID().begin();

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
  // Unconditional and first: a pending key release must never be starved by
  // radio traffic or the gamepad send interval below. See the comment on
  // serviceUiCommandRelease() above.
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

  // Skip the routine gamepad send while a UI-command press is outstanding.
  // Gamepad and keyboard share the one physical HID endpoint this stack
  // exposes (see the SolKeyboardHID comment above), and HID_::SendReport()
  // is a blocking call with no non-blocking readiness check on this stack
  // (unlike TinyUSB's usb_hid.ready() on the M0 boards) -- so a gamepad
  // send here can queue behind, or contend with, the pending release and
  // risk delaying it. Nothing is lost: controls are already forced neutral
  // for the duration of a UI command (see neutralizeControls() above), and
  // uiCmdKeyDown is only ever true for UI_CMD_HOLD_MS (40ms).
  if (!uiCmdKeyDown && millis() - lastHidSendMs >= HID_SEND_INTERVAL_MS) {
    updateGamepadReport();
    usb_hid.sendReport(gp);
    lastHidSendMs = millis();
  }
}
