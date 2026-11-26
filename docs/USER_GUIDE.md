# User guide and troubleshooting

## Getting connected
1. Open SRCS on both phones and switch **Online** on (allow notifications).
2. Tap the scan button (allow *Nearby devices*, or *Location* on Android 12 and older).
3. Tap **Connect** next to the other phone. The other phone shows a system invitation — accept it.
4. Both phones show the same 6-digit code. Tap **Codes match** on both. You only do this once.

## Three or more phones
One phone taps **Host a group**. The others scan and connect to that phone. The host relays calls
and messages between the others, so phones that cannot reach each other directly can still talk,
as long as both reach the host.

## Adding an iPhone
iPhones cannot use Wi-Fi Direct, but they can join an Android host's group as a normal Wi-Fi
network. The iPhone app is planned; this is the Android side of it.
1. An Android phone taps **Host a group**, then **Add an iPhone**. It shows a QR code, the
   network name and the password.
2. On the iPhone, open the Camera, point it at the QR code and tap **Join network** (or type the
   name and password in Settings → Wi-Fi).
3. Open SRCS on the iPhone. It connects to the host at 192.168.49.1 and you compare the pairing
   code as usual.

The Android host must stay in range and keep hosting; the iPhone reaches everyone else through it.

## Calls
Tap the phone icon next to a paired device. During a call: mute, choose Phone / Speaker /
Bluetooth / Headset, end. If the connection drops, the call shows *Reconnecting…* for up to
25 seconds.

## Walkie-talkie and group calls
Group icon (top bar) → choose *Walkie-talkie* or *Group call*, select paired devices, start.
Invited phones see an invitation on their home screen. In walkie-talkie mode hold the big button
to talk; if someone else is talking the channel shows as busy.

## Messages
Messages to a phone that is out of range wait on your phone (*Waiting for device*) and are sent
automatically when it is reachable — directly or through a paired relay. Ticks show *Delivered*
and *Read*.

## Troubleshooting

| Problem | Try |
|---|---|
| Nobody found | Wi-Fi must be on (it does not need a network). Scan on **both** phones at the same time. On Android 12 and older switch Location on. |
| "Wi-Fi Direct is busy" | Wait a few seconds and scan again; leave any existing Wi-Fi Direct group. |
| Connected but no pairing dialog | Diagnostics → Links. If empty, Diagnostics → Connect by address `192.168.49.1` on the client phone. |
| Codes differ | Cancel. Someone may be interfering; try again somewhere else. |
| No audio | Allow the microphone permission; check that no other app is using the microphone. |
| Echo | Use earpiece or a headset; keep speaker volume moderate. |
| Calls drop at distance | Move closer or put a third phone in between as host / relay. Walls reduce range a lot. |
| Messages stay "Waiting for device" | The other phone is out of reach. They are sent when it comes back (kept up to 24 h). |
| Battery | Go offline when not needed; relaying for others pauses automatically at low battery. |

## Privacy
No accounts, servers or internet. Everything is end-to-end encrypted between phones. Messages and
call history stay on your phone; delete conversations or set automatic deletion in Settings.
