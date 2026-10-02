package com.melody.melodylink.xiaomi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.EarbudsCapabilities
import com.melody.melodylink.domain.EarbudsState
import com.melody.melodylink.xiaomi.config.XiaomiDeviceCatalog
import com.melody.melodylink.xiaomi.config.XiaomiDeviceConfig
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class XiaomiTransportAdapter(
    private val context: Context,
    private val listener: Listener,
    private val clientFactory: (String) -> XiaomiSppClient = { XiaomiSppClient(listener::onLog) },
) {
    interface Listener {
        fun onConnecting()
        fun onConnected(state: EarbudsState)
        fun onStateChanged(state: EarbudsState)
        fun onBatteryState(state: EarbudsState)
        fun onAncWriteResult(success: Boolean, state: EarbudsState?, reason: String)
        fun onDisconnected()
        fun onFailed(reason: String)
        fun onLog(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private var job: Job? = null
    private var client: XiaomiSppClient? = null
    private var route: XiaomiDeviceConfig? = null
    private var decoder = XiaomiRcspStreamDecoder()
    private var targetInfoVerified = false
    private var currentState: EarbudsState? = null
    private var pending: CompletableDeferred<XiaomiRcspFrame>? = null
    private var pendingOpcode: Int? = null
    private var pendingSequence: Int? = null
    private var nextSequence = 0

    @Volatile var isConnected = false
        private set

    @SuppressLint("MissingPermission")
    @Synchronized fun connect(device: BluetoothDevice) {
        val selected = XiaomiDeviceCatalog.find(com.melody.melodylink.domain.DeviceIdentity(bluetoothName = device.name))?.route
            ?: run { listener.onFailed("Xiaomi device is not registered"); return }
        val request = generation.incrementAndGet()
        job?.cancel()
        job = scope.launch {
            release(false)
            route = selected; targetInfoVerified = false; currentState = null; decoder = XiaomiRcspStreamDecoder(); nextSequence = 0
            listener.onConnecting()
            val active = clientFactory(device.address); client = active
            try {
                active.connect(device).getOrThrow()
                if (request != generation.get()) return@launch
                coroutineScope {
                    val reader = launch(start = CoroutineStart.UNDISPATCHED) {
                        active.notifications.collect { chunk -> onIncoming(chunk, request) }
                    }
                    // mibudstest sends RCSP directly after the RFCOMM socket opens.  Xiaomi's
                    // AF00 authentication exchange belongs to its BLE characteristic channel
                    // and must not be injected into the SPP byte stream.
                    isConnected = true
                    listener.onLog("Xiaomi SPP RCSP channel ready")
                    readInitialState(request)
                    reader.join()
                }
            } catch (error: Throwable) {
                if (request == generation.get()) listener.onFailed("Xiaomi SPP session failed: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                if (request == generation.get()) release(isConnected)
            }
        }
    }

    fun disconnect() { generation.incrementAndGet(); job?.cancel(); scope.launch { release(isConnected) } }
    fun refreshBattery() = Unit // Xiaomi sends battery through +XIAOMI vendor events.

    fun acceptVendorBatteryEvent(value: String) {
        acceptVendorBatteryEvent(listOf(value))
    }

    fun acceptVendorBatteryEvent(values: Collection<String>) {
        val selected = route ?: return
        val battery = XiaomiBatteryParser.parseVendorEvent(values)
        if (battery.isEmpty()) return
        val state = (currentState ?: EarbudsState(capabilities(selected))).copy(battery = battery)
        currentState = state
        listener.onBatteryState(state)
    }

    fun setAncMode(mode: AncMode) {
        val selected = route
        val active = client
        if (!isConnected || !targetInfoVerified || selected == null || active == null) {
            listener.onAncWriteResult(false, null, "Xiaomi TargetInfo ANC session is not ready")
            return
        }
        scope.launch {
            val response = request(active, 0x08, XiaomiRcspCodec.setTargetInfoAnc(nextSequence(), mode))
            if (response?.status == 0 && response.payload.isEmpty()) {
                val state = (currentState ?: EarbudsState(capabilities(selected))).copy(ancMode = mode)
                currentState = state
                listener.onAncWriteResult(true, state, "")
            } else listener.onAncWriteResult(false, null, "Xiaomi TargetInfo ANC command was rejected or timed out")
        }
    }

    private suspend fun onIncoming(chunk: ByteArray, request: Long) {
        decoder.accept(chunk).forEach { frame ->
            listener.onLog("Xiaomi SPP RX frame opcode=0x${frame.opcode.toString(16).padStart(2, '0')}"
                + " control=0x${frame.control.toString(16).padStart(2, '0')}"
                + " parameterBytes=${frame.parameter.size}")
            acceptTargetInfoStatus(frame)
            if (!frame.isCommand && frame.opcode == pendingOpcode && frame.sequence == pendingSequence) pending?.complete(frame)
        }
    }

    private suspend fun readInitialState(request: Long) {
        val active = client ?: return
        val targetInfo = request(active, 0x02, XiaomiRcspCodec.getTargetInfo(
            nextSequence(), XiaomiRcspCodec.SPP_TARGET_APP
        ))
        targetInfoVerified = targetInfo?.status == 0
        targetInfo?.let(::acceptTargetInfoStatus)
        if (!targetInfoVerified) listener.onLog("Xiaomi TargetInfo read was not confirmed")
        if (request != generation.get()) return
        val selected = route ?: return
        val state = EarbudsState(capabilities(selected), ancMode = currentState?.ancMode,
            battery = currentState?.battery.orEmpty())
        currentState = state
        listener.onConnected(state)
    }

    private suspend fun request(active: XiaomiSppClient, opcode: Int, bytes: ByteArray): XiaomiRcspFrame? {
        val sequence = bytes[7].toInt() and 0xFF
        val completion = CompletableDeferred<XiaomiRcspFrame>()
        pending = completion; pendingOpcode = opcode; pendingSequence = sequence
        try {
            listener.onLog("Xiaomi SPP TX frame opcode=0x${opcode.toString(16).padStart(2, '0')}"
                + " sequence=$sequence bytes=${bytes.size}")
            active.write(bytes).getOrThrow()
            return withTimeoutOrNull(500L) { completion.await() }.also {
                if (it == null) listener.onLog("Xiaomi SPP response timeout opcode=0x${opcode.toString(16).padStart(2, '0')} sequence=$sequence")
            }
        } finally {
            pending = null; pendingOpcode = null; pendingSequence = null
        }
    }

    private fun nextSequence(): Int = nextSequence++ and 0xFF
    private fun capabilities(config: XiaomiDeviceConfig) = EarbudsCapabilities(
        ancModes = if (targetInfoVerified) setOf(AncMode.OFF, AncMode.NOISE_CANCELING, AncMode.TRANSPARENCY) else emptySet(),
        batteryParts = config.batteryParts,
    )

    private fun acceptTargetInfoStatus(frame: XiaomiRcspFrame) {
        val update = XiaomiTargetInfoStatusParser.parse(frame) ?: return
        val selected = route ?: return
        val state = (currentState ?: EarbudsState(capabilities(selected))).copy(
            ancMode = update.ancMode ?: currentState?.ancMode,
            battery = update.battery ?: currentState?.battery.orEmpty(),
        )
        currentState = state
        listener.onStateChanged(state)
        if (update.battery != null) {
            listener.onLog("Xiaomi TargetInfo SPP battery state received")
            listener.onBatteryState(state)
        }
    }

    private suspend fun release(notify: Boolean) {
        client?.close(); client = null; route = null; targetInfoVerified = false; currentState = null; isConnected = false
        if (notify) listener.onDisconnected()
    }

    fun releaseResources() { disconnect(); scope.cancel() }
}
