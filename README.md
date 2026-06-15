# LibreQC

Read-only Bose QuietComfort control app and BMAP protocol implementation for
Android.

Documentation:

- [Product functional specification](docs/product-functional-spec.md)
- [Implementation plan](docs/implementation-plan.md)
- [Stage 2 read-only snapshot plan](docs/stage-2-read-only-snapshot-plan.md)
- [Verified `prince` protocol findings](docs/prince-protocol.md)
- [USB findings on macOS](docs/usb-findings-2026-06-15.md)
- UI reference captures are under `captures/`.
- The redacted Stage 2 baseline is
  `captures/rfcomm-stage2-read-only-2026-06-14.txt`.

The probe:

- Selects a bonded Bluetooth device advertising the Bose BMAP UUID.
- Uses the standard SPP UUID used by Bose Music, with the advertised BMAP
  UUID as a diagnostic fallback.
- Sends only a fixed allowlist of BMAP `GET` packets.
- Logs raw request/response hex and decoded frame headers under
  `LibreQCProbe`.

`bmap-codec` is a pure Kotlin/JVM module containing packet encoding,
incremental stream decoding, error decoding, correlation, and diagnostics.

`prince-profile` is a pure Kotlin/JVM module containing typed parsers for EQ,
Shortcut, multipoint, modes, and remembered sources. It assembles independent
feature results into a capability-aware `DeviceSnapshot`.

The Android app presents a read-only Compose overview with Modes, Source, EQ,
Shortcut, and Diagnostics screens. Its refresh path still sends only the fixed
GET allowlist.

Tests use frames from the redacted RFCOMM capture:

```sh
./gradlew :bmap-codec:test :prince-profile:test \
  :app:testDebugUnitTest :app:assembleDebug
```

An explicit device can be selected with the `mac` activity extra. The Stage 2
probe has no state-changing activity extras or packet paths.

```sh
adb shell am start -n dev.libreqc.probe/.MainActivity \
  --es mac AA:BB:CC:DD:EE:FF
adb logcat -s LibreQCProbe:I '*:S'
```
