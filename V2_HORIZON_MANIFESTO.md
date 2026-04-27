# AcroNet V3.0.0-UNICORN
## Post-Quantum Tactical Messenger — Architectural Manifesto (Updated)

**Classification:** Team Scrapyard — InfoSec Cyber Club, Gautam Buddha University
**Lead Architect:** Ashish Kumar (Architect-Zero)
**Sprint:** Unicorn (April 2026)
**Lineage:** V1.0.0-OBSIDIAN → V2.0.0-HORIZON → V3.0.0-UNICORN

---

## 1. Executive Summary

AcroNet evolved across three architectural generations:
- **V1 Obsidian:** ESP32 + LoRa local mesh. Proved stealth-cloaked encrypted messaging on autonomous hardware.
- **V2 Horizon:** Nostr + Kyber-512. Extended privacy to the global internet with post-quantum cryptography.
- **V3 Unicorn:** BIP39 + TreeKEM + DOD retraction. Achieved feature parity with commercial giants while maintaining zero-trust physics.

### Architecture Comparison

| Dimension | V1 Obsidian | V2 Horizon | V3 Unicorn |
|-----------|-------------|------------|------------|
| **Transport** | LoRa 866MHz | Nostr WSS | Nostr + P2P LAN + BT |
| **Identity** | Room password | Ephemeral ECDH | BIP39 12-word seed |
| **Key Exchange** | PBKDF2 shared | X25519 + Kyber-512 | X25519 + Kyber-512 |
| **Groups** | Broadcast (all same key) | Pairwise | TreeKEM CGKA (O(log N)) |
| **Storage** | SPIFFS | SQLCipher | SQLCipher + 24h rolling |
| **Deletion** | UI-only | UI-only | DOD 5220.22-M NAND wipe |
| **Decoy** | Calculator | MarketPulse | MarketPulse (shatter unlock) |
| **Voice** | None | None | Opus + Blossom NIP-94 |
| **Multi-Device** | None | None | QR + BT/TCP mesh sync |
| **Bot API** | None | None | 127.0.0.1 WebSocket daemon |
| **Stories** | None | None | 24h TTL key evaporation |

---

## 2. V3 Feature Deep-Dive

### 2.1 BIP39 Seed Phrase Identity (Killing the SIM Card)

Phone numbers are surveillance anchors. AcroNet V3 abandons them entirely.

On first launch, `AcroNetIdentityManager.kt` generates 128 bits of entropy and maps them to 12 English words from the BIP39 standard wordlist (2048 words). These 12 words deterministically derive the user's entire cryptographic identity:

```
12 words → PBKDF2-SHA512 (2048 rounds) → 512-bit master seed
         → HMAC-SHA512("AcroNet seed") → master key + chain code
         → Hardened derivation m/44'/777'/0'/0/0
         → X25519 private key (32 bytes)
         → Kyber-512 seed (32 bytes)
         → Nostr signing key (32 bytes)
```

**Recovery:** Input the same 12 words on a blank device. The identical keys are regenerated mathematically. No server lookup required.

### 2.2 TreeKEM Continuous Group Key Agreement

WhatsApp uses pairwise encryption for groups: each message is encrypted N times for N members. At 100K members, this causes OOM crashes.

AcroNet uses TreeKEM: a binary tree where each leaf is a member. When membership changes, only O(log N) nodes ratchet. The root node derives a single **epoch key** — one AES-256-GCM encryption per message, broadcast to all members.

```
Complexity: WhatsApp groups = O(N) per message
            AcroNet groups  = O(1) per message, O(log N) per membership change
```

### 2.3 DOD 5220.22-M Cryptographic Retraction

When a user deletes a message:
1. NIP-09 deletion event broadcast over Nostr
2. Receiver locates the database row by message hash
3. `RandomAccessFile` executes 3-pass DOD overwrite on NAND sectors:
   - Pass 1: Random bytes
   - Pass 2: Bitwise complement
   - Pass 3: Random bytes (final)
4. `fsync()` forces flush to physical storage
5. `PRAGMA wal_checkpoint(TRUNCATE)` merges WAL
6. WAL/SHM ghost files securely deleted
7. `VACUUM` reclaims and overwrites freed pages

Forensic tools cannot recover the data.

### 2.4 Voice Notes via Blossom (NIP-94)

The audio pipeline splits data across two channels that never intersect:
- **Nostr text channel:** carries the Blossom URL + transient AES key (encrypted with Kyber)
- **Blossom media server:** holds the encrypted audio blob (sees only noise)

Neither the relay nor the Blossom server can decrypt the audio alone.

### 2.5 Local Bot Daemon (Zero-Trust Automation)

`AcroNetLocalDaemonBridge.kt` runs a WebSocket server on `127.0.0.1:8080`. External Node.js scripts connect locally to automate messaging. The phone decrypts Nostr data, passes cleartext to the bot over loopback, receives the response, and encrypts it back out. Metadata never leaves the hardware.

### 2.6 P2P Mesh Transfer (4K Video Bypass)

For large files on the same LAN, `AcroNetWebRTCMesh.kt` uses Android's NsdManager (mDNS) to discover peers locally. Files are chunked into 1MB pieces, each AES-256-GCM encrypted, and blasted at max Wi-Fi throughput. SHA-256 hash matrix verification on reassembly.

---

## 3. Red Team Coverage: 22 Tests

| Suite | Tests | Coverage |
|-------|-------|----------|
| V1 `AcroNetCryptoTest` | 8 | GCM tag/IV/body tampering, key mismatch |
| V2 `HorizonRedTeamTest` | 7 | Nostr metadata, ViewBinding leaks |
| V3 `UnicornRedTeamTest` | 7 | Loopback security, DOD wipe, BIP39, TreeKEM |

---

## 4. Dependency Matrix

| Library | Version | Purpose |
|---------|---------|---------|
| bcprov-jdk18on | 1.77 | Kyber-512, X25519, PBKDF2, HMAC-SHA512 |
| bcpkix-jdk18on | 1.77 | Key encoding utilities |
| android-database-sqlcipher | 4.5.4 | Encrypted database |
| sqlite-ktx | 2.4.0 | SQLite extensions |
| Java-WebSocket | 1.5.3 | Local daemon server |
| okhttp3 | 4.12.0 | Nostr relay client |
| security-crypto | 1.1.0-alpha06 | EncryptedSharedPreferences |
| biometric | 1.1.0 | Stealth trigger authentication |
| dynamicanimation | 1.0.0 | Spring physics animations |

---

## 5. Sphinx Mixnet (V4 Horizon — Deferred)

When a production-grade JVM Sphinx library matures (Nym/Katzenpost), AcroNet V4 will fragment Nostr events into fixed-size 512-byte Sphinx packets, route through 3 mix nodes with Poisson-distributed delays, and reassemble at destination. This is deferred as no stable Android library exists today.

---

**Team Scrapyard. First Principles. Irreducible Logic.**
*19 commits. 18 modules. 22 tests. Zero compromises.*
*Committed by Architect-Zero, April 2026.*
