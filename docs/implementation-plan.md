# LibreQC Implementation Plan

## Goal

Replace the current single-Activity probe with a maintainable Android app that
mirrors the local device controls documented in
`docs/product-functional-spec.md`.

The protocol layer must remain usable independently of Android UI code so it
can later support a CLI or another platform.

## Architecture

Use Kotlin for new code and keep four boundaries:

```text
app UI
  -> device service
    -> typed prince profile
      -> BMAP codec + RFCOMM transport
```

### `bmap-codec`

Pure Kotlin/JVM:

- Packet encoder.
- Incremental stream decoder.
- Operator/error decoding.
- Request/response correlation.
- Hex fixtures and diagnostic rendering.

No Android dependencies.

### `bmap-transport-android`

- Bonded-device discovery.
- Runtime Bluetooth permissions.
- RFCOMM SPP connection lifecycle.
- Single serialized request queue.
- Read loop and unsolicited status events.
- Timeouts, reconnects, cancellation, and socket ownership.

Only one client can own the headset's SPP socket. The UI must report a clear
conflict when Bose Music or another client is connected.

### `prince-profile`

Typed, capability-aware operations:

- Product information and battery.
- Audio modes and favorites.
- EQ range control.
- Shortcut configuration.
- Multipoint state.
- Paired-device management.

Unsupported responses must become typed `Unsupported` results, not generic
transport failures.

### `app`

Single-activity Compose UI with:

- Connection/device picker.
- Product overview.
- Modes sheet.
- Source screen.
- EQ screen.
- Shortcut screen.
- Diagnostics/export screen.

Use unidirectional state flow. UI controls should be disabled while their write
is in flight and reconciled from the device response rather than updated
optimistically.

## Delivery Stages

### Stage 0: Preserve Evidence

- Keep the existing redacted captures immutable.
- Add the new UI screenshots/XML captures to the evidence set.
- Record APK version, device firmware, and evidence labels in docs.
- Add a redaction check for MAC addresses, serial numbers, GUIDs, and account
  identifiers before committing future captures.

Exit condition: all current findings can be reproduced without `/tmp` content.

### Stage 1: Codec Extraction

- Move packet builders out of `MainActivity`.
- Implement an incremental decoder that handles split and coalesced frames.
- Model GET, STATUS, START, PROCESSING, RESULT, SETGET, and errors.
- Convert existing RFCOMM logs into unit-test fixtures.

Tests:

- Single complete frame.
- Multiple frames in one read.
- Frame split across reads.
- Truncated/invalid lengths.
- Unknown operator/function.
- Known mode-switch exchange.

Exit condition: the existing probe behavior is represented by passing JVM
tests and no longer depends on Activity helper methods.

### Stage 2: Read-Only Device Snapshot

Detailed execution plan:
`docs/stage-2-read-only-snapshot-plan.md`.

Extend the fixed read allowlist to capture:

- `[1.7]` EQ range control.
- `[1.9]` action-button configuration.
- `[1.10]` multipoint.
- `[4.0]` Device Management version/capabilities.
- `[4.4]` remembered-device list.
- `[4.5]` and `[4.6]` device metadata if required by the list parser.
- `[4.12]` routing/active source.
- `[31.4]` default mode.
- `[31.5]` mode persistence.

Do not issue connect, disconnect, remove, pairing, or write operations in this
stage.

Exit condition: response layouts for the four product tiles are captured and
documented for this firmware.

### Stage 3: Typed Profile

- Parse the 47-byte `prince` mode config.
- Parse EQ levels with device-provided min/max values.
- Parse action-button entries and supported action choices.
- Parse multipoint state.
- Parse device list, connected state, and active route.
- Build a `DeviceSnapshot` from independent feature results.

Tests should use captured bytes and include unsupported-function cases.

Exit condition: a test or debug screen can render all product state without
raw-byte interpretation in UI code.

### Stage 4: Read-Only App

- Replace the scrolling log as the main UI.
- Implement product overview and the four feature screens.
- Keep diagnostics available behind a separate entry.
- Display evidence-aware capability states:
  - available
  - unsupported
  - not yet verified
  - temporarily unavailable

Exit condition: the app mirrors Bose Music's information architecture and
reads all supported state without changing the headset.

