# AcroNet V2.0.0-HORIZON
## Post-Quantum Tactical Messenger — Architectural Manifesto

**Classification:** Team Scrapyard — InfoSec Cyber Club, Gautam Buddha University
**Lead Architect:** Ashish Kumar (Architect-Zero)
**Sprint:** Horizon (April 2026)
**Predecessor:** AcroNet V1.0.0-OBSIDIAN (ESP32/LoRa mesh, AES-256-GCM, Stealth Calculator)

---

## 1. Executive Summary

AcroNet V1 proved that a stealth-cloaked, encrypted messenger could be built on autonomous hardware (ESP32 + LoRa). V2 evolves the architecture from a **local mesh network** to a **global, decentralized, post-quantum transport layer** that operates over the public internet without exposing user identity, metadata, or message content — even to a quantum-capable adversary.

### What Changed (V1 → V2)

| Dimension | V1 (Obsidian) | V2 (Horizon) |
|-----------|---------------|--------------|
| **Transport** | LoRa 866MHz mesh (1-10km) | Nostr WebSocket relays (global) |
| **Encryption** | AES-256-GCM + PBKDF2 | X25519 + Kyber-512 Hybrid → AES-256-GCM |
| **Key Exchange** | Shared room password | Ephemeral ECDH + PQ-KEM (per-session) |
| **Storage** | SPIFFS / SharedPreferences | SQLCipher with 24h rolling master key |
| **Decoy** | Calculator app | Stock Market Ticker (live API data) |
| **Quantum Safety** | None | Kyber-512 (NIST ML-KEM) |

---

## 2. Why Post-Quantum? The "Harvest Now, Decrypt Later" Threat

A classical adversary who intercepts AES-256-GCM ciphertext today cannot break it. But a nation-state actor can store that ciphertext indefinitely. When large-scale quantum computers arrive (estimated 2030-2040), Shor's algorithm will break the X25519 key exchange that protected the AES session key.

**The math:**
- X25519 (Curve25519): Broken by quantum Shor's algorithm in polynomial time.
- AES-256-GCM: Resistant to quantum Grover's algorithm (effective security reduced to 128-bit, still infeasible).
- Kyber-512 (ML-KEM): Resistant to all known quantum algorithms. Based on Module Learning With Errors (MLWE) lattice problem.

**Our solution:** A hybrid scheme. We run X25519 AND Kyber-512 in parallel. The shared secrets are concatenated and fed through HKDF-SHA256 to derive the AES-256-GCM session key. If either primitive survives, the session key is safe.

```
SharedSecret = X25519(sk_a, pk_b) || Kyber.Decaps(sk_a, ct_b)
SessionKey   = HKDF-SHA256(SharedSecret, salt="AcroNet-Horizon-V2", 256 bits)
```

---

## 3. Why Bouncy Castle, Not Android Keystore?

### The Hardware Reality

Android's Hardware-Backed Keystore (StrongBox / TEE) supports:
- RSA (2048, 4096)
- EC (P-256, P-384, P-521)
- AES (128, 256)
- X25519 (only API 33+, no StrongBox)
- Kyber / ML-KEM: NO hardware support on any shipping device

Kyber-512 keys cannot be generated or stored in hardware. They must exist in software RAM.

### Our Mitigation

1. **Bouncy Castle PQC Provider** provides a pure-Java implementation of Kyber-512 (ML-KEM) that runs on all Android API levels >= 21.
2. **Ephemeral Keys Only:** Kyber keypairs are generated fresh for every session. They never touch disk.
3. **Zeroing:** After key agreement, the raw byte arrays holding private key material are overwritten with 0x00 before garbage collection.

---

## 4. Why Nostr, Not WebRTC?

### The WebRTC IP Leak

WebRTC requires a STUN server for NAT traversal. During ICE candidate gathering, the client's real public IP address is sent to the STUN server and potentially exposed to the remote peer. This is an unacceptable metadata leak for a zero-trust messenger.

### The Nostr Solution (NIP-01)

Nostr is a decentralized relay protocol where:
1. The client connects to a WebSocket relay.
2. The client publishes events signed with a throwaway Ed25519 key.
3. The relay never knows who is talking to whom.
4. The client's IP is visible only to the relay operator, not to other users.
5. We rotate through 10+ disposable relays to prevent traffic profiling.

The `created_at` field is always set to 0 to prevent timestamp-based traffic analysis.

---

## 5. SQLCipher Rolling-Key Architecture

The local message database uses SQLCipher (AES-256-CBC full-database encryption). Every 24 hours, a DatabaseKeyManager executes a key rotation:

1. Load current DB key from EncryptedSharedPreferences
2. Open database with current key
3. Generate new 256-bit key via SecureRandom
4. Execute: PRAGMA rekey = new_key
5. Store new key in EncryptedSharedPreferences
6. Zero-fill the old key bytes in RAM

---

## 6. The Stock Ticker Decoy

V2 uses a fully functional Stock Market Ticker that fetches real Bitcoin/ETH prices from CoinGecko, displays live candlestick charts, and appears as "MarketPulse" in the app drawer. The unlock gesture is a three-finger swipe down on the chart area followed by biometric verification.

---

## 7. Theoretical Horizon: Sphinx Mixnet (V3 Roadmap)

When a production-grade JVM Sphinx library emerges (Nym, Katzenpost), AcroNet V3 will fragment each Nostr event into fixed-size 512-byte Sphinx packets, route through 3 independent mix nodes, and apply Poisson-distributed delays at each hop.

This is deferred to V3 as there is no stable, audited JVM/Android library for Sphinx packet construction today.

---

## 8. Dependency Matrix

| Library | Version | Purpose |
|---------|---------|---------|
| bcprov-jdk18on | 1.78 | X25519, Kyber-512 (ML-KEM) |
| android-database-sqlcipher | 4.5.6 | Encrypted Room database |
| okhttp3 | 4.12.0 | WebSocket for Nostr relays |
| security-crypto | 1.1.0-alpha06 | EncryptedSharedPreferences |
| biometric | 1.2.0-alpha05 | Fingerprint unlock |

---

**Team Scrapyard. First Principles. Irreducible Logic.**
*Committed by Architect-Zero, April 2026.*
