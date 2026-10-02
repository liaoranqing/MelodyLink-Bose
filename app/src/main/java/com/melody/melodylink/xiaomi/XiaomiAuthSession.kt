package com.melody.melodylink.xiaomi

import com.xiaomi.aivsbluetoothsdk.impl.BluetoothAuth

interface XiaomiAuthEngine {
    fun initialize(): Boolean
    fun randomAuthData(): ByteArray
    fun randomCheckData(): ByteArray
    fun encryptAuthData(input: ByteArray): ByteArray
    fun encryptCheckData(input: ByteArray): ByteArray
}

object NativeXiaomiAuthEngine : XiaomiAuthEngine {
    override fun initialize() = BluetoothAuth.nativeInit()
    override fun randomAuthData() = BluetoothAuth.getRandomAuthData()
    override fun randomCheckData() = BluetoothAuth.getRandomAuthCheckData()
    override fun encryptAuthData(input: ByteArray) = BluetoothAuth.getEncryptedAuthData(input)
    override fun encryptCheckData(input: ByteArray) = BluetoothAuth.getEncryptedAuthCheckData(input)
}

/** Authentication data is deliberately kept out of logging and never exposed to callers. */
class XiaomiAuthSession(private val engine: XiaomiAuthEngine = NativeXiaomiAuthEngine) {
    private var phase = Phase.IDLE
    private var expectedCheck = ByteArray(0)

    fun start(): Result<ByteArray> = runCatching {
        check(engine.initialize()) { "Xiaomi authentication library initialization failed" }
        val challenge = engine.randomAuthData()
        check(challenge.size == 17 && challenge[0] == 0.toByte()) { "invalid Xiaomi authentication challenge" }
        engine.encryptAuthData(challenge) // Matches the source SDK cache side effect.
        val check = engine.randomCheckData()
        check(check.size == 16) { "invalid Xiaomi authentication check" }
        expectedCheck = engine.encryptCheckData(check)
        check(expectedCheck.size == 16) { "invalid Xiaomi encrypted authentication check" }
        phase = Phase.WAIT_ENCRYPTED_CHECK
        challenge
    }

    fun receive(value: ByteArray): Result<ByteArray?> = runCatching {
        when (phase) {
            Phase.WAIT_ENCRYPTED_CHECK -> {
                check(value.size == 17 && (value[0] == 0.toByte() || value[0] == 1.toByte())) { "unexpected Xiaomi authentication check" }
                check(value.copyOfRange(1, 17).contentEquals(expectedCheck)) { "Xiaomi authentication check mismatch" }
                phase = Phase.WAIT_PEER_CHALLENGE
                PASS
            }
            Phase.WAIT_PEER_CHALLENGE -> {
                check(value.size == 17 && value[0] == 0.toByte()) { "unexpected Xiaomi peer challenge" }
                val response = engine.encryptAuthData(value)
                check(response.size == 17 && response[0] == 1.toByte()) { "invalid Xiaomi peer response" }
                phase = Phase.WAIT_PASS
                response
            }
            Phase.WAIT_PASS -> {
                check(value.contentEquals(PASS)) { "Xiaomi authentication pass marker missing" }
                phase = Phase.AUTHENTICATED
                null
            }
            else -> error("unexpected Xiaomi authentication data")
        }
    }

    fun isAuthenticated(): Boolean = phase == Phase.AUTHENTICATED
    fun isWaiting(): Boolean = phase in setOf(Phase.WAIT_ENCRYPTED_CHECK, Phase.WAIT_PEER_CHALLENGE, Phase.WAIT_PASS)

    private enum class Phase { IDLE, WAIT_ENCRYPTED_CHECK, WAIT_PEER_CHALLENGE, WAIT_PASS, AUTHENTICATED }
    private companion object { val PASS = byteArrayOf(0x02, 0x70, 0x61, 0x73, 0x73) }
}
