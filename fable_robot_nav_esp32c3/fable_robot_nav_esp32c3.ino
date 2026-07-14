#include <Arduino.h>
#include <TinyGPSPlus.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

#define GPS_RX_PIN 4
#define GPS_TX_PIN 5
#define GPS_BAUD 9600

#define PICO_TX_PIN 6
#define PICO_RX_PIN 7
#define PICO_BAUD 115200

#define ESPNOW_CHANNEL 1
#define SNAPSHOT_LEN 56
#define GPS_RECENT_MS 2000
#define DRIVER_LINK_ALIVE_MS 2000
#define SNAPSHOT_INTERVAL_MS 100
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
HardwareSerial PicoSerial(0);

uint8_t broadcastPeer[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

portMUX_TYPE navStateMux = portMUX_INITIALIZER_UNLOCKED;
uint32_t navSeq = 0;
uint32_t targetSeq = 0;
uint32_t lastGpsUpdateMs = 0;
uint32_t lastDriverCommandMs = 0;
uint32_t lastSnapshotMs = 0;
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

static_assert(sizeof(NavCommandPacket) == 20, "Unexpected command packet size");
static_assert(sizeof(NavTelemetryPacket) == 49, "Unexpected telemetry packet size");

typedef struct {
  uint32_t navSeq;
  uint32_t targetSeq;
  uint32_t lastGpsUpdateMs;
  uint32_t lastDriverCommandMs;
  int32_t currentLatE7;
  int32_t currentLonE7;
  int32_t targetLatE7;
  int32_t targetLonE7;
  bool targetValid;
} NavState;

uint16_t crc16CcittFalse(const uint8_t *data, size_t len) {
  uint16_t crc = 0xFFFF;
  for (size_t i = 0; i < len; i++) {
    crc ^= ((uint16_t)data[i] << 8);
    for (uint8_t bit = 0; bit < 8; bit++) {
      crc = (crc & 0x8000) ? (crc << 1) ^ 0x1021 : crc << 1;
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

NavState copyNavState() {
  NavState state;
  portENTER_CRITICAL(&navStateMux);
  state.navSeq = navSeq;
  state.targetSeq = targetSeq;
  state.lastGpsUpdateMs = lastGpsUpdateMs;
  state.lastDriverCommandMs = lastDriverCommandMs;
  state.currentLatE7 = currentLatE7;
  state.currentLonE7 = currentLonE7;
  state.targetLatE7 = targetLatE7;
  state.targetLonE7 = targetLonE7;
  state.targetValid = targetValid;
  portEXIT_CRITICAL(&navStateMux);
  return state;
}

uint8_t fixQuality(const NavState &state, uint32_t now) {
  if (!gps.location.isValid()) return 0;
  if (state.lastGpsUpdateMs == 0 || now - state.lastGpsUpdateMs > GPS_RECENT_MS) return 0;
  if (gps.satellites.isValid() && gps.satellites.value() >= 4) return 2;
  return 1;
}

uint8_t statusFlags(const NavState &state, uint32_t now) {
  uint8_t flags = 0;
  if (fixQuality(state, now) > 0) flags |= FLAG_GPS_VALID;
  if (state.targetValid) flags |= FLAG_TARGET_VALID;
  if (gps.satellites.isValid()) flags |= FLAG_SATS_VALID;
  if (gps.hdop.isValid()) flags |= FLAG_HDOP_VALID;
  if (state.lastDriverCommandMs != 0
      && now - state.lastDriverCommandMs <= DRIVER_LINK_ALIVE_MS) {
    flags |= FLAG_DRIVER_LINK_ALIVE;
  }
  return flags;
}

uint32_t ageMs(uint32_t timestamp, uint32_t now) {
  return timestamp == 0 ? UINT32_MAX : now - timestamp;
}

uint16_t hdopX100() {
  if (!gps.hdop.isValid()) return 0;
  uint32_t value = gps.hdop.value();
  return value > 65535 ? 65535 : (uint16_t)value;
}

uint8_t satelliteCount() {
  if (!gps.satellites.isValid()) return 0;
  uint32_t value = gps.satellites.value();
  return value > 255 ? 255 : (uint8_t)value;
}

void buildSnapshot(uint8_t *next, const NavState &state, uint32_t now) {
  memset(next, 0, SNAPSHOT_LEN);
  memcpy(next, "FNAV", 4);
  next[4] = 1;
  next[5] = SNAPSHOT_LEN;
  next[6] = statusFlags(state, now);
  next[7] = fixQuality(state, now);
  putU32(next, 8, state.navSeq);
  putU32(next, 12, state.targetSeq);
  putI32(next, 16, state.currentLatE7);
  putI32(next, 20, state.currentLonE7);
  putI32(next, 24, state.targetLatE7);
  putI32(next, 28, state.targetLonE7);
  putU32(next, 32, ageMs(state.lastGpsUpdateMs, now));
  putU32(next, 36, ageMs(state.lastDriverCommandMs, now));
  putU16(next, 40, hdopX100());
  next[42] = satelliteCount();
  putU32(next, 44, gps.charsProcessed());
  putU32(next, 48, now);
  putU16(next, 54, crc16CcittFalse(next, 54));
}

void sendSnapshotToPico() {
  uint8_t snapshot[SNAPSHOT_LEN];
  NavState state = copyNavState();
  buildSnapshot(snapshot, state, millis());
  PicoSerial.write(snapshot, sizeof(snapshot));
}

bool validCommandPacket(const NavCommandPacket &packet) {
  if (memcmp(packet.magic, "FNC1", 4) != 0 || packet.version != 1) return false;
  uint16_t expected = crc16CcittFalse((const uint8_t *)&packet, sizeof(packet) - 2);
  return expected == packet.crc;
}

void onEspNowReceive(const esp_now_recv_info_t *info, const uint8_t *data, int len) {
  if (len != sizeof(NavCommandPacket)) return;

  NavCommandPacket packet;
  memcpy(&packet, data, sizeof(packet));
  if (!validCommandPacket(packet)) return;

  portENTER_CRITICAL(&navStateMux);
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
  portEXIT_CRITICAL(&navStateMux);
}

void sendTelemetry() {
  NavState state = copyNavState();
  uint32_t now = millis();

  NavTelemetryPacket packet;
  memset(&packet, 0, sizeof(packet));
  memcpy(packet.magic, "FNT1", 4);
  packet.version = 1;
  packet.type = MSG_TYPE_TELEMETRY;
  packet.navSeq = state.navSeq;
  packet.targetSeq = state.targetSeq;
  packet.latE7 = state.currentLatE7;
  packet.lonE7 = state.currentLonE7;
  packet.targetLatE7 = state.targetLatE7;
  packet.targetLonE7 = state.targetLonE7;
  packet.flags = statusFlags(state, now);
  packet.fixQuality = fixQuality(state, now);
  packet.satellites = satelliteCount();
  packet.hdopX100 = hdopX100();
  packet.gpsAgeMs = ageMs(state.lastGpsUpdateMs, now);
  packet.commandAgeMs = ageMs(state.lastDriverCommandMs, now);
  packet.uptimeMs = now;
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
  // UART0 is dedicated to the Pico. USB CDC debugging, when enabled for the
  // selected C3 board, remains separate from these remapped UART pins.
  PicoSerial.begin(PICO_BAUD, SERIAL_8N1, PICO_RX_PIN, PICO_TX_PIN);
  GPSSerial.begin(GPS_BAUD, SERIAL_8N1, GPS_RX_PIN, GPS_TX_PIN);

  WiFi.mode(WIFI_STA);
  esp_wifi_set_channel(ESPNOW_CHANNEL, WIFI_SECOND_CHAN_NONE);
  if (esp_now_init() == ESP_OK) {
    addBroadcastPeer();
    esp_now_register_recv_cb(onEspNowReceive);
  }

  sendSnapshotToPico();
}

void loop() {
  while (GPSSerial.available()) {
    char c = GPSSerial.read();
    if (gps.encode(c) && gps.location.isUpdated()) {
      portENTER_CRITICAL(&navStateMux);
      currentLatE7 = (int32_t)round(gps.location.lat() * 10000000.0);
      currentLonE7 = (int32_t)round(gps.location.lng() * 10000000.0);
      lastGpsUpdateMs = millis();
      navSeq++;
      portEXIT_CRITICAL(&navStateMux);
    }
  }

  uint32_t now = millis();
  if (now - lastSnapshotMs >= SNAPSHOT_INTERVAL_MS) {
    sendSnapshotToPico();
    lastSnapshotMs = now;
  }

  if (now - lastTelemetryMs >= TELEMETRY_INTERVAL_MS) {
    sendTelemetry();
    lastTelemetryMs = now;
  }
}
