package dev.libreqc.prince

sealed interface ParseResult<out T> {
    data class Success<T>(val value: T) : ParseResult<T>

    class Malformed(
        val reason: String,
        payload: ByteArray,
    ) : ParseResult<Nothing> {
        val payload: ByteArray = payload.copyOf()
    }
}

sealed interface EqBand {
    data object Bass : EqBand
    data object Mid : EqBand
    data object Treble : EqBand
    data class Unknown(val rangeId: Int) : EqBand
}

data class EqRange(
    val band: EqBand,
    val minimum: Int,
    val maximum: Int,
    val current: Int,
)

data class EqState(
    val ranges: List<EqRange>,
)

object EqParser {
    private const val RANGE_SIZE = 4

    fun parse(payload: ByteArray): ParseResult<EqState> {
        if (payload.size % RANGE_SIZE != 0) {
            return ParseResult.Malformed(
                reason = "EQ payload length must be a multiple of 4",
                payload = payload,
            )
        }

        val ranges = payload.asList().chunked(RANGE_SIZE).map { entry ->
            EqRange(
                band = band(entry[3].toInt() and 0xff),
                minimum = entry[0].toInt(),
                maximum = entry[1].toInt(),
                current = entry[2].toInt(),
            )
        }
        return ParseResult.Success(EqState(ranges))
    }

    private fun band(rangeId: Int): EqBand =
        when (rangeId) {
            0 -> EqBand.Bass
            1 -> EqBand.Mid
            2 -> EqBand.Treble
            else -> EqBand.Unknown(rangeId)
        }
}
