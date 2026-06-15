package dev.libreqc.prince

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EqParserTest {
    @Test
    fun `parses the captured prince EQ ranges`() {
        val result = EqParser.parse(hex("f60a0200f60a0401f60a0002"))

        val eq = assertIs<ParseResult.Success<EqState>>(result).value
        assertEquals(
            listOf(
                EqRange(EqBand.Bass, -10, 10, 2),
                EqRange(EqBand.Mid, -10, 10, 4),
                EqRange(EqBand.Treble, -10, 10, 0),
            ),
            eq.ranges,
        )
    }

    @Test
    fun `preserves an unknown range identifier`() {
        val result = EqParser.parse(hex("fb05037f"))

        val range = assertIs<ParseResult.Success<EqState>>(result).value.ranges.single()
        assertEquals(EqBand.Unknown(0x7f), range.band)
        assertEquals(-5, range.minimum)
        assertEquals(5, range.maximum)
        assertEquals(3, range.current)
    }

    @Test
    fun `rejects an incomplete range without discarding its bytes`() {
        val payload = hex("f60a0200f60a")

        val malformed = assertIs<ParseResult.Malformed>(EqParser.parse(payload))
        assertEquals("EQ payload length must be a multiple of 4", malformed.reason)
        assertContentEquals(payload, malformed.payload)
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
