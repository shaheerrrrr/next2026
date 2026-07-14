#include <Arduino.h>
#include <TinyGPSPlus.h>
#include <Wire.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

#define I2C_ADDRESS 0x42
#define I2C_SDA_PIN 8
#define I2C_SCL_PIN 9
#define I2C_FREQUENCY 100000

#define GPS_RX_PIN 4
#define GPS_TX_PIN 5
#define GPS_BAUD 9600

#define ESPNOW_CHANNEL 1
#define SNAPSHOT_LEN 56
#define GPS_RECENT_MS 2000
#define DRIVER_LINK_ALIVE_MS 2000
#define TELEMETRY_INTERVAL_MS 100

#define FLAG_GPS_VALID 0x01
#define FLAG_TARGET_VALID 0x02
#define FLAG_SATS_VALID 0x04
#define FLAG_HDOP_VALID 0x08
#define FLAG_DRIVER_LINK_ALIVE 0x10

#define MSG_TYPE_TARGET 1
#define MSG_TYPE_CLEAR_TARGET 2
#define MSG_TYPE_HEARTBEAT 3
#define MSG_TYPE_TELEMETRY 16

TinyGPSPlus gps;
HardwareSerial GPSSerial(1);

uint8_t broadcastPeer[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

portMUX_TYPE snapshotMux = portMUX_INITIALIZER_UNLOCKED;
uint8_t publishedSnapshot[SNAPSHOT_LEN];
volatile uint8_t i2cRegisterPointer = 0;

uint32_t navSeq = 0;
uint32_t targetSeq = 0;
uint32_t lastGpsUpdateMs = 0;
uint32_t lastDriverCommandMs = 0;
uint32_t lastTelemetryMs = 0;

int32_t currentLatE7 = 0;
int32_t currentLonE7 = 0;
int32_t targetLatE7 = 0;
int32_t targetLonE7 = 0;
bool targetValid = false;

typedef struct __attribute__((packed)) {
  char magic[4];
  uint8_t version;
  uint8_t type;
  uint32_t seq;
  int32_t targetLatE7;
  int32_t targetLonE7;
  uint16_t crc;
} NavCommandPacket;

typedef struct __attribute__((packed)) {
  char magic[4];
  uint8_t version;
  uint8_t type;
  uint32_t navSeq;
  uint32_t targetSeq;
  int32_t latE7;
  int32_t lonE7;
  int32_t targetLatE7;
  int32_t targetLonE7;
  uint8_t flags;
  uint8_t fixQuality;
  uint8_t satellites;
  uint16_t hdopX100;
  uint32_t gpsAgeMs;
  uint32_t commandAgeMs;
  uint32_t uptimeMs;
  uint16_t crc;
} NavTelemetryPacket;

uint16_t crc16CcittFalse(const uint8_t *data, size_t len) {
  uint16_t crc = 0xFFFF;
  for (size_t i = 0; i < len; i++) {
    crc ^= ((uint16_t)data[i] << 8);
    for (uint8_t bit = 0; bit < 8; bit++) {
      if (crc & 0x8000) {
        crc = (crc << 1) ^ 0x1021;
      } else {
        crc <<= 1;
      }
    }
  }
  return crc;
}

void putU16(uint8_t *buf, uint8_t offset, uint16_t value) {
  buf[offset] = value & 0xFF;
  buf[offset + 1] = (value >> 8) & 0xFF;
}

void putU32(uint8_t *buf, uint8_t offset, uint32_t value) {
  buf[offset] = value & 0xFF;
  buf[offset + 1] = (value >> 8) & 0xFF;
  buf[offset + 2] = (value >> 16) & 0xFF;
  buf[offset + 3] = (value >> 24) & 0xFF;
}

void putI32(uint8_t *buf, uint8_t offset, int32_t value) {
  putU32(buf, offset, (uint32_t)value);
}

uint8_t fixQuality() {
  if (!gps.location.isValid()) return 0;
  if (millis() - lastGpsUpdateMs > GPS_RECENT_MS) return 0;
  if (gps.satellites.isValid() && gps.satellites.value() >= 4) return 2;
  return 1;
}

uint8_t statusFlags() {
  uint8_t flags = 0;
  if (fixQuality() > 0) flags |= FLAG_GPS_VALID;
  if (targetValid) flags |= FLAG_TARGET_VALID;
  if (gps.satellites.isValid()) flags |= FLAG_SATS_VALID;
  if (gps.hdop.isValid()) flags |= FLAG_HDOP_VALID;
  if (lastDriverCommandMs != 0 && millis() - lastDriverCommandMs <= DRIVER_LINK_ALIVE_MS) {
    flags |= FLAG_DRIVER_LINK_ALIVE;
  }
  return flags;
}

uint32_t gpsAgeMs() {
  if (lastGpsUpdateMs == 0) return UINT32_MAX;
  return millis() - lastGpsUpdateMs;
}

uint32_t commandAgeMs() {
  if (lastDriverCommandMs == 0) return UINT32_MAX;
  return millis() - lastDriverCommandMs;
}

uint16_t hdopX100() {
  if (!gps.hdop.isValid()) return 0;
  uint32_t value = gps.hdop.value();
  if (value > 65535) return 65535;
  return (uint16_t)value;
}

uint8_t satelliteCount() {
  if (!gps.satellites.isValid()) return 0;
  uint32_t value = gps.satellites.value();
  return value > 255 ? 255 : (uint8_t)value;
}

void publishI2cSnapshot() {
  uint8_t next[SNAPSHOT_LEN];
  memset(next, 0, sizeof(next));

  next[0] = 'F';
  next[1] = 'N';
  next[2] = 'A';
  next[3] = 'V';
  next[4] = 1;
  next[5] = SNAPSHOT_LEN;
  next[6] = statusFlags();
  next[7] = fixQuality();
  putU32(next, 8, navSeq);
  putU32(next, 12, targetSeq);
  putI32(next, 16, currentLatE7);
  putI32(next, 20, currentLonE7);
  putI32(next, 24, targetLatE7);
  putI32(next, 28, targetLonE7);
  putU32(next, 32, gpsAgeMs());
  putU32(next, 36, commandAgeMs());
  putU16(next, 40, hdopX100());
  next[42] = satelliteCount();
  putU32(next, 44, gps.charsProcessed());
  putU32(next, 48, millis());

  uint16_t crc = crc16CcittFalse(next, 54);
  putU16(next, 54, crc);

  portENTER_CRITICAL(&snapshotMux);
  memcpy(publishedSnapshot, next, SNAPSHOT_LEN);
  portEXIT_CRITICAL(&snapshotMux);
}

void onI2cReceive(int count) {
  if (count <= 0) return;
  i2cRegisterPointer = Wire.read();
  while (Wire.available()) {
    Wire.read();
  }
}

void onI2cRequest() {
  uint8_t local[SNAPSHOT_LEN];
  portENTER_CRITICAL_ISR(&snapshotMux);
  memcpy(local, publishedSnapshot, SNAPSHOT_LEN);
  portEXIT_CRITICAL_ISR(&snapshotMux);

  uint8_t offset = i2cRegisterPointer;
  if (offset >= SNAPSHOT_LEN) {
    Wire.write((uint8_t)0);
    return;
  }
  Wire.write(local + offset, SNAPSHOT_LEN - offset);
}

bool validCommandPacket(const NavCommandPacket &packet) {
  if (memcmp(packet.magic, "FNC1", 4) != 0) return false;
  if (packet.version != 1) return false;
  uint16_t expected = crc16CcittFalse((const uint8_t *)&packet, sizeof(packet) - 2);
  return expected == packet.crc;
}

void onEspNowReceive(const esp_now_recv_info_t *info, const uint8_t *data, int len) {
  if (len != sizeof(NavCommandPacket)) return;

  NavCommandPacket packet;
  memcpy(&packet, data, sizeof(packet));
  if (!validCommandPacket(packet)) return;

  lastDriverCommandMs = millis();
  if (packet.type == MSG_TYPE_TARGET) {
    targetLatE7 = packet.targetLatE7;
    targetLonE7 = packet.targetLonE7;
    targetSeq = packet.seq;
    targetValid = true;
  } else if (packet.type == MSG_TYPE_CLEAR_TARGET) {
    targetSeq = packet.seq;
    targetValid = false;
    targetLatE7 = 0;
    targetLonE7 = 0;
  }

  publishI2cSnapshot();
}

void sendTelemetry() {
  NavTelemetryPacket packet;
  memset(&packet, 0, sizeof(packet));
  memcpy(packet.magic, "FNT1", 4);
  packet.version = 1;
  packet.type = MSG_TYPE_TELEMETRY;
  packet.navSeq = navSeq;
  packet.targetSeq = targetSeq;
  packet.latE7 = currentLatE7;
  packet.lonE7 = currentLonE7;
  packet.targetLatE7 = targetLatE7;
  packet.targetLonE7 = targetLonE7;
  packet.flags = statusFlags();
  packet.fixQuality = fixQuality();
  packet.satellites = satelliteCount();
  packet.hdopX100 = hdopX100();
  packet.gpsAgeMs = gpsAgeMs();
  packet.commandAgeMs = commandAgeMs();
  packet.uptimeMs = millis();
  packet.crc = crc16CcittFalse((const uint8_t *)&packet, sizeof(packet) - 2);

  esp_now_send(broadcastPeer, (const uint8_t *)&packet, sizeof(packet));
}

void addBroadcastPeer() {
  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, broadcastPeer, 6);
  peerInfo.channel = ESPNOW_CHANNEL;
  peerInfo.encrypt = false;
  esp_now_add_peer(&peerInfo);
}

