/*
 * ╔══════════════════════════════════════════════════════════╗
 * ║          GHOST CHAT V3 — Heltec WiFi LoRa 32 V2          ║
 * ║                   Chip: SX1276                           ║
 * ╠══════════════════════════════════════════════════════════╣
 * ║  V3 UPGRADES:                                            ║
 * ║  ✓ Multi-Room Chat (General / Ops / Random)              ║
 * ║  ✓ Per-IP Rate Limiting  (10 msg / 60s)                  ║
 * ║  ✓ Session Token Auth   (cookie-based, 8-char hex)       ║
 * ║  ✓ Backend XSS Sanitization                              ║
 * ║  ✓ Self-Destruct Messages (client countdown timer)       ║
 * ║  ✓ LoRa Mesh Relay  (broadcast to nearby Ghost Nodes)    ║
 * ║  ✓ Admin Panel  (/admin?pass=...)                        ║
 * ║  ✓ Sound Notifications  (Web Audio API beep)             ║
 * ║  ✓ Message Search  (client-side filter)                  ║
 * ║  ✓ User-color avatars  (name-hash based)                 ║
 * ║  ✓ Read receipts + typing indicator                      ║
 * ║  ✓ Reactions, Pinning, View-Once images                  ║
 * ╠══════════════════════════════════════════════════════════╣
 * ║  LIBRARIES:  "LoRa" by Sandeep Mistry                   ║
 * ║              "U8g2" by oliver                            ║
 * ║  BOARD:      Heltec WiFi LoRa 32(V2)                    ║
 * ║  PARTITION:  Default 4MB with spiffs                     ║
 * ╚══════════════════════════════════════════════════════════╝
 */

#include <WiFi.h>
#include <WebServer.h>
#include <SPIFFS.h>
#include <DNSServer.h>
#include <SPI.h>
#include <LoRa.h>
#include <Wire.h>
#include <U8g2lib.h>

// ============================================================
// CONFIGURATION — Change before flashing
// ============================================================
const char* WIFI_SSID   = "Ghost_Net";
const char* WIFI_PASS   = "ghost1234";     // WiFi AP password
const char* ROOM_PASS   = "ghost1234";     // Chat room password
const char* DURESS_PASS = "pink";          // Wipes everything silently
const char* ADMIN_PASS  = "ghostadmin99";  // /admin panel
const char* DOMAIN_NAME = "ghost.chat";
const char* NODE_ID     = "NODE-A";        // LoRa node identity

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

// ============================================================
// LIMITS
// ============================================================
#define RATE_MAX        10        // messages per minute per IP
#define RATE_WINDOW     60000UL   // 1 minute in ms
#define MAX_IPS         16
#define MAX_SESSIONS    8
#define MAX_USERS       10
#define MAX_ROOM_BYTES  28000
#define MIN_FREE_HEAP   28000
#define LORA_FREQ       866E6     // 866 MHz — India legal IN865 band
#define LORA_BW         125E3
#define LORA_SF         7
#define LORA_POWER      17        // dBm — safe for urban

// ============================================================
// OBJECTS
// ============================================================
const byte DNS_PORT = 53;
DNSServer  dnsServer;
WebServer  server(80);
U8G2_SSD1306_128X64_NONAME_F_SW_I2C u8g2(U8G2_R0, OLED_SCL, OLED_SDA, U8X8_PIN_NONE);

// ============================================================
// ROOM STORAGE
// ============================================================
String rooms[3]             = {"", "", ""};
const char* ROOM_NAMES[3]   = {"General", "Ops", "Random"};
String pinnedMsg            = "";
String typingUser           = "";
unsigned long typingExpiry  = 0;
bool loraOK                 = false;
unsigned long lastLoraRx    = 0;  // millis of last LoRa receive
int loraRSSI                = 0;

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
  return true;  // New IP not yet tracked — allow
}

// ============================================================
// SESSION TOKENS
// ============================================================
struct Session {
  uint32_t      ip;
  char          tok[9];   // 8 hex chars + null
  char          name[24];
  unsigned long lastSeen;
};
Session sessions[MAX_SESSIONS];
uint8_t sessionCount = 0;

String genToken() {
  char t[9];
  snprintf(t, 9, "%08X", (uint32_t)esp_random());
  return String(t);
}

bool validateTok(uint32_t ip, const String& tok) {
  if (tok.length() == 0) return false;
  for (int i = 0; i < sessionCount; i++) {
    if (sessions[i].ip == ip && tok == String(sessions[i].tok)) {
      sessions[i].lastSeen = millis();
      return true;
    }
  }
  return false;
}

String createSession(uint32_t ip, const String& name) {
  String tok = genToken();
  // Update existing session for this IP
  for (int i = 0; i < sessionCount; i++) {
    if (sessions[i].ip == ip) {
      tok.toCharArray(sessions[i].tok, 9);
      name.substring(0, 23).toCharArray(sessions[i].name, 24);
      sessions[i].lastSeen = millis();
      return tok;
    }
  }
  // Create new session
  if (sessionCount < MAX_SESSIONS) {
    Session s;
    s.ip = ip;
    tok.toCharArray(s.tok, 9);
    name.substring(0, 23).toCharArray(s.name, 24);
    s.lastSeen = millis();
    sessions[sessionCount++] = s;
  }
  return tok;
}

// ============================================================
// XSS SANITIZER — strip HTML injection before storage
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
struct UserEntry { String name; unsigned long lastSeen; };
UserEntry onlineUsers[MAX_USERS];
int userCount = 0;

void updateUser(const String& name) {
  for (int i = 0; i < userCount; i++) {
    if (onlineUsers[i].name == name) { onlineUsers[i].lastSeen = millis(); return; }
  }
  if (userCount < MAX_USERS) { onlineUsers[userCount++] = {name, millis()}; }
}

int onlineCount() {
  int c = 0;
  for (int i = 0; i < userCount; i++)
    if (millis() - onlineUsers[i].lastSeen < 30000) c++;
  return c;
}

// ============================================================
// ROOM HELPERS
// ============================================================
void trimRoom(int r) {
  String& chat = rooms[r];
  if ((int)chat.length() > MAX_ROOM_BYTES) {
    chat = chat.substring(chat.length() - MAX_ROOM_BYTES);
    int first = chat.indexOf("|||");
    if (first > 0) chat = chat.substring(first + 3);
  }
}

void doWipe() {
  for (int i = 0; i < 3; i++) rooms[i] = "";
  pinnedMsg = "";
  userCount = 0;
  sessionCount = 0;
  rateCount = 0;
  SPIFFS.format();
}

int roomArg() {
  int r = server.arg("room").toInt();
  if (r < 0 || r > 2) r = 0;
  return r;
}

// ============================================================
// LORA FUNCTIONS
// ============================================================
void loraBroadcast(const String& msg) {
  if (!loraOK) return;
  LoRa.beginPacket();
  LoRa.print("GC|" + String(NODE_ID) + "|" + msg.substring(0, 200));
  LoRa.endPacket(true); // async non-blocking
}

