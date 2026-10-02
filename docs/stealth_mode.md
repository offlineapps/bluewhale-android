# Stealth mode (listen only)

Settings → **Stealth (listen only)** turns the phone into a silent receiver on the Bluetooth
mesh.

## What changes

| | Normal | Stealth |
|---|---|---|
| BLE advertising | on | **off**: scanners do not see a Bluewhale device |
| GATT server (others connect to you) | on | **off** |
| Announce (nickname, keys) | every 30 s | **never**: peers do not learn who you are |
| Your messages, receipts, handshakes, sync | sent | **not sent** |
| Relaying other people's packets | yes | **no** |
| Scanning and connecting to peers | yes | yes |
| Receiving public messages and channels | yes | yes |

Every packet bound for the radio passes through one place
(`BluetoothPacketBroadcaster`), and stealth drops them there, so nothing slips out by a side
path. Typing a public message in stealth says that it was not sent. A private message waits in
the outbox until stealth is turned off (or goes over Nostr for a mutual favourite, which does
not touch Bluetooth). Leaving stealth announces you straight away.

## What it does not hide

- To receive, the phone connects to nearby peers as a GATT client. Those peers see an anonymous
  Bluetooth connection from a randomised address. They do not see a nickname, peer ID or key.
- Android scans actively, so nearby radios can see scan requests from a randomised address.
- A stealth device relays nothing, so it is a dead end in the mesh. If many people use it at
  once, the mesh gets thinner.
- Private messages addressed to you cannot be decrypted without a Noise session, and a session
  needs a handshake, which stealth suppresses. In stealth you read the public timeline and
  channels, not new private messages.
