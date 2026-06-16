# LibreQC Agent Memory

## Current State

- Branch: `main`.
- Goal: continue implementation plan handsfree until complete.
- Active comms mode: caveman, per user request.
- Worklog must be kept here for future agents.

## Completed Before This Session

- Stage 1 codec extraction and fixture tests.
- Stage 2 read-only snapshot probe and redacted capture.
- Stage 3 typed `prince-profile` parsers.
- Stage 4 read-only Compose overview/screens.
- Stage 5 writes accepted on hardware:
  - mode selection, accepted 2026-06-15.
  - EQ Bass SETGET/read-back, accepted 2026-06-15.
  - Shortcut assignment SETGET/read-back, accepted 2026-06-16.
  - Multipoint toggle SETGET/read-back/restore, accepted 2026-06-16.
  - Source disconnect/connect read-back/restore, accepted 2026-06-16.

## This Session Worklog

- Inspected repo status: clean `main`.
- Read `/Users/arnav/.agents/skills/caveman/SKILL.md`; caveman response mode active.
- Found immediate queue in `docs/implementation-plan.md`: multipoint toggle next.
- Added recovered `PrinceCommands.setMultipoint(enabled)` encoding:
  - enable: `01 0a 02 01 01`
  - disable: `01 0a 02 01 00`
- Added app Source screen multipoint toggle with warning copy.
- Added RFCOMM SETGET + GET read-back verification for multipoint.
- Corrected Source screen gate: enabling needs support; disabling also needs
  `canDisable`.
- Installed debug APK on attached Pixel 8.
- Force-stopped Bose Music so LibreQC could own SPP socket.
- Read initial Multipoint `07`, disabled with `010a020100`, verified read-back
  `06`, restored with `010a020101`, verified read-back `07`.
- Added redacted acceptance capture:
  `captures/rfcomm-multipoint-toggle-restore-2026-06-16.txt`.
- Pulled/decompiled Bose Music APK with `jadx`; recovered source packets:
  `START [4.2] payload=MAC` disconnect and `START [4.1] payload=00+MAC`
  connect.
- Added `PrinceCommands.connectSource` and `disconnectSource`.
- Added Source screen connect/disconnect buttons for non-local remembered
  sources.
- Hardware-accepted source disconnect/connect using non-local source:
  disconnect `04020506<source>` -> `[4.6]` mask `00`; connect
  `0401050700<source>` -> PROCESSING then `[4.6]` mask `01`, later full
  snapshot mask `07`.
- Added redacted acceptance capture:
  `captures/rfcomm-source-disconnect-connect-restore-2026-06-16.txt`.

## Next Work

- Stage 5 low-risk writes are complete through source connect/disconnect.
- Next implementation-plan stage: Stage 6 mode editing.
- Mode editing recovery started:
  - Decompiled Bose packet builders for mode config, favorites, user indices,
    default mode, and persistence.
  - Recovered prompt IDs: `None=00 00`, `Quiet=00 01`, `Aware=00 02`,
    `Commute=00 07`, `Run=00 14`, `Cinema=00 24`, plus full enum in code.
  - Corrected ModeConfig parser: byte `41` is mutability flags, byte `42` is
    CNC, byte `43` is auto-CNC. Slot 3 CNC is `5`, not `9`.
  - Added command builders/tests for 47-byte envelope preservation, Bose
    generic 37-byte config, favorites `[31.8]`, user indices `[31.7]`,
    default mode `[31.4]`, and persistence `[31.5]`.
  - Hardware rejected 37-byte SETGET and 47-byte SETGET on `[31.6]` with
    `InvalidLength(1)`. 37-byte SET timed out and broke the socket; slot 3
    was unchanged before retry.
  - Recovered Bose `[31.10]` mode settings config command:
    `SETGET [31.10] payload=[cncLevel, autoCnc, spatialAudio, windBlock,
    ancToggle]`.
  - Prince hardware returned `FunctionNotSupported(4)` for `GET [31.10]`, so
    this is not the missing mode-edit path on current firmware.
  - Evidence retained in
    `captures/rfcomm-mode-config-rejected-2026-06-16.txt`.
    `captures/rfcomm-mode-settings-config-noop-2026-06-16.txt` captures the
    unsupported `[31.10]` probe.
  - Do not expose user-facing mode edit UI yet. Need find missing precondition
    or alternate sequence for `[31.6]` writes.
