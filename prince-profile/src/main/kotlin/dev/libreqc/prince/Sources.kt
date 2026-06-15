package dev.libreqc.prince

import java.nio.charset.StandardCharsets
import java.util.Arrays

class DeviceIdentifier(bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == SIZE) { "Device identifier must contain exactly 6 bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is DeviceIdentifier && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = Arrays.hashCode(bytes)

    companion object {
        const val SIZE = 6
    }
}

data class DeviceList(
    val header: Int,
    val identifiers: List<DeviceIdentifier>,
)

object DeviceListParser {
    fun parse(payload: ByteArray): ParseResult<DeviceList> {
        if (payload.isEmpty() || (payload.size - 1) % DeviceIdentifier.SIZE != 0) {
            return ParseResult.Malformed(
                reason = "Device list must contain a header and 6-byte identifiers",
                payload = payload,
            )
        }
        return ParseResult.Success(
            DeviceList(
                header = payload[0].unsigned(),
                identifiers = (1 until payload.size step DeviceIdentifier.SIZE).map { offset ->
                    DeviceIdentifier(payload.copyOfRange(offset, offset + DeviceIdentifier.SIZE))
                },
            ),
        )
    }
}

sealed interface DeviceCategory {
    data class BluetoothClass(
        val major: Int,
        val minor: Int,
    ) : DeviceCategory

    data class BoseProduct(
        val productId: Int,
        val variant: Int?,
    ) : DeviceCategory
}

data class DeviceInfo(
    val identifier: DeviceIdentifier,
    val flags: Int,
    val name: String,
    val category: DeviceCategory,
) {
    val connected: Boolean
        get() = flags and 0x01 != 0
    val localDevice: Boolean
        get() = flags and 0x02 != 0
    val boseProduct: Boolean
        get() = flags and 0x04 != 0
    val component: Boolean
        get() = flags and 0x08 != 0
}

object DeviceInfoParser {
    private const val COMMON_SIZE = 9
    private const val BOSE_PRODUCT_FLAG = 0x04

    fun parse(payload: ByteArray): ParseResult<DeviceInfo> {
        if (payload.size < COMMON_SIZE) {
            return ParseResult.Malformed(
                reason = "Device info payload is shorter than its fixed fields",
                payload = payload,
            )
        }
        val flags = payload[6].unsigned()
        val boseProduct = flags and BOSE_PRODUCT_FLAG != 0
        val nameOffset = if (boseProduct) 10 else 9
        if (payload.size < nameOffset) {
            return ParseResult.Malformed(
                reason = "Bose device info payload is missing its variant byte",
                payload = payload,
            )
        }
        val category =
            if (boseProduct) {
                DeviceCategory.BoseProduct(
                    productId = (payload[7].unsigned() shl 8) or payload[8].unsigned(),
                    variant = payload[9].unsigned(),
                )
            } else {
                DeviceCategory.BluetoothClass(
                    major = payload[7].unsigned(),
                    minor = payload[8].unsigned(),
                )
            }
        return ParseResult.Success(
            DeviceInfo(
                identifier = DeviceIdentifier(payload.copyOfRange(0, DeviceIdentifier.SIZE)),
                flags = flags,
                name = String(
                    payload,
                    nameOffset,
                    payload.size - nameOffset,
                    StandardCharsets.UTF_8,
                ),
                category = category,
            ),
        )
    }
}

enum class BluetoothProfile {
    A2dp,
    Hfp,
    Avrcp,
    Spp,
    Iap,
}

class DeviceProfiles(
    val identifier: DeviceIdentifier,
    val paired: Set<BluetoothProfile>,
    val connected: Set<BluetoothProfile>,
    leAudioFlags: ByteArray,
) {
    val leAudioFlags: ByteArray = leAudioFlags.copyOf()
}

object DeviceProfilesParser {
    private const val FIXED_SIZE = 8

    fun parse(payload: ByteArray): ParseResult<DeviceProfiles> {
        if (payload.size < FIXED_SIZE) {
            return ParseResult.Malformed(
                reason = "Device profiles payload must contain at least 8 bytes",
                payload = payload,
            )
        }
        return ParseResult.Success(
            DeviceProfiles(
                identifier = DeviceIdentifier(payload.copyOfRange(0, DeviceIdentifier.SIZE)),
                paired = profiles(payload[6].unsigned()),
                connected = profiles(payload[7].unsigned()),
                leAudioFlags = payload.copyOfRange(FIXED_SIZE, payload.size),
            ),
        )
    }

    private fun profiles(mask: Int): Set<BluetoothProfile> =
        BluetoothProfile.entries
            .filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            .toSet()
}

private fun Byte.unsigned(): Int = toInt() and 0xff
