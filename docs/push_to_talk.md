# Live push-to-talk

Holding the mic button records a voice note as before. When there is a live route, the audio
is also streamed while you talk, so people in range hear you as you speak, like a walkie-talkie.
The voice note still goes out on release and replaces the live recording on the receiver, so
nothing is lost if some live packets were.

| Conversation | Live route |
|---|---|
| Mesh timeline | when at least one peer is connected; broadcast to the mesh |
| Private chat | when there is an established Noise session with the peer; encrypted |
| Channels, location channels, offline contacts | none: a plain voice note |

Settings → **Live push-to-talk** turns both sending and live playback off.

## Receiving

A live burst appears as a voice message marked **LIVE** while it arrives. It plays
automatically only while the app is in the foreground with that conversation open; otherwise
it is kept and can be played later. While someone talks on the public mesh, the message field
says "<name> is live", so you do not talk over them.

Public frames are taken only from verified peers and only if they are fresh (within 30 s).
Each burst is bounded in size and rate (384 KB, about 6 KB/s), at most eight bursts are
assembled at once, and gaps are skipped after 550 ms so a lost packet does not stall playback.

## Wire format

This is the push-to-talk protocol from upstream bitchat (`feat(voice): add live push-to-talk`),
compatible with bitchat for Android and iOS:

- public frames: message type `VOICE_FRAME = 0x29`, signed, never added to gossip sync. Relays
  cap their TTL at 5 on meshes of more than six peers and add 8 to 26 ms of jitter;
- private frames: Noise payload type `VOICE_FRAME = 0x08` inside the peer's session;
- payload: `[burstID: 8][seq: UInt16 BE][flags: UInt8][...]`, with START (codec), frame groups
  (length-prefixed AAC-LC 16 kHz mono access units), END (packet count, duration) and CANCEL.

## Sending reliably over GATT

Android allows one outstanding GATT operation per link. Packets used to be written back to back
and depended on the 20 ms gap between fragments. Live voice sends a packet every 64 ms on top of
everything else, so writes and notifications now go through a per-link queue that starts the next
operation from the completion callback. Writes use write-without-response.
