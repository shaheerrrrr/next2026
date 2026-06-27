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
#define BTN_START 0x10

#define STALE_MS 250
#define HID_SEND_INTERVAL_MS 20

RH_RF95 rf95(RFM95_CS, RFM95_INT);

uint8_t const desc_hid_report[] = {
  TUD_HID_REPORT_DESC_GAMEPAD()
};

Adafruit_USBD_HID usb_hid;
hid_gamepad_report_t gp;

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

int8_t triggerToHid(uint16_t value) {
  value = constrain(value, 0, 1000);
  return (int8_t)map(value, 0, 1000, -127, 127);
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

  // TinyUSB gamepad template uses z/rz for the second stick.
  gp.z = stickToHid(latestRx);
  gp.rz = stickToHid(latestRy);

  // Use rx/ry for analog triggers.
  gp.rx = triggerToHid(latestLt);
  gp.ry = triggerToHid(latestRt);

  gp.hat = 0;

  gp.buttons = 0;
  if (latestButtons & BTN_CROSS) {
    gp.buttons |= (1UL << 0);
  }
  if (latestButtons & BTN_CIRCLE) {
    gp.buttons |= (1UL << 1);
  }
  if (latestButtons & BTN_SQUARE) {
    gp.buttons |= (1UL << 2);
  }
  if (latestButtons & BTN_TRIANGLE) {
    gp.buttons |= (1UL << 3);
  }
  if (latestButtons & BTN_START) {
    gp.buttons |= (1UL << 8);
  }
}

void setup() {
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
      latestLx = readI16(buf, 6);
      latestLy = readI16(buf, 8);
      latestRx = readI16(buf, 10);
      latestRy = readI16(buf, 12);
      latestLt = readU16(buf, 14);
      latestRt = readU16(buf, 16);
      latestButtons = buf[5];

      lastFrameMs = millis();
      digitalWrite(LED, HIGH);
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
}