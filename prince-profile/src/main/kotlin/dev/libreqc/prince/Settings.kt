package dev.libreqc.prince

sealed interface ShortcutAction {
    data object BatteryLevel : ShortcutAction
    data object Disabled : ShortcutAction
    data object Spotify : ShortcutAction
    data class Unknown(val id: Int) : ShortcutAction
}

data class ShortcutState(
    val buttonId: Int,
    val eventType: Int,
    val configuredAction: ShortcutAction,
    val supportedActions: Set<ShortcutAction>,
    val unavailableActions: Set<ShortcutAction>,
) {
    val enabled: Boolean
        get() = configuredAction != ShortcutAction.Disabled
}

object ShortcutParser {
    private const val PAYLOAD_SIZE = 11

    fun parse(payload: ByteArray): ParseResult<ShortcutState> {
        if (payload.size != PAYLOAD_SIZE) {
            return ParseResult.Malformed(
                reason = "Shortcut payload must contain exactly 11 bytes",
                payload = payload,
            )
        }
        return ParseResult.Success(
            ShortcutState(
                buttonId = payload[0].unsigned(),
                eventType = payload[1].unsigned(),
                configuredAction = action(payload[2].unsigned()),
                supportedActions = actions(payload, 3),
                unavailableActions = actions(payload, 7),
            ),
        )
    }

    private fun actions(payload: ByteArray, offset: Int): Set<ShortcutAction> {
        val mask =
            (payload[offset].unsigned() shl 24) or
                (payload[offset + 1].unsigned() shl 16) or
                (payload[offset + 2].unsigned() shl 8) or
                payload[offset + 3].unsigned()
        return (0 until Int.SIZE_BITS)
            .filter { id -> mask and (1 shl id) != 0 }
            .mapTo(linkedSetOf(), ::action)
    }

    private fun action(id: Int): ShortcutAction =
        when (id) {
            3 -> ShortcutAction.BatteryLevel
            14 -> ShortcutAction.Disabled
            16 -> ShortcutAction.Spotify
            else -> ShortcutAction.Unknown(id)
        }
}

data class MultipointState(
    val enabled: Boolean,
    val supported: Boolean,
    val canDisable: Boolean,
    val unknownFlags: Int,
)

object MultipointParser {
    private const val KNOWN_FLAGS = 0x07

    fun parse(payload: ByteArray): ParseResult<MultipointState> {
        if (payload.size != 1) {
            return ParseResult.Malformed(
                reason = "Multipoint payload must contain exactly 1 byte",
                payload = payload,
            )
        }
        val flags = payload.single().unsigned()
        return ParseResult.Success(
            MultipointState(
                enabled = flags and 0x01 != 0,
                supported = flags and 0x02 != 0,
                canDisable = flags and 0x04 != 0,
                unknownFlags = flags and KNOWN_FLAGS.inv() and 0xff,
            ),
        )
    }
}

data class BatteryState(val percent: Int)

object BatteryParser {
    fun parse(payload: ByteArray): ParseResult<BatteryState> {
        if (payload.size != 1) {
            return ParseResult.Malformed(
                reason = "Battery payload must contain exactly 1 byte",
                payload = payload,
            )
        }
        return ParseResult.Success(BatteryState(payload.single().unsigned()))
    }
}

private fun Byte.unsigned(): Int = toInt() and 0xff
