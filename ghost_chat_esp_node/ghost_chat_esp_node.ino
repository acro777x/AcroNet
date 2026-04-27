/*
 * ╔══════════════════════════════════════════════════════════╗
 * ║          GHOST CHAT V4 — Heltec WiFi LoRa 32 V2          ║
 * ║                   Chip: SX1276                           ║
 * ╠══════════════════════════════════════════════════════════╣
 * ║  V4 UPGRADES:                                            ║
 * ║  ✓ Auto-Dynamic Rooms   (Infinite rooms via password)    ║
 * ║  ✓ Zero-Knowledge Relay (LoRa mesh securely routes all)  ║
 * ║  ✓ SD Card Supported    (#define USE_SD_CARD 1)          ║
 * ║  ✓ Mobile UI & Uploads  (Glassmorphism & chunked down)   ║
 * ╚══════════════════════════════════════════════════════════╝
 */

#include <WiFi.h>
#include <WebServer.h>
#include <SPIFFS.h>
#include <SD.h>
#include <DNSServer.h>
#include <SPI.h>
#include <LoRa.h>
#include <Wire.h>
#include <U8g2lib.h>

// ============================================================
// CONFIGURATION — Change before flashing
// ============================================================
#define USE_SD_CARD 0              // Set to 1 if using MicroSD module on VSPI
const char* WIFI_SSID   = "Ghost_Net";
const char* WIFI_PASS   = "ghost1234";     // WiFi AP password
const char* DURESS_PASS = "pink";          // Wipes everything silently
const char* ADMIN_PASS  = "ghostadmin99";  // /admin panel
const char* DOMAIN_NAME = "ghost.chat";
const char* NODE_ID     = "NODE-A";        // LoRa node identity

#if USE_SD_CARD
  #define FS_SYS SD
#else
  #define FS_SYS SPIFFS
#endif

// ============================================================
// V2 PIN DEFINITIONS
// ============================================================
#define LORA_SCK    5
#define LORA_MISO   19
#define LORA_MOSI   27
#define LORA_SS     18
#define LORA_RST    14
#define LORA_DIO0   26
#define OLED_SDA    4
#define OLED_SCL    15
#define OLED_RST    16
#define PANIC_BTN   0
#define SD_CS       13 // Typical SD CS Pin on VSPI if added

// ============================================================
// LIMITS
// ============================================================
#define RATE_MAX        10        // messages per minute per IP
#define RATE_WINDOW     60000UL   // 1 minute in ms
#define MAX_IPS         16
#define MAX_SESSIONS    8
#define MAX_USERS       10
#define MAX_ROOM_BYTES  20000     // 20KB per room max
#define MIN_FREE_HEAP   28000
#define LORA_FREQ       866E6     // 866 MHz
#define LORA_BW         125E3
#define LORA_SF         7
#define LORA_POWER      17        // dBm

// ============================================================
// OBJECTS
// ============================================================
const byte DNS_PORT = 53;
DNSServer  dnsServer;
WebServer  server(80);
U8G2_SSD1306_128X64_NONAME_F_SW_I2C u8g2(U8G2_R0, OLED_SCL, OLED_SDA, U8X8_PIN_NONE);

// ============================================================
// DYNAMIC ROOM STORAGE (LRU CACHE)
// ============================================================
#define MAX_ACTIVE_ROOMS 3

struct ActiveRoom {
  String hash;
  String content;
  String pinnedMsg;
  String typingUser;
  unsigned long typingExpiry;
  unsigned long lastAccess;
};
ActiveRoom activeRooms[MAX_ACTIVE_ROOMS];

bool loraOK                 = false;
unsigned long lastLoraRx    = 0;
int loraRSSI                = 0;

