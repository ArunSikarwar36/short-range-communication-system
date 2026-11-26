# P.A.A.I. Link --- Complete Product, Architecture & All-Phase Development Plan

**Document purpose:** Master specification and implementation plan for
Claude Code / Android Studio\
**Platform:** Android phones\
**Primary language:** Kotlin\
**UI:** Jetpack Compose\
**Connectivity:** Offline, direct device-to-device\
**Primary transport:** Wi-Fi Direct\
**Communication modes:** Full-duplex voice calls + push-to-talk\
**Participants:** One-to-one + group\
**Target range:** 100 metres as a field-test goal, not a guaranteed
range\
**Backend:** None required for core local communication

------------------------------------------------------------------------

# 1. Product vision

P.A.A.I. Link is an offline-first Android communication application.
Nearby Android devices should be able to discover one another and
communicate directly without relying on cellular service, mobile data,
public internet, a cloud server, or a Wi-Fi router.

The app should provide an experience closer to a phone call than a
traditional walkie-talkie:

-   Full-duplex, two-way voice calls.
-   Push-to-talk walkie-talkie mode.
-   One-to-one and group communication.
-   Offline text messaging.
-   Nearby device discovery and pairing.
-   Encrypted application-level communication.
-   Reconnection and clear connection-state feedback.
-   Optional Bluetooth headset support.

## 1.1 Non-negotiable product principles

1.  Core local communication must not require internet access.
2.  Do not use a cloud service for discovery, signaling, or media.
3.  Do not claim a wireless range without real-device testing.
4.  Do not treat a device name or Wi-Fi hardware address as a secure
    identity.
5.  Do not transmit voice or messages in plaintext in production.
6.  The app must clearly communicate when a device is disconnected,
    unreachable, or unsupported.
7.  Keep the transport, audio engine, call state, and UI as separate
    modules.

## 1.2 Range reality

The requested 100-metre range is an engineering target. Actual
performance varies with phone antennas, transmit power, obstacles,
interference, orientation, device manufacturer, and local radio
conditions.

A single direct Wi-Fi Direct link cannot guarantee 100 metres. Greater
coverage requires suitable relay devices and a multi-hop routing design.
A relay is not created automatically by Wi-Fi Direct.

------------------------------------------------------------------------

# 2. Product requirements

## 2.1 Functional requirements

  ID       Requirement                                         Priority
  -------- --------------------------------------------------- ----------
  FR-001   Discover nearby compatible devices                  Must
  FR-002   Connect devices directly using Wi-Fi Direct         Must
  FR-003   Display connection and peer status                  Must
  FR-004   Authenticate paired devices                         Must
  FR-005   Establish encrypted peer sessions                   Must
  FR-006   Make one-to-one full-duplex voice calls             Must
  FR-007   Support incoming call, accept, reject and hang up   Must
  FR-008   Support push-to-talk                                Must
  FR-009   Support group voice communication                   Must
  FR-010   Support offline text messages                       Should
  FR-011   Save local contacts and conversation history        Should
  FR-012   Reconnect after temporary link loss                 Should
  FR-013   Support Bluetooth headsets where Android permits    Should
  FR-014   Support relay / multi-hop networking                Later
  FR-015   Provide diagnostics and network topology            Later

## 2.2 Non-functional requirements

-   Offline operation for all core features.
-   Responsive UI and no blocking work on the main thread.
-   Bounded memory and audio buffers.
-   Graceful handling of packet loss and disconnects.
-   Battery-aware scanning and relay behavior.
-   Secure pairing and session establishment.
-   Compatibility checks for Android versions and device capabilities.
-   Automated tests for protocol and state-machine logic.
-   Real-device tests for Wi-Fi Direct, microphone, speaker, and
    Bluetooth routing.

------------------------------------------------------------------------

# 3. Recommended technology stack

  -----------------------------------------------------------------------
  Area                                Technology
  ----------------------------------- -----------------------------------
  Language                            Kotlin

  UI                                  Jetpack Compose + Material 3

  Architecture                        Clean Architecture / MVVM

  Async                               Kotlin Coroutines + Flow

  Dependency injection                Hilt

  Local database                      Room

  Preferences                         DataStore

  Device discovery                    Android Wi-Fi P2P APIs

  Control channel                     TCP

  Audio media                         UDP

  Audio capture                       AudioRecord

  Audio playback                      AudioTrack

  Codec                               Opus

  Audio effects                       Android AcousticEchoCanceler /
                                      NoiseSuppressor where available

  Security                            Android Keystore + authenticated
                                      key exchange + AEAD

  Background operation                Foreground service for active calls

  Testing                             JUnit, coroutine test, Android
                                      instrumentation tests
  -----------------------------------------------------------------------

Use current stable Android Studio, Android Gradle Plugin, Kotlin,
Compose BOM and SDK versions compatible with one another. Do not
hard-code obsolete versions simply because they appear in an old
example.

------------------------------------------------------------------------

# 4. System architecture

``` text
+------------------------------------------------------------------+
|                        PRESENTATION                              |
|  Compose screens | ViewModels | UI state | navigation            |
+-------------------------------+----------------------------------+
                                |
+-------------------------------v----------------------------------+
|                       DOMAIN / SESSION                            |
|  Peer discovery | Pairing | Call manager | PTT | Group manager   |
|  Messaging | Connection state machine | Permissions              |
+-------------------------------+----------------------------------+
                                |
+-------------------------------v----------------------------------+
|                         DATA / SERVICES                           |
|  Peer repository | Room | DataStore | Session repository         |
+-------------------------------+----------------------------------+
                                |
+-------------------------------v----------------------------------+
|                         NETWORK LAYER                             |
|  Wi-Fi Direct adapter | TCP control | UDP media                  |
|  Framing | Retry | Keepalive | Reconnection                      |
+-------------------------------+----------------------------------+
                                |
+-------------------------------v----------------------------------+
|                         SECURITY LAYER                            |
|  Device identity | Pairing | Key exchange | AEAD | Replay guard  |
+-------------------------------+----------------------------------+
                                |
+-------------------------------v----------------------------------+
|                          AUDIO LAYER                              |
|  AudioRecord | Opus encoder | Jitter buffer | Opus decoder        |
|  AudioTrack | AEC | Noise suppression | Route management         |
+------------------------------------------------------------------+
```

## 4.1 Module structure

Recommended Gradle modules:

``` text
PAAI-Link/
├── app/
├── core/
│   ├── common/
│   ├── model/
│   ├── permissions/
│   └── testing/
├── domain/
│   ├── discovery/
│   ├── pairing/
│   ├── calls/
│   ├── walkietalkie/
│   ├── groups/
│   └── messaging/
├── data/
│   ├── local/
│   ├── repository/
│   └── preferences/
├── network/
│   ├── wifidirect/
│   ├── transport/
│   ├── protocol/
│   └── security/
└── audio/
    ├── capture/
    ├── codec/
    ├── playback/
    └── processing/
```

For an initial small project, these can begin as packages in one app
module and be split into Gradle modules as the codebase grows. Do not
create empty modules just for appearance.

------------------------------------------------------------------------

# 5. Network design

## 5.1 Primary connectivity: Wi-Fi Direct

Wi-Fi Direct allows compatible devices to form a local peer-to-peer
group without a traditional access point.

Typical flow:

1.  Check that the device supports Wi-Fi Direct.
2.  Request the required Android runtime permission.
3.  Register Wi-Fi P2P state, peers-changed, and connection-changed
    receivers.
4.  Start peer discovery.
5.  Display discovered devices.
6.  User selects a peer.
7.  Request group formation.
8.  Observe the connection state broadcast.
9.  Retrieve group-owner information.
10. Establish the application control session.

The group owner is a Wi-Fi Direct role. It is not a permanent server or
a trusted identity.

## 5.2 TCP control channel

Use TCP for:

-   Session setup.
-   Pairing and authentication messages.
-   Call invitation / accept / reject.
-   Group membership changes.
-   Push-to-talk floor control.
-   Text messages and delivery acknowledgements.
-   Keepalive and diagnostics.

Define a versioned, length-prefixed protocol. Do not use ad-hoc
newline-delimited plaintext messages in production.

Suggested logical message envelope:

``` json
{
  "version": 1,
  "messageId": "unique-message-id",
  "sessionId": "session-id",
  "type": "CALL_INVITE",
  "senderDeviceId": "stable-device-id",
  "timestamp": 0,
  "payload": {}
}
```

This is a conceptual schema. Production messages must be encoded with a
strict schema, size limits, validation, authentication, and encryption.
Avoid trusting timestamps alone for replay protection.

## 5.3 UDP media channel

Use UDP for real-time voice because it avoids TCP head-of-line blocking.
The audio protocol must include:

-   Session identifier.
-   Stream identifier.
-   Sequence number.
-   Packet timestamp / media clock.
-   Codec and frame information negotiated for the session.
-   Authentication tag.
-   Bounded payload size.

Encrypt and authenticate media packets. Use a replay window and reject
duplicates or stale packets.

## 5.4 Group topology

Initial group-call topology:

``` text
               +----------------+
               | Group Owner    |
               | Audio Relay    |
               +-------+--------+
                       |
          +------------+------------+
          |            |            |
       Phone A       Phone B      Phone C
```

The group owner forwards authenticated media between members. The relay
must not decrypt end-to-end media if true end-to-end group encryption is
used. Group key management is a separate security design task.

Limit the first group implementation to a small configurable number of
peers and measure performance. Do not assume unlimited group size.

## 5.5 Mesh / multi-hop topology

Later-phase relay:

``` text
Phone A <----> Relay B <----> Relay C <----> Phone D
```

This requires a routing protocol, hop limit, loop prevention, peer
identity, route expiry, congestion handling, and secure forwarding. A
normal Wi-Fi Direct group does not itself provide multi-hop routing.

------------------------------------------------------------------------

# 6. Audio architecture

## 6.1 Full-duplex call pipeline

Each phone runs capture and playback at the same time:

``` text
MICROPHONE A
    |
AudioRecord
    |
Audio processing / AEC / noise suppression
    |
Opus encoder
    |
Encrypted UDP media packets
    |
Wi-Fi Direct
    |
Jitter buffer
    |
Opus decoder
    |
AudioTrack
    |
SPEAKER B
```

Phone B runs the reverse pipeline simultaneously.

## 6.2 Initial audio configuration

Start with a conservative, interoperable configuration and benchmark on
target phones:

-   Mono audio.
-   48 kHz Opus sampling configuration where supported by the chosen
    library.
-   Short Opus frames (for example 20 ms).
-   A bounded jitter buffer.
-   A target end-to-end latency of approximately 100--200 ms under
    favorable conditions, measured rather than assumed.
-   Adaptive buffering and bitrate based on measured network conditions.

The Opus encoder's internal sample-rate and frame configuration must
follow the library API. Verify the chosen Android Opus binding and
native ABI support before implementation.

## 6.3 Audio engine responsibilities

Create separate interfaces:

``` kotlin
interface AudioCapture {
    suspend fun start(onPcmFrame: (ByteArray) -> Unit)
    suspend fun stop()
}

interface AudioEncoder {
    fun encode(pcm: ByteArray): ByteArray
    fun close()
}

interface AudioDecoder {
    fun decode(packet: ByteArray): ByteArray
    fun close()
}

interface AudioPlayback {
    suspend fun start()
    suspend fun play(pcm: ByteArray)
    suspend fun stop()
}
```

These are design interfaces. Adapt them to the actual codec library and
audio buffer types.

## 6.4 Audio reliability

Implement:

-   Sequence number tracking.
-   Packet-loss measurement.
-   Jitter buffer.
-   Late-packet discard.
-   Concealment strategy supported by the codec.
-   Audio focus management.
-   Audio route changes.
-   Echo cancellation where supported.
-   Microphone mute.
-   Speaker / earpiece selection.
-   Bluetooth headset routing.
-   Cleanup on call termination.

Never perform encoding, decoding, socket I/O, or blocking audio
operations on the main thread.

------------------------------------------------------------------------

# 7. Security architecture

Security is a release requirement, not an optional enhancement.

## 7.1 Device identity

-   Generate a per-installation cryptographic key pair.
-   Store private key material using Android Keystore where supported.
-   Assign a random stable application device ID.
-   Do not use the Wi-Fi Direct MAC address as a permanent identity.
-   Allow users to name devices locally.

## 7.2 Pairing

1.  Devices discover one another.
2.  User explicitly selects a peer.
3.  Exchange protocol capabilities.
4.  Perform authenticated key agreement.
5.  Show a short authentication code or QR-based verification option.
6.  User confirms the peer.
7.  Store the peer's public identity / trust record locally.
8.  Establish a new session key for each session.

Use a reviewed cryptographic protocol and library. Do not invent a key
exchange or encryption scheme.

## 7.3 Session security

-   Authenticated encryption (AEAD).
-   Unique nonce per key.
-   Sequence numbers and replay protection.
-   Session key rotation / rekeying policy.
-   Strict message size limits.
-   Peer authorization for group operations.
-   No private keys or plaintext audio in logs.

For group calls, define a group key-management protocol. A relay
topology and end-to-end encryption must be designed together.

------------------------------------------------------------------------

# 8. Android platform requirements

## 8.1 Permissions

The exact permissions depend on Android API level and feature.

Phase 1 may require:

-   `INTERNET` for local sockets.
-   `ACCESS_WIFI_STATE`.
-   `CHANGE_WIFI_STATE`.
-   `ACCESS_NETWORK_STATE`.
-   `NEARBY_WIFI_DEVICES` on Android 13+.
-   `ACCESS_FINE_LOCATION` for Wi-Fi discovery on older Android
    releases, subject to platform behavior.

Voice calling will additionally require:

-   `RECORD_AUDIO`.
-   Foreground service declarations appropriate to the target Android
    version.
-   `FOREGROUND_SERVICE`.
-   `FOREGROUND_SERVICE_MICROPHONE` where required by the target SDK.
-   A microphone foreground service type.
-   Notification permission handling where applicable.

Request permissions only when the user starts the related feature.
Explain why the permission is needed. Verify current Android
documentation for target SDK behavior and background-start restrictions.

## 8.2 Background operation

When a call is active, use a correctly configured foreground service and
persistent notification. Respect Android's microphone background-use
restrictions. Do not assume a service can be started freely from the
background.

## 8.3 Lifecycle

-   Register and unregister receivers safely.
-   Do not leak Activity references.
-   Use a service/repository owner for long-lived calls.
-   Handle process death and socket closure.
-   Cancel coroutines and close sockets on teardown.
-   Avoid starting duplicate discovery or session tasks.

------------------------------------------------------------------------

# 9. UI / UX specification

## 9.1 Main screen

Show:

-   P.A.A.I. Link branding.
-   Local network status.
-   Scan button.
-   Nearby device list.
-   Paired devices.
-   Active call / group indicator.
-   Current transport state.
-   Settings and diagnostics.

## 9.2 Device discovery screen

Each peer item should show:

-   User-defined device name, if paired.
-   Nearby / connecting / connected status.
-   Pairing state.
-   Connect or disconnect action.

Do not display raw hardware addresses as the primary identity.

## 9.3 Call screen

Show:

-   Peer or group name.
-   Call duration.
-   Connection quality indicator.
-   Mute / unmute.
-   Speaker / earpiece / headset route.
-   End call.
-   Reconnecting state.

## 9.4 Walkie-talkie screen

Show:

-   Selected channel or group.
-   Large press-and-hold talk control.
-   Current speaker.
-   Busy / available floor state.
-   Connection state.
-   Recent talk activity.

## 9.5 Visual direction

Use a modern dark interface with a restrained blue/cyan accent, clear
typography, large touch targets, accessible contrast, and simple status
indicators. Prioritize call usability over decorative animations.

------------------------------------------------------------------------

# 10. Phase-by-phase delivery plan

## PHASE 1 --- Offline discovery and connection foundation

### Goal

Two real Android phones discover each other, form a Wi-Fi Direct group,
and complete an application-level TCP handshake without internet.

### Deliverables

-   Android project and Compose shell.
-   Runtime permissions.
-   Wi-Fi Direct capability check.
-   P2P broadcast receiver.
-   Peer discovery and list.
-   Connect / disconnect.
-   Group-owner and client role detection.
-   Group-owner address retrieval.
-   TCP server and client handshake.
-   Connection state UI.
-   Basic diagnostics.

### Acceptance criteria

-   App installs on two physical phones.
-   Both devices discover each other.
-   A connection can be formed.
-   Group owner and client roles are detected.
-   Client reaches the owner's TCP server.
-   HELLO / ACK handshake succeeds.
-   Test succeeds with mobile data disabled and no internet router.
-   Failures are surfaced with useful diagnostics.

### Explicitly out of scope

-   Voice, audio codec, UDP media, group calls, mesh routing, production
    pairing.

------------------------------------------------------------------------

## PHASE 2 --- One-to-one real-time voice engine

### Goal

Make two connected phones exchange live two-way audio.

### Work items

1.  Select and integrate a maintained Opus Android library.
2.  Verify native ABI support and licensing.
3.  Implement microphone permission flow.
4.  Implement AudioRecord capture.
5.  Implement PCM frame management.
6.  Implement Opus encoding and decoding.
7.  Implement encrypted UDP media transport.
8.  Implement sequence numbers and timestamps.
9.  Implement jitter buffer.
10. Implement AudioTrack playback.
11. Implement audio focus and route handling.
12. Implement mute and audio cleanup.
13. Add basic packet-loss and latency metrics.

### Acceptance criteria

-   Both users hear each other simultaneously.
-   No internet or cloud is used.
-   Audio starts and stops cleanly.
-   Mute works immediately.
-   Call teardown releases microphone, audio track, sockets, and codec
    resources.
-   Audio quality is tested in quiet and noisy environments.
-   Latency and packet loss are measured on actual phones.

### Performance targets

-   Initial end-to-end latency target: 100--200 ms in favorable
    conditions.
-   No unbounded audio queues.
-   No main-thread audio work.
-   No sustained audio underrun/overrun under the supported test
    conditions.

Targets must be validated, not assumed.

------------------------------------------------------------------------

## PHASE 3 --- Call signaling and complete calling UX

### Goal

Make voice calling usable like a phone call.

### Work items

-   Define call state machine.
-   CALL_INVITE.
-   CALL_RINGING.
-   CALL_ACCEPT.
-   CALL_REJECT.
-   CALL_CANCEL.
-   CALL_END.
-   CALL_BUSY.
-   Session negotiation.
-   Incoming call screen.
-   Accept / reject controls.
-   Call timer.
-   Mute and speaker controls.
-   Reconnection behavior.
-   Foreground microphone service.
-   Notification and audio focus integration.
-   Bluetooth headset routing.
-   Handle app background / screen lock.

### Call state model

``` text
IDLE
  |
  v
OUTGOING_INVITE ----> REMOTE_RINGING
  |                         |
  |                         v
  |                      CONNECTING
  |                         |
  +-------------------------+
                            |
                            v
                         ACTIVE
                            |
                  +---------+---------+
                  |                   |
                  v                   v
              RECONNECTING          ENDING
                  |                   |
             +----+----+              v
             |         |            ENDED
             v         v
           ACTIVE     FAILED
```

### Acceptance criteria

-   Incoming call can be accepted and rejected.
-   Caller sees ringing, accepted, rejected, busy, timeout, and
    disconnected states.
-   Both devices can end the call.
-   App handles screen lock according to Android platform rules.
-   Foreground notification is shown during an active microphone
    session.
-   Audio resources are released after ending a call.

------------------------------------------------------------------------

## PHASE 4 --- Walkie-talkie / push-to-talk

### Goal

Provide a reliable half-duplex voice mode.

### Work items

-   Press-and-hold talk UI.
-   Floor request and grant messages.
-   Floor release.
-   Floor timeout.
-   Current speaker indication.
-   Busy channel indication.
-   Group PTT session.
-   Interrupted touch / app lifecycle handling.
-   Optional voice activity indicator.

### PTT protocol concepts

``` text
PTT_REQUEST
PTT_GRANTED
PTT_DENIED
PTT_RELEASE
PTT_KEEPALIVE
PTT_REVOKED
```

The group controller must ensure only one authorized talker owns the
floor at a time. Define timeout and recovery behavior if a phone
disappears while holding the floor.

### Acceptance criteria

-   Holding the talk button transmits voice.
-   Releasing it stops transmission.
-   Other participants hear the speaker.
-   Only one group member owns the floor at a time.
-   A lost peer cannot permanently lock the channel.
-   PTT works without internet.

------------------------------------------------------------------------

## PHASE 5 --- Group communication and offline messaging

### Goal

Support group voice sessions and local text conversations.

### Group voice work items

-   Group creation and invitation.
-   Membership list.
-   Join / leave / kick.
-   Group owner election or recovery strategy.
-   Audio relay.
-   Per-member mute.
-   Active speaker indicator.
-   Group size limits.
-   Group key management.
-   Congestion and bandwidth handling.

### Offline messaging work items

-   Local conversation database using Room.
-   Message ID.
-   Sender and recipient identity.
-   Message timestamp.
-   Delivery status.
-   Retry queue.
-   Duplicate detection.
-   Message acknowledgement.
-   Store-and-forward while peers are temporarily unavailable.
-   Expiration / retention settings.

### Suggested message states

``` text
QUEUED -> SENT -> DELIVERED -> READ
   |        |
   v        v
FAILED   RETRYING
```

A message cannot be delivered to an unreachable device without a path.
Store-and-forward works only when a participating device later
reconnects or a trusted relay is available.

### Acceptance criteria

-   Small test group can establish a voice session.
-   Group membership updates are consistent.
-   Group calls are stable at the selected supported group size.
-   Offline text messages are persisted locally.
-   Delivery acknowledgements are authenticated.
-   Duplicate messages are not displayed twice.
-   No central server is required.

------------------------------------------------------------------------

## PHASE 6 --- Relay networking and range extension

### Goal

Allow devices to forward traffic for other devices when direct
connectivity is unavailable.

### Work items

-   Define relay-capable peer role.
-   Peer graph representation.
-   Route discovery.
-   Hop count / TTL.
-   Loop prevention.
-   Route expiry.
-   Duplicate packet suppression.
-   Relay authorization.
-   Congestion control.
-   Relay battery policy.
-   Network topology screen.
-   Recovery when a relay disappears.

### Important technical constraint

Wi-Fi Direct normally forms a group with a group owner and clients. It
does not automatically provide a general-purpose multi-hop mesh. Android
Wi-Fi concurrency and group behavior vary by hardware and OS. The relay
architecture must be validated on target phones and may require devices
to switch between groups or use a different local transport strategy.

### Acceptance criteria

-   Two devices can communicate through a designated relay in the
    supported test topology.
-   Loops and duplicate forwarding are prevented.
-   Routes expire when peers disappear.
-   Relay loss triggers route recovery.
-   Battery and throughput impact are measured.
-   Security is maintained across relayed traffic.

------------------------------------------------------------------------

## PHASE 7 --- Hardening, performance and release

### Goal

Prepare a stable release for real users.

### Work items

-   Compatibility matrix across Android versions and manufacturers.
-   Security review.
-   Threat model.
-   Protocol fuzzing and malformed-packet tests.
-   Network interruption tests.
-   Battery tests.
-   Long-duration calls.
-   Audio route tests.
-   Accessibility.
-   Localization.
-   Privacy policy and permission rationale.
-   Crash reporting strategy that does not require online core
    functionality.
-   Release signing.
-   Versioning and migration.
-   User guide and troubleshooting.

### Acceptance criteria

-   No known critical security issue.
-   No persistent resource leak after repeated calls.
-   Calls recover or fail cleanly after link interruption.
-   Supported device matrix is documented.
-   Range and latency claims are backed by measurements.
-   Release build is signed and installable.

------------------------------------------------------------------------

# 11. Suggested repository implementation order

Do not ask an agent to write all phases as one giant code change.
Implement and test one milestone at a time.

``` text
Milestone 1: Project skeleton and Phase 1 Wi-Fi Direct
Milestone 2: Stable peer session and control protocol
Milestone 3: Device identity and secure pairing
Milestone 4: Audio capture and playback loopback
Milestone 5: Opus encode/decode tests
Milestone 6: Encrypted UDP one-to-one audio
Milestone 7: Call signaling and call UI
Milestone 8: Foreground call service and Bluetooth routing
Milestone 9: Push-to-talk floor control
Milestone 10: Group voice relay
Milestone 11: Offline messaging
Milestone 12: Multi-hop relay research and prototype
Milestone 13: Security, performance and device compatibility
Milestone 14: Release preparation
```

Every milestone must include:

-   Implementation.
-   Unit tests.
-   Instrumentation tests where appropriate.
-   Build verification.
-   Manual test instructions.
-   Known limitations.
-   Updated documentation.

------------------------------------------------------------------------

# 12. Testing strategy

## Unit tests

Test:

-   Call state machine.
-   PTT floor state machine.
-   Message serialization and validation.
-   Sequence number / replay-window logic.
-   Retry and timeout logic.
-   Peer repository behavior.
-   Group membership transitions.

## Instrumentation tests

Test:

-   Permission flow.
-   Activity lifecycle.
-   Foreground service behavior.
-   Audio resource cleanup.
-   Room persistence.
-   UI state rendering.

## Two-device integration tests

Use two or more physical phones.

  Scenario                           Expected outcome
  ---------------------------------- ----------------------------------------
  Discovery with internet disabled   Peers discover
  Direct connection                  Group forms
  TCP handshake                      ACK received
  One-to-one voice                   Two-way audio
  Mute                               Local microphone stops transmitting
  Call end                           All resources released
  PTT                                Audio only while authorized
  Group call                         Members receive permitted streams
  Peer leaves                        Membership and session update
  Temporary link loss                Reconnect or clear failure
  No microphone permission           Voice feature blocked with explanation
  Unsupported Wi-Fi Direct           Clear unsupported state

## Range test methodology

Test in at least these environments:

1.  Open outdoor line of sight.
2.  Indoor same-room.
3.  Indoor through one wall.
4.  Indoor through multiple walls.
5.  Crowded Wi-Fi environment.

Record:

-   Phone models and Android versions.
-   Distance.
-   Obstacles.
-   RSSI / link metrics when available.
-   Packet loss.
-   Audio latency.
-   Disconnects.
-   Battery consumption.

Do not publish a single range figure from one test.

------------------------------------------------------------------------

# 13. Definition of done by phase

  Phase   Definition of done
  ------- ------------------------------------------------------------------
  1       Two phones discover, connect, and complete TCP handshake offline
  2       Two phones exchange simultaneous live audio
  3       Complete incoming/outgoing call experience
  4       Reliable push-to-talk with floor control
  5       Group voice and offline messaging
  6       Validated relay / multi-hop prototype
  7       Security, compatibility, performance and release readiness

------------------------------------------------------------------------

# 14. Instructions for Claude Code

Use this document as the master specification.

## Required working rules

1.  Inspect the existing repository before editing.
2.  Preserve working code and avoid unnecessary rewrites.
3.  Confirm the current Gradle, Kotlin, Android Gradle Plugin, Compose,
    and SDK versions.
4.  Implement only the requested phase or milestone.
5.  Do not create fake implementations, placeholder success states, or
    buttons that do nothing.
6.  Do not claim Wi-Fi Direct or audio works without testing.
7.  Keep UI, domain, network, security, and audio responsibilities
    separate.
8.  Use Kotlin coroutines for asynchronous work.
9.  Never block the main thread with socket, discovery, encoding,
    decoding, or audio operations.
10. Handle permission denial and unsupported hardware.
11. Add tests for state machines and protocol parsing.
12. Run Gradle build and tests after each milestone.
13. Report exact files changed, commands run, build result, test result,
    and remaining limitations.
14. Do not silently remove existing functionality.
15. Never put secrets, private keys, or plaintext private audio into
    logs.

## Prompt to begin implementation

``` text
Read the complete P.A.A.I. Link master specification.

First inspect the current repository and report:
- Existing project structure
- Gradle / Kotlin / Android Gradle Plugin versions
- Current SDK configuration
- Existing implementation and build status
- Any compatibility issues

Then implement Phase 1 only:
- Wi-Fi Direct capability check
- Runtime permission handling
- Peer discovery
- Peer list UI
- Connect and disconnect
- Group owner / client information
- A lifecycle-safe TCP server/client handshake
- Useful connection diagnostics
- Unit or instrumentation tests where appropriate

Do not implement voice, Opus, UDP media, group calls, or mesh networking yet.

Use current compatible Android APIs. Do not copy obsolete dependency versions blindly.
Do not claim a feature is working unless the build and relevant tests pass.
After implementation, run the Gradle build and tests and provide:
1. Files created or modified
2. Commands executed
3. Build and test results
4. Manual two-phone test instructions
5. Known limitations
```

## Prompt for each subsequent phase

``` text
Continue the P.A.A.I. Link project using the master specification.

Before coding:
1. Inspect the current code and tests.
2. Identify the next incomplete milestone.
3. List dependencies and Android platform constraints.
4. Propose a small implementation plan.

Implement only that milestone. Preserve existing working behavior.
Add tests, run the build, and report exact results and limitations.
Do not claim hardware behavior is validated unless tested on physical devices.
```

------------------------------------------------------------------------

# 15. Official Android references

Use current official documentation during implementation because
permissions and background-service requirements change with Android
versions.

-   Android Wi-Fi Direct:
    https://developer.android.com/develop/connectivity/wifi/wifip2p
-   Wi-Fi P2P manager:
    https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager
-   Nearby Wi-Fi devices permission:
    https://developer.android.com/develop/connectivity/wifi/wifi-permissions
-   Android audio capture:
    https://developer.android.com/media/platform/mediarecorder
-   AudioTrack:
    https://developer.android.com/reference/android/media/AudioTrack
-   Foreground services:
    https://developer.android.com/develop/background-work/services/foreground-services
-   Android Keystore:
    https://developer.android.com/privacy-and-security/keystore
-   Kotlin coroutines:
    https://kotlinlang.org/docs/coroutines-overview.html
-   Opus codec: https://opus-codec.org/

------------------------------------------------------------------------

# 16. Final project objective

P.A.A.I. Link should become a dependable local communication system that
works without internet infrastructure.

The development sequence is deliberate:

1.  Prove direct device connectivity.
2.  Prove low-latency two-way audio.
3.  Build a reliable calling experience.
4.  Add walkie-talkie behavior.
5.  Add group calls and offline messages.
6.  Explore relay networking to extend coverage.
7.  Harden, test, and release.

**Do not skip the physical-device validation between phases.** Wi-Fi
Direct behavior, audio performance, Bluetooth routing, and range vary
across Android devices. The app should be designed around measured
behavior rather than assumptions.
