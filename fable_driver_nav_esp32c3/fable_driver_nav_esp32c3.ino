#include <Arduino.h>
#include <FastLED.h>
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

#define STATUS_LED_PIN 2
#define STATUS_LED_COUNT 1
#define LED_FRAME_INTERVAL_MS 20
#define STARTUP_RAINBOW_MS 1800
#define TELEMETRY_FRESH_MS 2500
#define RX_PULSE_MS 140
#define TX_PULSE_MS 360
#define ERROR_PULSE_MS 600

uint8_t broadcastPeer[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
uint32_t commandSeq = 1;
uint32_t lastHeartbeatMs = 0;
String inputLine;

CRGB statusLed[STATUS_LED_COUNT];
uint32_t ledStartupMs = 0;
uint32_t lastLedFrameMs = 0;
volatile uint32_t lastTelemetryMs = 0;
volatile uint32_t rxPulseStartedMs = 0;
volatile bool telemetrySeen = false;
uint32_t txPulseStartedMs = 0;
bool txPulseFailed = false;
bool espNowInitFailed = false;

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

uint8_t pulseBrightness(uint32_t elapsedMs, uint32_t durationMs, uint8_t peak) {
  if (elapsedMs >= durationMs) return 0;
  return (uint8_t)map(elapsedMs, 0, durationMs, peak, 0);
}

void renderStatusLed() {
  uint32_t now = millis();
  if (now - lastLedFrameMs < LED_FRAME_INTERVAL_MS) return;
  lastLedFrameMs = now;

  uint8_t breath = sin8((uint8_t)(now / 10));

  if (espNowInitFailed) {
    statusLed[0] = CHSV(0, 255, map(breath, 0, 255, 18, 100));
  } else if (now - ledStartupMs < STARTUP_RAINBOW_MS) {
    statusLed[0] = CHSV((uint8_t)(now / 7), 235, map(breath, 0, 255, 55, 115));
  } else if (txPulseStartedMs != 0 && now - txPulseStartedMs < (txPulseFailed ? ERROR_PULSE_MS : TX_PULSE_MS)) {
    uint32_t duration = txPulseFailed ? ERROR_PULSE_MS : TX_PULSE_MS;
    uint8_t value = pulseBrightness(now - txPulseStartedMs, duration, 190);
    statusLed[0] = txPulseFailed ? CHSV(0, 255, value) : CHSV(160, 245, value);
  } else {
    uint32_t rxStartedMs = rxPulseStartedMs;
    if (rxStartedMs != 0 && now - rxStartedMs < RX_PULSE_MS) {
      statusLed[0] = CHSV(96, 235, pulseBrightness(now - rxStartedMs, RX_PULSE_MS, 175));
    } else {
      uint32_t telemetryMs = lastTelemetryMs;
      bool linkFresh = telemetrySeen && now - telemetryMs <= TELEMETRY_FRESH_MS;
      if (linkFresh) {
        statusLed[0] = CHSV(112 + breath / 32, 220, map(breath, 0, 255, 12, 58));
      } else {
        statusLed[0] = CHSV(24 + breath / 28, 240, map(breath, 0, 255, 10, 48));
      }
    }
  }

  FastLED.show();
}

void noteCommandSend(bool ok) {
  txPulseFailed = !ok;
  txPulseStartedMs = millis();
  if (txPulseStartedMs == 0) txPulseStartedMs = 1;
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

  uint32_t receivedMs = millis();
  lastTelemetryMs = receivedMs;
  rxPulseStartedMs = receivedMs == 0 ? 1 : receivedMs;
  telemetrySeen = true;
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
    noteCommandSend(ok);
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
    noteCommandSend(ok);
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
    noteCommandSend(ok);
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
  FastLED.addLeds<SK6812, STATUS_LED_PIN, GRB>(statusLed, STATUS_LED_COUNT);
  FastLED.clear(true);
  ledStartupMs = millis();

  WiFi.mode(WIFI_STA);
  esp_wifi_set_channel(ESPNOW_CHANNEL, WIFI_SECOND_CHAN_NONE);

  if (esp_now_init() != ESP_OK) {
    espNowInitFailed = true;
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
  renderStatusLed();

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