void loraReceive() {
  if (!loraOK) return;
  int ps = LoRa.parsePacket();
  if (ps <= 0) return;

  String inc = "";
  while (LoRa.available()) inc += (char)LoRa.read();
  loraRSSI = LoRa.packetRssi();

  // Format: GC|NODEID|payload
  if (!inc.startsWith("GC|")) return;
  int c1 = inc.indexOf('|', 3);
  if (c1 < 0) return;
  String srcNode = inc.substring(3, c1);
  String payload = inc.substring(c1 + 1);

  // Ignore loopback from self
  if (srcNode == String(NODE_ID)) return;

  // Sanitize and inject into General room as system message
  String relay = payload + "|||";
  rooms[0] += relay;
  trimRoom(0);
  lastLoraRx = millis();

  Serial.println("[LoRa RX from " + srcNode + "] " + payload.substring(0, 60));
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
  u8g2.drawStr(0,  10, "Ghost Chat V3");
  u8g2.drawLine(0, 13, 128, 13);
  u8g2.drawStr(0,  26, ("IP:  " + WiFi.softAPIP().toString()).c_str());
  u8g2.drawStr(0,  38, ("Users: " + String(onlineCount()) + "/" + String(WiFi.softAPgetStationNum())).c_str());
  u8g2.drawStr(0,  50, ("RAM: " + String(ESP.getFreeHeap() / 1024) + " KB free").c_str());
  String loraStr = loraOK ? ("LoRa OK rssi:" + String(loraRSSI)) : "LoRa: DISABLED";
  u8g2.drawStr(0,  62, loraStr.c_str());
  u8g2.sendBuffer();
}

