package dev.libreqc.bmap

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BmapCodecTest {
    @Test
    fun `encodes battery get request`() {
        val packet = BmapPackets.get(BmapAddress(2, 2))

        assertContentEquals(hex("02020100"), packet)
    }

    @Test
    fun `encodes setget and rejects payloads larger than the length field`() {
        val packet = BmapPackets.encode(
            address = BmapAddress(1, 7),
            operator = BmapOperator.SetGet,
            payload = hex("0501"),
        )

        assertContentEquals(hex("010702020501"), packet)
        assertFailsWith<IllegalArgumentException> {
            BmapPackets.encode(
                address = BmapAddress(1, 7),
                operator = BmapOperator.SetGet,
                payload = ByteArray(256),
            )
        }
    }

    @Test
    fun `represents the captured aware mode switch exchange`() {
        val request = BmapPackets.start(BmapAddress(31, 3), hex("0100"))
        val decoder = BmapStreamDecoder()

        val responses = decoder.feed(hex("1f0307001f03030101"))

        assertContentEquals(hex("1f0305020100"), request)
        assertEquals(
            listOf(BmapOperator.Processing, BmapOperator.Status),
            responses.map { it.operator },
        )
        assertContentEquals(hex("01"), responses.last().payload)
    }

    @Test
    fun `decodes captured firmware status frame`() {
        val decoder = BmapStreamDecoder()

        val frames = decoder.feed(
            hex("00050310312e302e362d38302b66356632313962"),
        )

        assertEquals(1, frames.size)
        assertEquals(BmapAddress(0, 5), frames.single().address)
        assertEquals(BmapOperator.Status, frames.single().operator)
        assertContentEquals(
            hex("312e302e362d38302b66356632313962"),
            frames.single().payload,
        )
    }

    @Test
    fun `decodes captured stage two settings frames`() {
        val frames = BmapStreamDecoder().feed(
            hex(
                "0107030cf60a0200f60a0401f60a0002" +
                    "0109030b8009100001400800000080" +
                    "010a030107",
            ),
        )

        assertEquals(
            listOf(BmapAddress(1, 7), BmapAddress(1, 9), BmapAddress(1, 10)),
            frames.map { it.address },
        )
        assertContentEquals(hex("f60a0200f60a0401f60a0002"), frames[0].payload)
        assertContentEquals(hex("8009100001400800000080"), frames[1].payload)
        assertContentEquals(hex("07"), frames[2].payload)
    }

    @Test
    fun `decodes redacted device list and stage two errors`() {
        val frames = BmapStreamDecoder().feed(
            hex(
                "0404031902111111111111222222222222333333333333444444444444" +
                    "0405040101" +
                    "040c040104",
            ),
        )

        assertEquals(25, frames[0].payload.size)
        assertContentEquals(
            hex("02111111111111222222222222333333333333444444444444"),
            frames[0].payload,
        )
        assertEquals(BmapError.InvalidLength, frames[1].error)
        assertEquals(BmapError.FunctionNotSupported, frames[2].error)
    }

    @Test
    fun `decodes redacted device info and extended info frames`() {
        val frames = BmapStreamDecoder().feed(
            hex(
                "040503121111111111110001034465766963652d3031" +
                    "040603081111111111110700" +
                    "040503122222222222220302034465766963652d3032" +
                    "04060308222222222222070f",
            ),
        )

        assertEquals(
            listOf(
                BmapAddress(4, 5),
                BmapAddress(4, 6),
                BmapAddress(4, 5),
                BmapAddress(4, 6),
            ),
            frames.map { it.address },
        )
        assertContentEquals(
            hex("1111111111110001034465766963652d3031"),
            frames[0].payload,
        )
        assertContentEquals(hex("222222222222070f"), frames[3].payload)
    }

    @Test
    fun `decodes default mode and persistence frames`() {
        val frames = BmapStreamDecoder().feed(hex("1f040301001f05030101"))

        assertContentEquals(hex("00"), frames[0].payload)
        assertContentEquals(hex("01"), frames[1].payload)
    }

    @Test
    fun `buffers a frame split across reads`() {
        val decoder = BmapStreamDecoder()
        val capturedFrame = hex(
            "1f06032f00000100000151756965740000000000000000000000000000000000" +
                "00000000000000000000000000000000000000",
        )

        assertTrue(decoder.feed(capturedFrame.copyOfRange(0, 7)).isEmpty())
        val frames = decoder.feed(capturedFrame.copyOfRange(7, capturedFrame.size))

        assertEquals(1, frames.size)
        assertEquals(BmapAddress(31, 6), frames.single().address)
        assertEquals(47, frames.single().payload.size)
        decoder.finish()
    }

    @Test
    fun `reports a truncated frame when the stream ends`() {
        val decoder = BmapStreamDecoder()
        decoder.feed(hex("0202030450ff"))

        val error = assertFailsWith<BmapDecodingException> {
            decoder.finish()
        }

        assertEquals(8, error.expectedFrameLength)
        assertEquals(6, error.actualByteCount)
    }

    @Test
    fun `can discard a truncated frame and continue decoding`() {
        val decoder = BmapStreamDecoder()
        decoder.feed(hex("0202030450ff"))

        val error = decoder.discardPending()
        val frames = decoder.feed(hex("0107030100"))

        assertEquals(8, error?.expectedFrameLength)
        assertEquals(BmapAddress(1, 7), frames.single().address)
        decoder.finish()
    }

    @Test
    fun `decodes known errors and preserves unknown operators`() {
        val decoder = BmapStreamDecoder()

        val frames = decoder.feed(hex("0106040104ffee0f00"))

        assertEquals(BmapError.FunctionNotSupported, frames[0].error)
        assertEquals(BmapOperator.Unknown, frames[1].operator)
        assertEquals(15, frames[1].operatorCode)
        assertEquals(BmapAddress(255, 238), frames[1].address)
    }

    @Test
    fun `correlates mode start processing and result responses`() {
        val request = BmapRequest(
            address = BmapAddress(31, 3),
            operator = BmapOperator.Start,
            payload = hex("0100"),
        )
        val responses = BmapStreamDecoder().feed(
            hex("1f0307001f03060101"),
        )

        assertTrue(responses.all(request::accepts))
        assertContentEquals(hex("1f0305020100"), request.toByteArray())
        assertEquals(
            "[31.3] RESULT payload=01",
            BmapDiagnostics.describe(responses.last()),
        )
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
