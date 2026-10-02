# L2CAP channels for large transfers

Over GATT a file travels as hundreds of MTU-sized fragments, each a separate write with a
pause between them. Bluetooth LE also has connection-oriented channels (L2CAP CoC, Android 10+):
a stream with link-layer flow control that moves the same bytes several times faster and with
far less per-packet overhead.

## How it is used

- On start, a device running Android 10+ opens an insecure L2CAP listening channel and adds its
  PSM to its announce as TLV `0x42` (2 bytes, big-endian). Peers that do not know the TLV skip it.
- When a **private file** goes to a **direct neighbour** that advertised a PSM, the signed,
  Noise-encrypted packet is written whole on a channel to that device, as one frame:
  `[length: u32 BE][packet]`. The receiver answers `0x06` once the whole frame has arrived.
- No acknowledgement within 20 s, a connection that does not open within 8 s, or any error: the
  same packet goes over GATT as before.
- On the receiving side the packet takes the GATT path from there on: signature checks,
  deduplication and decryption are unchanged.

Public files and relayed traffic still use GATT; they go to many peers and through relays.

## Safety

The channel is insecure at the Bluetooth layer, exactly like the GATT link: nothing is paired.
Security comes from the packet (Ed25519 signature, Noise encryption). Because anyone in range
can open a channel, frames are capped at the reassembly limit (1 MB plus slack), at most four
inbound channels are served at once, and a channel idle for 30 s is closed.
