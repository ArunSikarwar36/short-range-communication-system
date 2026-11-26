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

## iPhone support (in progress)
- **Done (Android side):** the host phone shows an "Add an iPhone" QR code with its Wi-Fi Direct
  network name and password, so an iPhone can join the group as an ordinary Wi-Fi client.
  Unit-tested and the on-screen QR code is decoded in a device test.
- **Not done:** the iPhone app itself (shared Kotlin Multiplatform core + iPhone UI, built on
  GitHub's macOS machines). Not yet checked on real phones that an iPhone joins and stays on an
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
