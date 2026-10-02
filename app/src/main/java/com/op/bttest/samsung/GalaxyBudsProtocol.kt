package com.op.bttest.samsung

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.BatteryValue
import com.melody.melodylink.samsung.config.SamsungGalaxyBudsModel
import java.io.ByteArrayOutputStream

object GalaxyBudsMessageId {
    const val ACKNOWLEDGEMENT = 0x42
    const val EQUALIZER = 0x56
    const val STATUS_UPDATED = 0x60
    const val EXTENDED_STATUS_UPDATED = 0x61
    const val NOISE_CONTROLS_UPDATE = 0x77
    const val NOISE_CONTROLS = 0x78
    const val SET_AMBIENT_MODE = 0x80
    const val AMBIENT_MODE_UPDATED = 0x81
    const val MANAGER_INFO = 0x88
    const val SET_NOISE_REDUCTION = 0x98
    const val NOISE_REDUCTION_MODE_UPDATE = 0x9B
    const val FW_VERSION = 0xB4
    const val FW_VERSION2 = 0x68
}

enum class GalaxyBudsFrameType { REQUEST, RESPONSE }

data class GalaxyBudsFrame(
    val type: GalaxyBudsFrameType,
    val messageId: Int,
    val payload: ByteArray,
)

/** Samsung's CRC-16/CCITT variant, calculated over message ID and payload. */
object GalaxyBudsCrc16 {
    fun calculate(data: ByteArray): Int {
        var crc = 0
        data.forEach { byte ->
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}

object GalaxyBudsFrameCodec {
    private const val LEGACY_START = 0xFE
    private const val LEGACY_END = 0xEE
    private const val MODERN_START = 0xFD
    private const val MODERN_END = 0xDD
    const val MAX_FRAME_SIZE = 1024

    fun encode(model: SamsungGalaxyBudsModel, frame: GalaxyBudsFrame): ByteArray {
        val payloadSize = frame.payload.size
        val size = 3 + payloadSize
        require(size <= 0x3FF) { "Galaxy Buds frame is too large" }
        val out = ByteArrayOutputStream(7 + payloadSize)
        if (model.legacyFraming) {
            out.write(LEGACY_START)
            out.write(if (frame.type == GalaxyBudsFrameType.REQUEST) 0 else 1)
            out.write(size)
        } else {
            out.write(MODERN_START)
            val header = size or if (frame.type == GalaxyBudsFrameType.REQUEST) 0x1000 else 0
            out.write(header and 0xFF)
            out.write((header ushr 8) and 0xFF)
        }
        out.write(frame.messageId)
        out.write(frame.payload)
        val crc = GalaxyBudsCrc16.calculate(byteArrayOf(frame.messageId.toByte()) + frame.payload)
        out.write(crc and 0xFF)
        out.write((crc ushr 8) and 0xFF)
        out.write(if (model.legacyFraming) LEGACY_END else MODERN_END)
        return out.toByteArray()
    }

    fun decode(model: SamsungGalaxyBudsModel, raw: ByteArray): GalaxyBudsFrame? {
        if (raw.size !in 7..MAX_FRAME_SIZE) return null
        val expectedStart = if (model.legacyFraming) LEGACY_START else MODERN_START
        val expectedEnd = if (model.legacyFraming) LEGACY_END else MODERN_END
        if (raw.first().u8() != expectedStart || raw.last().u8() != expectedEnd) return null

        val header = if (model.legacyFraming) raw[2].u8() else raw[1].u8() or (raw[2].u8() shl 8)
        val size = if (model.legacyFraming) header else header and 0x3FF
        if (size < 3 || size > MAX_FRAME_SIZE - 4 || raw.size != size + 4) return null
        val payloadSize = size - 3
        val messageId = raw[3].u8()
        val payload = raw.copyOfRange(4, 4 + payloadSize)
        val expectedCrc = raw[4 + payloadSize].u8() or (raw[5 + payloadSize].u8() shl 8)
        val actualCrc = GalaxyBudsCrc16.calculate(byteArrayOf(messageId.toByte()) + payload)
        if (actualCrc != expectedCrc) return null
        val type = if (model.legacyFraming) {
            if (raw[1].u8() == 0) GalaxyBudsFrameType.REQUEST else GalaxyBudsFrameType.RESPONSE
        } else if ((header and 0x1000) != 0) {
            GalaxyBudsFrameType.REQUEST
        } else {
            GalaxyBudsFrameType.RESPONSE
        }
        return GalaxyBudsFrame(type, messageId, payload)
    }
}

/** Reassembles arbitrary RFCOMM reads into complete, validated protocol frames. */
class GalaxyBudsFrameAccumulator(private val model: SamsungGalaxyBudsModel) {
    private var pending = ByteArray(0)

    fun append(chunk: ByteArray): List<GalaxyBudsFrame> {
        if (chunk.isEmpty()) return emptyList()
        pending = (pending + chunk).takeLast(GalaxyBudsFrameCodec.MAX_FRAME_SIZE * 2).toByteArray()
        val frames = mutableListOf<GalaxyBudsFrame>()
        val start = if (model.legacyFraming) 0xFE else 0xFD
        while (pending.isNotEmpty()) {
            val startIndex = pending.indexOfFirst { it.u8() == start }
            if (startIndex < 0) {
                pending = ByteArray(0)
                break
            }
            if (startIndex > 0) pending = pending.copyOfRange(startIndex, pending.size)
            if (pending.size < 3) break
            val header = if (model.legacyFraming) pending[2].u8() else pending[1].u8() or (pending[2].u8() shl 8)
            val size = if (model.legacyFraming) header else header and 0x3FF
            val total = size + 4
            if (size !in 3..(GalaxyBudsFrameCodec.MAX_FRAME_SIZE - 4)) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }
            if (pending.size < total) break
            val candidate = pending.copyOfRange(0, total)
            pending = pending.copyOfRange(total, pending.size)
            GalaxyBudsFrameCodec.decode(model, candidate)?.let(frames::add)
        }
        return frames
    }
}

data class GalaxyBudsCoreState(
    val battery: Map<BatteryPart, BatteryValue> = emptyMap(),
    val ancMode: AncMode? = null,
    val firmwareVersion: String? = null,
)

object GalaxyBudsStatusParser {
    fun parse(model: SamsungGalaxyBudsModel, frame: GalaxyBudsFrame): GalaxyBudsCoreState? = when (frame.messageId) {
        GalaxyBudsMessageId.EXTENDED_STATUS_UPDATED -> parseExtendedStatus(model, frame.payload)
        GalaxyBudsMessageId.STATUS_UPDATED -> parseStatus(model, frame.payload)
        GalaxyBudsMessageId.AMBIENT_MODE_UPDATED -> frame.payload.firstOrNull()?.asBoolean()?.let {
            GalaxyBudsCoreState(ancMode = if (it) AncMode.AMBIENT_SOUND else AncMode.OFF)
        }
        GalaxyBudsMessageId.NOISE_REDUCTION_MODE_UPDATE -> frame.payload.firstOrNull()?.asBoolean()?.let {
            GalaxyBudsCoreState(ancMode = if (it) AncMode.NOISE_CANCELING else AncMode.OFF)
        }
        GalaxyBudsMessageId.NOISE_CONTROLS_UPDATE -> frame.payload.firstOrNull()?.u8()?.toAncMode()
            ?.let { GalaxyBudsCoreState(ancMode = it) }
        GalaxyBudsMessageId.FW_VERSION -> firmware(frame.payload, 1, 13)
        GalaxyBudsMessageId.FW_VERSION2 -> firmware(frame.payload, 2, 14)
        else -> null
    }

