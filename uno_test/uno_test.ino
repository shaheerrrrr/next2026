#include <SPI.h>
#include <RH_RF95.h>

#define RFM95_CS 10
#define RFM95_RST 9
#define RFM95_INT 2

#define RF95_FREQ 915.0

RH_RF95 rf95(RFM95_CS, RFM95_INT);

uint16_t packetNum = 0;

void setup() {
  pinMode(RFM95_RST, OUTPUT);
  digitalWrite(RFM95_RST, HIGH);

  // Keep Uno in SPI master mode.
  pinMode(10, OUTPUT);

  Serial.begin(9600);
  delay(100);

  Serial.println("Uno LoRa TX test");

  digitalWrite(RFM95_RST, LOW);
  delay(10);
  digitalWrite(RFM95_RST, HIGH);
  delay(10);

  if (!rf95.init()) {
    Serial.println("LoRa init failed. Check wiring.");
    while (1);
  }

  if (!rf95.setFrequency(RF95_FREQ)) {
    Serial.println("setFrequency failed");
    while (1);
  }

  rf95.setTxPower(20, false);

  Serial.print("LoRa init OK at ");
  Serial.print(RF95_FREQ);
  Serial.println(" MHz");
}

void loop() {
  char packet[32];
  snprintf(packet, sizeof(packet), "uno hello %u", packetNum++);

  Serial.print("Sending: ");
  Serial.println(packet);

  rf95.send((uint8_t *)packet, strlen(packet) + 1);
  rf95.waitPacketSent();

  uint8_t buf[RH_RF95_MAX_MESSAGE_LEN];
  uint8_t len = sizeof(buf);

  if (rf95.waitAvailableTimeout(1000)) {
    if (rf95.recv(buf, &len)) {
      Serial.print("Reply: ");
      Serial.println((char *)buf);
      Serial.print("Reply RSSI: ");
      Serial.println(rf95.lastRssi());
    } else {
      Serial.println("Receive failed");
    }
  } else {
    Serial.println("No reply");
  }

  delay(1000);
}