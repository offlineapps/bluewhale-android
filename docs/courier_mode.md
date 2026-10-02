# Courier mode (store and carry)

The mesh only reaches people within a few Bluetooth hops. Courier mode moves private messages
between groups that are never in range at the same time, by having people carry them.

## Sending

In a private chat with someone out of reach, type:

```
/courier meet at the north gate at six
/courier! the bridge is closed        (urgent)
```

The message is sealed to the recipient's identity key and handed to everyone in range. You need
their key, so you must have met them once (or have them as a favourite). Your phone always
carries its own courier messages and hands them to every neighbour it meets.

A courier message arrives within three days or not at all. There is no delivery receipt beyond
that: the recipient's phone sends an acknowledgement that couriers use to drop their copies, but
it may never travel back to you.

## Carrying

Settings → **Courier mode** lets your phone carry other people's sealed messages. When it meets
someone it hands over every envelope that person has not had yet, most urgent first, up to 50
per meeting. When it meets the recipient, the recipient opens the message and acknowledges it,
and the acknowledgement spreads to other couriers so they stop carrying copies.

The store holds up to 300 envelopes. When full, expired ones go first, then the oldest normal
ones. Urgent envelopes and your own are kept. It survives restarts and is wiped by panic.

## What a courier learns

| | Visible to couriers |
|---|---|
| Message text | no |
| Sender | no (their key travels encrypted) |
| Recipient | an opaque 16-byte tag. A courier who already knows the recipient's key can recognise it |
| Expiry, priority, size | yes |

Envelopes are sealed with the one-way Noise pattern `Noise_X_25519_ChaChaPoly_SHA256`, using
the same primitives and keys as live Noise sessions. The envelope header is bound in as the
prologue, so a courier that changes the expiry, priority or tag makes the envelope unreadable.
As with every one-way pattern there is no forward secrecy: someone who later steals the
recipient's identity key and kept a copy of the envelope can read it.

## Wire format

Courier packets use message type `0x30`, are sent with TTL 0 (direct neighbours only, never
flooded), and are kept out of gossip sync. See `CourierWire` for the byte layout. Older versions
of the app ignore the type.
