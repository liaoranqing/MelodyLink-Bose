package com.melody.melodylink.samsung

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.BatteryValue
import com.melody.melodylink.domain.EarbudsCapabilities
import com.melody.melodylink.domain.EarbudsState
import com.melody.melodylink.samsung.config.SamsungGalaxyBudsCatalog
import com.melody.melodylink.samsung.config.SamsungGalaxyBudsModel
import com.melody.melodylink.transport.EarbudsTransport
import com.melody.melodylink.transport.RfcommTransport
import com.melody.melodylink.transport.TransportEndpoint
import com.op.bttest.samsung.GalaxyBudsCommands
import com.op.bttest.samsung.GalaxyBudsCoreState
import com.op.bttest.samsung.GalaxyBudsFrame
import com.op.bttest.samsung.GalaxyBudsFrameAccumulator
import com.op.bttest.samsung.GalaxyBudsFrameCodec
import com.op.bttest.samsung.GalaxyBudsStatusParser
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** RFCOMM lifecycle and core state bridge for the Samsung Galaxy Buds protocol. */
class SamsungTransportAdapter(
    private val listener: Listener,
    private val transportFactory: () -> EarbudsTransport = ::RfcommTransport,
) {
    interface Listener {
        fun onConnecting()
        fun onConnected(state: EarbudsState)
        fun onBatteryState(state: EarbudsState)
        fun onAncWriteResult(success: Boolean, state: EarbudsState?, reason: String)
        fun onDisconnected()
        fun onFailed(reason: String)
        fun onLog(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private val writeMutex = Mutex()
    private var activeJob: Job? = null
    private var transport: EarbudsTransport? = null
    private var model: SamsungGalaxyBudsModel? = null
    private var accumulator: GalaxyBudsFrameAccumulator? = null
    private var currentState: EarbudsState? = null
    private var managerInfoSent = false
    private var pendingAnc: AncMode? = null

    @Volatile
    var isConnected: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(device: BluetoothDevice) {
        val identity = runCatching {
            com.melody.melodylink.domain.DeviceIdentity(
                bluetoothName = device.name,
                address = device.address,
                serviceUuids = device.uuids?.map { it.uuid.toString() }?.toSet().orEmpty(),
            )
        }.getOrElse {
            listener.onFailed("Samsung Bluetooth identity is unavailable")
            return
        }
        val resolved = SamsungGalaxyBudsCatalog.find(identity)?.model ?: run {
            listener.onFailed("Samsung Galaxy Buds device is not recognized")
            return
        }
        val request = generation.incrementAndGet()
        activeJob?.cancel()
        activeJob = scope.launch {
            resetSession()
            model = resolved
            accumulator = GalaxyBudsFrameAccumulator(resolved)
            listener.onConnecting()
            val candidate = transportFactory()
            transport = candidate
            try {
                candidate.connect(TransportEndpoint.Rfcomm(device, resolved.serviceUuid)).getOrThrow()
                if (request != generation.get()) return@launch
                isConnected = true
                send(GalaxyBudsCommands.initialStatusRequest(), request)
                candidate.incomingFrames().collect { chunk ->
                    if (request == generation.get()) {
                        accumulator?.append(chunk)?.forEach { frame -> handleFrame(frame, request) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (request == generation.get()) {
                    listener.onFailed("Samsung RFCOMM failed: ${error.javaClass.simpleName}")
                }
            } finally {
                if (request == generation.get()) release(notify = isConnected)
            }
        }
    }

    @Synchronized
    fun disconnect() {
        generation.incrementAndGet()
        activeJob?.cancel()
        scope.launch { release(notify = isConnected) }
    }

    fun setAncMode(mode: AncMode) {
        val selected = model
        if (!isConnected || selected == null) {
            listener.onAncWriteResult(false, null, "Samsung ANC write requested while disconnected")
            return
        }
        val command = GalaxyBudsCommands.setAncMode(selected, mode)
        if (command == null) {
            listener.onAncWriteResult(false, null, "Samsung ANC mode is unsupported by ${selected.displayName}")
            return
        }
        val request = generation.get()
        scope.launch {
            pendingAnc = mode
            try {
                send(command, request)
            } catch (error: Throwable) {
                if (request == generation.get()) {
                    pendingAnc = null
                    listener.onAncWriteResult(false, null, "Samsung ANC command failed")
                }
            }
        }
    }

    fun refreshBattery() {
        val request = generation.get()
        scope.launch {
            try {
                send(GalaxyBudsCommands.initialStatusRequest(), request)
            } catch (error: Throwable) {
                listener.onLog("Samsung battery refresh failed: ${error.javaClass.simpleName}")
            }
        }
    }

    fun releaseResources() {
        disconnect()
        scope.cancel()
    }

    private suspend fun send(frame: GalaxyBudsFrame, request: Long) {
        if (request != generation.get()) return
        val selected = model ?: error("Samsung model unavailable")
        val active = transport ?: error("Samsung transport unavailable")
        writeMutex.withLock {
            if (request != generation.get()) return
            active.send(GalaxyBudsFrameCodec.encode(selected, frame)).getOrThrow()
        }
    }

    private suspend fun handleFrame(frame: GalaxyBudsFrame, request: Long) {
        val selected = model ?: return
        val parsed = GalaxyBudsStatusParser.parse(selected, frame) ?: return
        if (frame.messageId == com.op.bttest.samsung.GalaxyBudsMessageId.EXTENDED_STATUS_UPDATED && !managerInfoSent) {
            managerInfoSent = true
            send(GalaxyBudsCommands.managerInfo(), request)
        }
        publish(parsed)
    }

    private fun publish(parsed: GalaxyBudsCoreState) {
        val selected = model ?: return
        val previous = currentState
        val state = EarbudsState(
            capabilities = EarbudsCapabilities(
                ancModes = selected.ancModes,
                batteryParts = selected.batteryParts,
            ),
            ancMode = parsed.ancMode ?: previous?.ancMode,
            battery = if (parsed.battery.isEmpty()) previous?.battery.orEmpty() else parsed.battery,
        )
        currentState = state
        if (!isConnected) return
        if (parsed.battery.isNotEmpty()) listener.onBatteryState(state)
        if (previous == null) listener.onConnected(state)
        val expected = pendingAnc
        if (expected != null && parsed.ancMode == expected) {
            pendingAnc = null
            listener.onAncWriteResult(true, state, "")
        }
    }

    private suspend fun release(notify: Boolean) {
        val active = transport
        transport = null
        runCatching { active?.close() }
        (active as? RfcommTransport)?.release()
        val wasConnected = isConnected
        isConnected = false
        model = null
        accumulator = null
        currentState = null
        pendingAnc = null
        managerInfoSent = false
        if (notify || wasConnected) listener.onDisconnected()
    }

    private fun resetSession() {
        isConnected = false
        currentState = null
        managerInfoSent = false
        pendingAnc = null
    }
}
