package dev.libreqc.prince

import dev.libreqc.bmap.BmapAddress
import dev.libreqc.bmap.BmapOperator
import dev.libreqc.bmap.BmapPackets

object PrinceCommands {
    private val currentModeAddress = BmapAddress(31, 3)
    private val defaultModeAddress = BmapAddress(31, 4)
    private val modePersistenceAddress = BmapAddress(31, 5)
    private val modeConfigAddress = BmapAddress(31, 6)
    private val modeUserIndicesAddress = BmapAddress(31, 7)
    private val modeFavoritesAddress = BmapAddress(31, 8)
    private val eqAddress = BmapAddress(1, 7)
    private val shortcutAddress = BmapAddress(1, 9)
    private val multipointAddress = BmapAddress(1, 10)
    private val sourceConnectAddress = BmapAddress(4, 1)
    private val sourceDisconnectAddress = BmapAddress(4, 2)

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

    fun setMultipoint(enabled: Boolean): ByteArray =
        BmapPackets.encode(
            multipointAddress,
            BmapOperator.SetGet,
            byteArrayOf(if (enabled) 1 else 0),
        )

    fun connectSource(identifier: DeviceIdentifier): ByteArray =
        BmapPackets.start(
            sourceConnectAddress,
            byteArrayOf(0, *identifier.bytes),
        )

    fun disconnectSource(identifier: DeviceIdentifier): ByteArray =
        BmapPackets.start(sourceDisconnectAddress, identifier.bytes)

    fun setModeConfig(
        config: ModeConfig,
        prompt: AudioModePrompt = promptFromConfig(config),
        name: String = config.name,
        cncLevel: Int = config.cncLevel,
    ): ByteArray {
        require(cncLevel in 0..255) { "CNC level must fit in one byte" }
        require(config.headerBytes.size == MODE_CONFIG_HEADER_BYTES) {
            "Mode config header must contain $MODE_CONFIG_HEADER_BYTES bytes"
        }
        require(config.trailingBytes.size == MODE_CONFIG_TRAILING_BYTES) {
            "Mode config trailing data must contain $MODE_CONFIG_TRAILING_BYTES bytes"
        }
        val header = config.headerBytes.copyOf()
        header[0] = prompt.byte1.toByte()
        header[1] = prompt.byte2.toByte()
        val trailing = config.trailingBytes.copyOf()
        trailing[CNC_TRAILING_OFFSET] = cncLevel.toByte()

        return BmapPackets.encode(
            modeConfigAddress,
            BmapOperator.SetGet,
            byteArrayOf(config.index.toByte()) + header + encodeModeName(name) + trailing,
        )
    }

    fun setModeConfigBasic(
        index: Int,
        prompt: AudioModePrompt,
        name: String,
        cncLevel: Int,
        autoCncEnabled: Boolean,
        spatialAudio: SpatialAudioMode? = null,
        windBlockEnabled: Boolean? = null,
        ancToggleEnabled: Boolean? = null,
    ): ByteArray {
        require(index in 0..255) { "Mode index must fit in one byte" }
        require(cncLevel in 0..255) { "CNC level must fit in one byte" }

        var payload = byteArrayOf(index.toByte(), prompt.byte1.toByte(), prompt.byte2.toByte()) +
            encodeModeName(name) +
            byteArrayOf(cncLevel.toByte(), if (autoCncEnabled) 1 else 0)

        spatialAudio?.let { spatial ->
            payload += spatial.value.toByte()
            if (windBlockEnabled == null) {
                payload += 0
            }
        }
        windBlockEnabled?.let { enabled ->
            if (spatialAudio == null) {
                payload += SpatialAudioMode.Disabled.value.toByte()
            }
            payload += if (enabled) 1 else 0
        }
        ancToggleEnabled?.let { enabled ->
            payload += if (enabled) 1 else 0
        }

        return BmapPackets.encode(modeConfigAddress, BmapOperator.SetGet, payload)
    }

