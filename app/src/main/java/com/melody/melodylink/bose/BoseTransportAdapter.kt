package com.melody.melodylink.bose

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.BatteryValue
import com.melody.melodylink.domain.EarbudsState
import com.melody.melodylink.transport.TransportEndpoint
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bose BMAP session built on the shared RFCOMM transport.
 *
 * Protocol knowledge is ported from the field-verified Bose Melody Control
 * implementation (v1.4-1.7): short-lived connections, [31.3] mode START,
 * [31.10] AudioModesSettingsConfig GET/SETGET for CNC/ANC/spatial, and the
 * 4-byte repeating battery records on [2.2].
 */
class BoseTransportAdapter(private val listener: Listener) {

    interface Listener {
        fun onConnecting()
        fun onConnected(state: EarbudsState)
        fun onBatteryState(left: BatteryValue?, right: BatteryValue?, case: BatteryValue?)
        fun onSettingsState(settings: ByteArray)
        fun onAncWriteResult(success: Boolean, state: EarbudsState?, reason: String)
        fun onDisconnected()
        fun onFailed(reason: String)
        fun onLog(message: String)
    }

    @Volatile
    var isConnected: Boolean = false
        private set

    private val transport = com.melody.melodylink.transport.RfcommTransport()
    private val parser = BoseBmap.Parser()
    private val replies = Channel<BoseBmap.Frame>(Channel.BUFFERED)
    private var device: BluetoothDevice? = null

    @SuppressLint("MissingPermission")
    suspend fun connect(target: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        listener.onConnecting()
        device = target
        val endpoint = TransportEndpoint.Rfcomm(target, UUID.fromString(BoseDeviceConfig.BMAP_UUID))
        val opened = transport.connect(endpoint).also {
            if (it.isFailure) listener.onFailed("rfcomm open: ${it.exceptionOrNull()?.message}")
        }.isSuccess
        if (!opened) return@withContext false
        isConnected = true
        true
    }

    suspend fun disconnect() {
        transport.close()
        isConnected = false
        listener.onDisconnected()
    }

    fun release() = transport.release()

