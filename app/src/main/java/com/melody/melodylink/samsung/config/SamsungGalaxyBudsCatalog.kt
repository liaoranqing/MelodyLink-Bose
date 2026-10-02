package com.melody.melodylink.samsung.config

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.DeviceIdentity
import java.util.UUID

object SamsungGalaxyBudsUuids {
    val modernSpp: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    val legacySpp: UUID = UUID.fromString("00001102-0000-1000-8000-00805f9b34fd")
    val mepSpp: UUID = UUID.fromString("f8620674-a1ed-41ab-a8b9-de9ad655729d")

    private const val DEVICE_ID_PREFIX = "d908aab5-7a90-4cbe-8641-86a553db"

    fun deviceIdUuid(id: Int): UUID = UUID.fromString(
        DEVICE_ID_PREFIX + id.toString(16).padStart(4, '0'),
    )
}

enum class SamsungGalaxyBudsModel(
    val id: String,
    val displayName: String,
    val legacyFraming: Boolean,
    val batteryParts: Set<BatteryPart>,
    val supportsChargingState: Boolean,
    val ancModes: Set<AncMode>,
    val deviceIds: Set<Int>,
    val nameAliases: Set<String>,
) {
    GALAXY_BUDS(
        "samsung.galaxy_buds", "Galaxy Buds", true,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT), false,
        setOf(AncMode.OFF, AncMode.AMBIENT_SOUND),
        setOf(0x0101), setOf("buds"),
    ),
    GALAXY_BUDS_PLUS(
        "samsung.galaxy_buds_plus", "Galaxy Buds+", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), false,
        setOf(AncMode.OFF, AncMode.AMBIENT_SOUND),
        (0x0102..0x010A).toSet(), setOf("buds+", "buds plus"),
    ),
    GALAXY_BUDS_LIVE(
        "samsung.galaxy_buds_live", "Galaxy Buds Live", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), false,
        setOf(AncMode.OFF, AncMode.NOISE_CANCELING),
        (0x0116..0x011C).toSet(), setOf("buds live"),
    ),
    GALAXY_BUDS_PRO(
        "samsung.galaxy_buds_pro", "Galaxy Buds Pro", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), false,
        setOf(AncMode.OFF, AncMode.AMBIENT_SOUND, AncMode.NOISE_CANCELING),
        (0x012A..0x012D).toSet(), setOf("buds pro"),
    ),
    GALAXY_BUDS2(
        "samsung.galaxy_buds2", "Galaxy Buds2", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), true,
        setOf(AncMode.OFF, AncMode.AMBIENT_SOUND, AncMode.NOISE_CANCELING),
        (0x0139..0x0141).toSet() + 0x3801, setOf("buds2"),
    ),
    GALAXY_BUDS2_PRO(
        "samsung.galaxy_buds2_pro", "Galaxy Buds2 Pro", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), true,
        setOf(AncMode.OFF, AncMode.AMBIENT_SOUND, AncMode.NOISE_CANCELING),
        (0x0145..0x0148).toSet(), setOf("buds2 pro"),
    ),
    GALAXY_BUDS_FE(
        "samsung.galaxy_buds_fe", "Galaxy Buds FE", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), true,
        setOf(AncMode.OFF, AncMode.AMBIENT_SOUND, AncMode.NOISE_CANCELING),
        setOf(0x014A, 0x014B), setOf("buds fe"),
    ),
    GALAXY_BUDS3(
        "samsung.galaxy_buds3", "Galaxy Buds3", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), true,
        setOf(AncMode.OFF, AncMode.NOISE_CANCELING),
        setOf(0x014D, 0x014E), setOf("buds3"),
    ),
    GALAXY_BUDS3_PRO(
        "samsung.galaxy_buds3_pro", "Galaxy Buds3 Pro", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), true,
        setOf(AncMode.OFF, AncMode.AMBIENT_SOUND, AncMode.NOISE_CANCELING, AncMode.TRANSPARENCY),
        setOf(0x0154, 0x0155), setOf("buds3 pro"),
    ),
    GALAXY_BUDS3_FE(
        "samsung.galaxy_buds3_fe", "Galaxy Buds3 FE", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), true,
        setOf(AncMode.OFF, AncMode.AMBIENT_SOUND, AncMode.NOISE_CANCELING, AncMode.TRANSPARENCY),
        setOf(0x015B, 0x015C), setOf("buds3 fe"),
    ),
    GALAXY_BUDS_CORE(
        "samsung.galaxy_buds_core", "Galaxy Buds Core", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), true,
        setOf(AncMode.OFF, AncMode.AMBIENT_SOUND, AncMode.NOISE_CANCELING),
        setOf(0x0142, 0x0143), setOf("buds core"),
    ),
    GALAXY_BUDS4(
        "samsung.galaxy_buds4", "Galaxy Buds4", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), true,
        setOf(AncMode.OFF, AncMode.AMBIENT_SOUND, AncMode.NOISE_CANCELING, AncMode.TRANSPARENCY),
        setOf(0x0163, 0x0164), setOf("buds4"),
    ),
    GALAXY_BUDS4_PRO(
        "samsung.galaxy_buds4_pro", "Galaxy Buds4 Pro", false,
        setOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE), true,
        setOf(AncMode.OFF, AncMode.AMBIENT_SOUND, AncMode.NOISE_CANCELING, AncMode.TRANSPARENCY),
        setOf(0x0167, 0x0168, 0x0169), setOf("buds4 pro"),
    ),
    ;

    val serviceUuid: UUID
        get() = if (legacyFraming) SamsungGalaxyBudsUuids.legacySpp else SamsungGalaxyBudsUuids.modernSpp
}

