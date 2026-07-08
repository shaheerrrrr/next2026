#include <SPI.h>
#include <RH_RF95.h>

#define RFM95_CS 8
#define RFM95_RST 4
#define RFM95_INT 3

#define RF95_FREQ 915.0
#define LED 13

RH_RF95 rf95(RFM95_CS, RFM95_INT);

void setup() {
  pinMode(LED, OUTPUT);
  pinMode(RFM95_RST, OUTPUT);
  digitalWrite(RFM95_RST, HIGH);

  Serial.begin(115200);
  while (!Serial) delay(1);
  delay(100);

  Serial.println("Feather LoRa RX test");

  digitalWrite(RFM95_RST, LOW);
  delay(10);
  digitalWrite(RFM95_RST, HIGH);
  delay(10);

  if (!rf95.init()) {
    Serial.println("LoRa init failed. Check board/pins.");
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
  if (!rf95.available()) {
    return;
  }

  uint8_t buf[RH_RF95_MAX_MESSAGE_LEN];
  uint8_t len = sizeof(buf);

  if (rf95.recv(buf, &len)) {
    digitalWrite(LED, HIGH);

    Serial.print("Received: ");
    Serial.println((char *)buf);
    Serial.print("RSSI: ");
    Serial.println(rf95.lastRssi());

    const char reply[] = "feather ack";
    rf95.send((uint8_t *)reply, sizeof(reply));
    rf95.waitPacketSent();

    Serial.println("Sent reply");
    digitalWrite(LED, LOW);
  } else {
    Serial.println("Receive failed");
  }
}