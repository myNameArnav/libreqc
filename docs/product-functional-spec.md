# LibreQC Product Functional Specification

Target device:

- Bose QuietComfort Headphones (2023)
- Codename: `prince`
- Product ID: `0x4075`
- Tested firmware: `1.0.6-80+f5f219b`
- Reference app: Bose Music `13.0.7`

This document describes the product-level behavior LibreQC should reproduce.
`docs/prince-protocol.md` remains the authority for hardware-verified packet
details.

## Evidence Labels

- **Verified:** exercised against the real headphones.
- **Observed:** visible in Bose Music while connected to the headphones.
- **Recovered:** packet shape or model recovered from Bose Music, but not yet
  exercised by LibreQC.
- **App-wide only:** Bose Music supports it for some products; `prince` support
  has not been established.

## Product Home

The connected-product page provides:

- Product name.
- Product image.
- Battery percentage.
- Playback volume slider.
- Settings entry.
- Tiles for Modes, Source, EQ, Shortcut, Tips, and Product Support.

LibreQC v1 should reproduce the device controls and omit account, inbox,
marketing, tips, and web support surfaces.

Reference capture: `captures/product-page.png`.

## Modes

### Selection

**Verified/Observed**

- List the current favorite modes.
- Indicate the currently selected mode.
- Switch modes from the list.
- Built-in modes are:
  - `0`: Quiet
  - `1`: Aware
- The current user mode is:
  - `2`: Commute
- The hardware reports one additional configurable slot:
  - `3`: currently unnamed and not shown in the main list

The current favorites payload selects indices `0`, `1`, and `2`; this explains
why the fourth reported index is absent from the Bose Music list.

### Mode Details

**Observed/Recovered**

- Each mode has a prompt/name, description, icon, and noise-control value.
- Quiet and Aware are Bose modes.
- User modes can be created in the configurable slots.
- A mode config can also carry automatic CNC, spatial-audio mode, wind-block,
  and ANC-toggle fields. `prince` does not advertise those optional
  capabilities on the tested firmware.

Do not expose unsupported fields merely because the shared Bose protocol model
contains them.

### Add Mode

**Observed**

The add flow offers a product-provided prompt/name catalog, including:

- Commute
- Focus
- Home
- Music
- Outdoor
- Relax
- Run
- Walk
- Work

After naming, the user configures noise cancellation. Saving a mode requires a
47-byte `prince` mode-config write, which is not yet hardware-verified by
LibreQC.

### Mode Persistence

**Observed/Recovered**

The Modes settings page contains **Remember My Mode**:

- Enabled: power-on uses the last selected mode.
- Disabled: power-on uses a configured default mode.

The associated BMAP functions are AudioModes Persistence `[31.5]` and Default
Mode `[31.4]`. Reads and writes still require hardware verification.

Reference captures:

- `captures/modes-page.png`
- `captures/mode-detail.png`
- `captures/mode-add.png`
- `captures/modes-settings.png`

## Source

### Multipoint

**Observed/Recovered**

- Show whether multipoint is enabled.
- Allow enabling or disabling connection to two devices at once.
- Warn that disabling multipoint disconnects the second device.

The shared BMAP model maps this to Settings Multipoint `[1.10]`. The exact
`prince` response and write payload must be captured before implementation.

### Paired Devices

**Observed/Recovered**

- List remembered Bluetooth devices with device category and name.
- Identify connected devices.
- Identify the device carrying the active audio stream.
- Connect or disconnect a remembered device.
- Enter an edit mode for removing remembered devices.
- Enter pairing mode through **Add New**.

The underlying BMAP family is Device Management `[4.*]`, including list,
connect, disconnect, remove, pairing-mode, routing, and feature queries.

LibreQC should initially implement list/connect/disconnect only. Pairing-list
removal and pairing-mode writes should remain disabled until independently
captured and verified because mistakes can disrupt normal Bluetooth access.

Reference capture: `captures/source-page.png`.

## Equalizer

**Observed/Recovered**

The EQ page provides three independent bands:

- Bass
- Mid
- Treble

It also provides:

- A reset action.
- Bass Boost preset.
- Bass Reducer preset.
- Treble Boost preset.
- Treble Reducer preset.

The current captured values were Bass `+2`, Mid `+4`, and Treble `0`; these are
device state, not defaults.

The shared BMAP implementation uses Settings Range Control `[1.7]`:

- GET returns one entry per supported range.
- Each entry contains identifier, current level, minimum, and maximum.
- SETGET payload is `[target step, range identifier]`.
- Range identifiers are `0` Bass, `1` Mid, and `2` Treble.

The response layout and accepted range for `prince` still require a read-only
capture before writes are enabled. Presets should be implemented as ordinary
three-band target values, not as a separate protocol feature.

Reference capture: `captures/eq-page.png`.

## Shortcut

**Observed/Recovered**

- The physical control is the Action button on the left earcup.
- The assigned shortcut is invoked with a press and hold.
- Shortcut behavior can be enabled or disabled.
- The tested product UI offers:
  - Hear Battery Level
  - Spotify
- Spotify behavior resumes playback on the first invocation and requests
  discovery behavior on a subsequent invocation.

The shared BMAP implementation uses Settings Buttons `[1.9]`. An assignment is
encoded as:

```text
[button ID, button event type, button mode]
```

Relevant shared values include:

- Generic Shortcut button ID: `128`
- Battery Level action: `3`
- Spotify action: `16`

The tested `prince` payload, long-press event value, and representation of the
enable switch must be captured before writes are enabled.

Bose Music contains many other shared shortcut actions, including source
switching, mode carousel, track navigation, voice assistant, and conversation
mode. They are **not** evidence that `prince` exposes them. LibreQC must filter
actions using device capability/state responses rather than presenting the
entire app-wide enum.

Reference capture: `captures/shortcut-page.png`.

## Non-Obvious Findings

### Supported but hidden from the main screen

- A fourth mode index exists. It is an empty user-configurable slot rather than
  a secret finished mode.
- Mode startup persistence is under the Modes gear, not the product settings
  page.
- Source identifies the active audio stream separately from connection state.
- The Shortcut tile has an independent enable switch in addition to its
  selected action.
- The EQ presets are convenience values over the same three editable bands.

### Present in Bose Music but not supported by this device profile

The shared app and protocol libraries contain spatial audio, wind block,
automatic CNC, ANC toggle, source switching, mode carousel, conversation mode,
track controls, voice-assistant actions, and other features. The tested
AudioModes capability payload explicitly does not advertise the optional mode
features, and the `prince` Shortcut UI exposes only Battery Level and Spotify.

These app-wide features must not be labeled hidden `prince` features without a
positive device capability response and a hardware test.

## Replacement App Scope

### Version 1

- Discover/select a bonded `prince` headset.
- Connect over RFCOMM SPP.
- Show product name, firmware, battery, and connection state.
- Show current mode and switch among configured favorites.
- Show EQ values and edit Bass/Mid/Treble.
- Show paired sources and connect/disconnect them.
- Show shortcut state and choose supported assignments.
- Show explicit unsupported/unverified states instead of guessing.

### Later

- Create/edit/delete user modes.
- Edit mode favorites.
- Remember My Mode/default-mode settings.
- Multipoint toggle.
- Pairing and remembered-device removal.
- Product renaming, voice prompts, standby timer, and other general settings.

### Out of Scope

- Bose account and cloud services.
- Firmware update.
- Factory reset.
- Authentication or activation bypass.
- Broad protocol fuzzing.
- Silent pairing-list mutation.

