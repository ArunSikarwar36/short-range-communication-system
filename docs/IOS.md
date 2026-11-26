# SRCS on iPhone

The iPhone app talks to the Android app. iPhones cannot use Wi-Fi Direct, so an **Android phone
hosts the group** and the iPhone joins the host's Wi-Fi Direct network as an ordinary Wi-Fi client.
Pairing, encryption, calls, walkie-talkie, groups and messages then work exactly as between two
Android phones (same shared core, same protocol).

## Install (no Mac, no paid Apple account)

1. Download **[SRCS-unsigned.ipa](https://github.com/ArunSikarwar36/short-range-communication-system/releases/latest/download/SRCS-unsigned.ipa)**
   (or, for the newest build, the **SRCS-unsigned-ipa** artifact of the latest CI run).
2. On a Windows PC, install **Sideloadly** (or AltStore) and iTunes/Apple Devices.
3. Connect the iPhone by USB, drag `SRCS-unsigned.ipa` into Sideloadly, sign in with your Apple ID
   and press Start. On iPhones with iOS 16 or newer, turn on **Settings → Privacy & Security →
   Developer Mode** when asked, then trust your Apple ID under **Settings → General → VPN & Device
   Management**.
4. With a free Apple ID the app stops opening after **7 days**; reinstall it the same way (your
   pairings and messages are kept). A paid Apple Developer account (TestFlight / App Store)
   removes this limit.

## Use

1. Android phone: SRCS → Online → **Host a group** → **Add an iPhone**. A QR code appears.
2. iPhone: open the **Camera**, point it at the QR code, tap **Join network**. (Or Settings → Wi-Fi,
   network `DIRECT-…`, password shown on the Android phone.) iOS will say "No Internet Connection";
   that is expected.
3. Open SRCS on the iPhone → **Go online**. It connects to the host (192.168.49.1) automatically,
   or tap **Connect to Android host**. Allow **Local Network** access when iOS asks.
4. Compare the 6-digit code on both phones and tap **Codes match** on both.
5. Message, call or join groups. Allow the **microphone** on the first call.

## Offline maps

Map icon (top left) → **Map areas**: download an area once with internet, or import a `.pmtiles` file
with the Files picker. The map uses MapLibre and the same bundled style and fonts as Android.

## Limits

- The iPhone cannot host a group and needs an Android host in range.
- iOS pauses apps in the background. SRCS works while it is open or while a call / group is
  running (audio background mode); it cannot ring for a new call while the app is closed or the
  phone is locked, because iOS only allows that with internet push notifications.
- iOS uses pure-Kotlin crypto primitives instead of Bouncy Castle (see [SECURITY.md](SECURITY.md)).

## What is tested where

| Part | How |
|---|---|
| Shared core (protocol, crypto, calls, groups, messages, sockets) | 66 tests on an iPhone simulator in CI, 70 on the JVM |
| App controller used by the iPhone UI (`SrcsController`) | `ControllerTest`: pairing, messages + receipts, call with audio, restart from saved files, walkie-talkie group |
| iPhone app UI | CI UI test on a simulator against the desktop test phone: onboarding, go online, connect, pairing code, message + echo (screenshots in the `ios-ui-test-results` artifact) |
| Microphone/speaker, Wi-Fi join, real radio | **Not tested yet — needs a real iPhone and an Android phone** |

## Build (macOS)

```bash
brew install xcodegen
ios/scripts/build-deps.sh        # libopus + SrcsCore.xcframework
cd ios && xcodegen generate && open SRCS.xcodeproj
```
