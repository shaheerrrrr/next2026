#include <SPI.h>
#include <RH_RF95.h>
#include "Adafruit_TinyUSB.h"

#define RFM95_CS 8
#define RFM95_RST 4
#define RFM95_INT 3

#define RF95_FREQ 915.0
#define FRAME_LEN 19
#define LED 13

#define BTN_CROSS    0x01
#define BTN_CIRCLE   0x02
#define BTN_SQUARE   0x04
#define BTN_TRIANGLE 0x08
#define BTN_OPTIONS  0x10

#define STALE_MS 250
#define HID_SEND_INTERVAL_MS 20

RH_RF95 rf95(RFM95_CS, RFM95_INT);

// Custom gamepad descriptor:
// - 16 buttons
// - left stick: X/Y
// - right stick: Z/Rz
// - triggers: Brake/Accelerator analog axes, 0..255
uint8_t const desc_hid_report[] = {
  0x05, 0x01,        // Usage Page (Generic Desktop)
  0x09, 0x05,        // Usage (Game Pad)
  0xA1, 0x01,        // Collection (Application)

  0x05, 0x09,        // Usage Page (Button)
  0x19, 0x01,        // Usage Minimum (Button 1)
  0x29, 0x10,        // Usage Maximum (Button 16)
  0x15, 0x00,        // Logical Minimum (0)
  0x25, 0x01,        // Logical Maximum (1)
  0x75, 0x01,        // Report Size (1)
  0x95, 0x10,        // Report Count (16)
  0x81, 0x02,        // Input (Data, Variable, Absolute)

  0x05, 0x01,        // Usage Page (Generic Desktop)
  0x09, 0x30,        // Usage (X)
  0x09, 0x31,        // Usage (Y)
  0x09, 0x32,        // Usage (Z)
  0x09, 0x35,        // Usage (Rz)
  0x15, 0x81,        // Logical Minimum (-127)
  0x25, 0x7F,        // Logical Maximum (127)
  0x75, 0x08,        // Report Size (8)
  0x95, 0x04,        // Report Count (4)
  0x81, 0x02,        // Input (Data, Variable, Absolute)

  0x05, 0x02,        // Usage Page (Simulation Controls)
  0x09, 0xC5,        // Usage (Brake)
  0x09, 0xC4,        // Usage (Accelerator)
  0x15, 0x00,        // Logical Minimum (0)
  0x26, 0xFF, 0x00,  // Logical Maximum (255)
  0x75, 0x08,        // Report Size (8)
  0x95, 0x02,        // Report Count (2)
  0x81, 0x02,        // Input (Data, Variable, Absolute)

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
} lora_gamepad_report_t;

Adafruit_USBD_HID usb_hid;
lora_gamepad_report_t gp;

unsigned long lastFrameMs = 0;
unsigned long lastHidSendMs = 0;

int16_t latestLx = 0;
int16_t latestLy = 0;
int16_t latestRx = 0;
int16_t latestRy = 0;
uint16_t latestLt = 0;
uint16_t latestRt = 0;
uint8_t latestButtons = 0;

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
  if (buf[2] != 2) return false;

  uint8_t expected = xorChecksum(buf + 2, FRAME_LEN - 3);
  return expected == buf[FRAME_LEN - 1];
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

  gp.buttons = 0;

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

  // You found this is the correct Options/Start registration button.
  if (latestButtons & BTN_OPTIONS) {
    gp.buttons |= (1UL << 11);
  }
}

void printReceivedFrame(uint16_t seq, uint8_t buttons, int rssi) {
  if (!Serial) return;

  Serial.print("seq=");
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

  Serial.print(" brake=");
  Serial.print(gp.brake);
  Serial.print(" accelerator=");
  Serial.print(gp.accelerator);

  Serial.print(" cross=");
  Serial.print((buttons & BTN_CROSS) ? 1 : 0);
  Serial.print(" circle=");
  Serial.print((buttons & BTN_CIRCLE) ? 1 : 0);
  Serial.print(" square=");
  Serial.print((buttons & BTN_SQUARE) ? 1 : 0);
  Serial.print(" triangle=");
  Serial.print((buttons & BTN_TRIANGLE) ? 1 : 0);
  Serial.print(" options=");
  Serial.print((buttons & BTN_OPTIONS) ? 1 : 0);

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

  if (rf95.available()) {
    uint8_t buf[RH_RF95_MAX_MESSAGE_LEN];
    uint8_t len = sizeof(buf);

    if (rf95.recv(buf, &len) && frameIsValid(buf, len)) {
      uint16_t seq = readU16(buf, 3);

      latestLx = readI16(buf, 6);
      latestLy = readI16(buf, 8);
      latestRx = readI16(buf, 10);
      latestRy = readI16(buf, 12);
      latestLt = readU16(buf, 14);
      latestRt = readU16(buf, 16);
      latestButtons = buf[5];

      lastFrameMs = millis();
      digitalWrite(LED, HIGH);

      updateGamepadReport();
      printReceivedFrame(seq, latestButtons, rf95.lastRssi());
    }
  }

  if (lastFrameMs == 0 || millis() - lastFrameMs > STALE_MS) {
    neutralizeControls();
    digitalWrite(LED, LOW);
    
  }

  if (TinyUSBDevice.mounted() && usb_hid.ready()) {
    if (millis() - lastHidSendMs >= HID_SEND_INTERVAL_MS) {
      updateGamepadReport();
      usb_hid.sendReport(0, &gp, sizeof(gp));
      lastHidSendMs = millis();
    }
  }

  static unsigned long lastDebugMs = 0;
  if (millis() - lastDebugMs > 1000) {
    Serial.println("alive, waiting for LoRa frames");
    lastDebugMs = millis();
  }
}