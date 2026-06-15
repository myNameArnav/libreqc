# Stage 2: Read-Only Device Snapshot Plan

## Objective

Discover and document the read-only BMAP responses needed to represent the
four main LibreQC feature areas for Bose QuietComfort Headphones (`prince`):

- Modes
- Source
- EQ
- Shortcut

This stage extends the existing probe with a fixed `GET` allowlist, captures
the real headset responses, redacts sensitive values, and records the response
layouts. It does not implement writes or the final typed profile.

Target environment:

- Product ID: `0x4075`
- Tested firmware: `1.0.6-80+f5f219b`
- Transport: Bluetooth Classic RFCOMM over the standard SPP UUID

## Why This Stage Comes Next

Stage 1 established a tested BMAP encoder and incremental decoder. The next
unknown is not framing; it is the device-specific meaning of payloads for EQ,
Shortcut, multipoint, remembered sources, routing, and mode persistence.

Building parsers or UI before capturing these responses would encode
assumptions from shared Bose enums and other headphone models. That is unsafe
because the Bose Music app contains features and packet layouts that `prince`
may not support.

Stage 2 provides the hardware evidence required for:

- Capability filtering in the UI.
- Stable fixture tests in the `prince-profile` layer.
- Correct distinction between unsupported, unavailable, and malformed data.
- Safe future writes using captured request/response behavior and read-back.

## Scope

### In Scope

- Add known read-only `GET` requests to the probe allowlist.
- Use the `bmap-codec` API for every request and response.
- Capture raw transmitted and received bytes.
- Record unsupported and timeout responses without treating them as crashes.
- Redact device identifiers and local source names before retaining captures.
- Document observed response layouts and unresolved fields.
- Convert useful responses into immutable hex fixtures for Stage 3.

### Out of Scope

- `SET`, `SETGET`, or `START` requests.
- Connecting or disconnecting remembered sources.
- Removing remembered sources.
- Clearing the pairing list.
- Entering pairing mode.
- Changing routing or the active source.
- Changing EQ, Shortcut, multipoint, default mode, or mode persistence.
- Parsing responses into the final `DeviceSnapshot`.
- Replacing the probe UI with Compose.

The existing optional mode-switch path is a previously verified Stage 1
diagnostic. It must not be invoked during Stage 2 capture runs.

## Read Allowlist

Add the following requests in deterministic order.

| Probe name | Address | Purpose | Why needed |
| --- | --- | --- | --- |
| `settings.eq` | `[1.7]` | EQ range controls | Discover supported bands, current values, and min/max bounds |
| `settings.shortcut` | `[1.9]` | Action-button configuration | Discover enabled state, long-press mapping, and supported entries |
| `settings.multipoint` | `[1.10]` | Multipoint state | Populate the Source screen toggle without changing it |
| `device_management.info` | `[4.0]` | Version or capabilities | Establish the supported Device Management schema |
| `device_management.list` | `[4.4]` | Remembered-device list | Populate remembered sources and stable identifiers |
| `device_management.metadata_5` | `[4.5]` | Device metadata, if supported | Resolve names, categories, or per-device state required by `[4.4]` |
| `device_management.metadata_6` | `[4.6]` | Device metadata, if supported | Resolve names, categories, or per-device state required by `[4.4]` |
| `device_management.routing` | `[4.12]` | Active route or stream | Distinguish connected devices from the active audio source |
| `audio_modes.default` | `[31.4]` | Default mode | Represent startup behavior when persistence is disabled |
| `audio_modes.persistence` | `[31.5]` | Remember My Mode | Represent the mode persistence setting |

All requests use:

```text
[function block, function, GET=1, payload length=0]
```

Do not add speculative payload bytes to a `GET` unless decompilation and a
captured Bose Music request both show that the function requires them.

## Implementation Plan

### 1. Represent Probes Explicitly

Replace or wrap the current string-to-byte map with a small immutable probe
definition:

```kotlin
data class ReadProbe(
    val name: String,
    val address: BmapAddress,
    val payload: ByteArray = byteArrayOf(),
)
```

The transmitted packet must be generated with `BmapPackets.get`. Keeping the
address as structured data makes it possible to correlate and classify the
response without reparsing the request bytes.

Why:

- Prevents hand-written packet mistakes.
- Makes the allowlist auditable.
- Gives Stage 3 reusable names and addresses.
- Keeps any future state-changing operations outside the read-probe type.

### 2. Enforce Read-Only Operation

The Stage 2 runner should accept only `ReadProbe` values and always encode
operator `GET`. It should not accept an arbitrary operator or arbitrary raw
packet from an intent extra.

Add a startup log line such as:

```text
probe policy=read-only count=...
```

If the existing `mode` intent extra is retained, log clearly that it is a
separate verified diagnostic and ensure the Stage 2 capture command does not
provide it. Prefer moving mode switching behind an explicit diagnostic path so
the ordinary snapshot run cannot mutate state accidentally.

### 3. Improve Per-Probe Results

For each request, record:

- Probe name and BMAP address.
- Request hex.
- Response hex.
- Every decoded frame.
- Operator and error code.
- Elapsed time.
- Whether the result was `STATUS`, `ERROR`, timeout, truncated, or unrelated.

Expected result categories:

```text
Supported(frame)
Unsupported(error)
TimedOut
Malformed(reason)
Unexpected(frames)
```

The probe may continue using log output during Stage 2. These categories do
not need to become the final public profile API yet.

### 4. Handle Streaming Correctly

Use one `BmapStreamDecoder` for the connected input stream rather than
assuming one socket read equals one frame. Feed every read chunk into the
decoder and route decoded frames by address.

The request sequence remains serialized:

1. Send one `GET`.
2. Wait for a matching `STATUS` or `ERROR`.
3. Log unrelated frames as unsolicited.
4. Time out the individual request.
5. Continue to the next allowlisted read.

This avoids assigning a delayed or unsolicited frame to the wrong feature.

### 5. Preserve Raw Evidence

Capture the complete probe log before interpreting any payload. Suggested
filename:

```text
captures/rfcomm-stage2-read-only-YYYY-MM-DD.txt
```

Record alongside the capture:

- Product model and product ID.
- Firmware version returned by `[0.5]`.
- Probe application revision or source state.
- Android device/build used for the run.
- Whether Bose Music was force-stopped.
- Whether one or two remembered sources were connected.
- Whether audio was actively streaming.

The last two conditions matter because source-list and routing payloads may
change with connection state.

## Hardware Capture Procedure

### Preparation

1. Build and install the debug APK.
2. Confirm the headset is bonded and powered on.
3. Force-stop Bose Music so it does not own the SPP socket.
4. Do not change EQ, Shortcut, multipoint, modes, or paired devices.
5. Note the current source topology without recording private identifiers in
   the public documentation.
6. Clear or isolate the `LibreQCProbe` log buffer.

### Baseline Run

Run the probe without the `mode` extra:

```sh
adb shell am start -n dev.libreqc.probe/.MainActivity
adb logcat -s LibreQCProbe:I '*:S'
```

Confirm:

- Every Stage 2 address is transmitted with operator `GET`.
- No `SET`, `SETGET`, or `START` packet is transmitted.
- The run reaches completion even when a function is unsupported or times out.
- Raw responses are retained before redaction.

### Source-State Runs

One baseline run may not explain Device Management fields. If needed, perform
additional read-only runs under naturally occurring states:

- One remembered source connected, not streaming.
- One remembered source connected and streaming.
- Two remembered sources connected.

Do not use BMAP commands to create these states during Stage 2. Change them
through normal Bluetooth use only, then rerun the same read allowlist.

Label each capture with the state rather than overwriting the baseline.

## Privacy and Redaction

Device Management responses may contain:

- Bluetooth MAC addresses.
- Local paired-device display names.
- Device serials or stable identifiers.
- GUIDs or account-linked identifiers.

Before a capture is retained for publication or committed:

1. Keep an unmodified local working copy outside version control if needed for
   byte-offset analysis.
2. Replace identifiers with deterministic same-length placeholders where byte
   positions matter.
3. Replace local device names with neutral names of equal byte length where
   practical.
4. Preserve packet lengths and non-sensitive structural bytes.
5. Search the redacted output for MAC-address, UUID, serial, email, and known
   device-name patterns.
6. Mark the capture as redacted.

Do not edit the existing redacted captures in place.