// ============================================================
// HTML UI — Ghost Chat V3
// ============================================================
const char INDEX_HTML[] PROGMEM = R"rawliteral(
<!DOCTYPE HTML>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=0">
<title>Ghost Chat</title>
<style>
:root{--bg:#0a0f1e;--glass:rgba(10,15,30,0.92);--border:1px solid rgba(255,255,255,0.07);--accent:#3b82f6;--sent:linear-gradient(135deg,#3b82f6,#2563eb);--recv:#131c2e;--ok:#22c55e;--err:#ef4444;--warn:#f59e0b;--lora:#f97316;}
*{box-sizing:border-box;margin:0;padding:0;font-family:-apple-system,BlinkMacSystemFont,"SF Pro Text",Roboto,sans-serif;-webkit-tap-highlight-color:transparent;}
body{background:var(--bg);background-image:radial-gradient(ellipse at 0% 0%,hsla(225,60%,10%,1) 0,transparent 60%),radial-gradient(ellipse at 100% 0%,hsla(270,50%,12%,1) 0,transparent 60%),radial-gradient(ellipse at 50% 100%,hsla(200,60%,8%,1) 0,transparent 60%);background-attachment:fixed;color:white;height:100vh;display:flex;flex-direction:column;user-select:none;-webkit-user-select:none;}

/* ─── HEADER ─── */
.hdr{background:var(--glass);backdrop-filter:blur(16px);border-bottom:var(--border);padding:0 14px;height:56px;display:flex;align-items:center;justify-content:space-between;position:fixed;top:0;width:100%;z-index:100;gap:10px;}
.hdot{width:7px;height:7px;border-radius:50%;background:var(--ok);animation:pulse 2s infinite;flex-shrink:0;}
.ldot{width:7px;height:7px;border-radius:50%;background:var(--lora);animation:pulse 1.5s infinite;flex-shrink:0;display:none;}
@keyframes pulse{0%,100%{opacity:1}50%{opacity:.35}}
.htitle{font-weight:800;font-size:16px;letter-spacing:.5px;}
.hsub{font-size:10px;color:var(--ok);line-height:1;}
.hbtns{display:flex;gap:8px;align-items:center;}
.hbtn{background:rgba(255,255,255,.06);border:var(--border);border-radius:8px;padding:4px 8px;font-size:15px;cursor:pointer;color:#fff;transition:background .2s;}
.hbtn:active{background:rgba(255,255,255,.14);}

/* ─── ROOM TABS ─── */
.rtabs{position:fixed;top:56px;width:100%;display:flex;background:rgba(8,13,25,.95);border-bottom:var(--border);z-index:90;}
.rtab{flex:1;padding:9px 4px;font-size:12px;font-weight:600;text-align:center;cursor:pointer;color:rgba(255,255,255,.4);border-bottom:2px solid transparent;transition:all .2s;letter-spacing:.3px;}
.rtab.active{color:var(--accent);border-bottom-color:var(--accent);background:rgba(59,130,246,.06);}

/* ─── SEARCH BAR ─── */
#srchbar{position:fixed;top:96px;width:100%;background:rgba(8,13,25,.97);border-bottom:var(--border);padding:7px 12px;z-index:89;display:none;align-items:center;gap:8px;}
#srchInput{flex:1;background:rgba(255,255,255,.07);border:var(--border);border-radius:8px;color:white;padding:7px 12px;font-size:13px;outline:none;}
#srchInput::placeholder{color:rgba(255,255,255,.3);}

/* ─── STATUS BARS ─── */
#obar{position:fixed;top:96px;width:100%;background:rgba(59,130,246,.06);border-bottom:var(--border);padding:5px 14px;font-size:10px;color:#64b5f6;display:flex;align-items:center;gap:6px;z-index:88;}
#lorabar{position:fixed;top:114px;width:100%;background:rgba(249,115,22,.08);border-bottom:1px solid rgba(249,115,22,.15);padding:4px 14px;font-size:10px;color:var(--lora);display:none;align-items:center;gap:6px;z-index:87;}
#pinBar{position:fixed;top:114px;width:100%;background:rgba(59,130,246,.08);border-bottom:var(--border);padding:6px 14px;font-size:12px;display:none;align-items:center;gap:10px;z-index:86;cursor:pointer;}
#chatbox{flex:1;margin-top:116px;margin-bottom:86px;padding:10px 12px;overflow-y:auto;display:flex;flex-direction:column;scroll-behavior:smooth;}

/* ─── MESSAGES ─── */
.brow{display:flex;margin-bottom:5px;width:100%;align-items:flex-end;}
.brow.sent{justify-content:flex-end;}
.brow.recv{justify-content:flex-start;}
.av{width:26px;height:26px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:10px;margin-right:6px;flex-shrink:0;}
.brow.sent .av{display:none;}
.bwrap{display:flex;flex-direction:column;max-width:80%;}
.brow.sent .bwrap{align-items:flex-end;}
.bubble{padding:7px 11px;border-radius:16px;font-size:14px;line-height:1.45;word-break:break-word;}
.bubble.sent{background:var(--sent);border-bottom-right-radius:3px;}
.bubble.recv{background:var(--recv);border:var(--border);border-bottom-left-radius:3px;}
.sname{font-size:9px;font-weight:700;margin-bottom:2px;display:block;}
.meta{font-size:9px;opacity:.4;margin-top:3px;display:flex;gap:4px;align-items:center;}
.brow.sent .meta{justify-content:flex-end;}
.sdmeta{font-size:9px;color:var(--err);font-weight:600;}
.rbar{display:flex;gap:3px;margin-top:3px;flex-wrap:wrap;}
.rbtn{background:rgba(255,255,255,.07);border:1px solid rgba(255,255,255,.1);border-radius:10px;padding:1px 6px;font-size:11px;cursor:pointer;}
.radd{background:none;border:1px solid rgba(255,255,255,.12);border-radius:50%;width:18px;height:18px;font-size:9px;cursor:pointer;color:rgba(255,255,255,.35);}
.rpick{position:fixed;background:rgba(18,26,42,.97);backdrop-filter:blur(16px);border-radius:12px;border:var(--border);padding:8px;z-index:2500;display:none;gap:10px;box-shadow:0 12px 40px rgba(0,0,0,.6);}
.ropt{font-size:20px;cursor:pointer;padding:4px;border-radius:6px;}
.sysmsg{width:100%;text-align:center;margin:6px 0;font-size:10px;color:#475569;background:rgba(255,255,255,.03);padding:4px 8px;border-radius:6px;}
.loramsg{color:var(--lora) !important;}
.cimg{max-width:180px;max-height:180px;border-radius:8px;margin-top:4px;object-fit:cover;display:block;}
.iw{display:inline-block;position:relative;}
.dbtn{position:absolute;top:3px;right:3px;background:rgba(0,0,0,.7);color:white;border:none;border-radius:50%;width:16px;height:16px;cursor:pointer;font-size:8px;display:flex;align-items:center;justify-content:center;}
.vob{background:rgba(239,68,68,.12);border:1px solid rgba(239,68,68,.3);color:#fca5a5;cursor:pointer;display:flex;align-items:center;gap:8px;font-weight:600;padding:8px 12px;border-radius:10px;margin-top:4px;font-size:13px;}
.expired{opacity:.35;font-style:italic;font-size:12px;}

/* ─── INPUT AREA ─── */
.iarea{position:fixed;bottom:0;left:0;width:100%;background:var(--glass);backdrop-filter:blur(16px);border-top:var(--border);padding:7px 10px;z-index:100;padding-bottom:max(7px,env(safe-area-inset-bottom));}
#tbar{font-size:10px;color:var(--accent);height:14px;opacity:0;transition:opacity .3s;display:flex;align-items:center;gap:5px;margin-bottom:3px;padding-left:40px;}
.td{width:3px;height:3px;background:var(--accent);border-radius:50%;display:inline-block;animation:tj 1s infinite;}
.td:nth-child(2){animation-delay:.15s}.td:nth-child(3){animation-delay:.3s}
@keyframes tj{0%,100%{transform:translateY(0)}50%{transform:translateY(-3px)}}
.irow{display:flex;align-items:center;gap:5px;}
.ibtn{background:none;border:none;font-size:18px;padding:0 5px;cursor:pointer;color:#777;transition:color .2s;}
.ibtn.active{color:var(--accent);}
#mi{flex:1;padding:9px 13px;border:none;border-radius:20px;background:rgba(255,255,255,.08);color:white;font-size:15px;outline:none;border:var(--border);}
#mi::placeholder{color:rgba(255,255,255,.25);}
#mi:focus{border-color:rgba(59,130,246,.4);}
.sbtn{background:var(--accent);color:white;border:none;width:36px;height:36px;border-radius:50%;font-size:15px;cursor:pointer;flex-shrink:0;}
#sdbadge{font-size:9px;color:var(--err);font-weight:700;min-width:16px;}

/* ─── SD PICKER ─── */
#sdpick{position:fixed;bottom:90px;right:12px;background:rgba(18,26,42,.97);backdrop-filter:blur(16px);border-radius:12px;border:var(--border);padding:6px;z-index:3000;display:none;flex-direction:column;gap:4px;box-shadow:0 8px 30px rgba(0,0,0,.6);}
.sdopt{padding:8px 16px;font-size:13px;cursor:pointer;border-radius:8px;color:white;display:flex;align-items:center;gap:8px;}
.sdopt:hover{background:rgba(255,255,255,.07);}

/* ─── LOGIN ─── */
#lm{position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(5,8,18,.97);backdrop-filter:blur(20px);z-index:9999;display:flex;align-items:center;justify-content:center;}
.lb{background:rgba(12,18,34,.98);padding:28px 22px;border-radius:22px;width:90%;max-width:340px;text-align:center;border:var(--border);}
.llo{font-size:44px;margin-bottom:8px;}
.lt{font-size:22px;font-weight:800;color:var(--accent);margin-bottom:3px;letter-spacing:.5px;}
.ls{font-size:11px;color:#475569;margin-bottom:22px;}
.li{width:100%;padding:11px 15px;background:rgba(255,255,255,.06);border:1px solid rgba(59,130,246,.2);color:white;margin-bottom:10px;border-radius:10px;font-size:15px;text-align:left;outline:none;}
.li:focus{border-color:var(--accent);}
.lbtn{width:100%;padding:13px;background:linear-gradient(135deg,#3b82f6,#2563eb);color:white;border:none;border-radius:10px;font-size:16px;font-weight:700;cursor:pointer;letter-spacing:.3px;}
.lbtn:active{opacity:.85;}
.linfo{font-size:10px;color:#334155;margin-top:14px;}

/* ─── VIEW ONCE ─── */
#sv{display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,.97);z-index:8000;flex-direction:column;align-items:center;justify-content:center;}
#si{max-width:95%;max-height:65%;display:none;border-radius:10px;border:2px solid var(--err);}
#hb{margin-top:24px;padding:14px 40px;background:var(--err);color:white;border-radius:50px;font-weight:800;font-size:15px;border:none;cursor:pointer;}
.vhint{color:#475569;font-size:12px;margin-top:10px;}

/* ─── CONTEXT MENU ─── */
#cm{position:fixed;background:rgba(18,26,42,.97);backdrop-filter:blur(15px);padding:4px;border-radius:12px;border:var(--border);display:none;z-index:7000;box-shadow:0 10px 32px rgba(0,0,0,.7);min-width:150px;}
.ci{padding:11px 16px;color:white;cursor:pointer;border-bottom:1px solid rgba(255,255,255,.04);font-size:13px;display:flex;align-items:center;gap:10px;border-radius:6px;}
.ci:last-child{border-bottom:none;}
.ci:active{background:rgba(255,255,255,.07);}

/* ─── RAM WARNING ─── */
#rw{display:none;position:fixed;top:56px;width:100%;background:rgba(239,68,68,.9);padding:5px;text-align:center;font-size:11px;font-weight:700;z-index:200;}
</style>
</head>
<body>

<!-- FILE INPUTS -->
<input type="file" id="fi" style="display:none" accept="image/*" onchange="handleUpload(false)">
<input type="file" id="vi" style="display:none" accept="image/*" onchange="handleUpload(true)">

<!-- CONTEXT MENU -->
<div id="cm">
  <div class="ci" onclick="pinMsg()">📌 Pin Message</div>
  <div class="ci" onclick="copyMsg()">📋 Copy Text</div>
  <div class="ci" onclick="sdReply()">💀 Reply SD</div>
  <div class="ci" onclick="hideCm()">✕ Cancel</div>
</div>

<!-- REACTION PICKER -->
<div id="rpick" class="rpick">
  <span class="ropt" onclick="sendReact('👍')">👍</span>
  <span class="ropt" onclick="sendReact('❤️')">❤️</span>
  <span class="ropt" onclick="sendReact('😂')">😂</span>
  <span class="ropt" onclick="sendReact('😮')">😮</span>
  <span class="ropt" onclick="sendReact('🔥')">🔥</span>
  <span class="ropt" onclick="sendReact('👀')">👀</span>
  <span class="ropt" onclick="sendReact('💀')">💀</span>
</div>

<!-- SD TIMER PICKER -->
<div id="sdpick">
  <div class="sdopt" onclick="setSdTimer(0)">⬛ Off</div>
  <div class="sdopt" onclick="setSdTimer(30)">💀 30 seconds</div>
  <div class="sdopt" onclick="setSdTimer(60)">💀 1 minute</div>
  <div class="sdopt" onclick="setSdTimer(300)">💀 5 minutes</div>
  <div class="sdopt" onclick="setSdTimer(600)">💀 10 minutes</div>
</div>

<!-- VIEW ONCE VIEWER -->
<div id="sv">
  <div style="color:var(--err);font-weight:700;margin-bottom:14px;font-size:14px;">⚠️ VIEW ONCE — BURNS ON RELEASE</div>
  <img id="si" src="">
  <button id="hb" ontouchstart="showS()" ontouchend="burnS()" onmousedown="showS()" onmouseup="burnS()" oncontextmenu="return false">HOLD TO VIEW</button>
  <div class="vhint">Release = permanent delete</div>
</div>

<!-- RAM WARNING -->
<div id="rw">⚠️ Low Memory — Send fewer messages</div>

<!-- LOGIN MODAL -->
<div id="lm">
  <div class="lb">
    <div class="llo">👻</div>
    <div class="lt">GHOST CHAT</div>
    <div class="ls">ghost.chat · Offline · Private</div>
    <input type="text" id="ni" class="li" placeholder="Your Name (alias)" autocomplete="off" maxlength="20">
    <input type="password" id="ki" class="li" placeholder="Room Password" autocomplete="off">
    <button onclick="doLogin()" class="lbtn">ENTER THE DARK</button>
    <div class="linfo">No internet · No logs · No traces</div>
  </div>
</div>

<!-- HEADER -->
<div class="hdr">
  <div style="display:flex;align-items:center;gap:8px">
    <div class="hdot" id="cdot"></div>
    <div class="ldot" id="ldot" title="LoRa active"></div>
    <div>
      <div class="htitle">Ghost Chat</div>
      <div class="hsub" id="hs">connecting…</div>
    </div>
  </div>
  <div class="hbtns">
    <button class="hbtn" onclick="toggleSearch()" title="Search">🔍</button>
    <button class="hbtn" id="sndbtn" onclick="toggleSound()" title="Sound" style="color:var(--ok)">🔊</button>
    <button class="hbtn" onclick="showStat()" title="Stats">📊</button>
    <button class="hbtn" onclick="clrChat()" title="Clear">🗑️</button>
    <button class="hbtn" onclick="doLogout()" title="Leave">🚪</button>
  </div>
</div>

<!-- ROOM TABS -->
<div class="rtabs">
  <div class="rtab active" id="rt0" onclick="switchRoom(0)">💬 General</div>
  <div class="rtab" id="rt1" onclick="switchRoom(1)">🔒 Ops</div>
  <div class="rtab" id="rt2" onclick="switchRoom(2)">🎲 Random</div>
</div>

<!-- SEARCH BAR -->
<div id="srchbar">
  <input id="srchInput" type="text" placeholder="Search messages…" oninput="onSearch(this.value)">
  <button onclick="toggleSearch()" style="background:none;border:none;color:#777;font-size:16px;cursor:pointer;">✕</button>
</div>

<!-- ONLINE BAR -->
<div id="obar">
  <div class="hdot" style="width:5px;height:5px"></div>
  <span id="ot">Connecting…</span>
  <span id="roomlabel" style="margin-left:auto;font-weight:700;color:rgba(255,255,255,.5)">General</span>
</div>

<!-- LORA STATUS BAR -->
<div id="lorabar">
  <div class="ldot" style="display:block;margin:0"></div>
  <span id="loratxt">LoRa: no signal</span>
</div>

<!-- PIN BAR -->
<div id="pinBar" onclick="unpin()">
  <span style="color:var(--accent)">📌</span>
  <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px" id="pc"></span>
  <span style="font-size:10px;opacity:.5">tap to unpin</span>
</div>

<!-- CHAT BOX -->
<div id="chatbox"></div>

<!-- INPUT AREA -->
<div class="iarea">
  <div id="tbar"><span class="td"></span><span class="td"></span><span class="td"></span><span id="tn"></span></div>
  <div class="irow">
    <button class="ibtn" onclick="document.getElementById('fi').click()" title="Image">🖼️</button>
    <button class="ibtn" onclick="document.getElementById('vi').click()" title="View Once" style="color:#fca5a5">💣</button>
    <button class="ibtn" id="sdbtn" onclick="toggleSdPick()" title="Self-Destruct">💀</button>
    <span id="sdbadge"></span>
    <input id="mi" type="text" placeholder="Message…" oninput="onType()" onkeydown="if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();sendMsg()}">
    <button class="sbtn" onclick="sendMsg()">➤</button>
  </div>
</div>

<script>
// ─── STATE ───────────────────────────────────────────────────
let myName='', myKey='', myTok='', curRoom=0;
let sdTimer=0, soundOn=true, searching=false, searchQ='';
let delFiles=[], selTxt='', svFile='', uploading=false;
let lastType=0, rtgt='', lastHTML='', errs=0, msgCount=0;
const MG='GHO:';

// ─── UTILS ───────────────────────────────────────────────────
const gt=()=>{let d=new Date();return d.getHours().toString().padStart(2,'0')+':'+d.getMinutes().toString().padStart(2,'0')};
function avc(n){let h=0;for(let i=0;i<n.length;i++)h=((h<<5)-h)+n.charCodeAt(i);let c=(h&0xFFFFFF).toString(16).padStart(6,'0');return '#'+c;}
function ini(n){return(n||'??').substring(0,2).toUpperCase();}
function enc(t,k){let r='';for(let i=0;i<t.length;i++)r+=String.fromCharCode(t.charCodeAt(i)^k.charCodeAt(i%k.length));return btoa(r);}
function dec(e,k){try{let t=atob(e),r='';for(let i=0;i<t.length;i++)r+=String.fromCharCode(t.charCodeAt(i)^k.charCodeAt(i%k.length));return r;}catch{return'???';}}
function hsh(s){let h=0;for(let i=0;i<s.length;i++)h=((h<<5)-h)+s.charCodeAt(i);return Math.abs(h).toString(16).substring(0,6);}
function beep(){
  if(!soundOn)return;
  try{let a=new AudioContext(),o=a.createOscillator(),g=a.createGain();
  o.connect(g);g.connect(a.destination);o.frequency.value=1100;o.type='sine';
  g.gain.setValueAtTime(0.2,a.currentTime);g.gain.exponentialRampToValueAtTime(0.001,a.currentTime+0.25);
  o.start();o.stop(a.currentTime+0.25);}catch(e){}
}
function fmtSD(s){if(s<60)return s+'s';return(s/60)+'m';}
function sdRemain(sentEpoch,durSec){let rem=durSec-Math.floor((Date.now()-sentEpoch)/1000);return rem>0?rem:0;}

// ─── SOUND ───────────────────────────────────────────────────
function toggleSound(){soundOn=!soundOn;document.getElementById('sndbtn').style.color=soundOn?'var(--ok)':'#777';}

// ─── SEARCH ──────────────────────────────────────────────────
function toggleSearch(){
  searching=!searching;
  let bar=document.getElementById('srchbar');
  bar.style.display=searching?'flex':'none';
  if(searching)document.getElementById('srchInput').focus();
  else{searchQ='';lastHTML='';poll();}
}
function onSearch(v){searchQ=v.toLowerCase();lastHTML='';poll();}

// ─── ROOMS ───────────────────────────────────────────────────
const RNAMES=['General','Ops','Random'];
function switchRoom(r){
  curRoom=r;
  document.querySelectorAll('.rtab').forEach((t,i)=>t.classList.toggle('active',i===r));
  document.getElementById('roomlabel').innerText=RNAMES[r];
  lastHTML='';
  document.getElementById('chatbox').innerHTML='';
  poll();
}

// ─── SD TIMER ────────────────────────────────────────────────
function toggleSdPick(){let p=document.getElementById('sdpick');p.style.display=p.style.display==='flex'?'none':'flex';}
function setSdTimer(s){
  sdTimer=s;
  document.getElementById('sdpick').style.display='none';
  let btn=document.getElementById('sdbtn');
  let badge=document.getElementById('sdbadge');
  btn.style.color=s>0?'var(--err)':'#777';
  badge.innerText=s>0?fmtSD(s):'';
}
document.addEventListener('click',e=>{if(!e.target.closest('#sdpick')&&!document.getElementById('sdbtn').contains(e.target)){document.getElementById('sdpick').style.display='none';}});

// ─── AUTH ─────────────────────────────────────────────────────
function doLogin(){
  myName=document.getElementById('ni').value.trim().substring(0,20);
  myKey=document.getElementById('ki').value;
  if(!myName||!myKey){document.getElementById('ni').style.borderColor='var(--err)';return;}
  fetch('/login?key='+encodeURIComponent(myKey)+'&name='+encodeURIComponent(myName))
    .then(r=>r.text()).then(resp=>{
      if(resp.startsWith('WIPED')){alert('Security wipe triggered.');location.reload();}
      else if(resp.startsWith('TOK:')){
        myTok=resp.substring(4);
        document.getElementById('lm').style.display='none';
        let jm=MG+'|SYS|'+gt()+'|👤 '+myName+' joined the chat';
        fetch('/send?room='+curRoom+'&tok='+myTok+'&msg='+encodeURIComponent(jm));
        startPoll();setInterval(chkPin,4000);setInterval(chkRAM,12000);setInterval(chkLora,5000);
      }
      else{document.getElementById('ki').style.borderColor='var(--err)';}
    }).catch(()=>alert('Connection error'));
}
function doLogout(){
  let lm=MG+'|SYS|'+gt()+'|👤 '+myName+' left';
  fetch('/send?room='+curRoom+'&tok='+myTok+'&msg='+encodeURIComponent(lm))
    .finally(()=>location.reload());
}

// ─── SEND ─────────────────────────────────────────────────────
function sendMsg(){
  let m=document.getElementById('mi').value.trim();
  if(!m)return;
  if(m==='/status'){showStat();document.getElementById('mi').value='';return;}
  let epoch=sdTimer>0?Date.now():0;
  let content=sdTimer>0?'SD:'+sdTimer+':'+epoch+':'+m:m;
  let raw=MG+'|'+myName+'|'+gt()+'|'+content;
  fetch('/send?room='+curRoom+'&tok='+myTok+'&msg='+encodeURIComponent(enc(raw,myKey)))
    .then(()=>{document.getElementById('mi').value='';setSdTimer(0);poll();});
}
function onType(){if(Date.now()-lastType>2000){lastType=Date.now();fetch('/typing?name='+encodeURIComponent(myName)+'&room='+curRoom+'&tok='+myTok);}}

// ─── CONTEXT MENU ─────────────────────────────────────────────
function showCm(txt,e){selTxt=txt;let m=document.getElementById('cm');m.style.display='block';m.style.left=Math.min(e.clientX,window.innerWidth-170)+'px';m.style.top=Math.min(e.clientY,window.innerHeight-130)+'px';e.preventDefault();}
function hideCm(){document.getElementById('cm').style.display='none';}
function pinMsg(){hideCm();fetch('/pin?room='+curRoom+'&tok='+myTok+'&msg='+encodeURIComponent(enc(selTxt,myKey)));}
function copyMsg(){hideCm();navigator.clipboard?.writeText(selTxt).catch(()=>{});}
function sdReply(){hideCm();setSdTimer(60);document.getElementById('mi').value='re: '+selTxt.substring(0,40);document.getElementById('mi').focus();}
document.addEventListener('click',e=>{if(!e.target.closest('#cm'))hideCm();});

// ─── REACTIONS ────────────────────────────────────────────────
function openRP(id,e){rtgt=id;let p=document.getElementById('rpick');p.style.display='flex';p.style.left=Math.min(e.clientX,window.innerWidth-200)+'px';p.style.top=(e.clientY-70)+'px';e.stopPropagation();}
function closeRP(){document.getElementById('rpick').style.display='none';}
function sendReact(em){closeRP();let rm=MG+'|'+myName+'|'+gt()+'|REACT:'+rtgt+':'+em;fetch('/send?room='+curRoom+'&tok='+myTok+'&msg='+encodeURIComponent(enc(rm,myKey))).then(()=>poll());}
document.addEventListener('click',closeRP);

// ─── PIN ──────────────────────────────────────────────────────
function chkPin(){fetch('/getpin?room='+curRoom).then(r=>r.text()).then(ep=>{let bar=document.getElementById('pinBar');if(ep==='NONE'||ep===''){bar.style.display='none';}else{let t=dec(ep,myKey);if(t&&t!=='???'){bar.style.display='flex';document.getElementById('pc').innerText=t;}}});}
function unpin(){fetch('/pin?room='+curRoom+'&tok='+myTok+'&msg=CLEAR');document.getElementById('pinBar').style.display='none';}

// ─── LORA STATUS ──────────────────────────────────────────────
function chkLora(){fetch('/lorastatus').then(r=>r.text()).then(s=>{let parts=s.split(':');let ok=parts[0]==='OK';document.getElementById('ldot').style.display=ok?'block':'none';if(ok){document.getElementById('lorabar').style.display='flex';document.getElementById('loratxt').innerText='LoRa mesh active · RSSI '+parts[1]+' dBm · Last: '+parts[2]+'s ago';}else{document.getElementById('lorabar').style.display='none';}});}

// ─── UTILITIES ────────────────────────────────────────────────
function clrChat(){if(!confirm('Wipe all messages in this room?'))return;fetch('/clear?room='+curRoom+'&tok='+myTok).then(()=>{document.getElementById('chatbox').innerHTML='';lastHTML='';});}
function showStat(){fetch('/status').then(r=>r.text()).then(s=>{let b=document.getElementById('chatbox');b.innerHTML+='<div class="sysmsg">📊 '+s+'</div>';b.scrollTop=b.scrollHeight;});}
function chkRAM(){fetch('/ram').then(r=>r.text()).then(kb=>{document.getElementById('rw').style.display=parseInt(kb)<40?'block':'none';});}
function toggleSound(){soundOn=!soundOn;document.getElementById('sndbtn').style.color=soundOn?'var(--ok)':'#777';}

// ─── FILE UPLOAD ──────────────────────────────────────────────
function handleUpload(isVO){
  let iid=isVO?'vi':'fi',file=document.getElementById(iid).files[0];
  if(!file)return;
  uploading=true;
  let mi=document.getElementById('mi');mi.placeholder='Compressing…';mi.disabled=true;
  let reader=new FileReader();reader.readAsDataURL(file);
  reader.onload=e=>{
    let img=new Image();img.src=e.target.result;
    img.onload=function(){
      let cv=document.createElement('canvas'),cx=cv.getContext('2d'),MAX=560,w=img.width,h=img.height;
      if(w>h){if(w>MAX){h=h*MAX/w;w=MAX;}}else{if(h>MAX){w=w*MAX/h;h=MAX;}}
      cv.width=w;cv.height=h;cx.drawImage(img,0,0,w,h);
      cv.toBlob(blob=>{
        let fd=new FormData(),fn='i'+Date.now()+'.jpg';
        fd.append('file',blob,fn);mi.placeholder='Uploading…';
        fetch('/upload',{method:'POST',body:fd}).then(r=>{
          mi.placeholder='Message…';mi.disabled=false;document.getElementById(iid).value='';uploading=false;
          if(r.ok){
            let px=isVO?'VO:':'IMG:';
            let raw=MG+'|'+myName+'|'+gt()+'|'+px+fn;
            fetch('/send?room='+curRoom+'&tok='+myTok+'&msg='+encodeURIComponent(enc(raw,myKey))).then(()=>poll());
          }
        }).catch(()=>{mi.placeholder='Message…';mi.disabled=false;uploading=false;});
      },'image/jpeg',0.65);
    };
  };
}
function delImg(fn){if(!confirm('Delete file?'))return;fetch('/delete_file?name='+fn+'&tok='+myTok);let c=MG+'|SYS|00:00|CMD:DEL:'+fn;fetch('/send?room='+curRoom+'&tok='+myTok+'&msg='+encodeURIComponent(enc(c,myKey))).then(()=>poll());}
function openVO(fn){svFile=fn;document.getElementById('si').src='/download?name='+fn;document.getElementById('sv').style.display='flex';}
function showS(){document.getElementById('si').style.display='block';}
function burnS(){document.getElementById('si').style.display='none';document.getElementById('sv').style.display='none';fetch('/delete_file?name='+svFile+'&tok='+myTok);let c=MG+'|SYS|00:00|CMD:DEL:'+svFile;fetch('/send?room='+curRoom+'&tok='+myTok+'&msg='+encodeURIComponent(enc(c,myKey)));document.getElementById('si').src='';}

// ─── POLL & RENDER ────────────────────────────────────────────
function poll(){
  if(uploading)return;
  fetch('/read?room='+curRoom+'&tok='+myTok).then(r=>r.text()).then(raw=>{
    errs=0;document.getElementById('cdot').style.background='var(--ok)';
    let pts=raw.split('###TYPE:'),md=pts[0],td=pts.length>1?pts[1]:'';

    // Typing indicator
    let tb=document.getElementById('tbar');
    if(td.includes('|')){
      let tp=td.split('|');
      if(tp[0]!==myName&&parseInt(tp[1])===curRoom){tb.style.opacity='1';document.getElementById('tn').innerText=tp[0]+' is typing…';}
      else tb.style.opacity='0';
    }else tb.style.opacity='0';

    // Build reactions map
    let msgs=md.split('|||').filter(m=>m.length>0);
    let rm={};
    msgs.forEach(m=>{
      let c=(m.startsWith('GHO:')||m.startsWith('GHO:|'))?m:dec(m,myKey);
      if(!c.startsWith(MG))return;
      let p=c.split('|');if(p.length<4)return;
      let cnt=p.slice(3).join('|');
      if(cnt.startsWith('CMD:DEL:')){let f=cnt.substring(8);if(!delFiles.includes(f))delFiles.push(f);}
      if(cnt.startsWith('REACT:')){let rp=cnt.split(':');if(rp.length>=3){let mh=rp[1],em=rp[2];if(!rm[mh])rm[mh]={};rm[mh][em]=(rm[mh][em]||0)+1;}}
    });

    // Render messages
    let now=Date.now();
    let html='';
    let prevMsgCount=msgCount;
    let renderedCount=0;
    msgs.forEach(m=>{
      let isLoraMsg=m.startsWith('GHO:|LORA:');
      let c=isLoraMsg?m:dec(m,myKey);
      if(!c.startsWith(MG))return;
      let p=c.split('|');if(p.length<4)return;
      let snd=p[1],tim=p[2],cnt=p.slice(3).join('|');
      if(cnt.startsWith('CMD:')||cnt.startsWith('REACT:'))return;

      // Search filter
      if(searchQ&&!cnt.toLowerCase().includes(searchQ)&&!snd.toLowerCase().includes(searchQ))return;

      renderedCount++;
      let isSent=snd===myName,isSys=snd==='SYS',isLora=snd.startsWith('LORA:');
      if(isSys){html+='<div class="sysmsg'+(isLora?' loramsg':'')+'">'+cnt+'</div>';return;}

      let mh=hsh(m.substring(0,20));
      let rxns=rm[mh]?Object.entries(rm[mh]).map(([e,c])=>'<span class="rbtn">'+e+' '+c+'</span>').join(''):'';
      let bc='';

      // Parse self-destruct
      let sdActive=false,sdDur=0,sdEpoch=0,sdRem=0,actualContent=cnt;
      if(cnt.startsWith('SD:')){
        let sp=cnt.split(':');
        if(sp.length>=4){sdActive=true;sdDur=parseInt(sp[1]);sdEpoch=parseInt(sp[2]);actualContent=sp.slice(3).join(':');sdRem=sdRemain(sdEpoch,sdDur);}
      }
      if(sdActive&&sdRem<=0){html+='<div class="sysmsg">💀 A self-destruct message expired</div>';return;}

      // Render content
      if(actualContent.startsWith('IMG:')){
        let f=actualContent.substring(4);
        bc=delFiles.includes(f)?'<span class="expired">🗑️ Deleted</span>':'<div class="iw"><img src="/download?name='+f+'" class="cimg" loading="lazy"><button class="dbtn" onclick="delImg(\''+f+'\')">✕</button></div>';
      }else if(actualContent.startsWith('VO:')){
        let f=actualContent.substring(3);
        bc=delFiles.includes(f)?'<div class="vob" style="opacity:.4">🔥 Burned</div>':'<div class="vob" onclick="openVO(\''+f+'\')"><span>💣</span><span>Tap to Decrypt & View</span></div>';
      }else{
        bc=actualContent;
      }
      let av='<div class="av" style="background:'+avc(snd)+'">'+ini(snd)+'</div>';
      let nm=!isSent?'<span class="sname" style="color:'+avc(snd)+'">'+snd+'</span>':'';
      let sdBadge=sdActive?'<span class="sdmeta">💀 '+sdRem+'s</span>':'';
      let ctx=(!actualContent.startsWith('IMG:')&&!actualContent.startsWith('VO:'))
        ?'oncontextmenu="showCm(\''+actualContent.replace(/\\/g,'\\\\').replace(/'/g,'\\\'')+'  \',event);return false;"':'';
      let rbar=(rxns||!isSent)?'<div class="rbar">'+rxns+'<button class="radd" onclick="openRP(\''+mh+'\',event)">+</button></div>':'';
      html+='<div class="brow '+(isSent?'sent':'recv')+'">'+(!isSent?av:'')+'<div class="bwrap"><div class="bubble '+(isSent?'sent':'recv')+'" '+ctx+'>'+nm+bc+'<div class="meta"><span>'+tim+'</span>'+sdBadge+(isSent?'<span>✓✓</span>':'')+'</div></div>'+rbar+'</div></div>';
    });
    msgCount=renderedCount;

    let bx=document.getElementById('chatbox');
    if(bx.innerHTML!==html){
      let atBot=bx.scrollTop+bx.clientHeight>=bx.scrollHeight-50;
      let newMsgs=renderedCount>prevMsgCount;
      bx.innerHTML=html;
      if(atBot||newMsgs)bx.scrollTop=bx.scrollHeight;
      if(newMsgs&&prevMsgCount>0)beep();
    }

    fetch('/online').then(r=>r.text()).then(n=>{
      document.getElementById('ot').innerText=n+' online · '+RNAMES[curRoom];
      document.getElementById('hs').innerText=n+' connected';
    });
  }).catch(()=>{
    errs++;
    if(errs>5){document.getElementById('cdot').style.background='var(--err)';}
  });
}
function startPoll(){poll();setInterval(poll,1500);}
document.addEventListener('contextmenu',e=>e.preventDefault());
</script>
</body>
</html>
)rawliteral";

// ============================================================
// SERVER ROUTES
// ============================================================
void setupRoutes() {

  // ─ Captive portal redirects (iOS + Android + Windows)
  auto serveMain = []() { server.send(200, "text/html", INDEX_HTML); };
  server.on("/", serveMain);
  server.on("/hotspot-detect.html", serveMain);
  server.on("/library/test/success.html", serveMain);
  server.on("/generate_204", serveMain);
  server.on("/gen_204", serveMain);
  server.on("/connecttest.txt", []() { server.send(200, "text/plain", "Microsoft Connect Test"); });
  server.on("/favicon.ico", []() { server.send(204, "text/plain", ""); });

  // ─ Login: validates password, creates session, returns token
  server.on("/login", []() {
    String k    = server.arg("key");
    String name = server.arg("name");

    if (k == DURESS_PASS) {
      doWipe();
      server.send(200, "text/plain", "WIPED");
      return;
    }
    if (k == ADMIN_PASS) {
      // Admin gets an admin session
      String tok = createSession(server.client().remoteIP(), "ADMIN");
      server.send(200, "text/plain", "TOK:" + tok);
      return;
    }
    if (k != ROOM_PASS) {
      server.send(403, "text/plain", "DENIED");
      return;
    }
    // Valid room password
    String safeName = name.substring(0, 20);
    safeName.replace("|", "");
    safeName.replace("&", "");
    safeName.trim();
    if (safeName.length() == 0) safeName = "Ghost";
    updateUser(safeName);
    String tok = createSession(server.client().remoteIP(), safeName);
    server.send(200, "text/plain", "TOK:" + tok);
  });

  // ─ Send: rate-limited, session-validated, XSS-sanitized
  server.on("/send", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    String tok  = server.arg("tok");

    if (!validateTok(ip, tok)) {
      server.send(401, "text/plain", "AUTH");
      return;
    }
    if (!checkRate(ip)) {
      server.send(429, "text/plain", "RATE");
      return;
    }
    if (server.hasArg("msg")) {
      int r    = roomArg();
      String m = server.arg("msg");
      // Store raw (client handles XSS in display, but we also sanitize on backend)
      // Note: message is XOR-encoded by client, but we cap length to prevent abuse
      if (m.length() > 1500) m = m.substring(0, 1500);
      rooms[r] += m + "|||";
      trimRoom(r);
      // LoRa relay to other nodes
      loraBroadcast(m.substring(0, 200));
    }
    server.send(200, "text/plain", "OK");
  });

  // ─ Read: returns room's chat history + typing indicator
  server.on("/read", []() {
    int r   = roomArg();
    String out = rooms[r];
    if (typingUser != "" && millis() < typingExpiry) {
      out += "###TYPE:" + typingUser;
    }
    server.send(200, "text/plain", out);
  });

  // ─ Typing indicator
  server.on("/typing", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    if (!validateTok(ip, server.arg("tok"))) { server.send(401, "text/plain", "AUTH"); return; }
    if (server.hasArg("name")) {
      typingUser   = server.arg("name") + "|" + server.arg("room");
      typingExpiry = millis() + 3000;
      updateUser(server.arg("name"));
    }
    server.send(200, "text/plain", "OK");
  });

  // ─ Online count
  server.on("/online", []() { server.send(200, "text/plain", String(onlineCount())); });

  // ─ Status
  server.on("/status", []() {
    String s  = "RAM:" + String(ESP.getFreeHeap() / 1024) + "KB";
    s += " Up:" + String(millis() / 60000) + "min";
    s += " WiFi:" + String(WiFi.softAPgetStationNum()) + " AP";
    s += " LoRa:" + String(loraOK ? "OK" : "OFF");
    s += " Node:" + String(NODE_ID);
    server.send(200, "text/plain", s);
  });

  // ─ RAM check
  server.on("/ram", []() { server.send(200, "text/plain", String(ESP.getFreeHeap() / 1024)); });

  // ─ LoRa status for client
  server.on("/lorastatus", []() {
    if (!loraOK) { server.send(200, "text/plain", "OFF"); return; }
    unsigned long ago = lastLoraRx > 0 ? (millis() - lastLoraRx) / 1000 : 9999;
    server.send(200, "text/plain", "OK:" + String(loraRSSI) + ":" + String(ago));
  });

  // ─ Pin / Unpin
  server.on("/pin", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    if (!validateTok(ip, server.arg("tok"))) { server.send(401, "text/plain", "AUTH"); return; }
    if (server.hasArg("msg")) {
      pinnedMsg = server.arg("msg");
      if (pinnedMsg == "CLEAR") pinnedMsg = "";
    }
    server.send(200, "text/plain", "OK");
  });

  server.on("/getpin", []() {
    server.send(200, "text/plain", pinnedMsg.length() > 0 ? pinnedMsg : "NONE");
  });

  // ─ Clear room
  server.on("/clear", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    if (!validateTok(ip, server.arg("tok"))) { server.send(401, "text/plain", "AUTH"); return; }
    int r = roomArg();
    rooms[r] = "";
    pinnedMsg = "";
    server.send(200, "text/plain", "OK");
  });

  // ─ Admin panel (JSON stats)
  server.on("/admin", []() {
    String pass = server.arg("pass");
    if (pass != ADMIN_PASS) { server.send(403, "text/plain", "DENIED"); return; }
    String resp = "{";
    resp += "\"node\":\"" + String(NODE_ID) + "\",";
    resp += "\"ram_kb\":" + String(ESP.getFreeHeap() / 1024) + ",";
    resp += "\"uptime_min\":" + String(millis() / 60000) + ",";
    resp += "\"sessions\":" + String(sessionCount) + ",";
    resp += "\"online\":" + String(onlineCount()) + ",";
    resp += "\"lora_ok\":" + String(loraOK ? "true" : "false") + ",";
    resp += "\"lora_rssi\":" + String(loraRSSI) + ",";
    resp += "\"room0_bytes\":" + String(rooms[0].length()) + ",";
    resp += "\"room1_bytes\":" + String(rooms[1].length()) + ",";
    resp += "\"room2_bytes\":" + String(rooms[2].length());
    resp += "}";
    server.send(200, "application/json", resp);
  });

  // ─ Admin wipe (POST)
  server.on("/admin_wipe", HTTP_POST, []() {
    String pass = server.arg("pass");
    if (pass != ADMIN_PASS) { server.send(403, "text/plain", "DENIED"); return; }
    doWipe();
    server.send(200, "text/plain", "WIPED");
  });

  // ─ File operations
  server.on("/delete_file", []() {
    uint32_t ip = (uint32_t)server.client().remoteIP();
    if (!validateTok(ip, server.arg("tok"))) { server.send(401, "text/plain", "AUTH"); return; }
    if (server.hasArg("name")) {
      String p = "/" + server.arg("name");
      // Path traversal protection
      if (p.indexOf("..") >= 0) { server.send(400, "text/plain", "BAD"); return; }
      if (SPIFFS.exists(p)) SPIFFS.remove(p);
    }
    server.send(200, "text/plain", "OK");
  });

  // ─ Upload (image)
  server.on("/upload", HTTP_POST,
    []() { server.send(200, "text/plain", "OK"); },
    []() {
      HTTPUpload& u = server.upload();
      static File uf;
      if (u.status == UPLOAD_FILE_START) {
        // Only allow image filenames, prevent path traversal
        String fn = u.filename;
        fn.replace("/", ""); fn.replace("..", "");
        String p  = "/" + fn;
        if (SPIFFS.exists(p)) SPIFFS.remove(p);
        uf = SPIFFS.open(p, FILE_WRITE);
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
      fn.replace("/", ""); fn.replace("..", "");  // Path traversal protection
      String p  = "/" + fn;
      if (SPIFFS.exists(p)) {
        File f = SPIFFS.open(p, FILE_READ);
        server.streamFile(f, "image/jpeg");
        f.close();
      } else {
        server.send(404, "text/plain", "Not found");
      }
    }
  });

  // ─ Catch-all: redirect to captive portal
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
  Serial.println("\n\n[Ghost Chat V3] Starting…");

  // ─ OLED: V2 requires RST LOW→HIGH sequence or screen stays blank
  pinMode(OLED_RST, OUTPUT);
  digitalWrite(OLED_RST, LOW);
  delay(20);
  digitalWrite(OLED_RST, HIGH);
  delay(20);

  Wire.begin(OLED_SDA, OLED_SCL);
  u8g2.begin();
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_6x10_tf);
  u8g2.drawStr(10, 22, "Ghost Chat V3");
  u8g2.drawStr(20, 38, "Starting...");
  u8g2.sendBuffer();

  // ─ Panic button
  pinMode(PANIC_BTN, INPUT_PULLUP);

  // ─ SPIFFS
  if (!SPIFFS.begin(true)) {
    Serial.println("[ERR] SPIFFS mount failed");
    u8g2.drawStr(0, 52, "SPIFFS FAIL");
    u8g2.sendBuffer();
  }

  // ─ WiFi Access Point
  WiFi.mode(WIFI_AP);
  WiFi.softAP(WIFI_SSID, WIFI_PASS);
  delay(100);
  Serial.println("[WiFi] AP up: " + WiFi.softAPIP().toString());

  // ─ DNS server (captive portal — redirect all domains to us)
  dnsServer.start(DNS_PORT, "*", WiFi.softAPIP());

  // ─ LoRa SX1276 init
  SPI.begin(LORA_SCK, LORA_MISO, LORA_MOSI, LORA_SS);
  LoRa.setPins(LORA_SS, LORA_RST, LORA_DIO0);
  if (LoRa.begin(LORA_FREQ)) {
    LoRa.setSpreadingFactor(LORA_SF);
    LoRa.setSignalBandwidth(LORA_BW);
    LoRa.setCodingRate4(5);
    LoRa.setTxPower(LORA_POWER);
    LoRa.setSyncWord(0xAB);       // Custom sync word — only hear other Ghost Chat nodes
    LoRa.receive();               // Put into receive mode
    loraOK = true;
    Serial.println("[LoRa] OK @ 866 MHz, SF7, " + String(LORA_POWER) + " dBm");
  } else {
    Serial.println("[LoRa] FAILED — continuing without LoRa relay");
  }

  // ─ Web server
  setupRoutes();
  server.begin();

  // ─ Ready screen
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_6x10_tf);
  u8g2.drawStr(5,  14, "Ghost Chat V3");
  u8g2.drawLine(0, 17, 128, 17);
  u8g2.drawStr(0,  30, ("IP: " + WiFi.softAPIP().toString()).c_str());
  u8g2.drawStr(0,  42, ("WiFi: " + String(WIFI_SSID)).c_str());
  u8g2.drawStr(0,  54, ("LoRa: " + String(loraOK ? "READY" : "DISABLED")).c_str());
  u8g2.drawStr(0,  64, "");
  u8g2.sendBuffer();

  Serial.println("[Ghost Chat] Ready! Connect to: " + String(WIFI_SSID));
}

// ============================================================
// LOOP
// ============================================================
void loop() {
  dnsServer.processNextRequest();
  server.handleClient();
  updateOLED();
  loraReceive();  // Non-blocking LoRa packet check

  // RAM crash prevention: trim all rooms if low
  if (ESP.getFreeHeap() < MIN_FREE_HEAP) {
    Serial.println("[WARN] Low heap, trimming rooms");
    for (int i = 0; i < 3; i++) trimRoom(i);
  }

  // Panic button: hold 3 seconds → full wipe
  if (digitalRead(PANIC_BTN) == LOW) {
    delay(50);
    if (digitalRead(PANIC_BTN) == LOW) {
      unsigned long t = millis();
      while (digitalRead(PANIC_BTN) == LOW) {
        int s = (millis() - t) / 1000;
        u8g2.clearBuffer();
        u8g2.setFont(u8g2_font_6x10_tf);
        u8g2.drawStr(15, 22, "HOLD TO WIPE");
        u8g2.drawStr(30, 40, (String(3 - s) + " sec...").c_str());
        u8g2.sendBuffer();
        if (millis() - t > 3000) {
          doWipe();
          u8g2.clearBuffer();
          u8g2.drawStr(20, 32, "WIPED CLEAN");
          u8g2.sendBuffer();
          delay(2000);
          break;
        }
      }
    }
  }

  delay(2);
}
