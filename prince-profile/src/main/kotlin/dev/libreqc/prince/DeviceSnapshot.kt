package dev.libreqc.prince

import dev.libreqc.bmap.BmapError
import dev.libreqc.bmap.BmapFrame
import dev.libreqc.bmap.BmapOperator

sealed interface FeatureResult<out T> {
    data class Available<T>(val value: T) : FeatureResult<T>
    data class Unsupported(val error: BmapError) : FeatureResult<Nothing>
    data class Malformed(val reason: String) : FeatureResult<Nothing>
    data object NotVerified : FeatureResult<Nothing>
}

data class RememberedSource(
    val identifier: DeviceIdentifier,
    val info: DeviceInfo?,
    val profiles: DeviceProfiles?,
)

data class ModesSnapshot(
    val capabilities: FeatureResult<ModeCapabilities>,
    val current: FeatureResult<ModeIndex>,
    val default: FeatureResult<ModeIndex>,
    val persistence: FeatureResult<ModePersistence>,
    val configs: FeatureResult<List<ModeConfig>>,
    val favorites: FeatureResult<ModeFavorites>,
)

data class DeviceSnapshot(
    val battery: FeatureResult<BatteryState>,
    val eq: FeatureResult<EqState>,
    val shortcut: FeatureResult<ShortcutState>,
    val multipoint: FeatureResult<MultipointState>,
    val sources: FeatureResult<List<RememberedSource>>,
    val routing: FeatureResult<Unit>,
    val modes: ModesSnapshot,
)

data class PrinceSnapshotFrames(
    val battery: BmapFrame? = null,
    val eq: BmapFrame? = null,
    val shortcut: BmapFrame? = null,
    val multipoint: BmapFrame? = null,
    val deviceList: BmapFrame? = null,
    val deviceInfos: List<BmapFrame> = emptyList(),
    val deviceProfiles: List<BmapFrame> = emptyList(),
    val routing: BmapFrame? = null,
    val modeCapabilities: BmapFrame? = null,
    val currentMode: BmapFrame? = null,
    val defaultMode: BmapFrame? = null,
    val modePersistence: BmapFrame? = null,
    val modeConfigs: List<BmapFrame> = emptyList(),
    val modeFavorites: BmapFrame? = null,
)

object DeviceSnapshotParser {
    fun parse(frames: PrinceSnapshotFrames): DeviceSnapshot =
        DeviceSnapshot(
            battery = parseFrame(frames.battery, BatteryParser::parse),
            eq = parseFrame(frames.eq, EqParser::parse),
            shortcut = parseFrame(frames.shortcut, ShortcutParser::parse),
            multipoint = parseFrame(frames.multipoint, MultipointParser::parse),
            sources = parseSources(frames),
            routing = parseRouting(frames.routing),
            modes = ModesSnapshot(
                capabilities = parseFrame(
                    frames.modeCapabilities,
                    ModeCapabilitiesParser::parse,
                ),
                current = parseFrame(frames.currentMode, ModeIndexParser::parse),
                default = parseFrame(frames.defaultMode, ModeIndexParser::parse),
                persistence = parseFrame(
                    frames.modePersistence,
                    ModePersistenceParser::parse,
                ),
                configs = parseModeConfigs(frames.modeConfigs),
                favorites = parseFrame(
                    frames.modeFavorites,
                    ModeFavoritesParser::parse,
                ),
            ),
        )

    private fun parseSources(
        frames: PrinceSnapshotFrames,
    ): FeatureResult<List<RememberedSource>> {
        val list = parseFrame(frames.deviceList, DeviceListParser::parse)
        if (list !is FeatureResult.Available) {
            return list.cast()
        }
        val infos = frames.deviceInfos.mapNotNull { frame ->
            (parseFrame(frame, DeviceInfoParser::parse) as? FeatureResult.Available)?.value
        }.associateBy(DeviceInfo::identifier)
        val profiles = frames.deviceProfiles.mapNotNull { frame ->
            (parseFrame(frame, DeviceProfilesParser::parse) as? FeatureResult.Available)?.value
        }.associateBy(DeviceProfiles::identifier)
        return FeatureResult.Available(
            list.value.identifiers.map { identifier ->
                RememberedSource(
                    identifier = identifier,
                    info = infos[identifier],
                    profiles = profiles[identifier],
                )
            },
        )
    }

    private fun parseRouting(frame: BmapFrame?): FeatureResult<Unit> {
        if (frame == null) {
            return FeatureResult.NotVerified
        }
        if (frame.operator == BmapOperator.Error) {
            return FeatureResult.Unsupported(frame.error ?: BmapError.Unrecognized)
        }
        return FeatureResult.Malformed("Routing layout is not verified")
    }

    private fun parseModeConfigs(
        frames: List<BmapFrame>,
    ): FeatureResult<List<ModeConfig>> {
        if (frames.isEmpty()) {
            return FeatureResult.NotVerified
        }
        val configs = mutableListOf<ModeConfig>()
        for (frame in frames) {
            when (val result = parseFrame(frame, ModeConfigParser::parse)) {
                is FeatureResult.Available -> configs += result.value
                is FeatureResult.Unsupported -> return result
                is FeatureResult.Malformed -> return result
                FeatureResult.NotVerified -> return FeatureResult.NotVerified
            }
        }
        return FeatureResult.Available(configs)
    }

    private fun <T> parseFrame(
        frame: BmapFrame?,
        parser: (ByteArray) -> ParseResult<T>,
    ): FeatureResult<T> {
        if (frame == null) {
            return FeatureResult.NotVerified
        }
        if (frame.operator == BmapOperator.Error) {
            return FeatureResult.Unsupported(frame.error ?: BmapError.Unrecognized)
        }
        if (frame.operator != BmapOperator.Status) {
            return FeatureResult.Malformed(
                "Expected STATUS or ERROR, got ${frame.operator}",
            )
        }
        return when (val parsed = parser(frame.payload)) {
            is ParseResult.Success -> FeatureResult.Available(parsed.value)
            is ParseResult.Malformed -> FeatureResult.Malformed(parsed.reason)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> FeatureResult<*>.cast(): FeatureResult<T> = this as FeatureResult<T>
}
