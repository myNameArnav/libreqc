package dev.libreqc.prince

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SourcesParserTest {
    @Test
    fun `parses the redacted remembered device list`() {
        val result = DeviceListParser.parse(
            hex("02111111111111222222222222333333333333444444444444"),
        )

        val list = assertIs<ParseResult.Success<DeviceList>>(result).value
        assertEquals(2, list.header)
        assertEquals(4, list.identifiers.size)
        assertContentEquals(hex("111111111111"), list.identifiers.first().bytes)
        assertContentEquals(hex("444444444444"), list.identifiers.last().bytes)
    }

    @Test
    fun `device identifiers compare by byte content`() {
        assertEquals(
            DeviceIdentifier(hex("111111111111")),
            DeviceIdentifier(hex("111111111111")),
        )
    }

    @Test
    fun `parses captured Bluetooth class device info`() {
        val nonBose = parseInfo("1111111111110001034465766963652d3031")
        val second = parseInfo("4444444444440004064465766963652d303034")

        assertEquals("Device-01", nonBose.name)
        assertFalse(nonBose.connected)
        assertEquals(DeviceCategory.BluetoothClass(1, 3), nonBose.category)
        assertEquals("Device-004", second.name)
        assertEquals(DeviceCategory.BluetoothClass(4, 6), second.category)
    }

    @Test
    fun `parses recovered Bose product device info variant`() {
        val bose = parseInfo("11111111111104407501426f7365")

        assertEquals("Bose", bose.name)
        assertEquals(DeviceCategory.BoseProduct(0x4075, 1), bose.category)
    }

    @Test
    fun `parses connection flags and profile masks`() {
        val info = parseInfo("2222222222220302034465766963652d3032")
        val profiles = assertIs<ParseResult.Success<DeviceProfiles>>(
            DeviceProfilesParser.parse(hex("222222222222070f")),
        ).value

        assertTrue(info.connected)
        assertTrue(info.localDevice)
        assertEquals(setOf(BluetoothProfile.A2dp, BluetoothProfile.Hfp, BluetoothProfile.Avrcp), profiles.paired)
        assertEquals(
            setOf(
                BluetoothProfile.A2dp,
                BluetoothProfile.Hfp,
                BluetoothProfile.Avrcp,
                BluetoothProfile.Spp,
            ),
            profiles.connected,
        )
        assertTrue(profiles.leAudioFlags.isEmpty())
    }

    @Test
    fun `preserves optional LE audio profile bytes`() {
        val profiles = assertIs<ParseResult.Success<DeviceProfiles>>(
            DeviceProfilesParser.parse(hex("11111111111107000102")),
        ).value

        assertContentEquals(hex("0102"), profiles.leAudioFlags)
    }

    @Test
    fun `rejects malformed source payloads`() {
        assertIs<ParseResult.Malformed>(DeviceListParser.parse(hex("021111")))
        assertIs<ParseResult.Malformed>(DeviceInfoParser.parse(hex("11111111111100")))
        assertIs<ParseResult.Malformed>(DeviceProfilesParser.parse(hex("11111111111107")))
    }

    private fun parseInfo(value: String): DeviceInfo =
        assertIs<ParseResult.Success<DeviceInfo>>(DeviceInfoParser.parse(hex(value))).value

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
