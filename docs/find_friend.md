# Finding a friend in a crowd

Works without a network: everything goes over Bluetooth, and GPS needs no data connection.

Open it from a message or nickname (long-press → **find *name* nearby**), or type `/find <name>`
(`/find` alone in a private chat).

## Warmer / colder

While the finder is open the phone reads the Bluetooth signal strength of the direct link to
that person every 400 ms, instead of every 5 s. `ProximityEstimator` smooths the readings and
shows:

- a zone (right here, very near, nearby, in range but far),
- **warmer / colder** (the smoothed signal now against three seconds ago, ±2 dB),
- a rough distance and the raw dBm,
- haptic pulses that speed up as the signal strengthens, so you can follow it with the phone in
  your pocket.

Signal strength is a poor ruler: a body between the phones costs 10 dB or more. Walk, then follow
the trend. If the person is on the mesh but not linked to your phone, the finder says so.

## Ring their phone

**Ring their phone** sends a request inside your encrypted session. Their phone plays its
notification sound and vibrates, so it follows silent mode, but only if you are one of their
favourites. Anyone else gets "tried to ring your phone" in the chat. A phone rings at most once
every 10 seconds per sender.

## Swap GPS positions

**Send my gps** sends your current fix once, encrypted to that person only. Their chat shows how
far and in which direction you are from their own fix ("120 m north-east of you (±8 m, just
now)"), or your coordinates if they have no recent fix. Nothing is shared continuously, and
nothing goes to a server.

## Protocol

Two Noise payload types, carried only inside an established session and ignored over Nostr:

| Type | Value | Payload |
|---|---|---|
| `FIND_RING` | `0x40` | empty |
| `LOCATION_SHARE` | `0x41` | version (1), latitude (f64), longitude (f64), accuracy m (f32), fix time ms (i64) |

These are Bluewhale additions. Older clients and bitchat drop unknown payload types.
