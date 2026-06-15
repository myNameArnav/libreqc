package dev.libreqc.prince

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModesParserTest {
    @Test
    fun `parses all captured mode configs`() {
        val quiet = parseMode(
            "0000010000015175696574000000000000000000000000000000000000000000000000000000000000000000000000",
        )
        val aware = parseMode(
            "0100020000014177617265000000000000000000000000000000000000000000000000000000000000000a00000000",
        )
        val commute = parseMode(
            "020007010101436f6d6d75746500000000000000000000000000000000000000000000000000000000090000000000",
        )
        val emptySlot = parseMode(
            "0300000101000000000000000000000000000000000000000000000000000000000000000000000000090500000000",
        )

        assertEquals(0, quiet.index)
        assertEquals("Quiet", quiet.name)
        assertEquals(0, quiet.cncLevel)
        assertContentEquals(hex("0001000001"), quiet.headerBytes)
        assertContentEquals(hex("000000000000000000"), quiet.trailingBytes)
        assertEquals("Aware", aware.name)
        assertEquals(10, aware.cncLevel)
        assertEquals("Commute", commute.name)
        assertEquals(9, commute.cncLevel)
        assertEquals(3, emptySlot.index)
        assertEquals("", emptySlot.name)
        assertEquals(9, emptySlot.cncLevel)
        assertContentEquals(hex("0500000000"), emptySlot.trailingBytes.copyOfRange(4, 9))
    }

    @Test
    fun `mode config requires exactly 47 bytes`() {
        val payload = ByteArray(46)

        val malformed = assertIs<ParseResult.Malformed>(ModeConfigParser.parse(payload))
        assertEquals("Mode config payload must contain exactly 47 bytes", malformed.reason)
        assertContentEquals(payload, malformed.payload)
    }

    @Test
    fun `parses mode startup and favorites state`() {
        val current = assertIs<ParseResult.Success<ModeIndex>>(
            ModeIndexParser.parse(hex("ff")),
        ).value
        val persistence = assertIs<ParseResult.Success<ModePersistence>>(
            ModePersistenceParser.parse(hex("01")),
        ).value
        val favorites = assertIs<ParseResult.Success<ModeFavorites>>(
            ModeFavoritesParser.parse(hex("0407")),
        ).value

        assertEquals(-1, current.value)
        assertTrue(persistence.enabled)
        assertEquals(4, favorites.modeCount)
        assertEquals(listOf(0, 1, 2), favorites.indices)
    }

    @Test
    fun `persistence is enabled only for value one`() {
        val disabled = assertIs<ParseResult.Success<ModePersistence>>(
            ModePersistenceParser.parse(hex("02")),
        ).value

        assertFalse(disabled.enabled)
        assertEquals(2, disabled.rawValue)
    }

    @Test
    fun `parses captured mode capability counts and preserves flags`() {
        val capabilities = assertIs<ParseResult.Success<ModeCapabilities>>(
            ModeCapabilitiesParser.parse(hex("020200000009")),
        ).value

        assertEquals(2, capabilities.boseModeCount)
        assertEquals(2, capabilities.userModeCount)
        assertContentEquals(hex("00000009"), capabilities.featureBytes)
    }

    private fun parseMode(value: String): ModeConfig =
        assertIs<ParseResult.Success<ModeConfig>>(ModeConfigParser.parse(hex(value))).value

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