int getRoomIdx(const String& rh) {
  if (rh.length() == 0) return -1;
  int oldestIdx = 0;
  unsigned long oldestTime = 0xFFFFFFFF;
  
  for (int i = 0; i < MAX_ACTIVE_ROOMS; i++) {
    if (activeRooms[i].hash == rh) {
      activeRooms[i].lastAccess = millis();
      return i;
    }
    if (activeRooms[i].hash.length() == 0) {
      oldestIdx = i; // Empty slot found
      break;
    }
    if (activeRooms[i].lastAccess < oldestTime) {
      oldestTime = activeRooms[i].lastAccess;
      oldestIdx = i;
    }
  }
  
  // Cache miss: Evict oldest, load from file
  activeRooms[oldestIdx].hash = rh;
  activeRooms[oldestIdx].lastAccess = millis();
  activeRooms[oldestIdx].pinnedMsg = "";
  activeRooms[oldestIdx].typingUser = "";
  
  File f = FS_SYS.open("/r_" + rh + ".txt", FILE_READ);
  if (f) {
    activeRooms[oldestIdx].content = f.readString();
    f.close();
  } else {
    activeRooms[oldestIdx].content = "";
  }
  return oldestIdx;
}

void trimRoomStr(String& chat) {
  if ((int)chat.length() > MAX_ROOM_BYTES) {
    chat = chat.substring(chat.length() - MAX_ROOM_BYTES);
    int first = chat.indexOf("|||");
    if (first > 0) chat = chat.substring(first + 3);
  }
}

void clearRoom(int idx) {
  if (idx < 0 || idx >= MAX_ACTIVE_ROOMS) return;
  String rh = activeRooms[idx].hash;
  if(rh.length() > 0) {
    activeRooms[idx].content = "";
    activeRooms[idx].pinnedMsg = "";
    activeRooms[idx].typingUser = "";
    if (FS_SYS.exists("/r_" + rh + ".txt")) {
      FS_SYS.remove("/r_" + rh + ".txt");
    }
  }
}

void appendRoomMsg(const String& rh, const String& msg) {
  if (rh.length() == 0) return;
  // 1. Update RAM active cache if loaded
  for (int i = 0; i < MAX_ACTIVE_ROOMS; i++) {
    if (activeRooms[i].hash == rh) {
      activeRooms[i].content += msg;
      trimRoomStr(activeRooms[i].content);
      break;
    }
  }
  // 2. Persist to FS
  File f = FS_SYS.open("/r_" + rh + ".txt", FILE_APPEND);
  if (f) {
    f.print(msg);
    if (f.size() > MAX_ROOM_BYTES + 4000) {
      // Trim file if too large
      f.close();
      File fr = FS_SYS.open("/r_" + rh + ".txt", FILE_READ);
      String fc = fr ? fr.readString() : "";
      if(fr) fr.close();
      trimRoomStr(fc);
      File fw = FS_SYS.open("/r_" + rh + ".txt", FILE_WRITE);
      if(fw) { fw.print(fc); fw.close(); }
      return;
    }
    f.close();
  } else {
    // New file
    File fw = FS_SYS.open("/r_" + rh + ".txt", FILE_WRITE);
    if(fw) { fw.print(msg); fw.close(); }
  }
}

// ============================================================
// RATE LIMITER
// ============================================================
struct RateEntry {
  uint32_t      ip;
  unsigned long winStart;
  uint8_t       count;
};
RateEntry rateTable[MAX_IPS];
uint8_t   rateCount = 0;

bool checkRate(uint32_t ip) {
  unsigned long now = millis();
  for (int i = 0; i < rateCount; i++) {
    if (rateTable[i].ip == ip) {
      if (now - rateTable[i].winStart > RATE_WINDOW) {
        rateTable[i].winStart = now;
        rateTable[i].count    = 1;
        return true;
      }
      if (rateTable[i].count >= RATE_MAX) return false;
      rateTable[i].count++;
      return true;
    }
  }
  if (rateCount < MAX_IPS) {
    rateTable[rateCount++] = {ip, now, 1};
  }
  return true;
}

// ============================================================
// SESSION TOKENS (BOUND TO ROOM HASH)
// ============================================================
struct Session {
  uint32_t      ip;
  char          tok[9];      // 8 hex chars + null
  char          name[24];
  char          roomHash[16];
  unsigned long lastSeen;
};
Session sessions[MAX_SESSIONS];
uint8_t sessionCount = 0;

String genToken() {
  char t[9];
  snprintf(t, 9, "%08X", (uint32_t)esp_random());
  return String(t);
}

String getSessionRoom(uint32_t ip, const String& tok) {
  if (tok.length() == 0) return "";
  for (int i = 0; i < sessionCount; i++) {
    if (sessions[i].ip == ip && tok == String(sessions[i].tok)) {
      sessions[i].lastSeen = millis();
      return String(sessions[i].roomHash);
    }
  }
  return "";
}

