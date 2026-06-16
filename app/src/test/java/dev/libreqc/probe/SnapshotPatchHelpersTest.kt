package dev.libreqc.probe

import dev.libreqc.prince.BatteryState
import dev.libreqc.prince.BluetoothProfile
import dev.libreqc.prince.DeviceIdentifier
import dev.libreqc.prince.DeviceProfiles
import dev.libreqc.prince.DeviceSnapshot
import dev.libreqc.prince.FeatureResult
import dev.libreqc.prince.ModeIndex
import dev.libreqc.prince.ModesSnapshot
import dev.libreqc.prince.RememberedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SnapshotPatchHelpersTest {
    @Test
    fun patchesCurrentModeOnly() {
        val snapshot = snapshot()

        val updated = snapshot.withCurrentMode(ModeIndex(2))

        assertEquals(2, available(updated.modes.current).value)
        assertSame(snapshot.eq, updated.eq)
        assertSame(snapshot.sources, updated.sources)
    }

    @Test
    fun patchesMatchingSourceProfilesOnly() {
        val firstId = identifier(1)
        val secondId = identifier(2)
        val oldProfiles = profiles(firstId, connected = false)
        val newProfiles = profiles(secondId, connected = true)
        val snapshot = snapshot(
            sources = FeatureResult.Available(
                listOf(
                    RememberedSource(firstId, info = null, profiles = oldProfiles),
                    RememberedSource(secondId, info = null, profiles = null),
                ),
            ),
        )

        val updated = snapshot.withSourceProfiles(secondId, newProfiles)
        val sources = available(updated.sources)

        assertSame(oldProfiles, sources[0].profiles)
        assertSame(newProfiles, sources[1].profiles)
        assertSame(snapshot.eq, updated.eq)
    }

    @Test
    fun summariesHandleBatteryAndPendingStates() {
        assertEquals("Battery 85%", batterySummary(FeatureResult.Available(BatteryState(85))))
        assertEquals(
            "Verifying EQ...",
            fieldSummary(ProbeUiState(pendingField = UiField.Eq), UiField.Eq, "Bass +1"),
        )
    }

    private fun snapshot(
        sources: FeatureResult<List<RememberedSource>> = FeatureResult.NotVerified,
    ): DeviceSnapshot =
        DeviceSnapshot(
            battery = FeatureResult.NotVerified,
            eq = FeatureResult.NotVerified,
            shortcut = FeatureResult.NotVerified,
            multipoint = FeatureResult.NotVerified,
            sources = sources,
            routing = FeatureResult.NotVerified,
            modes = ModesSnapshot(
                capabilities = FeatureResult.NotVerified,
                current = FeatureResult.NotVerified,
                default = FeatureResult.NotVerified,
                persistence = FeatureResult.NotVerified,
                configs = FeatureResult.NotVerified,
                favorites = FeatureResult.NotVerified,
            ),
        )

    private fun profiles(identifier: DeviceIdentifier, connected: Boolean): DeviceProfiles =
        DeviceProfiles(
            identifier = identifier,
            paired = setOf(BluetoothProfile.A2dp),
            connected = if (connected) setOf(BluetoothProfile.A2dp) else emptySet(),
            leAudioFlags = byteArrayOf(),
        )

    private fun identifier(value: Int): DeviceIdentifier =
        DeviceIdentifier(byteArrayOf(0, 0, 0, 0, 0, value.toByte()))

    private fun <T> available(result: FeatureResult<T>): T =
        (result as FeatureResult.Available<T>).value
}
