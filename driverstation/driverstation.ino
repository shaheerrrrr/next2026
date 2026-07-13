#include <SPI.h>
#include <RH_RF95.h>

#define RFM95_CS 10
#define RFM95_RST 9
#define RFM95_INT 2

#define RF95_FREQ 915.0
#define FRAME_LEN 21
#define SERIAL_BAUD 115200
#define PROTOCOL_VERSION 3

RH_RF95 rf95(RFM95_CS, RFM95_INT);

uint8_t frame[FRAME_LEN];
uint8_t indexInFrame = 0;
bool haveFirstMagic = false;

uint8_t xorChecksum(const uint8_t *data, uint8_t len) {
  uint8_t c = 0;
  for (uint8_t i = 0; i < len; i++) {
    c ^= data[i];
  }
  return c;
}

bool frameIsValid(const uint8_t *buf) {
  if (buf[0] != 0xA5 || buf[1] != 0x5A) return false;
  if (buf[2] != PROTOCOL_VERSION) return false;
  uint8_t expected = xorChecksum(buf + 2, FRAME_LEN - 3);
  return expected == buf[FRAME_LEN - 1];
}

void resetRadio() {
  digitalWrite(RFM95_RST, LOW);
  delay(10);
  digitalWrite(RFM95_RST, HIGH);
  delay(10);
}

void setup() {
  pinMode(RFM95_RST, OUTPUT);
  digitalWrite(RFM95_RST, HIGH);
  pinMode(10, OUTPUT);

  Serial.begin(SERIAL_BAUD);
  delay(100);

  resetRadio();

  if (!rf95.init()) {
    while (1);
  }

  if (!rf95.setFrequency(RF95_FREQ)) {
    while (1);
  }

  rf95.setTxPower(20, false);
}

void loop() {
  while (Serial.available()) {
    uint8_t b = Serial.read();

    if (!haveFirstMagic) {
      if (b == 0xA5) {
        frame[0] = b;
        indexInFrame = 1;
        haveFirstMagic = true;
      }
      continue;
    }

    if (indexInFrame == 1) {
      if (b == 0x5A) {
        frame[1] = b;
        indexInFrame = 2;
      } else {
        haveFirstMagic = false;
        indexInFrame = 0;
      }
      continue;
    }

    frame[indexInFrame++] = b;

    if (indexInFrame >= FRAME_LEN) {
      if (frameIsValid(frame)) {
        rf95.send(frame, FRAME_LEN);
        rf95.waitPacketSent();
      }

      haveFirstMagic = false;
      indexInFrame = 0;
    }
  }
}

