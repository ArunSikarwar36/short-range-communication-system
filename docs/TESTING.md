# Testing

## Automated tests

```powershell
$env:GRADLE_USER_HOME = "D:\android-build\gradle-home"
.\gradlew.bat :core:test                 # 68 tests on the JVM, ~40 s (GitHub CI also runs them on an iPhone simulator)
.\gradlew.bat :app:testDebugUnitTest     # Room mapping, Wi-Fi QR round trip
.\gradlew.bat lintDebug
.\gradlew.bat connectedDebugAndroidTest  # on a phone or emulator
```

| Suite | Covers |
|---|---|
| `NoiseVectorsTest` | Noise XX and K against the published cacophony test vectors (handshake + transport) |
| `CryptoTest` | Sealing, tampering, wrong keys, media AEAD, replay window, pairing code, HKDF RFC 5869 vector |
| `ProtocolTest` | Round trip of every message; all truncations rejected; 20 000 random inputs + mutations never crash a decoder |
| `RoutingTest` | Multi-hop learning, split horizon, sequence freshness, withdrawal, expiry, hop limit, de-duplication |
| `CallStateMachineTest` | Every call path: answer, decline, busy, no answer, missed, cancel, reconnect, loss, ENDING |
| `FloorArbiterTest` | One talker, stale releases, keepalive revocation, max talk time, member departure |
| `JitterBufferTest` | Buffering, loss concealment, late/duplicate drop, latency bound, re-buffering, bitrate steps |
| `IntegrationTest` | 3 phones on loopback TCP/UDP with fake audio: pairing (both must confirm, decline), relayed messaging the relay cannot read, read receipts, offline queue, route withdrawal, automatic reconnect, two-way call audio, mute, teardown releases all audio resources, busy/decline, call through a relay, link loss → RECONNECTING → FAILED, PTT floor + revocation of a vanished talker, group call mixing, kick + key rotation, host take-over, group text |
| `OpusCodecTest` (device) | Native library loads; encode/decode keeps signal energy; concealment; closed codec is safe |
| `RoomStorageTest` (device) | Duplicate rejection, pending queue, trust records keep local names |
| `KeystoreIdentityTest` (device) | Identity survives reload |
| `UiSmokeTest` (device) | App launches and renders |
| `CryptoVectorsTest` | RFC 4231 / 7748 / 8439 and FIPS 180 vectors for SHA-256, HMAC, X25519, Poly1305, ChaCha20-Poly1305 on the platform crypto and on PortableCrypto |
| `PortableCryptoTest` (JVM) | PortableCrypto (used on iOS) equals Bouncy Castle on random inputs, tampering rejected |
| `DjTest` | DJ Mode across 3 phones: members hear the host's song, pause is silent at once, resume, song request reaches the DJ, stop releases all audio |
| `PmtilesTest` | PMTiles Hilbert tile IDs, directory and header round trip, area extraction keeps exactly the area's tiles (with leaf directories and repeated tiles), cancel leaves no file |
| `GeoTest`, `LocationTest` | Distance/bearing maths; opt-in sharing, rate limit, stop removes the position, group positions via the host, SOS delivered once |
| `WifiJoinTest`, `QrTest` | Wi-Fi QR text and escaping; the QR code decodes back to the same text |
| `JoinIphoneTest` (device) | "Add an iPhone" dialog shows name/password, and the QR code read from the screen pixels decodes correctly |

## Two-phone test plan

Record phone models and Android versions for every run. Mobile data **off**, no router.

| # | Scenario | Steps | Expected |
|---|---|---|---|
| 1 | Discovery offline | Airplane mode + Wi-Fi on, both online, scan | Each lists the other |
| 2 | Connect | A taps Connect; B accepts system dialog | Both show the pairing dialog; Diagnostics shows owner/client roles and owner 192.168.49.1 |
| 3 | Pair | Compare code; confirm on both | "Paired with …"; device listed as *Connected directly* |
| 4 | Wrong pairing | Cancel on one | Link closes on both; no trust saved |
| 5 | Call | A calls B; B answers | Ringing on both; two-way audio; timer runs |
| 6 | Mute | A mutes | B hears nothing from A within ~0.3 s; call stays active |
| 7 | Routes | Switch speaker / earpiece; connect Bluetooth headset mid-call | Audio follows; headset takes over |
| 8 | End | Either side hangs up | Both return to idle; mic indicator off; notification shows "Online" |
| 9 | Decline / busy / no answer | B declines; B in call with C; B ignores 45 s | "Call declined" / "Busy" / "No answer"; B sees missed call |
| 10 | Screen lock | Lock A during call; incoming call while locked | Call continues; incoming call shows over lock screen |
| 11 | Link loss | Walk out of range during call | "Reconnecting…", then recovers or "Connection lost" within ~30 s; resources released |
| 12 | PTT | Group of 3 in walkie-talkie mode; hold to talk | Only holder heard; second talker sees "Channel busy" |
| 13 | PTT lost talker | Holder force-stops app while talking | Channel free again within ~4 s |
| 14 | Group call | 3 phones, group call | All hear each other; remove one → it leaves, others continue |
| 15 | Host lost | Host leaves range | Next member hosts (needs members paired with each other and linked) |
| 16 | Messages offline | B offline; A sends; B comes online | A: "Waiting for device" → "Delivered"; one copy on B |
| 17 | Relay | A and C both join B's group (B hosts); A and C paired earlier | A calls C via B; Diagnostics shows 2 hops |
| 18 | No mic permission | Deny microphone, try to call | Explanation; call not started |
| 19 | No Wi-Fi Direct | Device without Wi-Fi Direct | Clear "not supported" message |
| 20 | Long call | 60 min call | No crash, no growing latency, battery measured |
| 21 | Repeated calls | 20 calls in a row | No leak: memory stable, mic released each time |
| 22 | iPhone joins Wi-Fi | A hosts, taps Add an iPhone; iPhone Camera scans the code | iPhone joins `DIRECT-…` and stays on it with no internet; A's group stays up |
| 23 | Wi-Fi Aware | Two Android 10+ phones with Wi-Fi Aware, both online, no group hosted | Each appears on the other's radar, links automatically, pairing code shown; Diagnostics shows 1 data path |
| 24 | Location and SOS outdoors | A shares location with B 200 m away; then A sends SOS | B's radar shows A at the right direction and roughly 200 m; SOS alarm sounds on B with A's position |
| 25 | Offline map | Download a 5 km area with internet, then airplane mode + Wi-Fi Direct only; A shares location | Map opens without internet; streets and names visible; A's dot moves on B's map |

### Measuring latency
Put both phones side by side, clap near A, record both speakers with a third device; the delay
between the clap and B's playback is the one-way latency. Note the jitter buffer value shown in
Diagnostics → Live audio.

## Range test method

Test every environment, at least three runs each:

1. Open outdoor line of sight
2. Indoor, same room
3. Indoor, through one wall
4. Indoor, through several walls
5. Crowded Wi-Fi area

Start together, then walk apart in 10 m steps; at each step hold a 30 s call and note:

| Field | Value |
|---|---|
| Phones (model, Android version) | |
| Environment / obstacles | |
| Distance (m) | |
| Packet loss % / jitter ms (Diagnostics → Live audio) | |
| RTT ms (Diagnostics → Links) | |
| Audible quality (good / fair / poor / none) | |
| Disconnects | |
| Battery used in 10 min | |

Do not publish a single range figure from one test.

## Device compatibility matrix (fill in)

| Phone | Android | Wi-Fi Direct | Pair | Call | PTT | Group (3) | Relay | Notes |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |
