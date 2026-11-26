# Security design and threat model

## Assets

- Voice audio and text content.
- Who talks to whom (metadata), device names.
- Each phone's identity private key and its list of trusted devices.

## Adversaries considered

| Adversary | Capability |
|---|---|
| Nearby attacker | Joins Wi-Fi Direct groups, scans, sends arbitrary packets, records radio traffic |
| Active man-in-the-middle | Intercepts the first connection between two phones |
| Malicious relay | A paired phone that forwards (or drops, delays, replays) other people's traffic |
| Removed group member | Former member who still has an old group key |
| Lost / stolen phone | Physical access to an unlocked or locked phone |

## Controls

| Threat | Control | Where |
|---|---|---|
| Eavesdropping on links | Noise XX link encryption (X25519, ChaCha20-Poly1305, SHA-256) | `core/transport/Transport.kt` |
| MITM during first contact | 6-digit code from the handshake hash, compared by both users; trust stored only after both confirm | `Sas`, `LinkNode.confirmPairing` |
| Impersonation | Device ID = SHA-256(static public key); sealed messages authenticate the sender's static key | `DeviceId`, `Sealer` |
| Relay reading or altering messages | End-to-end Noise K sealing; routing header bound as prologue | `Sealer`, `Envelope.boundHeader` |
| Relay reading or altering audio | Per-call / per-epoch media key known only to participants; AEAD over header | `MediaCipher`, `MediaPacket.aad` |
| Replay | Noise counters on links; message-ID de-duplication for sealed messages; 256-entry replay window per media stream; stale-timestamp check | `DedupeCache`, `ReplayWindow` |
| Spoofed / injected UDP | Accepted only from endpoints of paired direct links; authenticated before any processing or forwarding | `LinkNode.udpLoop`, `VoiceSession.onPacket` |
| Malformed input | Strict length-prefixed binary formats, bounded sizes and counts, UTF-8 validation; fuzz tests | `ProtocolTest` |
| Unpaired devices using the network | Unpaired links can only pair; no routing, relaying, messages or media | `LinkNode.handleEnvelope` |
| Removed member listening | New media key every membership epoch | `GroupManager.newEpoch` |
| PTT channel locked by a lost phone | Floor keepalive timeout (3 s) and max talk time (60 s) | `FloorArbiter` |
| Forwarding floods | Per-source token bucket on relayed media, bounded queues, TTL, hop limit | `TokenBucket`, `RouteTable` |
| Key theft from storage | Identity private key wrapped with an AndroidKeyStore AES-GCM key; stored in no-backup storage; backups disabled | `KeystoreIdentity`, manifest |
| Leaking secrets in logs | Logs contain IDs, names and states only — never keys, bodies or audio | `LogBuffer` callers |

## Known limitations (accepted for now)

- **Sealed messages (Noise K) lack forward secrecy against compromise of the recipient's static key**
  combined with recorded traffic. Calls use fresh random media keys, but those keys travel inside
  K-sealed invites. A future version could run an interactive Noise handshake per session over
  the routed path.
- **Group messages are attributed by the host.** Members trust the host (whom they paired with) to
  relay group text faithfully; the host could forge another member's group message.
- **Group media keys are shared** among members, so a member could impersonate another member's
  audio stream inside the group. Per-sender signatures would cost bandwidth and battery.
- **Metadata is visible to relays and nearby observers**: which device IDs talk, when and how much.
  Wi-Fi Direct device names are broadcast during discovery.
- **Pairing depends on users actually comparing the code.**
- **Denial of service** by radio jamming or a misbehaving relay dropping traffic cannot be prevented.
- No independent security review has been done. Do this before relying on the app for anything sensitive.

## Release checklist

- [ ] Independent review of `core/crypto` and `LinkNode` message handling.
- [ ] Long-running fuzzing of all decoders (current tests: 20 000 random inputs + mutations).
- [ ] Verify no secrets in `adb logcat` during pairing, calls and messaging.
- [ ] Release keystore created and backed up offline (see RELEASE.md).
