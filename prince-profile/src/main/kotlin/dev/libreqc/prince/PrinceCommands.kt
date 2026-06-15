package dev.libreqc.prince

import dev.libreqc.bmap.BmapAddress
import dev.libreqc.bmap.BmapPackets

object PrinceCommands {
    private val currentModeAddress = BmapAddress(31, 3)

    fun selectMode(index: Int, voicePrompt: Boolean): ByteArray {
        require(index in 0..255) { "Mode index must fit in one byte" }
        return BmapPackets.start(
            currentModeAddress,
            byteArrayOf(index.toByte(), if (voicePrompt) 1 else 0),
        )
    }
}