    private fun parseExtendedStatus(model: SamsungGalaxyBudsModel, payload: ByteArray): GalaxyBudsCoreState? {
        if (payload.size < 4) return null
        val battery = mutableMapOf<BatteryPart, BatteryValue>()
        battery.putIfPresent(BatteryPart.LEFT, payload.getOrNull(2)?.u8(), false)
        battery.putIfPresent(BatteryPart.RIGHT, payload.getOrNull(3)?.u8(), false)
        if (!model.legacyFraming) battery.putIfPresent(BatteryPart.CASE, payload.getOrNull(7)?.u8(), false)
        if (model.supportsChargingState) {
            val maskIndex = when (model) {
                SamsungGalaxyBudsModel.GALAXY_BUDS2 -> 36
                SamsungGalaxyBudsModel.GALAXY_BUDS2_PRO, SamsungGalaxyBudsModel.GALAXY_BUDS_FE -> 43
                else -> 42
            }
            payload.getOrNull(maskIndex)?.u8()?.let { mask ->
                battery[BatteryPart.LEFT]?.let { battery[BatteryPart.LEFT] = it.copy(charging = mask and 0x10 != 0) }
                battery[BatteryPart.RIGHT]?.let { battery[BatteryPart.RIGHT] = it.copy(charging = mask and 0x04 != 0) }
                battery[BatteryPart.CASE]?.let { battery[BatteryPart.CASE] = it.copy(charging = mask and 0x01 != 0) }
            }
        }
        val ancIndex = when {
            model == SamsungGalaxyBudsModel.GALAXY_BUDS -> 7
            model == SamsungGalaxyBudsModel.GALAXY_BUDS_PLUS -> 8
            model == SamsungGalaxyBudsModel.GALAXY_BUDS_LIVE -> 12
            else -> 12
        }
        val anc = payload.getOrNull(ancIndex)?.u8()?.let { raw ->
            when (model) {
                SamsungGalaxyBudsModel.GALAXY_BUDS, SamsungGalaxyBudsModel.GALAXY_BUDS_PLUS ->
                    if (raw == 1) AncMode.AMBIENT_SOUND else if (raw == 0) AncMode.OFF else null
                SamsungGalaxyBudsModel.GALAXY_BUDS_LIVE ->
                    if (raw == 1) AncMode.NOISE_CANCELING else if (raw == 0) AncMode.OFF else null
                else -> raw.toAncMode()
            }
        }
        return GalaxyBudsCoreState(battery = battery, ancMode = anc)
    }

