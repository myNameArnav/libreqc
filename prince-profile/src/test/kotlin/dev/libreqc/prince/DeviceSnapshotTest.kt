package dev.libreqc.prince

import dev.libreqc.bmap.BmapAddress
import dev.libreqc.bmap.BmapError
import dev.libreqc.bmap.BmapFrame
import dev.libreqc.bmap.BmapOperator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceSnapshotTest {
    @Test
    fun `builds independent typed features from captured frames`() {
        val snapshot = DeviceSnapshotParser.parse(
            PrinceSnapshotFrames(
                eq = status(1, 7, "f60a0200f60a0401f60a0002"),
                shortcut = status(1, 9, "8009100001400800000080"),
                multipoint = status(1, 10, "07"),
                deviceList = status(
                    4,
                    4,
                    "02111111111111222222222222333333333333444444444444",
                ),
                deviceInfos = listOf(
                    status(4, 5, "1111111111110001034465766963652d3031"),
                    status(4, 5, "2222222222220302034465766963652d3032"),
                ),
                deviceProfiles = listOf(
                    status(4, 6, "1111111111110700"),
                    status(4, 6, "222222222222070f"),
                ),
                routing = error(4, 12, BmapError.FunctionNotSupported),
                modeCapabilities = status(31, 2, "020200000009"),
                currentMode = status(31, 3, "00"),
                defaultMode = status(31, 4, "00"),
                modePersistence = status(31, 5, "01"),
                modeConfigs = listOf(
                    status(
                        31,
                        6,
                        "0000010000015175696574000000000000000000000000000000000000000000000000000000000000000000000000",
                    ),
                ),
                modeFavorites = status(31, 8, "0407"),
            ),
        )

        assertEquals(2, available(snapshot.eq).ranges.first().current)
        assertEquals(ShortcutAction.Spotify, available(snapshot.shortcut).configuredAction)
        assertTrue(available(snapshot.multipoint).enabled)
        assertEquals(4, available(snapshot.sources).size)
        assertEquals("Device-02", available(snapshot.sources)[1].info?.name)
        assertEquals(
            BmapError.FunctionNotSupported,
            assertIs<FeatureResult.Unsupported>(snapshot.routing).error,
        )
        assertEquals("Quiet", available(snapshot.modes.configs).single().name)
        assertEquals(listOf(0, 1, 2), available(snapshot.modes.favorites).indices)
    }

    @Test
    fun `malformed and missing features do not affect supported features`() {
        val snapshot = DeviceSnapshotParser.parse(
            PrinceSnapshotFrames(
                eq = status(1, 7, "f60a"),
                multipoint = status(1, 10, "07"),
                routing = error(4, 12, BmapError.FunctionNotSupported),
            ),
        )

        assertIs<FeatureResult.Malformed>(snapshot.eq)
        assertTrue(available(snapshot.multipoint).enabled)
        assertIs<FeatureResult.NotVerified>(snapshot.shortcut)
        assertIs<FeatureResult.NotVerified>(snapshot.sources)
        assertIs<FeatureResult.Unsupported>(snapshot.routing)
        assertIs<FeatureResult.NotVerified>(snapshot.modes.current)
    }

    @Test
    fun `unexpected response operator is malformed rather than supported`() {
        val snapshot = DeviceSnapshotParser.parse(
            PrinceSnapshotFrames(
                multipoint = BmapFrame(
                    BmapAddress(1, 10),
                    BmapOperator.Result,
                    BmapOperator.Result.code,
                    hex("07"),
                ),
            ),
        )

        assertIs<FeatureResult.Malformed>(snapshot.multipoint)
    }

    private fun status(block: Int, function: Int, payload: String): BmapFrame =
        BmapFrame(
            BmapAddress(block, function),
            BmapOperator.Status,
            BmapOperator.Status.code,
            hex(payload),
        )

    private fun error(block: Int, function: Int, error: BmapError): BmapFrame =
        BmapFrame(
            BmapAddress(block, function),
            BmapOperator.Error,
            BmapOperator.Error.code,
            byteArrayOf(error.code.toByte()),
        )

    private fun <T> available(result: FeatureResult<T>): T =
        assertIs<FeatureResult.Available<T>>(result).value

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