### Stage 5: Low-Risk Writes

Implement one write family at a time:

1. Mode selection, already verified.
2. EQ Bass/Mid/Treble using SETGET and read-back, already verified.
3. Shortcut enable/assignment using SETGET and read-back, already verified.
4. Multipoint toggle, with a disconnection warning.
5. Source connect/disconnect.

For every write:

- Capture Bose Music's request and response first.
- Compare it with the decompiled packet builder.
- Send only validated values.
- Wait for PROCESSING/RESULT where applicable.
- Read back the setting.
- Restore the original state during validation.
- Add a regression fixture.

Exit condition: each screen can safely mutate and reconcile its own state.

### Stage 6: Mode Editing

- Prove the 47-byte mode-config write layout.
- Resolve fixed prompt identifiers and name encoding.
- Add/edit the two user slots only.
- Implement favorites and mode ordering.
- Implement persistence/default mode.

Protect Bose modes from destructive edits unless the device explicitly permits
the operation.

Exit condition: creating the fourth mode makes it visible and usable in the
favorites list, with full round-trip verification.

### Stage 7: Packaging

- Rename the probe package/application.
- Add release signing and reproducible build instructions.
- Add privacy text explaining that no account or network service is required.
- Add diagnostics export with automatic redaction.
- Test Android lifecycle, Bluetooth-off, headset-off, competing socket owner,
  and reconnect behavior.

## Proposed API

```kotlin
interface BoseHeadset {
    val state: StateFlow<DeviceState>

    suspend fun refresh(): DeviceSnapshot
    suspend fun selectMode(index: Int, voicePrompt: Boolean = false)
    suspend fun setEq(band: EqBand, level: Int)
    suspend fun setShortcut(enabled: Boolean, action: ShortcutAction)
    suspend fun setMultipoint(enabled: Boolean)
    suspend fun connectSource(id: SourceId)
    suspend fun disconnectSource(id: SourceId)
}
```

Mode editing should use a separate experimental interface until its write
layout is verified.

## Test Strategy

- Pure JVM fixture tests for codec and parsers.
- Fake-transport tests for request ordering, timeout, and read-back behavior.
- Android instrumentation tests for permission and lifecycle behavior.
- Manual hardware acceptance checklist for each supported firmware.

Never make hardware tests depend on a user's current EQ, shortcut, source, or
mode state. Snapshot it first and restore it at the end.

## Immediate Work Queue

Completed:

1. Extract codec and add fixture tests.
2. Add the Stage 2 read-only probes.
3. Capture and document the new responses.
4. Implement the typed snapshot.
5. Build the read-only Compose UI.

Next:

1. Source connect/disconnect.
2. Add later writes one family at a time in the documented order.

Mode selection was hardware-accepted on 2026-06-15:

- Selected Aware with `1f 03 05 02 01 00`.
- Verified GET `[31.3]` returned mode index `1`.
- Restored Quiet with `1f 03 05 02 00 00`.
- Verified GET `[31.3]` returned mode index `0`.
- The Compose overview reconciled to each read-back value.

EQ adjustment was hardware-accepted on 2026-06-15:

- Changed Bass from `+2` to `+3` with `01 07 02 02 03 00`.
- Verified GET `[1.7]` returned Bass `+3`.
- Restored Bass to `+2` with `01 07 02 02 02 00`.
- Verified GET `[1.7]` returned Bass `+2`.

Shortcut assignment was hardware-accepted on 2026-06-16:

- Changed Shortcut from Spotify to Battery Level with `01 09 02 03 80 09 03`.
- Verified GET `[1.9]` returned action `3`.
- Restored Shortcut to Spotify with `01 09 02 03 80 09 10`.
- Verified GET `[1.9]` returned action `16`.

Multipoint toggle was hardware-accepted on 2026-06-16:

- Started with Multipoint enabled and GET `[1.10]` payload `07`.
- Disabled Multipoint with `01 0a 02 01 00`.
- Verified GET `[1.10]` returned payload `06`.
- Restored Multipoint with `01 0a 02 01 01`.
- Verified GET `[1.10]` returned payload `07`.
- The Source screen warns that turning off Multipoint may disconnect a second
  device and reconciled connected-source counts during the run.
