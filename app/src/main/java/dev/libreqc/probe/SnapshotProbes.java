package dev.libreqc.probe;

import dev.libreqc.bmap.BmapAddress;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SnapshotProbes {
    private static final int DEVICE_IDENTIFIER_SIZE = 6;

    public static final List<ReadProbe> BASE = List.of(
            probe("product.bmap_version", 0, 1),
            probe("product.firmware", 0, 5),
            probe("product.original_name", 0, 15),
            probe("settings.info", 1, 0),
            probe("settings.name", 1, 2),
            probe("settings.voice_prompts", 1, 3),
            probe("settings.cnc", 1, 5),
            probe("settings.anr", 1, 6),
            probe("settings.eq", 1, 7),
            probe("settings.shortcut", 1, 9),
            probe("settings.multipoint", 1, 10),
            probe("status.battery", 2, 2),
            probe("device_management.info", 4, 0),
            probe("device_management.list", 4, 4),
            probe("device_management.routing", 4, 12),
            probe("audio_modes.info", 31, 0),
            probe("audio_modes.capabilities", 31, 2),
            probe("audio_modes.current", 31, 3),
            probe("audio_modes.default", 31, 4),
            probe("audio_modes.persistence", 31, 5),
            probe("audio_modes.config.0", 31, 6, new byte[]{0}),
            probe("audio_modes.config.1", 31, 6, new byte[]{1}),
            probe("audio_modes.config.2", 31, 6, new byte[]{2}),
            probe("audio_modes.config.3", 31, 6, new byte[]{3}),
            probe("audio_modes.user_indices", 31, 7),
            probe("audio_modes.favorites", 31, 8),
            probe("audio_modes.settings", 31, 10),
            probe("audio_modes.names_supported", 31, 11)
    );

    private SnapshotProbes() {
    }

    public static List<ReadProbe> deviceDetails(byte[] listPayload) {
        if (listPayload.length == 0) {
            return List.of();
        }
        int identifierBytes = listPayload.length - 1;
        if (identifierBytes % DEVICE_IDENTIFIER_SIZE != 0) {
            return List.of();
        }
        int count = identifierBytes / DEVICE_IDENTIFIER_SIZE;

        List<ReadProbe> probes = new ArrayList<>(count * 2);
        for (int index = 0; index < count; index++) {
            int start = 1 + index * DEVICE_IDENTIFIER_SIZE;
            byte[] identifier = Arrays.copyOfRange(
                    listPayload, start, start + DEVICE_IDENTIFIER_SIZE);
            probes.add(new ReadProbe(
                    "device_management.info." + index,
                    new BmapAddress(4, 5),
                    identifier,
                    true));
            probes.add(new ReadProbe(
                    "device_management.extended_info." + index,
                    new BmapAddress(4, 6),
                    identifier));
        }
        return List.copyOf(probes);
    }

    private static ReadProbe probe(String name, int block, int function) {
        return new ReadProbe(name, new BmapAddress(block, function));
    }

    private static ReadProbe probe(
            String name, int block, int function, byte[] payload) {
        return new ReadProbe(name, new BmapAddress(block, function), payload);
    }
}
