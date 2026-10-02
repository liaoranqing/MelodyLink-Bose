package com.melody.melodylink.xiaomi

import com.melody.melodylink.domain.AncMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiProtocolTest {
    @Test
    fun encodesSppTargetInfoWithMibudstestControlBits() {
        val packet = XiaomiRcspCodec.getTargetInfo(0x07, XiaomiRcspCodec.SPP_TARGET_APP)
        assertEquals("FE DC BA C4 02 00 05 07 FF FF FF FF EF", packet.hex())
    }

    @Test
    fun encodesTargetInfoAncForEveryXiaomiDevice() {
        val packet = XiaomiRcspCodec.setTargetInfoAnc(0x07, AncMode.AMBIENT_SOUND)
        assertEquals("FE DC BA C4 08 00 04 07 02 04 02 EF", packet.hex())
    }

    @Test
    fun streamDecoderHandlesNoiseAndFragmentation() {
        val response = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0x01, 0xF3.toByte(), 0x00, 0x07, 0x00, 0x21, 0x04, 0x00, 0x0B, 0x01, 0x00, 0xEF.toByte())
        val decoder = XiaomiRcspStreamDecoder()
        assertTrue(decoder.accept(byteArrayOf(0x00, 0x11) + response.copyOfRange(0, 9)).isEmpty())
        val frame = decoder.accept(response.copyOfRange(9, response.size)).single()
        assertEquals(0xF3, frame.opcode)
        assertEquals(0x21, frame.sequence)
    }

    @Test
    fun parsesThreeBatteryValuesAndKeepsUnknownAbsent() {
        val values = XiaomiBatteryParser.parseVendorEvent("129,64,255,0")
        assertEquals(1, values[com.melody.melodylink.domain.BatteryPart.LEFT]?.percent)
        assertTrue(values[com.melody.melodylink.domain.BatteryPart.LEFT]?.charging == true)
        assertEquals(64, values[com.melody.melodylink.domain.BatteryPart.RIGHT]?.percent)
        assertFalse(values.containsKey(com.melody.melodylink.domain.BatteryPart.CASE))
    }

    @Test
    fun parsesSplitXiaomiVendorBatteryArguments() {
        val values = XiaomiBatteryParser.parseVendorEvent(listOf("+XIAOMI", "129,64,255,0,0,0"))
        assertEquals(1, values[com.melody.melodylink.domain.BatteryPart.LEFT]?.percent)
        assertEquals(64, values[com.melody.melodylink.domain.BatteryPart.RIGHT]?.percent)
        assertFalse(values.containsKey(com.melody.melodylink.domain.BatteryPart.CASE))
    }

    @Test
    fun parsesTargetInfoBatteryTlvs() {
        val frame = XiaomiRcspCodec.decode(byteArrayOf(
            0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0x04, 0x02, 0x00, 0x07,
            0x00, 0x21, 0x04, 0x07, 0x81.toByte(), 0x40, 0xFF.toByte(), 0xEF.toByte(),
        ))!!
        val update = XiaomiTargetInfoStatusParser.parse(frame)!!
        assertEquals(1, update.battery!![com.melody.melodylink.domain.BatteryPart.LEFT]?.percent)
        assertTrue(update.battery!![com.melody.melodylink.domain.BatteryPart.LEFT]?.charging == true)
        assertEquals(64, update.battery!![com.melody.melodylink.domain.BatteryPart.RIGHT]?.percent)
        assertFalse(update.battery!!.containsKey(com.melody.melodylink.domain.BatteryPart.CASE))
    }

    @Test
    fun parsesTargetInfoStatusOnCompanionNotificationChannel() {
        val frame = XiaomiRcspCodec.decode(byteArrayOf(
            0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC7.toByte(), 0x0E, 0x00, 0x04,
            0x31, 0x02, 0x04, 0x01, 0xEF.toByte(),
        ))!!
        assertEquals(AncMode.NOISE_CANCELING, XiaomiTargetInfoStatusParser.parse(frame)?.ancMode)
    }

    @Test
    fun ignoresTargetInfoStrengthOnlyNotification() {
        val frame = XiaomiRcspCodec.decode(byteArrayOf(
            0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC7.toByte(), 0xF4.toByte(), 0x00, 0x06,
            0x33, 0x04, 0x00, 0x0B, 0x01, 0x02, 0xEF.toByte(),
        ))!!
        assertNull(XiaomiTargetInfoStatusParser.parse(frame))
    }
}

private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
