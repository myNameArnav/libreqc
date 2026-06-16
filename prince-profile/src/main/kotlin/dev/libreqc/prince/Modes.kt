package dev.libreqc.prince

import java.nio.charset.StandardCharsets

class ModeConfig(
    val index: Int,
    val name: String,
    val cncLevel: Int,
    headerBytes: ByteArray,
    trailingBytes: ByteArray,
) {
    val headerBytes: ByteArray = headerBytes.copyOf()
    val trailingBytes: ByteArray = trailingBytes.copyOf()
}

object ModeConfigParser {
    private const val PAYLOAD_SIZE = 47
    private const val NAME_START = 6
    private const val NAME_END = 38
    private const val CNC_OFFSET = 42

    fun parse(payload: ByteArray): ParseResult<ModeConfig> {
        if (payload.size != PAYLOAD_SIZE) {
            return ParseResult.Malformed(
                reason = "Mode config payload must contain exactly 47 bytes",
                payload = payload,
            )
        }
        val nameBytes = payload.copyOfRange(NAME_START, NAME_END)
        val nameLength = nameBytes.indexOf(0).let { if (it < 0) nameBytes.size else it }
        return ParseResult.Success(
            ModeConfig(
                index = payload[0].toInt() and 0xff,
                name = String(nameBytes, 0, nameLength, StandardCharsets.UTF_8),
                cncLevel = payload[CNC_OFFSET].toInt(),
                headerBytes = payload.copyOfRange(1, NAME_START),
                trailingBytes = payload.copyOfRange(NAME_END, PAYLOAD_SIZE),
            ),
        )
    }
}

data class ModeIndex(val value: Int)

object ModeIndexParser {
    fun parse(payload: ByteArray): ParseResult<ModeIndex> =
        parseSingleByte("Mode index", payload) { ModeIndex(it.toByte().toInt()) }
}

data class ModePersistence(
    val enabled: Boolean,
    val rawValue: Int,
)

object ModePersistenceParser {
    fun parse(payload: ByteArray): ParseResult<ModePersistence> =
        parseSingleByte("Mode persistence", payload) {
            ModePersistence(enabled = it == 1, rawValue = it)
        }
}

data class ModeFavorites(
    val modeCount: Int,
    val indices: List<Int>,
)

object ModeFavoritesParser {
    fun parse(payload: ByteArray): ParseResult<ModeFavorites> {
        if (payload.size < 2) {
            return ParseResult.Malformed(
                reason = "Mode favorites payload must contain a count and bitset",
                payload = payload,
            )
        }
        val modeCount = payload[0].toInt() and 0xff
        val indices = buildList {
            for (index in 0 until modeCount) {
                val byteIndex = 1 + index / 8
                if (byteIndex < payload.size &&
                    payload[byteIndex].toInt() and (1 shl (index % 8)) != 0
                ) {
                    add(index)
                }
            }
        }
        return ParseResult.Success(ModeFavorites(modeCount, indices))
    }
}

class ModeCapabilities(
    val boseModeCount: Int,
    val userModeCount: Int,
    featureBytes: ByteArray,
) {
    val featureBytes: ByteArray = featureBytes.copyOf()
}

object ModeCapabilitiesParser {
    fun parse(payload: ByteArray): ParseResult<ModeCapabilities> {
        if (payload.size != 6) {
            return ParseResult.Malformed(
                reason = "Mode capabilities payload must contain exactly 6 bytes",
                payload = payload,
            )
        }
        return ParseResult.Success(
            ModeCapabilities(
                boseModeCount = payload[0].toInt() and 0xff,
                userModeCount = payload[1].toInt() and 0xff,
                featureBytes = payload.copyOfRange(2, payload.size),
            ),
        )
    }
}

private inline fun <T> parseSingleByte(
    name: String,
    payload: ByteArray,
    value: (Int) -> T,
): ParseResult<T> {
    if (payload.size != 1) {
        return ParseResult.Malformed(
            reason = "$name payload must contain exactly 1 byte",
            payload = payload,
        )
    }
    return ParseResult.Success(value(payload.single().toInt() and 0xff))
}
