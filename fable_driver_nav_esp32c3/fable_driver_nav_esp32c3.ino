#include <Arduino.h>
#include <WiFi.h>
#include <esp_now.h>
#include <esp_wifi.h>

#define ESPNOW_CHANNEL 1
#define SERIAL_BAUD 115200

#define MSG_TYPE_TARGET 1
#define MSG_TYPE_CLEAR_TARGET 2
#define MSG_TYPE_HEARTBEAT 3
#define MSG_TYPE_TELEMETRY 16

#define HEARTBEAT_INTERVAL_MS 500

uint8_t broadcastPeer[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
uint32_t commandSeq = 1;
uint32_t lastHeartbeatMs = 0;
String inputLine;

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

bool validTelemetryPacket(const NavTelemetryPacket &packet) {
  if (memcmp(packet.magic, "FNT1", 4) != 0) return false;
  if (packet.version != 1 || packet.type != MSG_TYPE_TELEMETRY) return false;
  uint16_t expected = crc16CcittFalse((const uint8_t *)&packet, sizeof(packet) - 2);
  return expected == packet.crc;
}

void printTelemetryJson(const NavTelemetryPacket &packet) {
  Serial.print("{\"type\":\"telemetry\"");
  Serial.print(",\"nav_seq\":");
  Serial.print(packet.navSeq);
  Serial.print(",\"target_seq\":");
  Serial.print(packet.targetSeq);
  Serial.print(",\"lat_e7\":");
  Serial.print(packet.latE7);
  Serial.print(",\"lon_e7\":");
  Serial.print(packet.lonE7);
  Serial.print(",\"target_lat_e7\":");
  Serial.print(packet.targetLatE7);
  Serial.print(",\"target_lon_e7\":");
  Serial.print(packet.targetLonE7);
  Serial.print(",\"flags\":");
  Serial.print(packet.flags);
  Serial.print(",\"fix\":");
  Serial.print(packet.fixQuality);
  Serial.print(",\"satellites\":");
  Serial.print(packet.satellites);
  Serial.print(",\"hdop_x100\":");
  Serial.print(packet.hdopX100);
  Serial.print(",\"gps_age_ms\":");
  Serial.print(packet.gpsAgeMs);
  Serial.print(",\"command_age_ms\":");
  Serial.print(packet.commandAgeMs);
  Serial.print(",\"uptime_ms\":");
  Serial.print(packet.uptimeMs);
  Serial.println("}");
}

void onEspNowReceive(const esp_now_recv_info_t *info, const uint8_t *data, int len) {
  if (len != sizeof(NavTelemetryPacket)) return;

  NavTelemetryPacket packet;
  memcpy(&packet, data, sizeof(packet));
  if (!validTelemetryPacket(packet)) return;

  printTelemetryJson(packet);
}

void printSendResult(bool ok, const char *error = "") {
  Serial.print("{\"type\":\"send\",\"ok\":");
  Serial.print(ok ? "true" : "false");
  if (!ok) {
    Serial.print(",\"error\":\"");
    Serial.print(error);
    Serial.print("\"");
  }
  Serial.println("}");
}

bool sendCommand(uint8_t type, int32_t targetLatE7, int32_t targetLonE7) {
  NavCommandPacket packet;
  memset(&packet, 0, sizeof(packet));
  memcpy(packet.magic, "FNC1", 4);
  packet.version = 1;
  packet.type = type;
  packet.seq = commandSeq++;
  packet.targetLatE7 = targetLatE7;
  packet.targetLonE7 = targetLonE7;
  packet.crc = crc16CcittFalse((const uint8_t *)&packet, sizeof(packet) - 2);

  esp_err_t result = esp_now_send(broadcastPeer, (const uint8_t *)&packet, sizeof(packet));
  return result == ESP_OK;
}

void handleCommand(String line) {
  line.trim();
  if (line.length() == 0) return;

  if (line == "CLEAR") {
    bool ok = sendCommand(MSG_TYPE_CLEAR_TARGET, 0, 0);
    printSendResult(ok, ok ? "" : "esp_now_send failed");
    return;
  }

  if (line.startsWith("TARGET ")) {
    int firstSpace = line.indexOf(' ');
    int secondSpace = line.indexOf(' ', firstSpace + 1);
    if (secondSpace < 0) {
      printSendResult(false, "TARGET requires lat_e7 lon_e7");
      return;
    }
    int32_t latE7 = line.substring(firstSpace + 1, secondSpace).toInt();
    int32_t lonE7 = line.substring(secondSpace + 1).toInt();
    bool ok = sendCommand(MSG_TYPE_TARGET, latE7, lonE7);
    printSendResult(ok, ok ? "" : "esp_now_send failed");
    return;
  }

  if (line.startsWith("TARGET_DEG ")) {
    int firstSpace = line.indexOf(' ');
    int secondSpace = line.indexOf(' ', firstSpace + 1);
    if (secondSpace < 0) {
      printSendResult(false, "TARGET_DEG requires lat lon");
      return;
    }
    double lat = line.substring(firstSpace + 1, secondSpace).toDouble();
    double lon = line.substring(secondSpace + 1).toDouble();
    bool ok = sendCommand(MSG_TYPE_TARGET, (int32_t)round(lat * 10000000.0), (int32_t)round(lon * 10000000.0));
    printSendResult(ok, ok ? "" : "esp_now_send failed");
    return;
  }

  printSendResult(false, "unknown command");
}

void addBroadcastPeer() {
  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, broadcastPeer, 6);
  peerInfo.channel = ESPNOW_CHANNEL;
  peerInfo.encrypt = false;
  esp_now_add_peer(&peerInfo);
}

void setup() {
  Serial.begin(SERIAL_BAUD);
  WiFi.mode(WIFI_STA);
  esp_wifi_set_channel(ESPNOW_CHANNEL, WIFI_SECOND_CHAN_NONE);

  if (esp_now_init() != ESP_OK) {
    Serial.println("{\"type\":\"status\",\"ok\":false,\"error\":\"esp_now_init failed\"}");
    return;
  }

  addBroadcastPeer();
  esp_now_register_recv_cb(onEspNowReceive);

  Serial.print("{\"type\":\"status\",\"ok\":true,\"mac\":\"");
  Serial.print(WiFi.macAddress());
  Serial.println("\"}");
}

void loop() {
  if (millis() - lastHeartbeatMs >= HEARTBEAT_INTERVAL_MS) {
    sendCommand(MSG_TYPE_HEARTBEAT, 0, 0);
    lastHeartbeatMs = millis();
  }

  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (inputLine.length() > 0) {
        handleCommand(inputLine);
        inputLine = "";
      }
    } else {
      inputLine += c;
      if (inputLine.length() > 96) {
        inputLine = "";
        printSendResult(false, "command too long");
      }
    }
  }
}
