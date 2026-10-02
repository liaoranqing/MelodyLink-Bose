package com.melody.melodylink.xiaomi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiAuthSessionTest {
    @Test
    fun acceptsOnlyTheExpectedAuthenticationSequence() {
        val engine = FakeAuthEngine()
        val session = XiaomiAuthSession(engine)
        assertArrayEquals(byteArrayOf(0) + ByteArray(16) { 1 }, session.start().getOrThrow())
        assertArrayEquals(PASS, session.receive(byteArrayOf(0) + ByteArray(16) { 3 }).getOrThrow())
        assertArrayEquals(byteArrayOf(1) + ByteArray(16) { 9 }, session.receive(byteArrayOf(0) + ByteArray(16) { 4 }).getOrThrow())
        assertFalse(session.isAuthenticated())
        assertTrue(session.receive(PASS).getOrThrow() == null)
        assertTrue(session.isAuthenticated())
    }

    private class FakeAuthEngine : XiaomiAuthEngine {
        override fun initialize() = true
        override fun randomAuthData() = byteArrayOf(0) + ByteArray(16) { 1 }
        override fun randomCheckData() = ByteArray(16) { 2 }
        override fun encryptAuthData(input: ByteArray) = byteArrayOf(1) + ByteArray(16) { 9 }
        override fun encryptCheckData(input: ByteArray) = ByteArray(16) { 3 }
    }

    private companion object { val PASS = byteArrayOf(0x02, 0x70, 0x61, 0x73, 0x73) }
}
