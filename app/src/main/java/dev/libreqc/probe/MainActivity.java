package dev.libreqc.probe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.libreqc.bmap.BmapDiagnostics;
import dev.libreqc.bmap.BmapFrame;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final String TAG = "LibreQCProbe";
    private static final int CONNECT_PERMISSION_REQUEST = 1;
    private static final UUID BMAP_UUID =
            UUID.fromString("00000000-deca-fade-deca-deafdecacaff");
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

    private TextView outputView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        outputView = new TextView(this);
        outputView.setTextIsSelectable(true);
        outputView.setPadding(24, 24, 24, 24);
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(outputView);
        setContentView(scrollView);

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                    CONNECT_PERMISSION_REQUEST);
        } else {
            startProbe();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CONNECT_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startProbe();
        } else {
            line("BLUETOOTH_CONNECT permission denied");
        }
    }

    private void startProbe() {
        new Thread(this::runProbe, "libreqc-probe").start();
    }

    @SuppressLint("MissingPermission")
    private void runProbe() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                line("Bluetooth adapter unavailable or disabled");
                return;
            }

            BluetoothDevice device = selectDevice(adapter.getBondedDevices());
            if (device == null) {
                line("No bonded device advertising " + BMAP_UUID);
                return;
            }

            line("device name=%s address=%s",
                    device.getName(), redactAddress(device.getAddress()));
            line("device uuids=%s", Arrays.toString(device.getUuids()));

            if (!probeTransport(device, SPP_UUID, "spp-uuid")) {
                probeTransport(device, BMAP_UUID, "bmap-uuid");
            }
        } catch (Throwable error) {
            line("fatal %s: %s", error.getClass().getSimpleName(), error.getMessage());
            Log.e(TAG, "Probe failed", error);
        }
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice selectDevice(Set<BluetoothDevice> bondedDevices) {
        String requestedMac = getIntent().getStringExtra("mac");
        for (BluetoothDevice device : bondedDevices) {
            if (requestedMac != null && requestedMac.equalsIgnoreCase(device.getAddress())) {
                return device;
            }
        }
        for (BluetoothDevice device : bondedDevices) {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids == null) {
                continue;
            }
            for (ParcelUuid uuid : uuids) {
                if (BMAP_UUID.equals(uuid.getUuid())) {
                    return device;
                }
            }
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    private boolean probeTransport(BluetoothDevice device, UUID uuid, String label) {
        line("transport %s connecting uuid=%s", label, uuid);
        try (BluetoothSocket socket = device.createRfcommSocketToServiceRecord(uuid)) {
            socket.connect();
            line("transport %s connected", label);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            ReadProbeRunner runner = new ReadProbeRunner(new ProbeLogListener());
            runner.drain(input, 300);

            line("probe policy=read-only base_count=%d", SnapshotProbes.BASE.size());
            for (ReadProbe probe : SnapshotProbes.BASE) {
                ReadProbeRunner.Result result = exchange(runner, input, output, probe);
                if ("device_management.list".equals(probe.name())
                        && result.response() != null) {
                    java.util.List<ReadProbe> details =
                            SnapshotProbes.deviceDetails(result.response().getPayload());
                    line("probe dynamic=device-details count=%d", details.size());
                    for (ReadProbe detail : details) {
                        Thread.sleep(150);
                        exchange(runner, input, output, detail);
                    }
                }
                Thread.sleep(150);
            }

            line("transport %s complete", label);
            return true;
        } catch (Throwable error) {
            line("transport %s failed %s: %s",
                    label, error.getClass().getSimpleName(), error.getMessage());
            Log.w(TAG, "Transport failed: " + label, error);
            return false;
        }
    }

    private ReadProbeRunner.Result exchange(
            ReadProbeRunner runner,
            InputStream input,
            OutputStream output,
            ReadProbe probe)
            throws Exception {
        line("probe start name=%s address=[%d.%d]",
                probe.name(),
                probe.address().getFunctionBlock(),
                probe.address().getFunction());
        line("tx %-30s %s", probe.name(), hex(probe.packet()));
        ReadProbeRunner.Result result = runner.exchange(input, output, probe, 3000);
        String error = result.response() == null || result.response().getError() == null
                ? ""
                : " error=" + result.response().getError().name()
                + "(" + result.response().getErrorCode() + ")";
        line("probe result name=%s outcome=%s elapsed_ms=%d%s unrelated=%d",
                probe.name(),
                result.outcome(),
                result.elapsedMs(),
                error,
                result.unrelatedFrames().size());
        if (result.malformed() != null) {
            line("probe malformed name=%s expected=%d actual=%d",
                    probe.name(),
                    result.malformed().getExpectedFrameLength(),
                    result.malformed().getActualByteCount());
        }
        return result;
    }

    private static String hex(byte[] bytes) {
        return BmapDiagnostics.hex(bytes);
    }

    private static String printable(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            result.append(value >= 32 && value <= 126 ? value : '.');
        }
        return result.toString();
    }

    private static String redactAddress(String address) {
        if (address == null || address.length() < 5) {
            return "<unknown>";
        }
        return "XX:XX:XX:XX:" + address.substring(address.length() - 5);
    }

    private final class ProbeLogListener implements ReadProbeRunner.Listener {
        @Override
        public void onReceiveChunk(byte[] bytes) {
            line("rx chunk %s", hex(bytes));
        }

        @Override
        public void onFrame(BmapFrame frame, boolean matchesCurrentProbe) {
            line("frame %s correlation=%s text=%s",
                    BmapDiagnostics.describe(frame),
                    matchesCurrentProbe ? "related" : "unsolicited",
                    printable(frame.getPayload()));
        }
    }

    private void line(String format, Object... args) {
        String message = String.format(Locale.US, format, args);
        Log.i(TAG, message);
        runOnUiThread(() -> outputView.append(message + "\n"));
    }
}
