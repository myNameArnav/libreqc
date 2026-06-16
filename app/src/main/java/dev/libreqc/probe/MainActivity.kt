package dev.libreqc.probe

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.libreqc.bmap.BmapDiagnostics
import dev.libreqc.bmap.BmapAddress
import dev.libreqc.bmap.BmapFrame
import dev.libreqc.bmap.BmapOperator
import dev.libreqc.prince.AudioModePrompt
import dev.libreqc.prince.DeviceSnapshot
import dev.libreqc.prince.DeviceSnapshotParser
import dev.libreqc.prince.DeviceIdentifier
import dev.libreqc.prince.DeviceProfilesParser
import dev.libreqc.prince.EqBand
import dev.libreqc.prince.EqParser
import dev.libreqc.prince.FeatureResult
import dev.libreqc.prince.ModeConfig
import dev.libreqc.prince.ModeConfigParser
import dev.libreqc.prince.ModesSnapshot
import dev.libreqc.prince.MultipointParser
import dev.libreqc.prince.ParseResult
import dev.libreqc.prince.PrinceCommands
import dev.libreqc.prince.RememberedSource
import dev.libreqc.prince.ShortcutAction
import dev.libreqc.prince.ShortcutParser
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(ProbeUiState())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startProbe()
            } else {
                updateStatus("Bluetooth permission denied", running = false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                LibreQcApp(
                    state = state,
                    onRefresh = ::startProbe,
                    onSelectPage = { state = state.copy(page = it) },
                    onSelectMode = ::startModeSelection,
                    onSetEq = ::startEqSelection,
                    onSetShortcut = ::startShortcutSelection,
                    onSetMultipoint = ::startMultipointSelection,
                    onSetSourceConnection = ::startSourceConnection,
                )
            }
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val verifyModeConfigIndex = intent.getIntExtra(EXTRA_VERIFY_MODE_CONFIG_INDEX, -1)
            if (verifyModeConfigIndex >= 0) {
                startModeConfigWriteVerification(verifyModeConfigIndex)
            } else {
                startProbe()
            }
        } else {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun startProbe() {
        if (state.running) return
        state = ProbeUiState(status = "Connecting...", running = true)
        Thread(::runProbe, "libreqc-probe").start()
    }

    private fun startModeSelection(index: Int) {
        if (state.running) return
        state = state.copy(status = "Changing mode...", running = true)
        Thread(
            {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val device = adapter?.let { selectDevice(it.bondedDevices) }
                        ?: error("No bonded Bose BMAP device found")
                    selectMode(device, index)
                    onMain {
                        state = state.copy(status = "Mode changed", running = false)
                        startProbe()
                    }
                } catch (error: Throwable) {
                    line("mode selection failed %s: %s", error.javaClass.simpleName, error.message)
                    updateStatus("Mode change failed: ${error.message}", running = false)
                }
            },
            "libreqc-mode-selection",
        ).start()
    }

    private fun startModeConfigWriteVerification(index: Int) {
        if (state.running) return
        state = state.copy(status = "Verifying mode config write...", running = true)
        Thread(
            {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val device = adapter?.let { selectDevice(it.bondedDevices) }
                        ?: error("No bonded Bose BMAP device found")
                    verifyModeConfigWrite(device, index)
                    updateStatus("Mode config write verified", running = false)
                } catch (error: Throwable) {
                    line("mode config write failed %s: %s", error.javaClass.simpleName, error.message)
                    updateStatus("Mode config write failed: ${error.message}", running = false)
                }
            },
            "libreqc-mode-config-write-intent",
        ).start()
    }

    private fun startEqSelection(band: EqBand, target: Int) {
        if (state.running) return
        state = state.copy(status = "Changing EQ...", running = true)
        Thread(
            {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val device = adapter?.let { selectDevice(it.bondedDevices) }
                        ?: error("No bonded Bose BMAP device found")
                    setEq(device, band, target)
                    onMain {
                        state = state.copy(status = "EQ changed", running = false)
                        startProbe()
                    }
                } catch (error: Throwable) {
                    line("EQ change failed %s: %s", error.javaClass.simpleName, error.message)
                    updateStatus("EQ change failed: ${error.message}", running = false)
                }
            },
            "libreqc-eq-selection",
        ).start()
    }

    private fun startShortcutSelection(action: ShortcutAction) {
        if (state.running) return
        state = state.copy(status = "Changing shortcut...", running = true)
        Thread(
            {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val device = adapter?.let { selectDevice(it.bondedDevices) }
                        ?: error("No bonded Bose BMAP device found")
                    setShortcut(device, action)
                    onMain {
                        state = state.copy(status = "Shortcut changed", running = false)
                        startProbe()
                    }
                } catch (error: Throwable) {
                    line("shortcut change failed %s: %s", error.javaClass.simpleName, error.message)
                    updateStatus("Shortcut change failed: ${error.message}", running = false)
                }
            },
            "libreqc-shortcut-selection",
        ).start()
    }

    private fun startMultipointSelection(enabled: Boolean) {
        if (state.running) return
        state = state.copy(status = "Changing multipoint...", running = true)
        Thread(
            {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val device = adapter?.let { selectDevice(it.bondedDevices) }
                        ?: error("No bonded Bose BMAP device found")
                    setMultipoint(device, enabled)
                    onMain {
                        state = state.copy(status = "Multipoint changed", running = false)
                        startProbe()
                    }
                } catch (error: Throwable) {
                    line("multipoint change failed %s: %s", error.javaClass.simpleName, error.message)
                    updateStatus("Multipoint change failed: ${error.message}", running = false)
                }
            },
            "libreqc-multipoint-selection",
        ).start()
    }

    private fun startSourceConnection(identifier: DeviceIdentifier, connect: Boolean) {
        if (state.running) return
        state = state.copy(
            status = if (connect) "Connecting source..." else "Disconnecting source...",
            running = true,
        )
        Thread(
            {
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val device = adapter?.let { selectDevice(it.bondedDevices) }
                        ?: error("No bonded Bose BMAP device found")
                    setSourceConnection(device, identifier, connect)
                    onMain {
                        state = state.copy(
                            status = if (connect) "Source connected" else "Source disconnected",
                            running = false,
                        )
                        startProbe()
                    }
                } catch (error: Throwable) {
                    val action = if (connect) "connect" else "disconnect"
                    line("source %s failed %s: %s", action, error.javaClass.simpleName, error.message)
                    updateStatus("Source $action failed: ${error.message}", running = false)
                }
            },
            "libreqc-source-connection",
        ).start()
    }

    @SuppressLint("MissingPermission")
    private fun setSourceConnection(
        device: BluetoothDevice,
        identifier: DeviceIdentifier,
        connect: Boolean,
    ) {
        device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
            socket.connect()
            val input = socket.inputStream
            val output = socket.outputStream
            val runner = ReadProbeRunner(ProbeLogListener())
            runner.drain(input, 300)

            val command =
                if (connect) PrinceCommands.connectSource(identifier)
                else PrinceCommands.disconnectSource(identifier)
            line(
                "tx source %s START [%s] %s",
                if (connect) "connect" else "disconnect",
                if (connect) "4.1" else "4.2",
                BmapDiagnostics.hex(command),
            )
            output.write(command)
            output.flush()
            runner.drain(input, 2_000)

            val connected = verifySourceConnection(runner, input, output, identifier, connect)
            line(
                "source %s verified connected=%s",
                if (connect) "connect" else "disconnect",
                connected,
            )
        }
    }

    private fun verifySourceConnection(
        runner: ReadProbeRunner,
        input: InputStream,
        output: OutputStream,
        identifier: DeviceIdentifier,
        expectedConnected: Boolean,
    ): Boolean {
        val attempts = if (expectedConnected) 12 else 4
        var lastConnected: Boolean? = null
        repeat(attempts) { attempt ->
            if (attempt > 0) Thread.sleep(1_000)
            val readBack = exchange(
                runner,
                input,
                output,
                ReadProbe(
                    "device_management.extended_info.verify",
                    BmapAddress(4, 6),
                    identifier.bytes,
                ),
            )
            val parsed = readBack.response()?.let { DeviceProfilesParser.parse(it.payload) }
            val profiles = (parsed as? ParseResult.Success)?.value
                ?: error("Source read-back was unavailable or malformed")
            val connected = profiles.connected.isNotEmpty()
            lastConnected = connected
            if (connected == expectedConnected) return connected
        }
        error(
            "Source read-back mismatch: expected connected=$expectedConnected, " +
                "got connected=${lastConnected ?: "unknown"}",
        )
    }

    @SuppressLint("MissingPermission")
    private fun setMultipoint(device: BluetoothDevice, enabled: Boolean) {
        device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
            socket.connect()
            val input = socket.inputStream
            val output = socket.outputStream
            val runner = ReadProbeRunner(ProbeLogListener())
            runner.drain(input, 300)

            val command = PrinceCommands.setMultipoint(enabled)
            line("tx multipoint set SETGET [1.10] %s", BmapDiagnostics.hex(command))
            output.write(command)
            output.flush()
            runner.drain(input, 300)

            val readBack = exchange(
                runner,
                input,
                output,
                ReadProbe("settings.multipoint", BmapAddress(1, 10)),
            )
            val parsed = readBack.response()?.let { MultipointParser.parse(it.payload) }
            val multipoint = (parsed as? ParseResult.Success)?.value
                ?: error("Multipoint read-back was unavailable or malformed")
            check(multipoint.enabled == enabled) {
                "Multipoint read-back mismatch: expected ${onOff(enabled)}, got ${onOff(multipoint.enabled)}"
            }
            line("multipoint change verified enabled=%s", enabled)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setShortcut(device: BluetoothDevice, action: ShortcutAction) {
        device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
            socket.connect()
            val input = socket.inputStream
            val output = socket.outputStream
            val runner = ReadProbeRunner(ProbeLogListener())
            runner.drain(input, 300)

            val command = PrinceCommands.setShortcut(action)
            line("tx shortcut set SETGET [1.9] %s", BmapDiagnostics.hex(command))
            output.write(command)
            output.flush()
            runner.drain(input, 300)

            val readBack = exchange(
                runner,
                input,
                output,
                ReadProbe("settings.shortcut", BmapAddress(1, 9)),
            )
            val parsed = readBack.response()?.let { ShortcutParser.parse(it.payload) }
            val shortcut = (parsed as? ParseResult.Success)?.value
                ?: error("Shortcut read-back was unavailable or malformed")
            check(shortcut.configuredAction == action) {
                "Shortcut read-back mismatch: expected ${actionName(action)}, " +
                    "got ${actionName(shortcut.configuredAction)}"
            }
            line("shortcut change verified action=%s", actionName(action))
        }
    }

    @SuppressLint("MissingPermission")
    private fun setEq(device: BluetoothDevice, band: EqBand, target: Int) {
        device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
            socket.connect()
            val input = socket.inputStream
            val output = socket.outputStream
            val runner = ReadProbeRunner(ProbeLogListener())
            runner.drain(input, 300)

            val command = PrinceCommands.setEq(band, target)
            line("tx EQ set SETGET [1.7] %s", BmapDiagnostics.hex(command))
            output.write(command)
            output.flush()
            runner.drain(input, 300)

            val readBack = exchange(
                runner,
                input,
                output,
                ReadProbe("settings.eq", BmapAddress(1, 7)),
            )
            val parsed = readBack.response()?.let { EqParser.parse(it.payload) }
            val eq = (parsed as? ParseResult.Success)?.value
                ?: error("EQ read-back was unavailable or malformed")
            val actual = eq.ranges.firstOrNull { it.band == band }?.current
            check(actual == target) {
                "EQ read-back mismatch: expected $target, got ${actual ?: "no value"}"
            }
            line("EQ change verified band=%s target=%d", band, target)
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectMode(device: BluetoothDevice, index: Int) {
        device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
            socket.connect()
            val input = socket.inputStream
            val output = socket.outputStream
            val runner = ReadProbeRunner(ProbeLogListener())
            runner.drain(input, 300)

            val command = PrinceCommands.selectMode(index, voicePrompt = false)
            line("tx mode.select START [31.3] %s", BmapDiagnostics.hex(command))
            output.write(command)
            output.flush()

            val readBack = exchange(
                runner,
                input,
                output,
                ReadProbe("audio_modes.current", BmapAddress(31, 3)),
            )
            val actual = readBack.response()?.payload?.firstOrNull()?.toInt()?.and(0xff)
            check(actual == index) {
                "Mode read-back mismatch: expected $index, got ${actual ?: "no value"}"
            }
            line("mode selection verified index=%d", index)
        }
    }

    @SuppressLint("MissingPermission")
    private fun verifyModeConfigWrite(device: BluetoothDevice, index: Int) {
        device.createRfcommSocketToServiceRecord(SPP_UUID).use { socket ->
            socket.connect()
            val input = socket.inputStream
            val output = socket.outputStream
            val runner = ReadProbeRunner(ProbeLogListener())
            runner.drain(input, 300)

            val currentResult = exchange(
                runner,
                input,
                output,
                ReadProbe("audio_modes.config.$index.current", BmapAddress(31, 6), byteArrayOf(index.toByte())),
            )
            val current = (currentResult.response()?.let { ModeConfigParser.parse(it.payload) } as? ParseResult.Success)?.value
                ?: error("Mode config snapshot was unavailable or malformed")
            check(current.isUserConfigurable()) { "Mode config $index is not user-configurable" }

            val command = PrinceCommands.setModeConfigBasic(
                index = current.index,
                prompt = promptFromConfig(current),
                name = current.name,
                cncLevel = current.cncLevel,
                autoCncEnabled = false,
            )
            line("tx mode.config SETGET [31.6] %s", BmapDiagnostics.hex(command))
            val writeResult = exchange(
                runner,
                input,
                output,
                ReadProbe(
                    "audio_modes.config.$index.set",
                    BmapAddress(31, 6),
                    BmapOperator.SetGet,
                    command.copyOfRange(4, command.size),
                ),
            )
            check(writeResult.response()?.operator != BmapOperator.Error) {
                "Mode config write was rejected"
            }

            val readBack = exchange(
                runner,
                input,
                output,
                ReadProbe("audio_modes.config.$index.verify", BmapAddress(31, 6), byteArrayOf(index.toByte())),
            )
            val updated = (readBack.response()?.let { ModeConfigParser.parse(it.payload) } as? ParseResult.Success)?.value
                ?: error("Mode config read-back was unavailable or malformed")
            check(updated.index == current.index && updated.name == current.name && updated.cncLevel == current.cncLevel) {
                "Mode config read-back mismatch: expected index=${current.index} name=${current.name} cnc=${current.cncLevel}, " +
                    "got index=${updated.index} name=${updated.name} cnc=${updated.cncLevel}"
            }
            line("mode config write verified index=%d cnc=%d", updated.index, updated.cncLevel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun runProbe() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                updateStatus("Bluetooth is unavailable or disabled", running = false)
                return
            }
            val device = selectDevice(adapter.bondedDevices)
            if (device == null) {
                updateStatus("No bonded Bose BMAP device found", running = false)
                return
            }
            onMain {
                state = state.copy(
                    deviceName = device.name ?: "Bose headphones",
                    status = "Connecting...",
                )
            }
            line("device name=%s address=%s", device.name, redactAddress(device.address))
            line("device uuids=%s", device.uuids.contentToString())

            if (!probeTransport(device, SPP_UUID, "spp-uuid")) {
                if (!probeTransport(device, BMAP_UUID, "bmap-uuid")) {
                    updateStatus(
                        "Unable to connect. Close Bose Music and retry.",
                        running = false,
                    )
                }
            }
        } catch (error: Throwable) {
            line("fatal %s: %s", error.javaClass.simpleName, error.message)
            Log.e(TAG, "Probe failed", error)
            updateStatus("Probe failed: ${error.message ?: error.javaClass.simpleName}", false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun selectDevice(bondedDevices: Set<BluetoothDevice>): BluetoothDevice? {
        val requestedMac = intent.getStringExtra("mac")
        bondedDevices.firstOrNull {
            requestedMac != null && requestedMac.equals(it.address, ignoreCase = true)
        }?.let { return it }
        return bondedDevices.firstOrNull { device ->
            device.uuids?.any { it.uuid == BMAP_UUID } == true
        }
    }

    @SuppressLint("MissingPermission")
    private fun probeTransport(
        device: BluetoothDevice,
        uuid: UUID,
        label: String,
    ): Boolean {
        line("transport %s connecting uuid=%s", label, uuid)
        return try {
            device.createRfcommSocketToServiceRecord(uuid).use { socket ->
                socket.connect()
                runSnapshot(socket, label)
            }
            true
        } catch (error: Throwable) {
            line(
                "transport %s failed %s: %s",
                label,
                error.javaClass.simpleName,
                error.message,
            )
            Log.w(TAG, "Transport failed: $label", error)
            false
        }
    }

    private fun runSnapshot(socket: BluetoothSocket, label: String) {
        line("transport %s connected", label)
        updateStatus("Reading device...", running = true)
        val input = socket.inputStream
        val output = socket.outputStream
        val collector = SnapshotFrameCollector()
        val runner = ReadProbeRunner(ProbeLogListener())
        runner.drain(input, 300)

        line("probe policy=read-only base_count=%d", SnapshotProbes.BASE.size)
        for (probe in SnapshotProbes.BASE) {
            val result = exchange(runner, input, output, probe)
            result.response()?.let { collector.accept(probe.name(), it) }
            if (probe.name() == "device_management.list" && result.response() != null) {
                val details = SnapshotProbes.deviceDetails(result.response()!!.payload)
                line("probe dynamic=device-details count=%d", details.size)
                for (detail in details) {
                    Thread.sleep(PROBE_DELAY_MS)
                    val detailResult = exchange(runner, input, output, detail)
                    detailResult.response()?.let { collector.accept(detail.name(), it) }
                }
            }
            Thread.sleep(PROBE_DELAY_MS)
        }

        val snapshot = DeviceSnapshotParser.parse(collector.frames())
        line("transport %s complete", label)
        onMain {
            state = state.copy(
                snapshot = snapshot,
                status = "Connected",
                running = false,
            )
        }
    }

    private fun exchange(
        runner: ReadProbeRunner,
        input: InputStream,
        output: OutputStream,
        probe: ReadProbe,
    ): ReadProbeRunner.Result {
        line(
            "probe start name=%s address=[%d.%d]",
            probe.name(),
            probe.address().functionBlock,
            probe.address().function,
        )
        line("tx %-30s %s", probe.name(), BmapDiagnostics.hex(probe.packet()))
        val result = runner.exchange(input, output, probe, PROBE_TIMEOUT_MS)
        val error = result.response()?.error?.let {
            " error=${it.name}(${result.response()?.errorCode})"
        }.orEmpty()
        line(
            "probe result name=%s outcome=%s elapsed_ms=%d%s unrelated=%d",
            probe.name(),
            result.outcome(),
            result.elapsedMs(),
            error,
            result.unrelatedFrames().size,
        )
        result.malformed()?.let {
            line(
                "probe malformed name=%s expected=%d actual=%d",
                probe.name(),
                it.expectedFrameLength,
                it.actualByteCount,
            )
        }
        return result
    }

    private fun updateStatus(message: String, running: Boolean) {
        onMain { state = state.copy(status = message, running = running) }
    }

    private fun line(format: String, vararg args: Any?) {
        val message = String.format(Locale.US, format, *args)
        Log.i(TAG, message)
        onMain { state = state.copy(logs = state.logs + message) }
    }

    private fun onMain(action: () -> Unit) {
        runOnUiThread(action)
    }

    private inner class ProbeLogListener : ReadProbeRunner.Listener {
        override fun onReceiveChunk(bytes: ByteArray) {
            line("rx chunk %s", BmapDiagnostics.hex(bytes))
        }

        override fun onFrame(frame: BmapFrame, matchesCurrentProbe: Boolean) {
            line(
                "frame %s correlation=%s text=%s",
                BmapDiagnostics.describe(frame),
                if (matchesCurrentProbe) "related" else "unsolicited",
                printable(frame.payload),
            )
        }
    }

    companion object {
        private const val TAG = "LibreQCProbe"
        private const val EXTRA_VERIFY_MODE_CONFIG_INDEX = "verifyModeConfigIndex"
        private const val PROBE_TIMEOUT_MS = 3_000L
        private const val PROBE_DELAY_MS = 150L
        private val BMAP_UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        private fun printable(bytes: ByteArray): String =
            String(bytes, StandardCharsets.UTF_8).map { value ->
                if (value.code in 32..126) value else '.'
            }.joinToString("")

        private fun redactAddress(address: String?): String {
            if (address == null || address.length < 5) return "<unknown>"
            return "XX:XX:XX:XX:${address.takeLast(5)}"
        }
    }
}

private fun promptFromConfig(config: ModeConfig): AudioModePrompt {
    val byte1 = config.headerBytes.getOrNull(0)?.toInt()?.and(0xff) ?: 0
    val byte2 = config.headerBytes.getOrNull(1)?.toInt()?.and(0xff) ?: 0
    return AudioModePrompt.entries.firstOrNull { it.byte1 == byte1 && it.byte2 == byte2 }
        ?: AudioModePrompt.None
}

private data class ProbeUiState(
    val deviceName: String = "LibreQC",
    val status: String = "Waiting for Bluetooth permission",
    val running: Boolean = false,
    val snapshot: DeviceSnapshot? = null,
    val logs: List<String> = emptyList(),
    val page: AppPage = AppPage.Overview,
)

private enum class AppPage {
    Overview,
    Modes,
    Sources,
    Eq,
    Shortcut,
    Diagnostics,
}

@Composable
private fun LibreQcApp(
    state: ProbeUiState,
    onRefresh: () -> Unit,
    onSelectPage: (AppPage) -> Unit,
    onSelectMode: (Int) -> Unit,
    onSetEq: (EqBand, Int) -> Unit,
    onSetShortcut: (ShortcutAction) -> Unit,
    onSetMultipoint: (Boolean) -> Unit,
    onSetSourceConnection: (DeviceIdentifier, Boolean) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        when (state.page) {
            AppPage.Overview -> Overview(state, onRefresh, onSelectPage)
            AppPage.Modes -> FeatureScreen("Modes", onSelectPage) {
                ModesContent(state.snapshot?.modes, state.running, onSelectMode)
            }
            AppPage.Sources -> FeatureScreen("Source", onSelectPage) {
                SourcesContent(
                    sources = state.snapshot?.sources,
                    multipoint = state.snapshot?.multipoint,
                    running = state.running,
                    onSetMultipoint = onSetMultipoint,
                    onSetSourceConnection = onSetSourceConnection,
                )
            }
            AppPage.Eq -> FeatureScreen("Equalizer", onSelectPage) {
                EqContent(state.snapshot?.eq, state.running, onSetEq)
            }
            AppPage.Shortcut -> FeatureScreen("Shortcut", onSelectPage) {
                ShortcutContent(state.snapshot?.shortcut, state.running, onSetShortcut)
            }
            AppPage.Diagnostics -> DiagnosticsScreen(state, onSelectPage)
        }
    }
}

@Composable
private fun Overview(
    state: ProbeUiState,
    onRefresh: () -> Unit,
    onSelectPage: (AppPage) -> Unit,
) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    state.deviceName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    state.status,
                    color = if (state.status == "Connected") Color(0xFF26734D) else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRefresh, enabled = !state.running) {
                        Text(if (state.running) "Reading..." else "Refresh")
                    }
                    TextButton(onClick = { onSelectPage(AppPage.Diagnostics) }) {
                        Text("Diagnostics")
                    }
                }
            }
            item {
                FeatureCard(
                    title = "Modes",
                    summary = modesSummary(state.snapshot?.modes),
                    onClick = { onSelectPage(AppPage.Modes) },
                )
            }
            item {
                FeatureCard(
                    title = "Source",
                    summary = sourcesSummary(state.snapshot),
                    onClick = { onSelectPage(AppPage.Sources) },
                )
            }
            item {
                FeatureCard(
                    title = "EQ",
                    summary = eqSummary(state.snapshot?.eq),
                    onClick = { onSelectPage(AppPage.Eq) },
                )
            }
            item {
                FeatureCard(
                    title = "Shortcut",
                    summary = shortcutSummary(state.snapshot?.shortcut),
                    onClick = { onSelectPage(AppPage.Shortcut) },
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun FeatureCard(title: String, summary: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FeatureScreen(
    title: String,
    onSelectPage: (AppPage) -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TextButton(onClick = { onSelectPage(AppPage.Overview) }) {
                    Text("Back")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
            }
            item { content() }
        }
    }
}

@Composable
private fun ModesContent(
    modes: ModesSnapshot?,
    running: Boolean,
    onSelectMode: (Int) -> Unit,
) {
    if (modes == null) return StatusText("Not yet read")
    ValueRow("Current", featureText(modes.current) { "Mode ${it.value}" })
    ValueRow("Startup mode value", featureText(modes.default) { "Mode ${it.value}" })
    ValueRow(
        "Remember my mode",
        featureText(modes.persistence) { if (it.enabled) "On" else "Off" },
    )
    val configs = (modes.configs as? FeatureResult.Available)?.value
    val favorites = (modes.favorites as? FeatureResult.Available)?.value?.indices.orEmpty()
    val current = (modes.current as? FeatureResult.Available)?.value?.value
    configs?.forEach {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(it.name.ifEmpty { "Configurable slot ${it.index}" })
                Text(
                    "CNC ${it.cncLevel}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (it.index in favorites) {
                Button(
                    onClick = { onSelectMode(it.index) },
                    enabled = !running && it.index != current,
                ) {
                    Text(if (it.index == current) "Current" else "Select")
                }
            }
        }
    }
}

