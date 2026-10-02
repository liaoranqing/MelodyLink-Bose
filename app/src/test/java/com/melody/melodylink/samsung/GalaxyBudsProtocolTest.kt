package com.melody.melodylink.samsung

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.samsung.config.SamsungGalaxyBudsModel
import com.op.bttest.samsung.GalaxyBudsCommands
import com.op.bttest.samsung.GalaxyBudsCrc16
import com.op.bttest.samsung.GalaxyBudsFrame
import com.op.bttest.samsung.GalaxyBudsFrameAccumulator
import com.op.bttest.samsung.GalaxyBudsFrameCodec
import com.op.bttest.samsung.GalaxyBudsFrameType
import com.op.bttest.samsung.GalaxyBudsMessageId
import com.op.bttest.samsung.GalaxyBudsStatusParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalaxyBudsProtocolTest {
    @Test
    fun crc16MatchesCcittCheckVector() {
        assertEquals(0x31C3, GalaxyBudsCrc16.calculate("123456789".encodeToByteArray()))
    }

    @Test
    fun encodesAndDecodesModernFrame() {
        val frame = GalaxyBudsFrame(GalaxyBudsFrameType.REQUEST, 0x78, byteArrayOf(2))
        val encoded = GalaxyBudsFrameCodec.encode(SamsungGalaxyBudsModel.GALAXY_BUDS2_PRO, frame)

        assertEquals(0xFD, encoded.first().toInt() and 0xFF)
        assertEquals(0xDD, encoded.last().toInt() and 0xFF)
        val decoded = GalaxyBudsFrameCodec.decode(SamsungGalaxyBudsModel.GALAXY_BUDS2_PRO, encoded)!!
        assertEquals(frame.type, decoded.type)
        assertEquals(frame.messageId, decoded.messageId)
        assertArrayEquals(frame.payload, decoded.payload)
    }

    @Test
    fun supportsLegacyEnvelope() {
        val frame = GalaxyBudsFrame(GalaxyBudsFrameType.REQUEST, 0x61, byteArrayOf())
        val encoded = GalaxyBudsFrameCodec.encode(SamsungGalaxyBudsModel.GALAXY_BUDS, frame)

        assertEquals(0xFE, encoded.first().toInt() and 0xFF)
        assertEquals(0xEE, encoded.last().toInt() and 0xFF)
        val decoded = GalaxyBudsFrameCodec.decode(SamsungGalaxyBudsModel.GALAXY_BUDS, encoded)!!
        assertEquals(frame.type, decoded.type)
        assertEquals(frame.messageId, decoded.messageId)
        assertArrayEquals(frame.payload, decoded.payload)
    }

    @Test
    fun rejectsBadCrc() {
        val encoded = GalaxyBudsFrameCodec.encode(
            SamsungGalaxyBudsModel.GALAXY_BUDS2,
            GalaxyBudsFrame(GalaxyBudsFrameType.REQUEST, 0x61, byteArrayOf()),
        )
        encoded[4] = (encoded[4].toInt() xor 0xFF).toByte()

        assertNull(GalaxyBudsFrameCodec.decode(SamsungGalaxyBudsModel.GALAXY_BUDS2, encoded))
    }

    @Test
    fun reassemblesFragmentedAndCoalescedFrames() {
        val first = GalaxyBudsFrameCodec.encode(
            SamsungGalaxyBudsModel.GALAXY_BUDS3_PRO,
            GalaxyBudsFrame(GalaxyBudsFrameType.RESPONSE, 0x77, byteArrayOf(3)),
        )
        val second = GalaxyBudsFrameCodec.encode(
            SamsungGalaxyBudsModel.GALAXY_BUDS3_PRO,
            GalaxyBudsFrame(GalaxyBudsFrameType.RESPONSE, 0x81, byteArrayOf(1)),
        )
        val accumulator = GalaxyBudsFrameAccumulator(SamsungGalaxyBudsModel.GALAXY_BUDS3_PRO)

        assertEquals(emptyList<GalaxyBudsFrame>(), accumulator.append(first.copyOfRange(0, 3)))
        val decoded = accumulator.append(first.copyOfRange(3, first.size) + second)

        assertEquals(2, decoded.size)
        assertEquals(0x77, decoded[0].messageId)
        assertEquals(0x81, decoded[1].messageId)
    }

    @Test
    fun parsesCoreExtendedStatusAndChargingMask() {
        val payload = ByteArray(44)
        payload[2] = 80
        payload[3] = 70
        payload[7] = 60
        payload[12] = 2
        payload[43] = 0x15
        val state = GalaxyBudsStatusParser.parse(
            SamsungGalaxyBudsModel.GALAXY_BUDS2_PRO,
            GalaxyBudsFrame(GalaxyBudsFrameType.RESPONSE, GalaxyBudsMessageId.EXTENDED_STATUS_UPDATED, payload),
        )!!

        assertEquals(AncMode.AMBIENT_SOUND, state.ancMode)
        assertEquals(80, state.battery[BatteryPart.LEFT]?.percent)
        assertEquals(true, state.battery[BatteryPart.LEFT]?.charging)
        assertEquals(true, state.battery[BatteryPart.RIGHT]?.charging)
        assertEquals(true, state.battery[BatteryPart.CASE]?.charging)
    }

    @Test
    fun choosesExpectedAncCommandFamilies() {
        val ambient = GalaxyBudsCommands.setAncMode(SamsungGalaxyBudsModel.GALAXY_BUDS_PLUS, AncMode.AMBIENT_SOUND)!!
        val live = GalaxyBudsCommands.setAncMode(SamsungGalaxyBudsModel.GALAXY_BUDS_LIVE, AncMode.NOISE_CANCELING)!!
        val modern = GalaxyBudsCommands.setAncMode(SamsungGalaxyBudsModel.GALAXY_BUDS3_PRO, AncMode.TRANSPARENCY)!!

        assertEquals(GalaxyBudsMessageId.SET_AMBIENT_MODE, ambient.messageId)
        assertArrayEquals(byteArrayOf(1), ambient.payload)
        assertEquals(GalaxyBudsMessageId.SET_NOISE_REDUCTION, live.messageId)
        assertEquals(GalaxyBudsMessageId.NOISE_CONTROLS, modern.messageId)
        assertArrayEquals(byteArrayOf(3), modern.payload)
    }
}