    /** One BMAP request; returns the first matching reply frame or null on timeout. */
    private suspend fun command(
        block: Int,
        function: Int,
        operator: Int,
        payload: ByteArray?,
        wantBlock: Int = block,
        wantFunction: Int = function,
        timeoutMs: Long = 3_000L,
    ): BoseBmap.Frame? = withContext(Dispatchers.IO) {
        val result = transport.send(BoseBmap.packet(block, function, operator, payload))
        if (result.isFailure) {
            listener.onLog("bose send failed: ${result.exceptionOrNull()?.message}")
            return@withContext null
        }
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val frame = replies.receiveCatching().getOrNull() ?: return@withTimeoutOrNull null
                if (frame.matches(wantBlock, wantFunction)) return@withTimeoutOrNull frame
            }
            null
        }
    }

    /** Read current mode + audio settings and publish domain state. */
    suspend fun readState(): EarbudsState? {
        val mode = command(BLOCK_AUDIO, FUNC_MODE, BoseBmap.OP_GET, null)
        val settings = command(BLOCK_AUDIO, FUNC_SETTINGS, BoseBmap.OP_GET, null)
        if (mode == null && settings == null) return null
        if (settings != null && settings.payload.size >= 5) listener.onSettingsState(settings.payload)
        val ancMode = mode?.takeIf { it.payload.isNotEmpty() }?.let { frame ->
            when (frame.u8(0)) {
                BoseDeviceConfig.MODE_QUIET ->
                    if (settings != null && settings.payload.size >= 5 && settings.u8(BoseDeviceConfig.SETTING_ANC) != 0)
                        AncMode.NOISE_CANCELING else AncMode.OFF
                BoseDeviceConfig.MODE_AWARE -> AncMode.TRANSPARENCY
                else -> null
            }
        }
        val state = EarbudsState(capabilities = BoseDeviceConfig.capabilities, ancMode = ancMode)
        listener.onConnected(state)
        return state
    }

    suspend fun refreshBattery(): Boolean {
        val battery = command(BLOCK_BATTERY, FUNC_BATTERY, BoseBmap.OP_GET, null, timeoutMs = 4_000L)
            ?: return false
        if (battery.operator == BoseBmap.OP_ERROR) {
            listener.onLog("bose battery rejected")
            return false
        }
        var left: BatteryValue? = null
        var right: BatteryValue? = null
        var case: BatteryValue? = null
        var offset = 0
        while (offset + 3 < battery.payload.size) {
            val level = battery.payload[offset].toInt() and 0xff
            val component = battery.payload[offset + 3].toInt() and 0xff
            if (level <= 100) {
                when (component) {
                    1 -> right = BatteryValue(level)
                    2 -> left = BatteryValue(level)
                    3 -> case = BatteryValue(level)
                }
            }
            offset += 4
        }
        listener.onBatteryState(left, right, case)
        return left != null || right != null || case != null
    }

    /**
     * Switch listening mode. ColorOS "off" maps to Quiet with ANC cleared,
     * matching the verified Control implementation.
     */
    suspend fun setAncMode(mode: AncMode): Boolean {
        val (boseMode, anc) = when (mode) {
            AncMode.NOISE_CANCELING -> BoseDeviceConfig.MODE_QUIET to 1
            AncMode.TRANSPARENCY -> BoseDeviceConfig.MODE_AWARE to null
            AncMode.OFF -> BoseDeviceConfig.MODE_QUIET to 0
            else -> return false
        }
        val answer = command(BLOCK_AUDIO, FUNC_MODE, BoseBmap.OP_START, byteArrayOf(boseMode.toByte(), 0))
        if (answer == null || answer.operator == BoseBmap.OP_ERROR) {
            listener.onAncWriteResult(false, null, "mode START rejected")
            return false
        }
        if (anc != null && !writeSettings(mapOf(BoseDeviceConfig.SETTING_ANC to anc))) {
            listener.onAncWriteResult(false, null, "ANC byte rejected")
            return false
        }
        val state = EarbudsState(capabilities = BoseDeviceConfig.capabilities, ancMode = mode)
        listener.onAncWriteResult(true, state, "ok")
        return true
    }

    /** Merge new values into the live [31.10] settings; preserves untouched bytes. */
    suspend fun writeSettings(values: Map<Int, Int>): Boolean {
        val current = command(BLOCK_AUDIO, FUNC_SETTINGS, BoseBmap.OP_GET, null) ?: return false
        if (current.payload.size < 5) return false
        val payload = current.payload.copyOf(5)
        values.forEach { (index, value) ->
            if (index in payload.indices) payload[index] = value.coerceIn(0, 255).toByte()
        }
        val answer = command(BLOCK_AUDIO, FUNC_SETTINGS, BoseBmap.OP_SETGET, payload)
            ?: return false
        if (answer.operator == BoseBmap.OP_ERROR) {
            listener.onLog("bose settings rejected: ${BoseBmap.hex(answer.payload)}")
            return false
        }
        val confirmed = command(BLOCK_AUDIO, FUNC_SETTINGS, BoseBmap.OP_GET, null)
        if (confirmed != null && confirmed.payload.size >= 5) listener.onSettingsState(confirmed.payload)
        return true
    }

    companion object {
        private const val BLOCK_BATTERY = BoseBmap.BLOCK_BATTERY
        private const val FUNC_BATTERY = BoseBmap.FUNC_BATTERY
        private const val BLOCK_AUDIO = BoseBmap.BLOCK_AUDIO_MODES
        private const val FUNC_MODE = BoseBmap.FUNC_CURRENT_MODE
        private const val FUNC_SETTINGS = BoseBmap.FUNC_AUDIO_SETTINGS
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    init {
        // Route incoming bytes through the incremental frame parser into replies.
        scope.launch {
            transport.incomingFrames().collect { chunk ->
                parser.feed(chunk) { frame -> replies.trySend(frame) }
            }
        }
    }
}
