# Jamming detection

Bluetooth shares the 2.4 GHz band with cheap jammers. A phone cannot measure the noise floor,
so Bluewhale looks for what a jammer does to the mesh, and for what ordinary movement does not.

## Signals

**Link storm.** Three or more Bluetooth links fail with radio timeouts (supervision timeout,
LL response timeout, connection establishment failure) within 15 seconds. Losing every link at
once also counts if there were at least two. Clean disconnects never count. When you walk away
from a group, links drop one at a time over minutes, and that is not a storm.

**Silence.** The app is scanning, at least three Bluewhale devices were seen in the last two
minutes, and none has been heard for 30 seconds of scan time. Time with scanning off (duty
cycling, Bluetooth off) does not count.

| Signals | Level |
|---|---|
| none | clear |
| one | possible interference |
| both, within 30 s of each other | likely jamming |

## What happens

- **Possible**: a line in the chat says what was seen.
- **Likely**: a warning in the chat with advice (move a few rooms or a street away, get line of
  sight), and **survival mode**: the radio goes to full power (continuous low-latency scanning,
  the strongest advertising) to give the best chance of getting through a weak jammer. Survival
  mode is skipped on a critical battery. Messages keep waiting in the outbox and go out when links
  come back.
- **Clear again**: survival mode ends and the chat says so. A signal stays raised for a minute
  after its cause stops, and silence ends with the first advertisement heard.

Turning your own Bluetooth off resets the detector without a notice, because dropped links
caused by your own radio are not jamming.

## Limits

This is a heuristic. A crowd leaving a room together, or a phone moved into a metal box, can
look like jamming. A smart jammer that only targets the advertising channels, or jams in short
bursts, may only show up as "possible". The detector cannot tell where a jammer is.
