# USB Findings on macOS

Date: 2026-06-15

Target device:

- Bose QuietComfort Headphones (2023)
- Product ID: `0x40fc`
- Vendor ID: `0x05a7` (Bose Corporation)

Host environment:

- macOS `24G90`
- Darwin `24.6.0`
- Apple Silicon MacBook Air

## Summary

The headphones are visible to macOS as a USB 2.0 full-speed device, but this
host does not attach any audio, serial, or HID class driver to them.

That means a direct USB-C connection to this Mac does not currently expose an
obvious control surface that LibreQC can use as a substitute for the verified
Bluetooth RFCOMM path.

## Observed Enumeration

From `system_profiler SPUSBDataType`:

- Product: `Bose QuietComfort Headphones`
- Vendor ID: `0x05a7`
- Product ID: `0x40fc`
- USB version: `2.0` (`bcdUSB = 0x0200`)
- Device speed: full speed (`12 Mb/s`)
- Current required: `100 mA`
- Device version: `27.91`
- Serial number: present but redacted in this document

From `ioreg -p IOUSB -n "Bose QuietComfort Headphones" -r -l -w 0`:

- `bDeviceClass = 0`
- `bDeviceSubClass = 0`
- `bDeviceProtocol = 0`
- `bNumConfigurations = 1`
- `kUSBCurrentConfiguration = 1`

The device appears in the USB registry as a plain `IOUSBHostDevice` only. No
child interfaces or class-driver attachments were visible in the targeted
inspection run.

## Negative Findings

These checks did not expose a usable host-side interface:

- `system_profiler SPAudioDataType`
  - No Bose USB audio input or output device appeared.
- `/dev/cu.*` and `/dev/tty.*`
  - No new Bose serial device node appeared.
- `ioreg` targeted searches
  - No Bose-attached `IOHID` or `IOSerialBSDClient` path was visible.

## What This Likely Means

The most conservative reading is:

- USB is at least usable for power/charging.
- On this Mac, the connected headset does not present a standard USB Audio
  Class device.
- On this Mac, it also does not present an immediately accessible serial or
  HID control endpoint.

This does not prove the headset has no USB vendor protocol. It only shows that
macOS did not bind it to a standard host-facing class driver and that our
current quick inspection did not reveal an obvious interface to open.

## Impact on LibreQC

Current LibreQC findings are verified over:

- Bluetooth Classic RFCOMM
- Standard SPP UUID `00001101-0000-1000-8000-00805f9b34fb`

So the practical development path remains:

- phone connected to the workstation over USB for `adb`
- headphones connected to the phone over Bluetooth

That path avoids wireless ADB while preserving access to the known-good BMAP
transport.

## Next USB-Side Investigation Options

If USB exploration is still worth doing, the next step is a descriptor-level
capture from Linux with `lsusb -v` or a packet capture setup that can see
control transfers during Bose app or firmware-update activity.

That would answer whether the headset exposes:

- vendor-specific interfaces
- hidden endpoints not surfaced by macOS tooling
- a firmware-update-only transport
- a USB control path distinct from the Bluetooth BMAP transport
