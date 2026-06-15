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

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
