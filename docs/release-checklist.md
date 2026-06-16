# Release Checklist

Use this before pushing a GitHub release.

## Done

- BMAP packet encoder and incremental decoder.
- Address-aware response collection and diagnostics.
- Typed `prince` parsers for battery, modes, EQ, shortcut, multipoint, and
  remembered sources.
- Android Compose UI for overview, modes, sources, EQ, shortcut, and
  diagnostics.
- Hardware-verified mode selection, EQ adjustment, shortcut assignment,
  multipoint toggle, and remembered-source connect/disconnect paths.
- Launcher icon, MIT license, privacy note, CI workflow, release workflow, and
  local media capture script.

## Remains

- Verify mode-config `[31.6]` edit/reset write sequence before exposing mode
  editing UI.
- Add release signing config if distributing signed APKs outside GitHub debug
  builds.
- Add diagnostics export with automatic identifier redaction.
- Test Android lifecycle, Bluetooth-off, headset-off, competing socket owner,
  and reconnect behavior.
- Capture real screenshots from a paired headset before each public release.

## Local Build

```sh
./gradlew clean test :app:assembleDebug
```

APK path:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Release

1. Update `versionName` and `versionCode` in
   `app/build.gradle.kts`.
2. Run local tests and assemble.
3. Capture release media:

   ```sh
   scripts/capture-release-media.sh
   ```

4. Push to GitHub.
5. Create and push a tag:

   ```sh
   git tag v0.1.0
   git push origin v0.1.0
   ```

6. GitHub Actions will create a release and upload the debug APK. If runners
   are unavailable, upload `app/build/outputs/apk/debug/app-debug.apk`
   manually from the GitHub Releases page.
