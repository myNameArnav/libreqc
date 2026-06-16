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

    @Test
    fun `encodes captured shortcut set-get commands`() {
        assertContentEquals(
            hex("01090203800903"),
            PrinceCommands.setShortcut(ShortcutAction.BatteryLevel),
        )
        assertContentEquals(
            hex("0109020380090e"),
            PrinceCommands.setShortcut(ShortcutAction.Disabled),
        )
        assertContentEquals(
            hex("01090203800910"),
            PrinceCommands.setShortcut(ShortcutAction.Spotify),
        )
    }

    @Test
    fun `rejects unsupported shortcut actions and identifiers outside one byte`() {
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setShortcut(ShortcutAction.Unknown(7))
        }
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setShortcut(ShortcutAction.BatteryLevel, buttonId = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setShortcut(ShortcutAction.BatteryLevel, eventType = 256)
        }
    }

    @Test
    fun `encodes recovered multipoint set-get commands`() {
        assertContentEquals(
            hex("010a020101"),
            PrinceCommands.setMultipoint(enabled = true),
        )
        assertContentEquals(
            hex("010a020100"),
            PrinceCommands.setMultipoint(enabled = false),
        )
    }

    @Test
    fun `encodes recovered source connect and disconnect commands`() {
        val source = DeviceIdentifier(hex("112233445566"))

        assertContentEquals(
            hex("0401050700112233445566"),
            PrinceCommands.connectSource(source),
        )
        assertContentEquals(
            hex("04020506112233445566"),
            PrinceCommands.disconnectSource(source),
        )
    }

    @Test
    fun `encodes prince mode config set-get from current config envelope`() {
        val config = ModeConfig(
            index = 3,
            name = "",
            cncLevel = 5,
            headerBytes = hex("0000010100"),
            trailingBytes = hex("000000090500000000"),
        )

        assertContentEquals(
            hex("1f06022f030000010100") + ByteArray(32) + hex("000000090500000000"),
            PrinceCommands.setModeConfig(config),
        )
    }

    @Test
    fun `updates editable prince mode config fields while preserving envelope`() {
        val config = ModeConfig(
            index = 3,
            name = "",
            cncLevel = 5,
            headerBytes = hex("0000010100"),
            trailingBytes = hex("000000090500000000"),
        )
        val modeName = ByteArray(32).also { field ->
            "Run".encodeToByteArray().copyInto(field)
        }

        assertContentEquals(
            hex("1f06022f030014010100") + modeName + hex("000000090700000000"),
            PrinceCommands.setModeConfig(
                config = config,
                prompt = AudioModePrompt.Run,
                name = "Run",
                cncLevel = 7,
            ),
        )
    }

    @Test
    fun `encodes recovered generic mode config set-get command`() {
        assertContentEquals(
            hex("1f060225030000") + ByteArray(32) + hex("0900"),
            PrinceCommands.setModeConfigBasic(
                index = 3,
                prompt = AudioModePrompt.None,
                name = "",
                cncLevel = 9,
                autoCncEnabled = false,
            ),
        )
    }

    @Test
    fun `encodes recovered mode config with optional fields`() {
        val modeName = ByteArray(32).also { field ->
            "Run".encodeToByteArray().copyInto(field)
        }

        assertContentEquals(
            hex("1f060228020014") + modeName + hex("0501010101"),
            PrinceCommands.setModeConfigBasic(
                index = 2,
                prompt = AudioModePrompt.Run,
                name = "Run",
                cncLevel = 5,
                autoCncEnabled = true,
                spatialAudio = SpatialAudioMode.FixedToRoom,
                windBlockEnabled = true,
                ancToggleEnabled = true,
            ),
        )
    }

    @Test
    fun `encodes recovered mode favorites bitset command`() {
        assertContentEquals(
            hex("1f0802020407"),
            PrinceCommands.setModeFavorites(modeCount = 4, favoriteIndices = listOf(0, 1, 2)),
        )
        assertContentEquals(
            hex("1f080203090101"),
            PrinceCommands.setModeFavorites(modeCount = 9, favoriteIndices = listOf(0, 8)),
        )
    }

    @Test
    fun `encodes recovered mode user indices command`() {
        assertContentEquals(
            hex("1f07020400010203"),
            PrinceCommands.setModeUserIndices(listOf(0, 1, 2, 3)),
        )
    }

    @Test
    fun `encodes recovered default mode and persistence commands`() {
        assertContentEquals(
            hex("1f04020100"),
            PrinceCommands.setDefaultMode(index = 0),
        )
        assertContentEquals(
            hex("1f05020101"),
            PrinceCommands.setModePersistence(enabled = true),
        )
        assertContentEquals(
            hex("1f05020100"),
            PrinceCommands.setModePersistence(enabled = false),
        )
    }

    @Test
    fun `rejects invalid mode edit inputs`() {
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setModeConfigBasic(
                index = 256,
                prompt = AudioModePrompt.None,
                name = "",
                cncLevel = 9,
                autoCncEnabled = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setModeConfigBasic(
                index = 3,
                prompt = AudioModePrompt.None,
                name = "a".repeat(32),
                cncLevel = 9,
                autoCncEnabled = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setModeFavorites(modeCount = 4, favoriteIndices = listOf(4))
        }
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setModeUserIndices(listOf(256))
        }
        assertFailsWith<IllegalArgumentException> {
            PrinceCommands.setDefaultMode(index = -1)
        }
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
