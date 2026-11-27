# Status by phase (2026-11-26)

Legend: **Built** = code complete · **Auto-tested** = verified by automated tests on the PC or the
emulator · **Needs phones** = can only be validated on physical devices (not done yet).

The plan's definition of done for every phase requires physical-phone tests. Those have **not**
been run (no phones were connected during development), so no phase is "done" in the plan's
sense yet. Everything below the radio and the real microphone/speaker is built and tested.

| Phase | Built | Auto-tested | Needs phones |
|---|---|---|---|
| 1 Discovery & connection | Wi-Fi Direct scan/connect/host/leave, owner/client detection, TCP + Noise XX handshake, pairing code, diagnostics, connect by address | Handshake vs. Noise test vectors; pairing / decline / reconnect over sockets; emulator ↔ desktop pairing | Discovery and group formation between two phones with mobile data off |
| 2 Voice engine | AudioRecord (VOICE_COMMUNICATION + AEC/NS/AGC), Opus 1.5.2 via NDK, encrypted UDP, sequence/timestamps, adaptive jitter buffer, PLC, audio focus, routes, mute, loss/jitter metrics, adaptive bitrate | Opus round trip on emulator; 2-way audio, mute, teardown with fake audio; emulator ↔ desktop call with real Opus packets (0 auth failures); mic released after calls | Audio quality quiet/noisy, measured latency and loss |
| 3 Calling UX | Full state machine incl. ENDING, invite/ringing/accept/reject/cancel/end/busy, timeouts, reconnecting, incoming-call notification + full-screen intent, lock-screen display, foreground service mic type, proximity sensor, Bluetooth routing, call log | State machine tests; busy/decline/loss over sockets; incoming + outgoing call on emulator; FGS type switches 0x10 ↔ 0x90 | Screen lock, Bluetooth headsets, background behaviour per vendor |
| 4 Push-to-talk | Press-and-hold UI, request/grant/deny/release/keepalive/revoke/floor state, timeout & max talk, hub drops non-holder audio, release on touch cancel and app stop | Arbiter tests; 3-node floor contention and lost-holder recovery; PTT on emulator | Feel and latency on phones |
| 5 Groups & messaging | Group create/invite/join/leave/kick, epochs + key rotation, host relay, per-member mute, active speaker, size limit, host take-over; Room messaging with ack/read, retry/backoff, dedupe, store-and-forward, expiry, retention, group chat | Group call mixing, kick/rekey, host recovery, group text; message relay, read receipts, offline queue; Room on emulator; emulator ↔ desktop messages and PTT group | Group of 3–6 phones: stability, battery, Wi-Fi capacity |
| 6 Relay | DSDV routing, TTL/hop limit, loop prevention, route expiry/withdrawal, dedupe, relay authorisation (paired only), per-source rate limit, battery policy, topology screen | Line-topology routing tests; relayed messages and calls; withdrawal when relay disappears | A–B–C range extension in the field; battery/throughput impact. Multi-group bridging is not possible with standard Wi-Fi Direct (see ARCHITECTURE.md) |
| 7 Hardening & release | Threat model, fuzz tests, lint clean, R8 release build, signing setup, data excluded from backup, user guide | Protocol fuzzing (20k random + mutations), lint | Compatibility matrix, long-call and battery tests, independent security review, release key |

## First real-phone test (2026-11-27)
- Phones: Xiaomi 25053PC47I (Android 16, Wi-Fi Direct + Wi-Fi Aware) and Redmi K50i (Android 14,
  Wi-Fi Direct only), indoors.
- **v0.4.0 failed to connect:** SRCS started Wi-Fi Aware on the Xiaomi, and its Wi-Fi chip cannot run
  Wi-Fi Aware and Wi-Fi Direct together (`Failed to create P2p iface … existingIface=[STA, NAN]`), so
  Wi-Fi Direct scans were refused ("busy") while the Redmi only had Wi-Fi Direct.
