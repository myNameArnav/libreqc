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

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
