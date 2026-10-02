package com.melody.melodylink.xiaomi

import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.BatteryValue

object XiaomiBatteryParser {
    fun parseVendorEvent(value: String): Map<BatteryPart, BatteryValue> {
        return parseVendorEvent(listOf(value))
    }

    /**
     * Bluetooth stacks differ on whether +XIAOMI and its comma-separated payload arrive as
     * one argument or separate vendor-event arguments.  Normalize both forms before parsing.
     */
    fun parseVendorEvent(values: Collection<String>): Map<BatteryPart, BatteryValue> {
        val fields = values
            .flatMap { it.split(',') }
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.equals("+XIAOMI", ignoreCase = true) }
        val values = fields.take(3).map { it.toIntOrNull() ?: return emptyMap() }
        if (values.size != 3) return emptyMap()
        return listOf(BatteryPart.LEFT, BatteryPart.RIGHT, BatteryPart.CASE).zip(values).mapNotNull { (part, raw) ->
            if (raw !in 0..255 || raw == 255) null else part to BatteryValue(raw and 0x7F, raw and 0x80 != 0)
        }.toMap()
    }
}
