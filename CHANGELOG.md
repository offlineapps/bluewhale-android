# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),

## [Unreleased]

## [1.1.1] - 2026-07-28

A security release. Every mesh-facing fix below is reachable from unauthenticated
Bluetooth traffic, so upgrading is recommended for anyone running 1.1.0 or 1.0.0.

### Security
- An announce may now only claim the peer ID that its own announced Noise key
  hashes to, and a signing key learned from an announce stays provisional until
  one is proven inside an established Noise session. Without that binding any
  device in range could take over another peer's identity by announcing it
  under keys of its own (#12).
- The NIP-17 seal is authenticated before the rumor's author is trusted, so a
  private message can no longer be attributed to somebody who did not send it
  (#13).
- The declared decompressed size of a compressed payload is checked before it
  is acted on, so a small hostile packet can no longer exhaust memory (#16).
- The replay window advanced in the wrong direction: recorded nonces moved away
  from, rather than with, the newest one as the window slid, so a nonce that
  had already been seen could be accepted again (#17).
- The SMS gateway receiver now requires BROADCAST_SMS, so only the system can
  trigger it rather than any app on the device (#18).
- LEAVE packets must carry a valid signature. An unsigned one is relayed and
  removes the sender from the peer list of every node that sees it, so anyone
  in range could evict any peer across the whole mesh (#19).
- An established Noise session is no longer torn down when an unauthenticated
  handshake arrives. A replacement is negotiated alongside it and only takes
  over once it completes, so a handshake packet, which carries no signature and
  whose sender ID is only a claim, can no longer cut two peers off from each
  other. A peer that genuinely lost its state still recovers (#20).
- Signatures on geohash events and location notes are verified (#21).
- Sync requests must carry a valid signature, so answering one cannot disclose
  cached history to an unauthenticated peer (#22).
- State keyed by an unauthenticated peer ID is bounded. Per-peer packet queues,
  tracked peers, in-progress handshakes and stored signing key bindings all
  have caps, so a peer inventing identities cannot grow memory without limit
  (#23).
- The list of direct neighbours is no longer attached to announces unless it is
  switched on in debug settings. It mapped who is physically next to whom for
  anyone listening (#24).
- The 8 bytes advertised over BLE are now an HMAC over a device-local secret
  and the current 15 minute window, instead of a value derived from the peer
  ID. A scanner can still tell two advertisements apart within a window but can
  no longer recognise a device across them (#25).

### Changed
- Screen capture is blocked across every screen in the app. Screenshots and
  screen recordings come out blank, the app's contents are hidden in the recent
  apps thumbnail, and it will not mirror to non-secure external displays (#14).

### Fixed
- `/ai` failed on every model in release builds with "Field modelPath_ for T4.g
  not found". R8 stripped the protobuf fields that MediaPipe resolves by name
  when it passes the model options to the native engine (#11).

## [1.1.0] - 2026-07-16

### Added
- `/ai <prompt>`: offline text generation. A language model runs on the device
  and the reply is posted to the conversation the command was typed in, under
  your nickname, prefixed with `[ai]` and quoting the prompt. Only successful
  answers are transmitted; the thinking indicator, failures and usage errors
  stay on the device. No model ships with the app: install a single MediaPipe
  `.task` bundle as described in `docs/offline_ai.md` (#8).
- Message range: a settings slider limiting how many hops (1 to 8, default 8,
  the previous fixed reach) messages this device originates travel through the
  mesh. Relaying for other peers is unaffected. A local warning is shown when
  a private message is sent to a peer the mesh graph places beyond the
  configured range (#9).
- Optional "reduce background activity" setting, off by default, that slows
  background presence to 12 to 18 minutes for maximum battery savings, at the
  cost of dropping out of geohash lists until the app is opened (#10).

### Changed
- Reduced idle background battery usage: the mesh notification is event driven
  instead of refreshed every 5 seconds, RSSI polling backs off when no peers
  are connected, and presence heartbeats slow down while backgrounded. Idle
  wakeups drop from roughly 1620 to 87 per hour with default settings (#10).
- The arm64 APK grows about 26 MB from the bundled MediaPipe inference runtime,
  whether or not a model is installed (#8).

## [1.0.0] - 2026-06-10

First release of **bluewhale**, a fork of bitchat.

### Changed
- Rebrand from bitchat to bluewhale: package `com.bluewhale.android`,
  applicationId `com.bluewhale.droid`, app name, themes, deep-link scheme, and
  localized strings (#5). Default accent color is Light Blue.

### Added
- SMS webhook gateway: turn the device into a gateway that forwards incoming SMS
  to a configured HTTPS webhook, with optional Nostr directory publishing and
  discovery (#2).
- Selectable text/accent color (Green, Yellow, Pink, Light Blue, Orange) with
  distinct light/dark variants across chat, sheets, and input; defaults to
  Light Blue (#4).

### Fixed
- FragmentManager: bound reassembly memory with per-set and global byte caps plus
  LRU eviction, preventing memory exhaustion from many in-flight fragment sets (#6).

## [1.4.0] - 2025-10-15
### Fixed
- fix: Resolve debug settings bottom sheet crash on some devices (Issue #472)
  - Fixed IllegalFormatConversionException in DebugSettingsSheet.kt when scrolling through debug settings
  - Corrected string formatting for debug_target_fpr_fmt and debug_derived_p_fmt string resources
  - Improved string resource parameter handling for numeric values

## [0.7.2] - 2025-07-20
### Fixed
- fix: battery optimization screen content scrollable with fixed buttons

## [0.7.1] - 2025-07-19

### Added
- feat(battery): add battery optimization management for background reliability

### Fixed
- fix: center align toolbar item in ChatHeader - passed modifier.fillmaxHeight so the content inside the row can actually be centered
- fix: update sidebar text to use string resources
- fix(chat): cursor location and enhance message input with slash command styling

### Changed
- refactor: remove context attribute at ChatViewModel.kt
- Refactor: Migrate MainViewModel to use StateFlow

### Improved
- Use HorizontalDivider instead of deprecated Divider
- Use contentPadding instead of padding so items remain fully visible


and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.7]

### Added
- Location services check during app startup with educational UI
- Message text selection functionality in chat interface
- Enhanced RSSI tracking and unread message indicators
- Major Bluetooth connection architecture refactoring with dedicated managers

### Fixed
- **Critical**: Android-iOS message fragmentation compatibility issues
  - Fixed fragment size (500→150 bytes) and ID generation for cross-platform messaging
  - Ensures Android can properly communicate with iOS devices
- DirectMessage notifications and text copying functionality
- Smart routing optimizations (no relay loops, targeted delivery)
- Build system compilation issues and null pointer exceptions

### Changed
- Comprehensive dependency updates (AGP 8.10.1, Kotlin 2.2.0, Compose 2025.06.01)
- Optimized BLE scan intervals for better battery performance
- Reduced excessive logging output

### Improved
- Cross-platform compatibility with iOS and Rust implementations
- Connection stability through architectural improvements
- Battery performance via scan duty cycling
- User onboarding with location services education

## [0.6]

### Added
- Channel password management with `/pass` command for channel owners
- Monochrome/themed launcher icon for Android 12+ dynamic theming support
- Unit tests package with initial testing infrastructure
- Production build optimization with code minification and shrinking
- Native back gesture/button handling for all app views

### Fixed
- Favorite peer functionality completely restored and improved
  - Enhanced favorite system with fallback mechanism for peers without key exchange
  - Fixed UI state updates for favorite stars in both header and sidebar
  - Improved favorite persistence across app sessions
- `/w` command now displays user nicknames instead of peer IDs
- Button styling and layout improvements across the app
  - Enhanced back button positioning and styling
  - Improved private chat and channel header button layouts
  - Fixed button padding and alignment issues
- Color scheme consistency updates
  - Updated orange color throughout the app to match iOS version
  - Consistent color usage for private messages and UI elements
- App startup reliability improvements
  - Better initialization sequence handling
  - Fixed null pointer exceptions during startup
  - Enhanced error handling and logging
- Input field styling and behavior improvements
- Sidebar user interaction enhancements
- Permission explanation screen layout fixes with proper vertical padding

### Changed
- Updated GitHub organization references in project files
- Improved README documentation with updated clone URLs
- Enhanced logging throughout the application for better debugging

## [0.5.1] - 2025-07-10

### Added
- Bluetooth startup check with user prompt to enable Bluetooth if disabled

### Fixed
- Improved Bluetooth initialization reliability on first app launch

## [0.5] - 2025-07-10

### Added
- New user onboarding screen with permission explanations
- Educational content explaining why each permission is required
- Privacy assurance messaging (no tracking, no servers, local-only data)

### Fixed
- Comprehensive permission validation - ensures all required permissions are granted
- Proper Bluetooth stack initialization on first app load
- Eliminated need for manual app restart after installation
- Enhanced permission request coordination and error handling

### Changed
- Improved first-time user experience with guided setup flow

## [0.4] - 2025-07-10

### Added
- Push notifications for direct messages
- Enhanced notification system with proper click handling and grouping

### Improved
- Direct message (DM) view with better user interface
- Enhanced private messaging experience

### Known Issues
- Favorite peer functionality currently broken

## [0.3] - 2025-07-09

### Added
- Battery-aware scanning policies for improved power management
- Dynamic scan behavior based on device battery state

### Fixed
- Android-to-Android Bluetooth Low Energy connections
- Peer discovery reliability between Android devices
- Connection stability improvements

## [0.2] - 2025-07-09

### Added
- Initial Android implementation of bitchat protocol
- Bluetooth Low Energy mesh networking
- End-to-end encryption for private messages
- Channel-based messaging with password protection
- Store-and-forward message delivery
- IRC-style commands (/msg, /join, /clear, etc.)
- RSSI-based signal quality indicators

### Fixed
- Various Bluetooth handling improvements
- User interface refinements
- Connection reliability enhancements

## [0.1] - 2025-07-08

### Added
- Initial release of bitchat Android client
- Basic mesh networking functionality
- Core messaging features
- Protocol compatibility with iOS bitchat client

[Unreleased]: https://github.com/permissionlesstech/bitchat-android/compare/0.5.1...HEAD
[0.5.1]: https://github.com/permissionlesstech/bitchat-android/compare/0.5...0.5.1
[0.5]: https://github.com/permissionlesstech/bitchat-android/compare/0.4...0.5
[0.4]: https://github.com/permissionlesstech/bitchat-android/compare/0.3...0.4
[0.3]: https://github.com/permissionlesstech/bitchat-android/compare/0.2...0.3
[0.2]: https://github.com/permissionlesstech/bitchat-android/compare/0.1...0.2
[0.1]: https://github.com/permissionlesstech/bitchat-android/releases/tag/0.1