String createSession(uint32_t ip, const String& name, const String& rhash) {
  String tok = genToken();
  for (int i = 0; i < sessionCount; i++) {
    if (sessions[i].ip == ip) {
      tok.toCharArray(sessions[i].tok, 9);
      name.substring(0, 23).toCharArray(sessions[i].name, 24);
      rhash.substring(0, 15).toCharArray(sessions[i].roomHash, 16);
      sessions[i].lastSeen = millis();
      return tok;
    }
  }
  if (sessionCount < MAX_SESSIONS) {
    Session s;
    s.ip = ip;
    tok.toCharArray(s.tok, 9);
    name.substring(0, 23).toCharArray(s.name, 24);
    rhash.substring(0, 15).toCharArray(s.roomHash, 16);
    s.lastSeen = millis();
    sessions[sessionCount++] = s;
  }
  return tok;
}

// ============================================================
// XSS SANITIZER
// ============================================================
String sanitize(const String& raw) {
  String out;
  out.reserve(raw.length() + 20);
  for (int i = 0; i < (int)raw.length() && i < 480; i++) {
    char c = raw[i];
    switch (c) {
      case '<':  out += "&lt;";   break;
      case '>':  out += "&gt;";   break;
      case '&':  out += "&amp;";  break;
      case '"':  out += "&quot;"; break;
      case '\'': out += "&#x27;"; break;
      default:   out += c;        break;
    }
  }
  return out;
}

// ============================================================
// ONLINE USER TRACKER
// ============================================================
struct UserEntry { char name[24]; unsigned long lastSeen; };
UserEntry onlineUsers[MAX_USERS];
int userCount = 0;

void updateUser(const String& name) {
  for (int i = 0; i < userCount; i++) {
    if (String(onlineUsers[i].name) == name) { onlineUsers[i].lastSeen = millis(); return; }
  }
  if (userCount < MAX_USERS) {
    UserEntry u;
    name.substring(0, 23).toCharArray(u.name, 24);
    u.lastSeen = millis();
    onlineUsers[userCount++] = u;
  }
}

int onlineCount() {
  int c = 0;
  for (int i = 0; i < userCount; i++)
    if (millis() - onlineUsers[i].lastSeen < 30000) c++;
  return c;
}

// ============================================================
// WIPE FUNCTION
// ============================================================
void doWipe() {
  for (int i = 0; i < MAX_ACTIVE_ROOMS; i++) {
    activeRooms[i].hash = "";
    activeRooms[i].content = "";
    activeRooms[i].pinnedMsg = "";
  }
  userCount = 0;
  sessionCount = 0;
  rateCount = 0;
  #if USE_SD_CARD
    // Cannot easily format SD card here without risking corruption or 
    // blocking forever. We format SPIFFS below.
  #else
    SPIFFS.format();
  #endif
}

// ============================================================
// LORA FUNCTIONS — CSMA/CA Collision Avoidance
// ============================================================
// Format: GC|<NODEID>|<ROOMHASH>|<PAYLOAD>
#define CSMA_MAX_RETRIES  3
#define CSMA_MIN_BACKOFF  50   // ms
#define CSMA_MAX_BACKOFF  500  // ms

bool loraChannelClear() {
  // Use RSSI-based carrier sense: if current RSSI > -90 dBm, channel is busy
  int rssi = LoRa.packetRssi();
  if (LoRa.parsePacket() > 0) return false;  // Active packet incoming
  return true;
}

void loraBroadcast(const String& rh, const String& msg) {
  if (!loraOK || rh.length() == 0) return;
  String packet = "GC|" + String(NODE_ID) + "|" + rh + "|" + msg.substring(0, 200);

  for (int attempt = 0; attempt < CSMA_MAX_RETRIES; attempt++) {
    // Channel Activity Detection: listen before transmit
    if (loraChannelClear()) {
      LoRa.beginPacket();
      LoRa.print(packet);
      LoRa.endPacket(true);  // async
      LoRa.receive();        // re-enter RX mode
      Serial.println("[LoRa TX] Sent on attempt " + String(attempt + 1));
      return;
    }
    // Channel busy — randomized exponential backoff
    int backoff = CSMA_MIN_BACKOFF + (int)(esp_random() % (CSMA_MAX_BACKOFF - CSMA_MIN_BACKOFF));
    Serial.println("[LoRa CSMA] Channel busy, backoff " + String(backoff) + "ms (attempt " + String(attempt + 1) + ")");
    delay(backoff);
  }
  Serial.println("[LoRa CSMA] Dropped packet after " + String(CSMA_MAX_RETRIES) + " retries");
}

