# Privacy

LibreQC is a local Bluetooth utility. It does not require an account and does
not send headset data to any server.

## Data Used

- Bluetooth device name and address, as provided by Android after the
  Bluetooth permission is granted.
- Bose BMAP request and response bytes needed to show headset state.
- Local diagnostic logs shown in the in-app Diagnostics screen.

## Data Sharing

LibreQC does not include analytics, ads, crash reporting, or network upload
code. Diagnostic logs stay on the device unless the user manually copies or
shares them.

## Sensitive Logs

Raw protocol diagnostics can contain local device identifiers. Redact device
addresses, local device names, and remembered source identifiers before opening
an issue or publishing captures.
