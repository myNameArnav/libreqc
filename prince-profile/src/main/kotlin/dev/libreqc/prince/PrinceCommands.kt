package dev.libreqc.prince

import dev.libreqc.bmap.BmapAddress
import dev.libreqc.bmap.BmapOperator
import dev.libreqc.bmap.BmapPackets

object PrinceCommands {
    private val currentModeAddress = BmapAddress(31, 3)
    private val eqAddress = BmapAddress(1, 7)
    private val shortcutAddress = BmapAddress(1, 9)

    fun selectMode(index: Int, voicePrompt: Boolean): ByteArray {
        require(index in 0..255) { "Mode index must fit in one byte" }
        return BmapPackets.start(
            currentModeAddress,
            byteArrayOf(index.toByte(), if (voicePrompt) 1 else 0),
        )
    }

    fun setEq(band: EqBand, target: Int): ByteArray {
        require(target in Byte.MIN_VALUE..Byte.MAX_VALUE) {
            "EQ target must fit in one signed byte"
        }
        val rangeId = when (band) {
            EqBand.Bass -> 0
            EqBand.Mid -> 1
            EqBand.Treble -> 2
            is EqBand.Unknown -> throw IllegalArgumentException("Cannot set an unknown EQ band")
        }
        return BmapPackets.encode(
            eqAddress,
            BmapOperator.SetGet,
            byteArrayOf(target.toByte(), rangeId.toByte()),
        )
    }

    fun setShortcut(action: ShortcutAction, buttonId: Int = 0x80, eventType: Int = 9): ByteArray {
        require(buttonId in 0..255) { "Shortcut button ID must fit in one byte" }
        require(eventType in 0..255) { "Shortcut event type must fit in one byte" }
        val actionId = when (action) {
            ShortcutAction.BatteryLevel -> 3
            ShortcutAction.Disabled -> 14
            ShortcutAction.Spotify -> 16
            is ShortcutAction.Unknown -> throw IllegalArgumentException("Cannot set an unknown shortcut action")
        }
        return BmapPackets.encode(
            shortcutAddress,
            BmapOperator.SetGet,
            byteArrayOf(buttonId.toByte(), eventType.toByte(), actionId.toByte()),
        )
    }
}