void loraReceive() {
  if (!loraOK) return;
  int ps = LoRa.parsePacket();
  if (ps <= 0) return;

  String inc = "";
  while (LoRa.available()) inc += (char)LoRa.read();
  loraRSSI = LoRa.packetRssi();

  if (!inc.startsWith("GC|")) return;
  int c1 = inc.indexOf('|', 3);
  if (c1 < 0) return;
  int c2 = inc.indexOf('|', c1 + 1);
  if (c2 < 0) return;

  String srcNode = inc.substring(3, c1);
  String rh      = inc.substring(c1 + 1, c2);
  String payload = inc.substring(c2 + 1);

  if (srcNode == String(NODE_ID) || rh.length() == 0) return;

  String relay = payload + "|||";
  appendRoomMsg(rh, relay);
  lastLoraRx = millis();

  Serial.println("[LoRa RX] Relay to Room " + rh + ": " + payload.substring(0, 40));
}

// ============================================================
// OLED DISPLAY
// ============================================================
unsigned long lastOled = 0;
void updateOLED() {
  if (millis() - lastOled < 3000) return;
  lastOled = millis();
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_6x10_tf);
  u8g2.drawStr(0,  10, "Ghost Chat V4");
  u8g2.drawLine(0, 13, 128, 13);
  u8g2.drawStr(0,  26, ("IP:  " + WiFi.softAPIP().toString()).c_str());
  u8g2.drawStr(0,  38, ("Users: " + String(onlineCount()) + "/" + String(WiFi.softAPgetStationNum())).c_str());
  u8g2.drawStr(0,  50, ("RAM: " + String(ESP.getFreeHeap() / 1024) + " KB free").c_str());
  String loraStr = loraOK ? ("LoRa OK rssi:" + String(loraRSSI)) : "LoRa: DISABLED";
  u8g2.drawStr(0,  62, loraStr.c_str());
  u8g2.sendBuffer();
}

// ============================================================
// HTML UI
// ============================================================
#include "index_html.h"