    private fun parseStatus(model: SamsungGalaxyBudsModel, payload: ByteArray): GalaxyBudsCoreState? {
        if (payload.size < 3) return null
        val battery = mutableMapOf<BatteryPart, BatteryValue>()
        battery.putIfPresent(BatteryPart.LEFT, payload.getOrNull(1)?.u8(), false)
        battery.putIfPresent(BatteryPart.RIGHT, payload.getOrNull(2)?.u8(), false)
        if (!model.legacyFraming) battery.putIfPresent(BatteryPart.CASE, payload.getOrNull(6)?.u8(), false)
        return GalaxyBudsCoreState(battery = battery)
    }

    private fun firmware(payload: ByteArray, start: Int, end: Int): GalaxyBudsCoreState? {
        if (payload.size < end) return null
        return GalaxyBudsCoreState(firmwareVersion = payload.copyOfRange(start, end).decodeToString().trimEnd(' '))
    }

    private fun MutableMap<BatteryPart, BatteryValue>.putIfPresent(part: BatteryPart, raw: Int?, charging: Boolean) {
        if (raw != null && raw in 1..100) put(part, BatteryValue(raw, charging))
    }

    private fun Int.toAncMode(): AncMode? = when (this) {
        0 -> AncMode.OFF
        1 -> AncMode.NOISE_CANCELING
        2 -> AncMode.AMBIENT_SOUND
        3 -> AncMode.TRANSPARENCY
        else -> null
    }

    private fun Byte.asBoolean(): Boolean? = when (u8()) {
        0 -> false
        1 -> true
        else -> null
    }
}

object GalaxyBudsCommands {
    fun initialStatusRequest() = GalaxyBudsFrame(GalaxyBudsFrameType.REQUEST, GalaxyBudsMessageId.EXTENDED_STATUS_UPDATED, byteArrayOf())

    fun managerInfo() = GalaxyBudsFrame(
        GalaxyBudsFrameType.REQUEST,
        GalaxyBudsMessageId.MANAGER_INFO,
        byteArrayOf(1, 1, 34),
    )

    fun setAncMode(model: SamsungGalaxyBudsModel, mode: AncMode): GalaxyBudsFrame? {
        if (mode !in model.ancModes) return null
        return when (model) {
            SamsungGalaxyBudsModel.GALAXY_BUDS, SamsungGalaxyBudsModel.GALAXY_BUDS_PLUS -> GalaxyBudsFrame(
                GalaxyBudsFrameType.REQUEST,
                GalaxyBudsMessageId.SET_AMBIENT_MODE,
                byteArrayOf(if (mode == AncMode.AMBIENT_SOUND) 1 else 0),
            )
            SamsungGalaxyBudsModel.GALAXY_BUDS_LIVE -> GalaxyBudsFrame(
                GalaxyBudsFrameType.REQUEST,
                GalaxyBudsMessageId.SET_NOISE_REDUCTION,
                byteArrayOf(if (mode == AncMode.NOISE_CANCELING) 1 else 0),
            )
            else -> GalaxyBudsFrame(
                GalaxyBudsFrameType.REQUEST,
                GalaxyBudsMessageId.NOISE_CONTROLS,
                byteArrayOf(
                    when (mode) {
                        AncMode.OFF -> 0
                        AncMode.NOISE_CANCELING -> 1
                        AncMode.AMBIENT_SOUND -> 2
                        AncMode.TRANSPARENCY -> 3
                    }.toByte(),
                ),
            )
        }
    }
}

private fun Byte.u8(): Int = toInt() and 0xFF
