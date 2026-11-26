# SRCS — Short Range Communication System

SRCS (formerly "P.A.A.I. Link" in the master plan) is an **offline communication network for people
nearby**: calls, walkie-talkie, group channels and text between Android phones and iPhones, with
**no SIM, mobile data, internet, server or Wi-Fi router**. Phones connect directly (Wi-Fi Aware or
Wi-Fi Direct on Android; iPhones join an Android phone's Wi-Fi Direct group) and can relay for each
other.

| | |
|---|---|
| **Nearby Radar** | See SRCS phones around you: linked, reached through a relay, or nearby and not yet linked |
| **Private Calling** | One-to-one encrypted voice calls |
| **Walkie-Talkie** | Instant push-to-talk channels |
| **Group Channel** | Group calls with several phones |
| **Offline Chat** | Text messages with delivery and read receipts, delivered later when out of range |
| **Signal Relay** | Phones in between pass calls and messages on, end-to-end encrypted |

## Download

**[Download SRCS.apk](https://github.com/ArunSikarwar36/short-range-communication-system/releases/latest/download/SRCS.apk)** (Android 8.0 or newer, about 19 MB)

**[Download SRCS-unsigned.ipa](https://github.com/ArunSikarwar36/short-range-communication-system/releases/latest/download/SRCS-unsigned.ipa)** (iPhone, iOS 16 or newer; install with Sideloadly or AltStore, see [docs/IOS.md](docs/IOS.md))

This is a test build. It is tested on the PC and the emulator but not yet on physical phones.
Android will ask you to allow installing apps from your browser or file manager. Install it on
two phones, turn mobile data off, and follow [docs/TESTING.md](docs/TESTING.md).

The full product plan is in [PAAI_Link_All_Phases_Master_Plan.md](PAAI_Link_All_Phases_Master_Plan.md).
What is built, what is tested and what still needs real phones is in [docs/STATUS.md](docs/STATUS.md).

> This folder sits under `pythonProjects`, but the app itself is **Kotlin / Android** (as the plan
> specifies). There is no Python code.

## Features

| Feature | Notes |
|---|---|
| Discovery and connection | Wi-Fi Aware (Android 10+, if the phone supports it: automatic, no host needed) and Wi-Fi Direct scan / connect / host; iPhones join a host via QR code |
| Nearby radar | Shared layout for Android and iPhone: linked / via relay / nearby rings, tap for actions |
| Pairing | Noise XX handshake + 6-digit code compared on both screens; trust stored once |
| One-to-one calls | Full duplex, Opus 48 kHz / 20 ms, encrypted UDP, adaptive jitter buffer, echo cancellation |
| Call experience | Ringing, accept / decline, busy, no answer, missed, reconnecting, mute, speaker / earpiece / Bluetooth / wired |
| Walkie-talkie (PTT) | Press and hold; host arbitrates the floor; lost talkers are revoked automatically |
| Group calls | Host relays audio, per-member mute, active speaker, remove member, key rotation, host take-over |
| Offline messages | Room storage, delivery / read receipts, retries, duplicate suppression, store-and-forward, retention |
| Relaying | Distance-vector routing through paired phones, hop limit, loop prevention, battery policy, topology view |
| Security | Per-device X25519 identity (Keystore-wrapped), end-to-end Noise K sealing, ChaCha20-Poly1305 media, replay windows |

## Project layout

```
core/   Kotlin Multiplatform library shared by Android and iPhone: crypto, protocol, transport,
        routing, calls, PTT, groups, messaging, media pipeline, app controller, radar layout.
        Tested on the JVM and on an iPhone simulator (GitHub Actions).
app/    Android app: Wi-Fi Aware + Wi-Fi Direct, AudioRecord/AudioTrack, Opus (NDK), Room,
        DataStore, Keystore, foreground service, notifications, Jetpack Compose UI.
ios/    iPhone app: SwiftUI, AVAudioEngine voice processing, Opus, Keychain (see docs/IOS.md).
app/src/main/cpp/opus   libopus 1.5.2 source (BSD), built with CMake.
docs/   Architecture, protocol, security, testing, status, user guide, release.
```

## Build

Requirements (already on this PC): JDK 17, Android SDK with platform `android-37.2`,
build-tools 37, NDK 26.1, CMake 3.22.1.

```powershell
cd D:\pythonProjects\short-range-communication-system
$env:GRADLE_USER_HOME = "D:\android-build\gradle-home"   # keeps Gradle downloads off C:
.\gradlew.bat assembleDebug                 # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :core:test :app:testDebugUnitTest lintDebug
.\gradlew.bat connectedDebugAndroidTest     # needs a phone or emulator
```

Install on a phone (USB debugging on): `adb install -r app\build\outputs\apk\debug\app-debug.apk`

Versions: AGP 9.5.3, Gradle 9.9.2, Kotlin 2.4.21, Compose BOM 2026.11.01, Room 2.8.5,
compileSdk 37.2, targetSdk 36, minSdk 26 (Android 8.0).

## First use (two phones)

1. Install on both phones, open, enter a name, switch **Online** on.
2. On both: tap the scan button under *Nearby devices*.
3. On one phone tap **Connect** next to the other; accept the system invitation on the second phone.
4. Both phones show a 6-digit code. If the codes match, tap **Codes match** on both.
5. The phone appears under *Paired devices* — call, message, or start a walkie-talkie group.

For three or more phones, one phone taps **Host a group** and the others connect to it; the host
then relays between them. See [docs/USER_GUIDE.md](docs/USER_GUIDE.md).

## Range

Range depends on phone models, obstacles and interference. It is **not** guaranteed and has not
been measured yet. Use [docs/TESTING.md](docs/TESTING.md#range-test-method) to measure it.
