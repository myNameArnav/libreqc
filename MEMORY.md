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
- Mode editing needs proof of 47-byte mode-config write layout before UI.