    fun setModeFavorites(modeCount: Int, favoriteIndices: Collection<Int>): ByteArray {
        require(modeCount in 0..255) { "Mode count must fit in one byte" }
        favoriteIndices.forEach { index ->
            require(index in 0 until modeCount) { "Favorite index must be within mode count" }
        }

        val payloadSize = 1 + ((modeCount + 7) / 8)
        val payload = ByteArray(payloadSize)
        payload[0] = modeCount.toByte()
        favoriteIndices.forEach { index ->
            val byteIndex = payloadSize - (index / 8) - 1
            payload[byteIndex] = (payload[byteIndex].toInt() or (1 shl (index % 8))).toByte()
        }

        return BmapPackets.encode(modeFavoritesAddress, BmapOperator.SetGet, payload)
    }

    fun setModeUserIndices(indices: Collection<Int>): ByteArray {
        indices.forEach { index ->
            require(index in 0..255) { "Mode index must fit in one byte" }
        }
        return BmapPackets.encode(
            modeUserIndicesAddress,
            BmapOperator.SetGet,
            indices.map { it.toByte() }.toByteArray(),
        )
    }

    fun setDefaultMode(index: Int): ByteArray {
        require(index in 0..255) { "Mode index must fit in one byte" }
        return BmapPackets.encode(defaultModeAddress, BmapOperator.SetGet, byteArrayOf(index.toByte()))
    }

    fun setModePersistence(enabled: Boolean): ByteArray =
        BmapPackets.encode(
            modePersistenceAddress,
            BmapOperator.SetGet,
            byteArrayOf(if (enabled) 1 else 0),
        )

    private fun encodeModeName(name: String): ByteArray {
        val bytes = name.encodeToByteArray()
        require(bytes.size <= MODE_NAME_MAX_BYTES) {
            "Mode name must be $MODE_NAME_MAX_BYTES UTF-8 bytes or fewer"
        }
        return ByteArray(MODE_NAME_FIELD_BYTES).also { field ->
            bytes.copyInto(field)
        }
    }

    private const val MODE_NAME_FIELD_BYTES = 32
    private const val MODE_NAME_MAX_BYTES = 31
    private const val MODE_CONFIG_HEADER_BYTES = 5
    private const val MODE_CONFIG_TRAILING_BYTES = 9
    private const val CNC_TRAILING_OFFSET = 4

    private fun promptFromConfig(config: ModeConfig): AudioModePrompt {
        val byte1 = config.headerBytes.getOrNull(0)?.toInt()?.and(0xff) ?: 0
        val byte2 = config.headerBytes.getOrNull(1)?.toInt()?.and(0xff) ?: 0
        return AudioModePrompt.entries.firstOrNull { it.byte1 == byte1 && it.byte2 == byte2 }
            ?: AudioModePrompt.None
    }
}

enum class AudioModePrompt(
    val byte1: Int,
    val byte2: Int,
    val displayName: String,
) {
    None(0, 0, "None"),
    Quiet(0, 1, "Quiet"),
    Aware(0, 2, "Aware"),
    Transparent(0, 3, "Transparent"),
    Transparency(0, 4, "Transparency"),
    Masking(0, 5, "Masking"),
    Comfort(0, 6, "Comfort"),
    Commute(0, 7, "Commute"),
    Outdoor(0, 8, "Outdoor"),
    Workout(0, 9, "Workout"),
    Home(0, 10, "Home"),
    Work(0, 11, "Work"),
    Music(0, 12, "Music"),
    Focus(0, 13, "Focus"),
    Relax(0, 14, "Relax"),
    Flight(0, 15, "Flight"),
    Airport(0, 16, "Airport"),
    Driving(0, 17, "Driving"),
    Training(0, 18, "Training"),
    Gym(0, 19, "Gym"),
    Run(0, 20, "Run"),
    Walk(0, 21, "Walk"),
    Hike(0, 22, "Hike"),
    Talk(0, 23, "Talk"),
    Call(0, 24, "Call"),
    Whisper(0, 25, "Whisper"),
    Hearing(0, 26, "Hearing"),
    Learn(0, 27, "Learn"),
    Podcast(0, 28, "Podcast"),
    Audiobook(0, 29, "Audiobook"),
    Calm(0, 30, "Calm"),
    Sleep(0, 31, "Sleep"),
    Meditate(0, 32, "Meditate"),
    Yoga(0, 33, "Yoga"),
    Immersion(0, 34, "Immersion"),
    Stereo(0, 35, "Stereo"),
    Cinema(0, 36, "Cinema"),
}

enum class SpatialAudioMode(val value: Int) {
    Disabled(0),
    FixedToRoom(1),
    FixedToHead(2),
}
