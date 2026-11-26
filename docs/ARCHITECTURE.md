# Architecture and protocol

## Layers

```
app/ui            Compose screens, LinkViewModel (UI state only)
app/              AppContainer (object graph), LinkStack (per online session), LinkService
app/wifidirect    WifiDirectController — discovery, group formation, owner/client role
app/audio         AndroidCapture / AndroidPlayback / Opus JNI / AudioRouter (focus, routes)
app/data          Room (peers, messages, call log, group names), DataStore settings
app/security      KeystoreIdentity — X25519 identity wrapped by an AndroidKeyStore AES key
──────────────── core (Kotlin Multiplatform: Android/JVM + iOS, tested on both) ───────────────
core/call         CallStateMachine (pure) + CallManager (signaling, timers, keepalive, stall detection)
core/group        GroupManager — invites, membership epochs, key rotation, host recovery, PTT
core/ptt          FloorArbiter (pure) + PttState
core/messaging    MessagingService — queue, acks, retry/backoff, expiry, group relay, retention
core/media        VoiceSession (capture→encode→encrypt→UDP, UDP→jitter→decode→mix→play),
                  JitterBuffer, BitrateController, MediaCoordinator, MediaRouter
core/node         LinkNode — links, pairing, trust, sealing, routing, relaying, store-and-forward
core/routing      RouteTable (DSDV distance vector), DedupeCache
core/transport    Link (TCP + Noise XX), LinkServer, MediaSocket (UDP)
core/protocol     Wire formats (frames, envelopes, app messages, media packets)
core/crypto       Noise (XX, K), primitives (Bouncy Castle on Android, PortableCrypto on iOS), Sealer,
                  MediaCipher, ReplayWindow, SAS
core/util         Platform seams (expect/actual): clock, secure random, threads, locks; Net = sockets
                  (java.net on Android, BSD sockets on iOS)
```

The core has no Android dependency, so everything above the radio and the audio hardware is tested
on the development PC with several nodes talking over loopback sockets.

`AppContainer` lives for the whole process. Going online creates a `LinkStack` (node + managers);
going offline closes it. A call therefore survives activity recreation, and `LinkService` keeps the
process alive in the background (foreground service type `connectedDevice`, plus `microphone`
while a voice session runs).

## Connectivity

Wi-Fi Direct provides only an IP link. The group owner is `192.168.49.1`; clients dial it on TCP
47700. Everything above IP is the app's own protocol, so the same stack also works on a shared
hotspot or LAN ("Connect by address" in Diagnostics).

| Port | Use |
|---|---|
| TCP 47700 | Control links (Noise XX encrypted) |
| UDP 47701 | Voice packets (ChaCha20-Poly1305 per packet) |

## Control channel

Frames: `u16 length | payload` (max 65535). The first three frames are the Noise XX handshake
(`Noise_XX_25519_ChaChaPoly_SHA256`, prologue `SRCS/1`); the encrypted handshake payloads carry
`LinkHello {version, name, udpPort, relayCapable}`. Every later frame is a Noise transport message
(counter nonce), so modification, replay or reordering closes the link.

Inside each frame is an **envelope**:

```
version u8 | type u8 | ttl u8 | flags u8 | messageId[16] | src[16] | dst[16] | body (u16 len)
```

| Type | Scope | Purpose |
|---|---|---|
| PAIR_CONFIRM / PAIR_REJECT | link | user confirmed / rejected the code |
| PING / PONG | link | keepalive (4 s) and RTT; link closed after 15 s silence |
| ROUTE_ADVERT | link (paired only) | distance-vector routes |
| SEALED | routed | end-to-end encrypted application message |
| CLOSE | link | orderly shutdown |

`SEALED` bodies are one-way Noise K messages (`Noise_K_25519_ChaChaPoly_SHA256`) from the source's
static key to the destination's static key. The envelope header without `ttl` is the Noise
prologue, so relays cannot change sender, recipient, type or message ID. The plaintext is
`sentAt u64 | AppMessage`.

### Application messages

