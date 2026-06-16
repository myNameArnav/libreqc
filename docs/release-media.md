# Release Media

Tracked preview images live in `docs/media/`. Current README media was captured
from a Pixel 8 over `adb` with LibreQC connected to a headset.

For a real release, replace or supplement them with device captures:

```sh
scripts/capture-release-media.sh
```

The script uses `adb exec-out screencap`. It saves device media under
`docs/media/`:

- `overview-device.png`
- `modes-device.png`
- `sources-device.png`

Raw diagnostics can contain local identifiers. Review screenshots before
publishing.