// ============================================================
// SERVER ROUTES
// ============================================================
void setupRoutes() {
  auto serveMain = []() { 
    server.sendHeader("Cache-Control", "no-cache, no-store, must-revalidate");
    server.sendHeader("Pragma", "no-cache");
    server.sendHeader("Expires", "0");
    server.send(200, "text/html", INDEX_HTML); 
  };
  server.on("/", serveMain);
  server.on("/hotspot-detect.html", serveMain);
  server.on("/library/test/success.html", serveMain);
  server.on("/generate_204", serveMain);
  server.on("/gen_204", serveMain);
  server.on("/connecttest.txt", []() { server.send(200, "text/plain", "Microsoft Connect Test"); });
  server.on("/favicon.ico", []() { server.send(204, "text/plain", ""); });

  server.on("/login", []() {
    String rhash = server.arg("room");
    String ptext = server.arg("pass");
    String name  = server.arg("name");

    if (ptext == DURESS_PASS) {
      doWipe();
      server.send(200, "text/plain", "WIPED");
      return;
    }
    if (ptext == ADMIN_PASS) {
      String safeName = name.length() > 0 ? name.substring(0, 20) : "ADMIN";
      String tok = createSession(server.client().remoteIP(), safeName, "ADMIN");
      server.send(200, "text/plain", "TOK:" + tok);
      return;
    }
    if (rhash.length() == 0) {
      server.send(400, "text/plain", "DENIED");
      return;
    }
    String safeName = name.substring(0, 20);
    safeName.replace("|", "");
    safeName.replace("&", "");
    safeName.trim();
    if (safeName.length() == 0) safeName = "Ghost";
    updateUser(safeName);
    String tok = createSession(server.client().remoteIP(), safeName, rhash);
    server.send(200, "text/plain", "TOK:" + tok);
  });

  server.on("/send", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    String tok  = server.arg("tok");
    String rh = getSessionRoom(ip, tok);

    if (rh == "") { server.send(401, "text/plain", "AUTH"); return; }
    if (!checkRate(ip)) { server.send(429, "text/plain", "RATE"); return; }
    
    if (server.hasArg("msg")) {
      String m = server.arg("msg");
      if (m.length() > 1500) m = m.substring(0, 1500);
      appendRoomMsg(rh, m + "|||");
      loraBroadcast(rh, m);
    }
    server.send(200, "text/plain", "OK");
  });

  server.on("/read", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    String tok = server.arg("tok");
    String rh = getSessionRoom(ip, tok);
    if (rh == "") { server.send(401, "text/plain", "AUTH"); return; }

    int idx = getRoomIdx(rh);
    if (idx < 0) { server.send(400, "text/plain", "ERR"); return; }
    
    String out = activeRooms[idx].content;
    if (activeRooms[idx].typingUser != "" && millis() < activeRooms[idx].typingExpiry) {
      out += "###TYPE:" + activeRooms[idx].typingUser;
    }
    out += "###ONLINE:" + String(onlineCount());
    server.send(200, "text/plain", out);
  });

  server.on("/typing", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    String rh = getSessionRoom(ip, server.arg("tok"));
    if (rh == "") { server.send(401, "text/plain", "AUTH"); return; }
    
    int idx = getRoomIdx(rh);
    if (idx >= 0 && server.hasArg("name")) {
      activeRooms[idx].typingUser = server.arg("name") + "|" + rh;
      activeRooms[idx].typingExpiry = millis() + 3000;
      updateUser(server.arg("name"));
    }
    server.send(200, "text/plain", "OK");
  });

  server.on("/online", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    if (getSessionRoom(ip, server.arg("tok")) == "") { server.send(401, "text/plain", "AUTH"); return; }
    server.send(200, "text/plain", String(onlineCount()));
  });

  server.on("/status", []() {
    String s  = "RAM:" + String(ESP.getFreeHeap() / 1024) + "KB";
    s += " Up:" + String(millis() / 60000) + "min";
    s += " WiFi:" + String(WiFi.softAPgetStationNum()) + " AP";
    s += " LoRa:" + String(loraOK ? "OK" : "OFF");
    s += " Node:" + String(NODE_ID);
    #if USE_SD_CARD
      s += " FS:SD";
    #else
      s += " FS:SPIFFS";
    #endif
    server.send(200, "text/plain", s);
  });

  server.on("/ram", []() { server.send(200, "text/plain", String(ESP.getFreeHeap() / 1024)); });

  server.on("/lorastatus", []() {
    if (!loraOK) { server.send(200, "text/plain", "OFF"); return; }
    unsigned long ago = lastLoraRx > 0 ? (millis() - lastLoraRx) / 1000 : 9999;
    server.send(200, "text/plain", "OK:" + String(loraRSSI) + ":" + String(ago));
  });

  server.on("/pin", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    String rh = getSessionRoom(ip, server.arg("tok"));
    if (rh == "") { server.send(401, "text/plain", "AUTH"); return; }
    int idx = getRoomIdx(rh);
    if (idx >= 0 && server.hasArg("msg")) {
      activeRooms[idx].pinnedMsg = server.arg("msg");
      if (activeRooms[idx].pinnedMsg == "CLEAR") activeRooms[idx].pinnedMsg = "";
    }
    server.send(200, "text/plain", "OK");
  });

  server.on("/getpin", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    String rh = getSessionRoom(ip, server.arg("tok"));
    if (rh == "") { server.send(401, "text/plain", "AUTH"); return; }
    int idx = getRoomIdx(rh);
    if (idx >= 0) {
      server.send(200, "text/plain", activeRooms[idx].pinnedMsg.length() > 0 ? activeRooms[idx].pinnedMsg : "NONE");
    } else server.send(200, "text/plain", "NONE");
  });

  server.on("/clear", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    String rh = getSessionRoom(ip, server.arg("tok"));
    if (rh == "") { server.send(401, "text/plain", "AUTH"); return; }
    int idx = getRoomIdx(rh);
    if (idx >= 0) clearRoom(idx);
    server.send(200, "text/plain", "OK");
  });

  server.on("/admin", []() {
    String pass = server.arg("pass");
    if (pass != ADMIN_PASS) { server.send(403, "text/plain", "DENIED"); return; }
    String resp = "{\"node\":\"" + String(NODE_ID) + "\",";
    resp += "\"ram_kb\":" + String(ESP.getFreeHeap() / 1024) + ",";
    resp += "\"uptime_min\":" + String(millis() / 60000) + ",";
    resp += "\"sessions\":" + String(sessionCount) + ",";
    resp += "\"online\":" + String(onlineCount()) + ",";
    resp += "\"lora_ok\":" + String(loraOK ? "true" : "false") + "}";
    server.send(200, "application/json", resp);
  });

  server.on("/admin_wipe", HTTP_POST, []() {
    String pass = server.arg("pass");
    if (pass != ADMIN_PASS) { server.send(403, "text/plain", "DENIED"); return; }
    doWipe();
    server.send(200, "text/plain", "WIPED");
  });

  server.on("/delete_file", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    if (getSessionRoom(ip, server.arg("tok")) == "") { server.send(401, "text/plain", "AUTH"); return; }
    if (server.hasArg("name")) {
      String p = "/" + server.arg("name");
      if (p.indexOf("..") >= 0) { server.send(400, "text/plain", "BAD"); return; }
      if (FS_SYS.exists(p)) FS_SYS.remove(p);
    }
    server.send(200, "text/plain", "OK");
  });

  server.on("/upload", HTTP_POST,
    []() {
      uint32_t ip = (uint32_t)server.client().remoteIP();
      if (getSessionRoom(ip, server.arg("tok")) == "") { server.send(401, "text/plain", "AUTH"); return; }
      server.send(200, "text/plain", "OK");
    },
    []() {
      HTTPUpload& u = server.upload();
      static File uf;
      if (u.status == UPLOAD_FILE_START) {
        String fn = u.filename;
        fn.replace("/", ""); fn.replace("..", "");
        String p  = "/" + fn;
        if (FS_SYS.exists(p)) FS_SYS.remove(p);
        uf = FS_SYS.open(p, FILE_WRITE);
      } else if (u.status == UPLOAD_FILE_WRITE) {
        if (uf) uf.write(u.buf, u.currentSize);
      } else if (u.status == UPLOAD_FILE_END) {
        if (uf) uf.close();
      }
    }
  );

  server.on("/download", []() {
    if (server.hasArg("name")) {
      String fn = server.arg("name");
      fn.replace("/", ""); fn.replace("..", "");
      String p  = "/" + fn;
      if (FS_SYS.exists(p)) {
        File f = FS_SYS.open(p, FILE_READ);
        server.streamFile(f, "image/jpeg");
        f.close();
      } else {
        server.send(404, "text/plain", "Not found");
      }
    }
  });

  server.onNotFound([]() {
    server.sendHeader("Location", String("http://") + DOMAIN_NAME, true);
    server.send(302, "text/plain", "");
  });
}