void setup() {
  Serial.begin(115200);
  GPSSerial.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);

  memset(publishedSnapshot, 0, sizeof(publishedSnapshot));
  publishI2cSnapshot();

  Wire.onReceive(onI2cReceive);
  Wire.onRequest(onI2cRequest);
  Wire.begin((uint8_t)I2C_ADDRESS, I2C_SDA_PIN, I2C_SCL_PIN, I2C_FREQUENCY);

  WiFi.mode(WIFI_STA);
  esp_wifi_set_channel(ESPNOW_CHANNEL, WIFI_SECOND_CHAN_NONE);
  if (esp_now_init() == ESP_OK) {
    addBroadcastPeer();
    esp_now_register_recv_cb(onEspNowReceive);
  }

  Serial.print("Fable robot nav ESP ready, MAC=");
  Serial.println(WiFi.macAddress());
}

void loop() {
  while (GPSSerial.available()) {
    char c = GPSSerial.read();
    if (gps.encode(c) && gps.location.isUpdated()) {
      currentLatE7 = (int32_t)round(gps.location.lat() * 10000000.0);
      currentLonE7 = (int32_t)round(gps.location.lng() * 10000000.0);
      lastGpsUpdateMs = millis();
      navSeq++;
      publishI2cSnapshot();
    }
  }

  static unsigned long lastSnapshotMs = 0;
  if (millis() - lastSnapshotMs >= 50) {
    publishI2cSnapshot();
    lastSnapshotMs = millis();
  }

  if (millis() - lastTelemetryMs >= TELEMETRY_INTERVAL_MS) {
    sendTelemetry();
    lastTelemetryMs = millis();
  }
}