## Response Analysis

For each address, document:

- Request bytes.
- Response operator.
- Payload length.
- Stable fields across runs.
- Fields that vary with device state.
- Endianness and length-prefix behavior.
- Error code if unsupported.
- Confidence level: Verified, Observed, Recovered, or Unknown.

### EQ `[1.7]`

Determine:

- Number of entries.
- Entry size.
- Range identifiers for Bass, Mid, and Treble.
- Current value encoding, including signed values.
- Minimum and maximum values.
- Whether values are steps or direct display units.

Cross-check the decoded current values against the Bose Music EQ screen, but
do not change them.

### Shortcut `[1.9]`

Determine:

- Whether the payload contains one or multiple button entries.
- Button ID for the physical Action button.
- Event value for press-and-hold.
- Action values for Battery Level and Spotify.
- How the separate enable switch is represented.
- Whether supported actions are returned by the device or inferred by the app.

Do not expose other shared Bose actions without a positive `prince` response.

### Multipoint `[1.10]`

Determine:

- Enabled/disabled field.
- Any capability or maximum-connection field.
- Whether the response changes with one versus two connected sources.

### Device Management `[4.*]`

Determine:

- Version or capability format from `[4.0]`.
- Number and boundaries of remembered-device entries from `[4.4]`.
- Identifier, name, category, connection state, and ordering fields.
- Whether `[4.5]` or `[4.6]` require a device identifier payload.
- Active route representation from `[4.12]`.
- Difference between remembered, connected, and actively streaming states.

If `[4.5]` or `[4.6]` require an identifier, do not guess. First establish the
request shape from Bose Music traffic or decompiled builders, then add only the
corresponding read request.

### Mode Startup `[31.4]` and `[31.5]`

Determine:

- Default mode index representation.
- Persistence enabled/disabled representation.
- Whether either function returns `FuncNotSupp`.
- How the two settings combine to match the **Remember My Mode** UI.

## Tests

### JVM Tests

Add fixtures for each captured response that is safe to retain:

- Complete `STATUS` response.
- `FuncNotSupp` or other error response.
- Coalesced source-related frames if observed.
- Split frame boundaries independent of payload type.
- Unknown fields preserved as raw bytes.

Stage 2 tests should verify collection and classification, not prematurely
assert an unproven field layout. Field-level parser tests belong in Stage 3
after the response structure is documented.

### Read-Only Policy Tests

Verify that:

- Every snapshot probe encodes operator `GET`.
- The allowlist contains only approved addresses.
- No snapshot code path accepts `SET`, `SETGET`, or `START`.
- A timeout or unsupported function does not abort later probes.
- An unrelated frame is not correlated to the current request.

### Manual Acceptance

- Bose Music is not connected to the SPP socket during the run.
- Headset settings are unchanged before and after the run.
- Every transmitted packet is listed in the approved read allowlist.
- The capture includes enough context to reproduce the run.
- The retained capture passes the redaction review.

## Deliverables

Stage 2 is complete when the repository contains:

1. The extended read-only probe allowlist.
2. Request/result logging with address-aware correlation.
3. One or more redacted Stage 2 RFCOMM captures.
4. Updated `docs/prince-protocol.md` tables for each tested address.
5. Fixture-backed tests for supported and unsupported responses.
6. A list of unresolved fields or requests requiring further evidence.

## Exit Criteria

Proceed to Stage 3 only when:

- EQ, Shortcut, multipoint, source list/routing, default mode, and persistence
  each have a captured result or a confirmed unsupported response.
- Device Management identifiers and local names are redacted.
- No Stage 2 run transmitted a state-changing operator.
- Response boundaries and request requirements are documented.
- The captured bytes are sufficient to build a typed snapshot without reading
  raw payload offsets in Android UI code.

If a function times out or returns ambiguous data, record that result and keep
the corresponding capability as **not yet verified**. Do not fill the gap with
layouts from another Bose product.

## Next Stage

Stage 3 will turn the captured responses into a `prince-profile` module with:

- Typed feature results.
- EQ, Shortcut, multipoint, source, routing, and mode-startup parsers.
- A capability-aware `DeviceSnapshot`.
- Fixture tests for supported, unsupported, malformed, and partial snapshots.

