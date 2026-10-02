package com.op.bttest.huawei

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.huawei.config.HuaweiConfigLoader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiProtocolTest {
    private val route = HuaweiConfigLoader.fromDirectory(mainAssetsDirectory()).registry.profiles
        .first { it.id == "huawei.freebuds7i" }

    @Test fun validatesCapturedAncStateFrame() {
        val frame = framed(byteArrayOf(0x2B, 0x2A, 0x01, 0x02, 0x00, 0x02))
        assertTrue(HuaweiFrameCodec.validFrames(frame).isNotEmpty())
        assertEquals(AncMode.TRANSPARENCY, HuaweiStatusParser.parse(frame, route)?.ancMode)
    }

    @Test fun parsesBatteryFrame() {
        val payload = byteArrayOf(0x01, 0x08, 0x02, 0x03, 80, 70, 60, 0x03, 0x03, 1, 0, 1)
        val frame = framed(payload)
        val battery = HuaweiStatusParser.parse(frame, route)?.battery.orEmpty()
        assertEquals(80, battery[BatteryPart.LEFT]?.percent)
        assertEquals(70, battery[BatteryPart.RIGHT]?.percent)
        assertEquals(60, battery[BatteryPart.CASE]?.percent)
    }

    @Test fun lowLatencyPacketsMatchHuaweiPodsCaptures() {
        assertArrayEquals(bytes("5A0006002B6C010101A411"), HuaweiCommands.setLowLatency(route, true))
        assertArrayEquals(bytes("5A0006002B6C010100B430"), HuaweiCommands.setLowLatency(route, false))
        val unsupported = HuaweiConfigLoader.fromDirectory(mainAssetsDirectory()).registry.profiles
            .first { it.id == "huawei.freebuds3" }
        assertNull(HuaweiCommands.setLowLatency(unsupported, true))
    }

    private fun framed(body: ByteArray): ByteArray {
        val withoutCrc = byteArrayOf(0x5A, 0x00, (body.size + 1).toByte(), 0x00) + body
        val crc = HuaweiFrameCodec.crc16Xmodem(withoutCrc)
        return withoutCrc + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }

    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun mainAssetsDirectory(): File = sequenceOf(
    File("src/main/assets"),
    File("app/src/main/assets"),
    File("../app/src/main/assets"),
).firstOrNull(File::isDirectory) ?: error("main assets directory was not found")
