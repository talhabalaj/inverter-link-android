# Verification and parity

Android 0.1.0, version code 1. Source baseline: iOS build 20261013.9 (`6458b22`).

## Automated evidence

17 JVM tests pass. Coverage includes:

- Recorded PI30 CRC bytes, rejected checksums / NAK / non-ASCII replies.
- Fragmented and combined tunnel frames, length/overflow rejection and transaction/route pinning.
- The verified 24-field reading layout and malformed status rejection.
- Private IP / hostname validation.
- All 37 setting IDs and exact expected command formats, integer/decimal boundaries, dynamic charging choices, injection rejection, unavailable third LED color and normalized readback.
- Real loopback TCP handshake, identity and PI30 detection, status/settings reads, cancellation of a pending socket read.
- An ACK/readback mismatch that returns an error and sends the write once only.

Debug and unsigned Release builds pass. Lint has no errors; remaining notices concern dependency updates and Android’s deprecated backup attribute. Backup/transfer exclusion rules are supplied.

The user-facing Release APK is separately signed with a local RSA key and checked by `apksigner`. Signing keys and passwords are outside the repository. GitHub Actions builds source, runs tests/lint and exports the debug APK and reports.

The signed Release APK installs and launches on the API 36 emulator. A `preview=true` intent is ignored by Release, which opens the normal empty Devices screen. The private GitHub prerelease asset's SHA-256 matches the local signed APK recorded in `docs/build-verification.json`.


[GitHub Actions run 38460212212](https://github.com/talhabalaj/inverter-link-android/actions/runs/38460212212) passed tests, lint, Debug and Release APK builds on commit `d6c93b7`, after public-history sanitization. Subsequent documentation updates do not alter application code.

## Emulator walkthrough

Pixel 9 Pro, Android 16 / API 36, labeled sample data:

- Devices has Connected / Saved / Discovered groups and Devices / Settings root navigation.
- A device opens the dashboard without bottom navigation.
- The Filament house renders, supports gestures and binds battery fill, window/panel colors and particles to sample readings.
- Details opens inverter settings and diagnostics.
- LED color presets update RGB hex and preview; unchanged values disable Review; changed values open a separate current/new sheet. Preview Apply is disabled.
- White retains the selected hue; changing hue then dragging saturation/brightness selects an arbitrary green RGB value, with synchronized hex and preview.
- Disconnect clears readings; inverter settings shows one offline message without category controls.
- Purple accent updates controls and survives a cold launch.
- Open default inverter opens the saved sample dashboard after force-stop/relaunch.

Argent’s native Compose tree occasionally remained stale after state changes. Screenshots were used only where the tree could not expose the current controls; this limitation is not treated as an app failure.

## Feature parity checklist

| iOS function | Android implementation |
| --- | --- |
| Device discovery and manual connection | Wi-Fi-bound UDP probes, IPv4/hostname entry, TCP callback |
| Saved names, default, forget | DataStore library and per-device menu |
| Reconnect after address changes | Hostname, last IP, discovery and pinned logger identity |
| Compact dashboard without tabs | Native Compose dashboard and Details |
| Interactive 3D energy scene | Filament GLB, orbit/zoom, reading-driven materials/fill/particles |
| Live measurements and latest time | Same parsed fields, units, modes, warning bits and timestamp |
| 37 inverter settings | Same mappings, limits and availability rules |
| Full color picker, presets, hex | Compose HSV picker, accessible sliders, presets and RGB input |
| Separate review and verified write | Native modal sheet, ACK then fresh readback; no automatic retries |
| Eleven diagnostic queries | Fixed query enum and selectable raw result |
| Refresh/accent/default launch | Same interval and accent choices; persistent preferences |
| Live Activity | Android notification with live/paused readings and timestamp; promotion requested where supported |
| Background behavior | Monitoring pauses on background, reconnects to an open dashboard |
| Reduce Motion | System animation setting hides moving energy particles |

## Physical and platform limits

No physical Android phone was attached during this build. **Real Android-to-inverter discovery, callback networking, readback and writes remain unverified.** Emulator UI and loopback mock tests do not prove LAN reachability on a phone. The Android 17 permission branch is implemented but its runtime prompt requires API 37 device verification; the emulator is API 36.

The original iOS/local implementation read the real inverter and physically verified LED color 1 (blue) with ACK and matching readback. That evidence is not claimed as Android write verification. Every non-LED physical write remains unverified.

Live Update promotion and OEM lock-screen placement require a supporting device and user notification settings. No always-on foreground service or history collector is included. Cloud-destination restoration after local takeover is unverified. Read the README warnings before using real settings.
