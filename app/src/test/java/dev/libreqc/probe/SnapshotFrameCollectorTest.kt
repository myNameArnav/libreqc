package dev.libreqc.probe

import dev.libreqc.bmap.BmapAddress
import dev.libreqc.bmap.BmapFrame
import dev.libreqc.bmap.BmapOperator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SnapshotFrameCollectorTest {
    @Test
    fun mapsBaseAndDynamicProbeNamesToTypedFrameSlots() {
        val collector = SnapshotFrameCollector()
        val battery = frame(2, 2)
        val eq = frame(1, 7)
        val deviceInfo = frame(4, 5)
        val profiles = frame(4, 6)
        val mode = frame(31, 6)

        collector.accept("status.battery", battery)
        collector.accept("settings.eq", eq)
        collector.accept("device_management.info.0", deviceInfo)
        collector.accept("device_management.extended_info.0", profiles)
        collector.accept("audio_modes.config.2", mode)
        collector.accept("unrelated.name", frame(99, 99))

        val frames = collector.frames()
        assertSame(battery, frames.battery)
        assertSame(eq, frames.eq)
        assertEquals(listOf(deviceInfo), frames.deviceInfos)
        assertEquals(listOf(profiles), frames.deviceProfiles)
        assertEquals(listOf(mode), frames.modeConfigs)
    }

    private fun frame(block: Int, function: Int): BmapFrame =
        BmapFrame(
            BmapAddress(block, function),
            BmapOperator.Status,
            BmapOperator.Status.code,
            byteArrayOf(),
        )
}
