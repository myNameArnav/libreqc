package dev.libreqc.prince

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SettingsParserTest {
    @Test
    fun `parses the captured shortcut assignment and capabilities`() {
        val result = ShortcutParser.parse(hex("8009100001400800000080"))

        val shortcut = assertIs<ParseResult.Success<ShortcutState>>(result).value
        assertEquals(0x80, shortcut.buttonId)
        assertEquals(9, shortcut.eventType)
        assertEquals(ShortcutAction.Spotify, shortcut.configuredAction)
        assertTrue(shortcut.enabled)
        assertEquals(
            setOf(
                ShortcutAction.BatteryLevel,
                ShortcutAction.Disabled,
                ShortcutAction.Spotify,
            ),
            shortcut.supportedActions,
        )
        assertEquals(setOf(ShortcutAction.Unknown(7)), shortcut.unavailableActions)
    }

    @Test
    fun `disabled assignment controls shortcut enabled state`() {
        val result = ShortcutParser.parse(hex("80090e0001400800000080"))

        val shortcut = assertIs<ParseResult.Success<ShortcutState>>(result).value
        assertEquals(ShortcutAction.Disabled, shortcut.configuredAction)
        assertFalse(shortcut.enabled)
    }

    @Test
    fun `rejects a truncated shortcut payload`() {
        val payload = hex("80091000014008")

        val malformed = assertIs<ParseResult.Malformed>(ShortcutParser.parse(payload))
        assertContentEquals(payload, malformed.payload)
    }

    @Test
    fun `parses captured multipoint feature flags`() {
        val result = MultipointParser.parse(hex("07"))

        val multipoint = assertIs<ParseResult.Success<MultipointState>>(result).value
        assertTrue(multipoint.enabled)
        assertTrue(multipoint.supported)
        assertTrue(multipoint.canDisable)
        assertEquals(0, multipoint.unknownFlags)
    }

    @Test
    fun `preserves unknown multipoint flags`() {
        val result = MultipointParser.parse(hex("87"))

        val multipoint = assertIs<ParseResult.Success<MultipointState>>(result).value
        assertEquals(0x80, multipoint.unknownFlags)
    }

    @Test
    fun `requires exactly one multipoint byte`() {
        val payload = hex("0700")

        val malformed = assertIs<ParseResult.Malformed>(MultipointParser.parse(payload))
        assertEquals("Multipoint payload must contain exactly 1 byte", malformed.reason)
        assertContentEquals(payload, malformed.payload)
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
