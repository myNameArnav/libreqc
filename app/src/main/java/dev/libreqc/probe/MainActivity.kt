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
import dev.libreqc.prince.DeviceSnapshot
import dev.libreqc.prince.DeviceSnapshotParser
import dev.libreqc.prince.EqBand
import dev.libreqc.prince.EqParser
import dev.libreqc.prince.FeatureResult
import dev.libreqc.prince.ModesSnapshot
import dev.libreqc.prince.ParseResult
import dev.libreqc.prince.PrinceCommands
import dev.libreqc.prince.RememberedSource
import dev.libreqc.prince.ShortcutAction
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
                )
            }
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startProbe()
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
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        when (state.page) {
            AppPage.Overview -> Overview(state, onRefresh, onSelectPage)
            AppPage.Modes -> FeatureScreen("Modes", onSelectPage) {
                ModesContent(state.snapshot?.modes, state.running, onSelectMode)
            }
            AppPage.Sources -> FeatureScreen("Source", onSelectPage) {
                SourcesContent(state.snapshot?.sources, state.snapshot?.multipoint)
            }
            AppPage.Eq -> FeatureScreen("Equalizer", onSelectPage) {
                EqContent(state.snapshot?.eq, state.running, onSetEq)
            }
            AppPage.Shortcut -> FeatureScreen("Shortcut", onSelectPage) {
                ShortcutContent(state.snapshot?.shortcut)
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

@Composable
private fun SourcesContent(
    sources: FeatureResult<List<RememberedSource>>?,
    multipoint: FeatureResult<dev.libreqc.prince.MultipointState>?,
) {
    ValueRow(
        "Multipoint",
        featureText(multipoint) { if (it.enabled) "On" else "Off" },
    )
    val available = (sources as? FeatureResult.Available)?.value
    if (available == null) {
        StatusText(featureStatus(sources))
        return
    }
    available.forEach { source ->
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ValueRow(
            source.info?.name ?: "Remembered device",
            if (source.info?.connected == true) "Connected" else "Not connected",
        )
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
private fun ShortcutContent(shortcut: FeatureResult<dev.libreqc.prince.ShortcutState>?) {
    val available = (shortcut as? FeatureResult.Available)?.value
    if (available == null) {
        StatusText(featureStatus(shortcut))
        return
    }
    ValueRow("Enabled", if (available.enabled) "On" else "Off")
    ValueRow("Assignment", actionName(available.configuredAction))
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Available assignments", style = MaterialTheme.typography.titleMedium)
    available.supportedActions.forEach { Text(actionName(it)) }
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
