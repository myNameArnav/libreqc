package dev.libreqc.probe

import dev.libreqc.bmap.BmapFrame
import dev.libreqc.prince.PrinceSnapshotFrames

class SnapshotFrameCollector {
    private val frames = linkedMapOf<String, BmapFrame>()

    fun accept(name: String, frame: BmapFrame) {
        frames[name] = frame
    }

    fun frames(): PrinceSnapshotFrames =
        PrinceSnapshotFrames(
            eq = frames["settings.eq"],
            shortcut = frames["settings.shortcut"],
            multipoint = frames["settings.multipoint"],
            deviceList = frames["device_management.list"],
            deviceInfos = matching("device_management.info."),
            deviceProfiles = matching("device_management.extended_info."),
            routing = frames["device_management.routing"],
            modeCapabilities = frames["audio_modes.capabilities"],
            currentMode = frames["audio_modes.current"],
            defaultMode = frames["audio_modes.default"],
            modePersistence = frames["audio_modes.persistence"],
            modeConfigs = matching("audio_modes.config."),
            modeFavorites = frames["audio_modes.favorites"],
        )

    private fun matching(prefix: String): List<BmapFrame> =
        frames.entries
            .filter { (name, _) -> name.startsWith(prefix) }
            .sortedBy { (name, _) -> name.substringAfterLast('.').toIntOrNull() }
            .map(Map.Entry<String, BmapFrame>::value)
}
