# Bose QuietComfort Headphones (`prince`) Protocol

Confirmed against product ID `0x4075`, firmware `1.0.6-80+f5f219b`.

## Transport

- Bluetooth Classic RFCOMM.
- Standard SPP UUID: `00001101-0000-1000-8000-00805f9b34fb`.
- The current device advertises SPP on RFCOMM channel 8.
- The advertised Bose BMAP UUID does not carry normal BMAP frames on this
  model; it returned `ff550200ee10` during probing.
- A `GET [0.1]` request works, but no separate initialization exchange was
  required before other reads.

## Framing

Packets use:

```text
[function block, function, operator, payload length, payload...]
```

Relevant operators:

- `1`: GET
- `3`: STATUS
- `5`: START
- `7`: PROCESSING

## Confirmed Reads

| Address | Meaning | Observed payload |
| --- | --- | --- |
| `[0.1]` | BMAP version | `1.1.0` |
| `[0.5]` | Firmware | `1.0.6-80+f5f219b` |
| `[1.0]` | Settings version | `1.1.0` |
| `[1.2]` | Product name | length-prefixed name |
| `[1.3]` | Voice prompts | 7-byte settings payload |
| `[1.5]` | CNC | 3-byte CNC payload |
| `[1.7]` | EQ | three 4-byte range entries |
| `[1.9]` | Shortcut | 11-byte shortcut configuration |
| `[1.10]` | Multipoint | `07` while enabled |
| `[2.2]` | Battery | first byte is percent |
| `[4.0]` | Device Management version | `1.1.0` |
| `[4.4]` | Remembered-device list | header byte plus 6-byte MAC addresses |
| `[4.5]` | Device info | GET with 6-byte MAC |
| `[4.6]` | Device extended info | GET with 6-byte MAC |
| `[4.12]` | Active routing | `FuncNotSupp` |
| `[31.0]` | AudioModes version | `1.0.0` |
| `[31.2]` | AudioModes capabilities | `02 02 00 00 00 09` |
| `[31.3]` | Current mode | one-byte mode index |
| `[31.4]` | Default mode | one-byte mode index |
| `[31.5]` | Remember My Mode | one-byte enabled value |
| `[31.6]` | Mode config | GET with one-byte mode index |
| `[31.7]` | Mode indices | `00 01 02 03` |
| `[31.8]` | Favorites | mode count plus bitset |

`[1.6]`, `[31.10]`, and `[31.11]` returned `FuncNotSupp`.

## Stage 2 Snapshot Reads

The baseline Stage 2 run used only `GET` requests and completed 28 base probes
plus eight identifier-derived Device Management probes. It had one remembered
source connected with no active audio stream. Bose Music was force-stopped
before the run.

### EQ `[1.7]`

Observed payload:

```text
f6 0a 02 00  f6 0a 04 01  f6 0a 00 02
```

The payload is three 4-byte entries:

```text
[minimum signed byte, maximum signed byte, current signed byte, range ID]
```

The UI values and response agree on:

| Range ID | UI band | Minimum | Maximum | Current |
| --- | --- | --- | --- | --- |
| `0` | Bass | `-10` | `10` | `2` |
| `1` | Mid | `-10` | `10` | `4` |
| `2` | Treble | `-10` | `10` | `0` |

Confidence: Verified.

### EQ Writes

The Bose packet constructor and hardware acceptance agree on:

```text
SETGET [1.7] payload=[signed target value, range ID]
```

Range IDs are Bass `0`, Mid `1`, and Treble `2`. The target is an absolute
signed value, not a delta. The device returns the full 12-byte EQ status, and
a subsequent `GET [1.7]` independently confirms the mutation.

Accepted Bass step and restoration:

```text
Bass +3: 01 07 02 02 03 00
Bass +2: 01 07 02 02 02 00
```

The acceptance run started at Bass `+2`, Mid `+4`, Treble `0`, changed Bass
to `+3`, and restored Bass to `+2`. The identifier-free exchange is retained
in `captures/rfcomm-eq-bass-step-2026-06-15.txt`.

### Shortcut `[1.9]`

Observed payload:

```text
80 09 10 00 01 40 08 00 00 00 80
```

The recovered Bose parser gives this layout:

```text
[button ID, event type, configured action,
 4-byte supported-action bitset,
 remaining unavailable-action bitset]
```

For the captured payload:

- Button ID `0x80`: generic Shortcut button.
- Event type `9`: long press.
- Configured action `16`: Spotify Go Mode.
- Supported actions: Battery Level `3`, Disabled `14`, Spotify Go Mode `16`.
- Unavailable action: Toggle Wake-Up Word `7`.

The separate enable switch is represented by selecting `Disabled` versus an
active configured action; there is no independent enable field in this
response.

Confidence: Verified payload, Recovered enum meanings.

### Multipoint `[1.10]`

Observed payload `07`. The recovered parser defines:

| Bit | Meaning | Captured |
| --- | --- | --- |
| `0` | Enabled | yes |
| `1` | Multipoint supported | yes |
| `2` | Disabling multipoint supported | yes |

Confidence: Verified payload, Recovered bit meanings.

### Device Management `[4.*]`

- `[4.0]` returns ASCII version `1.1.0`.
- `[4.4]` returned 25 payload bytes: one unknown header byte followed by four
  6-byte Bluetooth MAC addresses. The header was `02` in the retained run
  and `03` in an earlier run. Bose's parser ignores it and divides the
  remaining payload into 6-byte addresses.
- `[4.5]` and `[4.6]` require one of those 6-byte MAC addresses as the GET
  payload. Empty requests return `InvalidLength(1)`.
- `[4.12]` returned `FunctionNotSupported(4)` on this firmware.

`[4.5]` Device Info:

```text
Non-Bose device:
[6-byte MAC, flags, major class, minor class, UTF-8 name...]

Bose device:
[6-byte MAC, flags, product ID MSB, product ID LSB,
 variant, UTF-8 name...]
```

Flag byte:

| Bit | Meaning |
| --- | --- |
| `0` | Connected |
| `1` | Local device |
| `2` | Bose product |
| `3` | Component |
| `7` | Bose product type, when bit 2 is set |

`[4.6]` Device Extended Info:

```text
[6-byte MAC, paired-profile flags, connected-profile flags]
```

For each profile byte, bits `0..4` represent A2DP, HFP, AVRCP, SPP, and iAP.
The parser also supports optional LE Audio unicast/broadcast flags at payload
bytes 10 and 11; those bytes were absent in this capture.

Four remembered sources were returned. Their paired profile masks were
`07`, `07`, `07`, and `05`. Their connected profile masks were `00`, `0f`,
`00`, and `00`, so one source was connected over A2DP, HFP, AVRCP, and SPP.

The retained fixtures replace every MAC and device name with deterministic
same-length placeholders. Ordering semantics and active-stream selection
remain unresolved.

Confidence: Verified payloads, Recovered field layouts.

### Mode Startup `[31.4]` and `[31.5]`

- `[31.4]` returned `00`, matching Quiet mode index `0` as the default mode.
- `[31.5]` returned `01` while **Remember My Mode** was enabled in Bose Music.

The recovered Bose parsers confirm `[31.4]` is a signed one-byte mode index
and `[31.5]` is enabled only when the byte equals `1`.

Confidence: Verified.

## Modes

Mode config responses are 47 bytes:

| Index | Name | CNC level |
| --- | --- | --- |
| `0` | Quiet | `0` |
| `1` | Aware | `10` |
| `2` | Commute | `9` |
| `3` | Unnamed configurable slot | `9` |

The capability payload reports two Bose modes, two user modes, CNC support,
and user favorites support. Spatial audio, automatic CNC, wind block, and
ANC toggle are not reported.

## Mode Switching

The Bose app packet constructor and hardware probe agree on:

```text
START [31.3] payload=[mode index, play voice prompt]
```

Examples without a voice prompt:

```text
Aware: 1f 03 05 02 01 00
Quiet: 1f 03 05 02 00 00
```

The device first returns `PROCESSING`:

```text
1f 03 07 00
```

A subsequent `GET [31.3]` confirms the selected index.

Raw captures are in:

- `captures/rfcomm-probe-spp-2026-06-13.txt`
- `captures/rfcomm-probe-mode-configs-2026-06-13.txt`
- `captures/rfcomm-switch-aware-2026-06-13.txt`
- `captures/rfcomm-switch-quiet-2026-06-13.txt`
- `captures/rfcomm-stage2-read-only-2026-06-14.txt`

## Stage 2 Unresolved Fields

- Multipoint behavior with two simultaneously connected sources.
- Device-list header byte and ordering semantics.
- Mapping Bluetooth major/minor class values to UI categories.
- Active stream representation because `[4.12]` is unsupported.