data class SamsungGalaxyBudsMatch(
    val model: SamsungGalaxyBudsModel,
    val confidence: Int,
)

object SamsungGalaxyBudsCatalog {
    val models: List<SamsungGalaxyBudsModel> = SamsungGalaxyBudsModel.entries

    fun find(identity: DeviceIdentity): SamsungGalaxyBudsMatch? {
        val serviceUuids = identity.serviceUuids.mapNotNull(::parseUuid).toSet()
        val legacyQualified = SamsungGalaxyBudsUuids.legacySpp in serviceUuids &&
            SamsungGalaxyBudsUuids.mepSpp in serviceUuids
        if (legacyQualified) {
            return SamsungGalaxyBudsMatch(SamsungGalaxyBudsModel.GALAXY_BUDS, 100)
        }

        val modernQualified = SamsungGalaxyBudsUuids.modernSpp in serviceUuids &&
            SamsungGalaxyBudsUuids.mepSpp in serviceUuids
        if (!modernQualified) return null

        val identified = serviceUuids
            .mapNotNull(::deviceId)
            .firstNotNullOfOrNull { id -> models.firstOrNull { id in it.deviceIds } }
        if (identified != null) return SamsungGalaxyBudsMatch(identified, 100)

        val normalizedName = identity.bluetoothName?.trim()?.lowercase().orEmpty()
        if (normalizedName.isBlank()) return null
        val named = models
            .filter { !it.legacyFraming }
            .sortedByDescending { model -> model.nameAliases.maxOf { it.length } }
            .firstOrNull { model -> model.nameAliases.any(normalizedName::contains) }
            ?: return null
        return SamsungGalaxyBudsMatch(named, 60)
    }

    private fun parseUuid(value: String): UUID? = runCatching { UUID.fromString(value.lowercase()) }.getOrNull()

    private fun deviceId(uuid: UUID): Int? {
        val value = uuid.toString()
        if (!value.startsWith("d908aab5-7a90-4cbe-8641-86a553db")) return null
        return value.takeLast(4).toIntOrNull(16)
    }
}
