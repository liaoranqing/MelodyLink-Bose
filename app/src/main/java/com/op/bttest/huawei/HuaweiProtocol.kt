package com.op.bttest.huawei

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.BatteryValue
import com.melody.melodylink.huawei.config.HuaweiDeviceConfig

/** Independent Kotlin implementation of the verified Huawei 0x5A/0x2B RFCOMM framing. */
data class HuaweiParsedState(
    val ancMode: AncMode? = null,
    val battery: Map<BatteryPart, BatteryValue> = emptyMap(),
)

object HuaweiCommands {
    private val batteryQuery = packet(0x5A, 0x00, 0x09, 0x00, 0x01, 0x08, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00, 0xFB, 0xB9)
    private val currentStateQuery = packet(0x5A, 0x00, 0x05, 0x00, 0x2B, 0x2A, 0x01, 0x00, 0x42, 0x7E)
    private val modernOff = packet(0x5A, 0x00, 0x07, 0x00, 0x2B, 0x04, 0x01, 0x02, 0x00, 0x00, 0xD2, 0x2D)
    private val modernAnc = packet(0x5A, 0x00, 0x07, 0x00, 0x2B, 0x04, 0x01, 0x02, 0x01, 0xFF, 0xFF, 0xEC)
    private val transparency = packet(0x5A, 0x00, 0x07, 0x00, 0x2B, 0x04, 0x01, 0x02, 0x02, 0xFF, 0xAA, 0xBF)
    // Verified HuaweiPods captures. The device does not expose a readable low-latency state.
    private val lowLatencyDisabled = packet(0x5A, 0x00, 0x06, 0x00, 0x2B, 0x6C, 0x01, 0x01, 0x00, 0xB4, 0x30)
    private val lowLatencyEnabled = packet(0x5A, 0x00, 0x06, 0x00, 0x2B, 0x6C, 0x01, 0x01, 0x01, 0xA4, 0x11)

    fun batteryQuery(route: HuaweiDeviceConfig): ByteArray? = batteryQuery.takeIf { route.batteryParts.isNotEmpty() }?.copyOf()
    fun stateQuery(route: HuaweiDeviceConfig): ByteArray? = currentStateQuery.takeIf { route.supportsAncReadback }?.copyOf()
    fun setAnc(route: HuaweiDeviceConfig, mode: AncMode): ByteArray? = when (mode) {
        AncMode.OFF -> modernOff
        AncMode.NOISE_CANCELING -> modernAnc
        AncMode.AMBIENT_SOUND, AncMode.TRANSPARENCY -> transparency.takeIf { route.supportsTransparency }
    }?.takeIf { route.supportsAnc }?.copyOf()

    fun setLowLatency(route: HuaweiDeviceConfig, enabled: Boolean): ByteArray? =
        (if (enabled) lowLatencyEnabled else lowLatencyDisabled)
            .takeIf { route.supportsLowLatency }
            ?.copyOf()

    private fun packet(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
}

object HuaweiFrameCodec {
    fun validFrames(stream: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset + 5 <= stream.size) {
            if (u8(stream[offset]) != 0x5A || u8(stream[offset + 1]) != 0) { offset++; continue }
            val length = u8(stream[offset + 2]) or (u8(stream[offset + 3]) shl 8)
            val size = 5 + length
            if (length <= 3 || offset + size > stream.size) break
            val frame = stream.copyOfRange(offset, offset + size)
            if (crc(frame.copyOf(size - 2)) == ((u8(frame[size - 2]) shl 8) or u8(frame[size - 1]))) {
                result += frame
                offset += size
            } else offset++
        }
        return result
    }

    fun crc16Xmodem(bytes: ByteArray): Int = crc(bytes)

    private fun crc(bytes: ByteArray): Int {
        var value = 0
        bytes.forEach { byte ->
            value = value xor (u8(byte) shl 8)
            repeat(8) { value = if (value and 0x8000 != 0) ((value shl 1) xor 0x1021) and 0xFFFF else (value shl 1) and 0xFFFF }
        }
        return value
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xFF
}

object HuaweiStatusParser {
    fun parse(stream: ByteArray, route: HuaweiDeviceConfig): HuaweiParsedState? {
        var state: HuaweiParsedState? = null
        HuaweiFrameCodec.validFrames(stream).forEach { frame ->
            val service = frame.getOrNull(4)?.u8() ?: return@forEach
            val command = frame.getOrNull(5)?.u8() ?: return@forEach
            val fields = fields(frame)
            if (service == 0x01 && command in setOf(0x08, 0x27)) {
                val levels = fields[0x02] ?: return@forEach
                val charging = fields[0x03] ?: byteArrayOf()
                val battery = buildMap {
                    putBattery(BatteryPart.LEFT, levels, charging, 0)
                    putBattery(BatteryPart.RIGHT, levels, charging, 1)
                    if (BatteryPart.CASE in route.batteryParts) putBattery(BatteryPart.CASE, levels, charging, 2)
                }
                state = (state ?: HuaweiParsedState()).copy(battery = battery)
            } else if (service == 0x2B && command == 0x2A) {
                val value = fields[0x01] ?: return@forEach
                val mode = when (value.getOrNull(1)?.u8()) { 0 -> AncMode.OFF; 1 -> AncMode.NOISE_CANCELING; 2 -> AncMode.TRANSPARENCY; else -> null }
                if (mode != null) state = (state ?: HuaweiParsedState()).copy(ancMode = mode)
            }
        }
        return state
    }

    private fun MutableMap<BatteryPart, BatteryValue>.putBattery(part: BatteryPart, levels: ByteArray, charging: ByteArray, index: Int) {
        val percent = levels.getOrNull(index)?.u8() ?: return
        if (percent !in 0..100) return
        put(part, BatteryValue(percent, charging.getOrNull(index)?.u8() != 0))
    }

    private fun fields(frame: ByteArray): Map<Int, ByteArray> {
        val result = linkedMapOf<Int, ByteArray>(); var offset = 6; val end = frame.size - 2
        while (offset + 2 <= end) { val type = frame[offset].u8(); val size = frame[offset + 1].u8(); val next = offset + 2 + size; if (next > end) return emptyMap(); result[type] = frame.copyOfRange(offset + 2, next); offset = next }
        return if (offset == end) result else emptyMap()
    }

    private fun Byte.u8(): Int = toInt() and 0xFF
}