private fun ModeConfig.isUserConfigurable(): Boolean =
    headerBytes.getOrNull(2)?.toInt() != 0

@Composable
private fun SourcesContent(
    sources: FeatureResult<List<RememberedSource>>?,
    multipoint: FeatureResult<dev.libreqc.prince.MultipointState>?,
    running: Boolean,
    onSetMultipoint: (Boolean) -> Unit,
    onSetSourceConnection: (DeviceIdentifier, Boolean) -> Unit,
) {
    val multipointValue = (multipoint as? FeatureResult.Available)?.value
    ValueRow(
        "Multipoint",
        featureText(multipoint) { onOff(it.enabled) },
    )
    if (multipointValue != null) {
        Text(
            "Turning off multipoint may disconnect a second device.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { onSetMultipoint(!multipointValue.enabled) },
            enabled = !running &&
                multipointValue.supported &&
                (!multipointValue.enabled || multipointValue.canDisable),
        ) {
            Text(if (multipointValue.enabled) "Turn off" else "Turn on")
        }
    }
    val available = (sources as? FeatureResult.Available)?.value
    if (available == null) {
        StatusText(featureStatus(sources))
        return
    }
    available.forEach { source ->
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val connected = source.profiles?.connected.orEmpty().isNotEmpty()
        val local = source.info?.localDevice == true
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(source.info?.name ?: "Remembered device")
                Text(
                    if (connected) "Connected" else "Not connected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!local) {
                Button(
                    onClick = { onSetSourceConnection(source.identifier, !connected) },
                    enabled = !running,
                ) {
                    Text(if (connected) "Disconnect" else "Connect")
                }
            }
        }
        val profiles = source.profiles?.connected.orEmpty()
        if (profiles.isNotEmpty()) {
            Text(
                profiles.joinToString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EqContent(
    eq: FeatureResult<dev.libreqc.prince.EqState>?,
    running: Boolean,
    onSetEq: (EqBand, Int) -> Unit,
) {
    val available = (eq as? FeatureResult.Available)?.value
    if (available == null) {
        StatusText(featureStatus(eq))
        return
    }
    available.ranges.forEach {
        val band = it.band
        val name = when (band) {
            EqBand.Bass -> "Bass"
            EqBand.Mid -> "Mid"
            EqBand.Treble -> "Treble"
            is EqBand.Unknown -> "Range ${band.rangeId}"
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name)
                Text(
                    "${signed(it.current)}  (${it.minimum} to ${it.maximum})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSetEq(band, it.current - 1) },
                    enabled = !running && band !is EqBand.Unknown && it.current > it.minimum,
                ) {
                    Text("-")
                }
                Button(
                    onClick = { onSetEq(band, it.current + 1) },
                    enabled = !running && band !is EqBand.Unknown && it.current < it.maximum,
                ) {
                    Text("+")
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun ShortcutContent(
    shortcut: FeatureResult<dev.libreqc.prince.ShortcutState>?,
    running: Boolean,
    onSetShortcut: (ShortcutAction) -> Unit,
) {
    val available = (shortcut as? FeatureResult.Available)?.value
    if (available == null) {
        StatusText(featureStatus(shortcut))
        return
    }
    ValueRow("Enabled", if (available.enabled) "On" else "Off")
    ValueRow("Assignment", actionName(available.configuredAction))
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Available assignments", style = MaterialTheme.typography.titleMedium)
    available.supportedActions.forEach { action ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(actionName(action), modifier = Modifier.weight(1f))
            Button(
                onClick = { onSetShortcut(action) },
                enabled = !running &&
                    action !is ShortcutAction.Unknown &&
                    action != available.configuredAction,
            ) {
                Text(if (action == available.configuredAction) "Current" else "Set")
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(state: ProbeUiState, onSelectPage: (AppPage) -> Unit) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            item {
                TextButton(onClick = { onSelectPage(AppPage.Overview) }) {
                    Text("Back")
                }
                Text(
                    "Diagnostics",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Raw protocol data may contain local device identifiers.",
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
            }
            items(state.logs) {
                Text(it, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun modesSummary(modes: ModesSnapshot?): String =
    if (modes == null) {
        "Not yet read"
    } else {
        val current = (modes.current as? FeatureResult.Available)?.value?.value
        val configs = (modes.configs as? FeatureResult.Available)?.value
        configs?.firstOrNull { it.index == current }?.name?.ifEmpty { null }
            ?: current?.let { "Mode $it" }
            ?: featureStatus(modes.current)
    }

private fun sourcesSummary(snapshot: DeviceSnapshot?): String {
    val sources = (snapshot?.sources as? FeatureResult.Available)?.value
        ?: return featureStatus(snapshot?.sources)
    val connected = sources.count { it.info?.connected == true }
    val multipoint = (snapshot.multipoint as? FeatureResult.Available)?.value
    return "${sources.size} remembered, $connected connected" +
        if (multipoint != null) {
            " · Multipoint ${if (multipoint.enabled) "on" else "off"}"
        } else {
            ""
        }
}

private fun eqSummary(eq: FeatureResult<dev.libreqc.prince.EqState>?): String {
    val ranges = (eq as? FeatureResult.Available)?.value?.ranges
        ?: return featureStatus(eq)
    return ranges.joinToString(" · ") {
        val band = it.band
        val name = when (band) {
            EqBand.Bass -> "Bass"
            EqBand.Mid -> "Mid"
            EqBand.Treble -> "Treble"
            is EqBand.Unknown -> "Range ${band.rangeId}"
        }
        "$name ${signed(it.current)}"
    }
}

private fun shortcutSummary(
    shortcut: FeatureResult<dev.libreqc.prince.ShortcutState>?,
): String {
    val value = (shortcut as? FeatureResult.Available)?.value
        ?: return featureStatus(shortcut)
    return if (value.enabled) actionName(value.configuredAction) else "Off"
}

private fun actionName(action: ShortcutAction): String =
    when (action) {
        ShortcutAction.BatteryLevel -> "Hear battery level"
        ShortcutAction.Disabled -> "Disabled"
        ShortcutAction.Spotify -> "Spotify"
        is ShortcutAction.Unknown -> "Action ${action.id}"
    }

private fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

private fun onOff(value: Boolean): String = if (value) "On" else "Off"

private fun featureStatus(result: FeatureResult<*>?): String =
    when (result) {
        is FeatureResult.Available -> "Available"
        is FeatureResult.Unsupported -> "Unsupported"
        is FeatureResult.Malformed -> "Malformed response"
        FeatureResult.NotVerified, null -> "Not yet verified"
    }

private fun <T> featureText(result: FeatureResult<T>?, value: (T) -> String): String =
    when (result) {
        is FeatureResult.Available -> value(result.value)
        else -> featureStatus(result)
    }