- **Fixed in v0.4.1:** Wi-Fi Direct always comes first; Wi-Fi Aware is an opt-in setting
  (Settings → Connections), and SRCS turns it off by itself if it blocks a Wi-Fi Direct scan.
- **v0.4.1 result:** discovery, Wi-Fi Direct group (~6 s), link, pairing code on both, one-to-one call
  ACTIVE for 24 s with the bitrate rising 24 → 32 kbps on both phones (clean link), clean hang-up and
  audio released. Audio quality as heard by the users: not yet reported.

## DJ Mode (2026-11-26)
- **Built on Android and iPhone.** The group host picks songs on the phone (any format the phone
  decodes) and every member hears them: Opus tuned for music (48 kHz mono, 64 kbit/s), encrypted,
  sent straight to each member, ~300 ms buffer so phones play together closely. Play / pause /
  next / end, playlist with automatic next song, song requests from members. Pausing or changing
  song clears every listener's buffer, so it takes effect at once. Music plays on the media stream
  (media volume) next to walkie-talkie voice.
- **Limits:** only the group host can DJ (it is directly linked to every member); mono; songs only
  from the phone (no capture from other apps); no album art yet.
- **Tested:** `DjTest` (two listeners hear the song, pause is silent, resume, request reaches the DJ,
  stop releases all audio), full core suite 80 tests; Android and iPhone builds. Not yet heard on phones.

## Offline maps (2026-11-26)
- **Built on Android and iPhone.** Download an area around a position once (2 / 5 / 15 / 30 km,
  street detail up to 8 km) from the newest Protomaps world build, or import a `.pmtiles` file; the
  map then works with no internet. Shows this phone, friends who share their location and SOS
  alerts. Style, fonts (incl. Devanagari) and icons are bundled.
- **Tested:** `PmtilesTest` (Hilbert IDs, directories, extraction keeps exactly the area's tiles,
  cancel, leaf directories), real extract of central New Delhi (174 tiles, 5.1 MB) validated with the
  reference PMTiles reader; Android emulator: in-app download of a 2 km area (46 tiles, 1.9 MB) and the
  offline map rendered with streets, labels, icons and this phone's position. iPhone: built in CI.
- **Not yet:** friend/SOS markers on the map seen on screen (logic shared with the radar), routes and
  sharing a map area between phones over SRCS. The APK is now ~60 MB (MapLibre for 3 CPU types).

## Live location and SOS (2026-11-26)
- **Built on Android and iPhone, shared logic in core (`LocationService`).** Opt-in sharing with paired
  phones and/or the group (through the host), 15 min / 1 h / 8 h, stop at any time; updates only
  when moved 8 m (at most every 5 s, at least every minute). SOS goes to every paired phone and the
  group with the GPS position if one is found within 8 s, and is stored and forwarded for phones out of
  range. The radar places friends who share at their real direction and distance.
- **Tested:** `GeoTest`, `LocationTest` (rate limit, stop, group via host, SOS once), on the emulator
  against the desktop test phone: friend shown 315 m NE on the radar, location received by the desktop,
  SOS both ways with coordinates and note. Real GPS accuracy outdoors not tested yet.
- **Next:** offline maps from downloaded OpenStreetMap region files, then DJ Mode.

## Wi-Fi Aware and Nearby radar (2026-11-26)
- **Android Wi-Fi Aware: built, not yet tested on phones.** Runs alongside Wi-Fi Direct on Android 10+
  phones with Wi-Fi Aware hardware: phones discover each other (only a random per-session number is
  broadcast) and link directly without a group owner. Diagnostics shows support, peers and data
  paths. The emulator has no Wi-Fi Aware, so it has only been compiled and lint-checked.
- **iPhone Wi-Fi Aware: deferred.** Needs a paid Apple Developer account and Apple's approval of the
  managed Wi-Fi Aware entitlement (iOS 26, iPhone 12 or newer). Android ↔ iPhone over Wi-Fi Aware
  also needs real-device testing; iPhones keep joining an Android host's Wi-Fi Direct group.