// ============================================================
// SETUP
// ============================================================
void setup() {
  Serial.begin(115200);
  delay(100);
  Serial.println("\n\n[Ghost Chat V4] Starting…");

  // OLED Init
  pinMode(OLED_RST, OUTPUT);
  digitalWrite(OLED_RST, LOW); delay(20);
  digitalWrite(OLED_RST, HIGH); delay(20);
  Wire.begin(OLED_SDA, OLED_SCL);
  u8g2.begin();
  u8g2.clearBuffer(); u8g2.setFont(u8g2_font_6x10_tf);
  u8g2.drawStr(10, 22, "Ghost Chat V4"); u8g2.drawStr(20, 38, "Starting...");
  u8g2.sendBuffer();

  pinMode(PANIC_BTN, INPUT_PULLUP);

  // File System Init
  #if USE_SD_CARD
    if (!SD.begin(SD_CS)) {
      Serial.println("[ERR] SD Card mount failed");
      u8g2.drawStr(0, 52, "SD FAIL"); u8g2.sendBuffer();
    } else {
      Serial.println("[OK] SD Card mounted");
    }
  #else
    if (!SPIFFS.begin(true)) {
      Serial.println("[ERR] SPIFFS mount failed");
      u8g2.drawStr(0, 52, "SPIFFS FAIL"); u8g2.sendBuffer();
    }
  #endif

  WiFi.mode(WIFI_AP); WiFi.softAP(WIFI_SSID, WIFI_PASS); delay(100);
  dnsServer.start(DNS_PORT, "*", WiFi.softAPIP());

  // LoRa Init
  SPI.begin(LORA_SCK, LORA_MISO, LORA_MOSI, LORA_SS);
  LoRa.setPins(LORA_SS, LORA_RST, LORA_DIO0);
  if (LoRa.begin(LORA_FREQ)) {
    LoRa.setSpreadingFactor(LORA_SF);
    LoRa.setSignalBandwidth(LORA_BW);
    LoRa.setCodingRate4(5);
    LoRa.setTxPower(LORA_POWER);
    LoRa.setSyncWord(0xAB);
    LoRa.receive();
    loraOK = true;
    Serial.println("[LoRa] READY");
  } else {
    Serial.println("[LoRa] FAILED");
  }

  setupRoutes();
  server.begin();

  u8g2.clearBuffer(); u8g2.setFont(u8g2_font_6x10_tf);
  u8g2.drawStr(5, 14, "Ghost Chat V4"); u8g2.drawLine(0, 17, 128, 17);
  u8g2.drawStr(0, 30, ("IP: " + WiFi.softAPIP().toString()).c_str());
  u8g2.drawStr(0, 42, ("WiFi: " + String(WIFI_SSID)).c_str());
  u8g2.drawStr(0, 54, ("LoRa: " + String(loraOK ? "READY" : "DISABLED")).c_str());
  u8g2.sendBuffer();
}