| Code | Message | Code | Message |
|---|---|---|---|
| 1 | CALL_INVITE {callId, mediaKey, codec} | 20 | GROUP_INVITE {groupId, name, mode, codec} |
| 2 | CALL_RINGING | 21–24 | GROUP_JOIN / DECLINE / LEAVE / KICK |
| 3 | CALL_ACCEPT | 25 | GROUP_UPDATE {epoch, mediaKey, ordered members} |
| 4 | CALL_REJECT {reason} | 26 | GROUP_END |
| 5 | CALL_CANCEL | 30 | TEXT {messageId, groupId, origin, sentAt, body} |
| 6 | CALL_END | 31 | TEXT_ACK {DELIVERED / READ} |
| 7 | CALL_BUSY | 40 | MEDIA_REPORT {loss‰, jitter} |
| 8 | CALL_KEEPALIVE | | |
| 10–16 | PTT_REQUEST / GRANTED / DENIED / RELEASE / KEEPALIVE / REVOKED / FLOOR_STATE | | |

All decoders enforce sizes, counts, enum values and UTF-8 validity and reject trailing bytes.

## Media channel

```
'P' | version | kind | ttl | src[16] | dst[16] | session[16] | stream u32 | seq u32 | ts u32 | ciphertext
```

- Key: random 32 bytes per call (in CALL_INVITE) or per group epoch (in GROUP_UPDATE).
- Nonce: `0000 | stream | seq`. Stream IDs are unique per sender in a session (caller 1, callee 2;
  group members get distinct IDs from the host), so nonces never repeat under a key.
- AAD: header without `ttl` and `dst`, so hubs and relays can re-address but not alter packets.
- Receiver: replay window (256) per (src, stream), then jitter buffer per stream, Opus decode or
  concealment, mixing, playback.
- Only UDP datagrams from the endpoints of paired, directly linked devices are accepted.

Codec: Opus, mono, 48 kHz, 20 ms frames, VOIP mode, in-band FEC, 24 kbps start; receiver reports
every 2 s step the bitrate between 8 and 32 kbps.

## Calls

`CallStateMachine` is a pure function `(state, event) → (state, actions)`:

```
IDLE → OUTGOING_INVITE → REMOTE_RINGING → CONNECTING → ACTIVE ⇄ RECONNECTING → ENDING → ENDED/FAILED
IDLE → INCOMING_RINGING → CONNECTING → …
```

Timers: invite 10 s (no RINGING → "No response"), ring 45 s, connect 12 s, reconnect 25 s. While
media runs, CALL_KEEPALIVE goes every 2 s; no audio or keepalive for 5 s → RECONNECTING.

## Groups and push-to-talk

Star topology: the creating phone is host and hub. Members send audio to the host, which forwards
the still-encrypted packets to the other members. Membership changes start a new epoch with a new
media key (sent sealed to each member). In PTT mode the host's `FloorArbiter` grants one talker; the
holder sends keepalives every second and is revoked after 3 s of silence or 60 s of talk; the host
also drops any audio not from the floor holder. If the host disappears for 4 s, the next member in
the succession list takes over with a new epoch; others leave after 15 s without an update.

## Routing (relay / multi-hop)

DSDV-style distance vector over paired links: adverts every 3 s with per-destination sequence
numbers (even = alive, odd + hop 255 = withdrawn), split horizon, max 6 hops, 12 s expiry,
immediate withdrawal when a link drops. Envelopes carry TTL and are de-duplicated by message ID.
Relays forward only between paired neighbours, rate-limit forwarded media per source, and stop
relaying when the user disables it or the battery is below the configured level. Sealed messages
flagged store-and-forward are held (200 messages, 24 h) when no route exists.

**Wi-Fi Direct constraint:** a phone is normally in one Wi-Fi Direct group at a time. In practice
the supported relay topology is a **star**: phones A and C both join phone B's group and B relays
between them, extending reach to roughly twice a single link. Longer chains require a device to
bridge two groups, which Android does not generally allow; the routing layer supports it if the IP
links exist (e.g. a hotspot plus Wi-Fi Direct), but this is unvalidated.