- **Nearby radar: built** on Android and iPhone from one shared layout; checked on the emulator with
  the desktop test phone (linked phone on the inner ring, tap → Call / Message).

## iPhone support (in progress)
- **Done (Android side):** the host phone shows an "Add an iPhone" QR code with its Wi-Fi Direct
  network name and password, so an iPhone can join the group as an ordinary Wi-Fi client.
  Unit-tested and the on-screen QR code is decoded in a device test.
- **Done (shared core):** `core` is Kotlin Multiplatform (Android/JVM + iOS). Only crypto
  primitives, sockets and clock/random/thread helpers differ per platform. All 68 core tests run on
  the JVM locally; GitHub Actions (`.github/workflows/ci.yml`) runs them on an iPhone simulator and
  builds the iPhone framework `SrcsCore` on every push (first green run 2026-11-26: 66 tests on the
  iPhone simulator, incl. the multi-phone socket tests and crypto vectors). The Android app is unchanged and was
  re-verified on the emulator (pairing, call, message with the desktop test phone).
- **Not done:** the iPhone app itself (SwiftUI screens, audio, Keychain storage, joining flow). Not yet checked on real phones that an iPhone joins and stays on an
  Android Wi-Fi Direct group (test 22 in TESTING.md).
- **Limits by design:** an Android phone must host; the iPhone app works only while it is open
  (iOS cannot ring for offline calls in the background); without a paid Apple account the app
  must be sideloaded and reinstalled every 7 days.

## Known limitations
- Only one Wi-Fi Direct group per phone, so practical relaying is the star topology (A and C via host B).
- Group media is relayed by the host (more battery on the host); default limit 6 members.
- See [SECURITY.md](SECURITY.md#known-limitations-accepted-for-now) for security limitations.
- Range has not been measured. Do not quote a range before running the range test method.
- Emulators have no Wi-Fi Direct radio; the emulator demo links over the emulator's virtual network
  to the desktop test phone (`gradlew :core:demoPeer`).

## How the emulator demo was run
```powershell
$env:ANDROID_AVD_HOME = "D:\android-build\avd"
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd paai36 -no-snapshot -gpu swiftshader_indirect
.\gradlew.bat :core:demoPeer          # desktop test phone on TCP 47710 / UDP 47711
adb emu redir add udp:47701:47701     # lets the desktop send voice into the emulator
```
In the app: Online → Diagnostics → Connect by address `10.0.2.2:47710` → Codes match. The desktop
phone echoes messages and answers calls by sending your own Opus audio back. Write `call`,
`hangup` or `msg <text>` into `D:\android-build\demo-peer.cmd` to drive it.

## 0.5.0 — new look and in-app updates (2026-11-27)

- New Android design (previewed as a design canvas first): full-screen map with a glowing radar,
  one glossy main button, glass panels, small round buttons for SOS / chat / DJ, a new tab bar.
  Home has three states: offline ("Go online"), nobody nearby ("Scan for phones", "Host a group",
  "Devices") and connected (mic + "Hold to talk" + channel strip).
- Tapping a connected phone (radar or Team list) opens an options sheet: Call, Walkie-talkie,
  Message, Share location, On the map, DJ Mode, Rename, Unpair.
- Team tab: purple header, stats (nearby / connected / farthest), glass list with call buttons.
  Call screen: purple glow, pulsing rings, glass control bar.
- In-app updates (`update/UpdateManager.kt`): asks api.github.com for the latest release at most
  every 6 hours when online (Settings → App updates can turn it off, Me → App updates checks now).
  The APK is downloaded to the app cache and only handed to the Android installer if it is the
  same package, signed with the same key and newer. Release assets must keep the name `SRCS.apk`
  and the tag `vX.Y.Z`.
