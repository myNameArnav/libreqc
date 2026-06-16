package dev.libreqc.prince

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class PrinceCommandsTest {
    @Test
    fun `encodes the captured mode selection command`() {
        assertContentEquals(
            hex("1f0305020100"),
            PrinceCommands.selectMode(index = 1, voicePrompt = false),
        )
        assertContentEquals(
            hex("1f0305020001"),
            PrinceCommands.selectMode(index = 0, voicePrompt = true),
        )
    }

    @Test
    fun `rejects mode indices outside one byte`() {
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.selectMode(index = -1, voicePrompt = false)
        }
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.selectMode(index = 256, voicePrompt = false)
        }
    }

    @Test
    fun `encodes captured EQ set-get commands`() {
        assertContentEquals(
            hex("010702020300"),
            PrinceCommands.setEq(EqBand.Bass, target = 3),
        )
        assertContentEquals(
            hex("01070202fe01"),
            PrinceCommands.setEq(EqBand.Mid, target = -2),
        )
        assertContentEquals(
            hex("010702020002"),
            PrinceCommands.setEq(EqBand.Treble, target = 0),
        )
    }

    @Test
    fun `rejects unsupported EQ bands and values outside a signed byte`() {
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setEq(EqBand.Unknown(3), target = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setEq(EqBand.Bass, target = -129)
        }
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setEq(EqBand.Bass, target = 128)
        }
    }

    @Test
    fun `encodes captured shortcut set-get commands`() {
        assertContentEquals(
            hex("01090203800903"),
            PrinceCommands.setShortcut(ShortcutAction.BatteryLevel),
        )
        assertContentEquals(
            hex("0109020380090e"),
            PrinceCommands.setShortcut(ShortcutAction.Disabled),
        )
        assertContentEquals(
            hex("01090203800910"),
            PrinceCommands.setShortcut(ShortcutAction.Spotify),
        )
    }

    @Test
    fun `rejects unsupported shortcut actions and identifiers outside one byte`() {
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setShortcut(ShortcutAction.Unknown(7))
        }
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setShortcut(ShortcutAction.BatteryLevel, buttonId = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setShortcut(ShortcutAction.BatteryLevel, eventType = 256)
        }
    }

    @Test
    fun `encodes recovered multipoint set-get commands`() {
        assertContentEquals(
            hex("010a020101"),
            PrinceCommands.setMultipoint(enabled = true),
        )
        assertContentEquals(
            hex("010a020100"),
            PrinceCommands.setMultipoint(enabled = false),
        )
    }

    @Test
    fun `encodes recovered source connect and disconnect commands`() {
        val source = DeviceIdentifier(hex("112233445566"))

        assertContentEquals(
            hex("0401050700112233445566"),
            PrinceCommands.connectSource(source),
        )
        assertContentEquals(
            hex("04020506112233445566"),
            PrinceCommands.disconnectSource(source),
        )
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