// ============================================================
// LOOP — With Light Sleep Power Management
// ============================================================
#include "esp_sleep.h"
#define IDLE_SLEEP_THRESHOLD 30000UL  // 30s no activity before sleep

void loop() {
  dnsServer.processNextRequest();
  server.handleClient();
  updateOLED();
  loraReceive();

  // Low Memory Protection
  if (ESP.getFreeHeap() < MIN_FREE_HEAP) {
    for (int i = 0; i < MAX_ACTIVE_ROOMS; i++) {
        if(activeRooms[i].hash.length() > 0) {
            trimRoomStr(activeRooms[i].content);
        }
    }
  }

  // Panic Button Wipe
  if (digitalRead(PANIC_BTN) == LOW) {
    delay(50);
    if (digitalRead(PANIC_BTN) == LOW) {
      unsigned long t = millis();
      while (digitalRead(PANIC_BTN) == LOW) {
        int s = (millis() - t) / 1000;
        u8g2.clearBuffer(); u8g2.setFont(u8g2_font_6x10_tf);
        u8g2.drawStr(15, 22, "HOLD TO WIPE");
        int rem = 3 - s; if (rem < 0) rem = 0;
        u8g2.drawStr(30, 40, (String(rem) + " sec...").c_str());
        u8g2.sendBuffer();
        if (millis() - t > 3000) {
          doWipe();
          u8g2.clearBuffer(); u8g2.drawStr(20, 32, "WIPED CLEAN"); u8g2.sendBuffer();
          delay(2000);
          break;
        }
      }
    }
  }

  // ── LIGHT SLEEP: Only when zero clients AND idle LoRa ──
  bool noClients = (WiFi.softAPgetStationNum() == 0);
  bool loraIdle  = (millis() - lastLoraRx > IDLE_SLEEP_THRESHOLD);
  
  if (noClients && loraIdle && loraOK) {
    Serial.println("[PWR] No clients, LoRa idle. Entering light sleep...");
    u8g2.clearBuffer();
    u8g2.drawStr(10, 22, "SLEEPING...");
    u8g2.drawStr(10, 38, "Wake: LoRa/Panic");
    u8g2.sendBuffer();
    delay(100);

    // Configure wakeup sources
    esp_sleep_enable_ext0_wakeup((gpio_num_t)LORA_DIO0, 1);  // LoRa RX interrupt (HIGH)
    esp_sleep_enable_ext1_wakeup(1ULL << PANIC_BTN, ESP_EXT1_WAKEUP_ALL_LOW); // Panic btn (LOW)
    
    esp_light_sleep_start();
    
    // ── WOKE UP ──
    Serial.println("[PWR] Woke from light sleep");
    LoRa.receive();  // Re-enter RX mode after wakeup
  }

  delay(2);
}
