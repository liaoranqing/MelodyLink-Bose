package com.melody.melodylink.huawei

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.EarbudsCapabilities
import com.melody.melodylink.domain.EarbudsState
import com.melody.melodylink.huawei.config.HuaweiDeviceCatalog
import com.melody.melodylink.huawei.config.HuaweiDeviceConfig
import com.melody.melodylink.transport.EarbudsTransport
import com.melody.melodylink.transport.RfcommTransport
import com.melody.melodylink.transport.TransportEndpoint
import com.op.bttest.huawei.HuaweiCommands
import com.op.bttest.huawei.HuaweiStatusParser
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** RFCOMM lifecycle bridge for HuaweiPods' verified Huawei 0x5A protocol. */
class HuaweiTransportAdapter(
    private val listener: Listener,
    private val transportFactory: () -> EarbudsTransport = ::RfcommTransport,
) {
    interface Listener {
        fun onConnecting()
        fun onConnected(state: EarbudsState)
        fun onBatteryState(state: EarbudsState)
        fun onAncWriteResult(success: Boolean, state: EarbudsState?, reason: String)
        fun onLowLatencyWriteResult(success: Boolean, enabled: Boolean?, reason: String)
        fun onDisconnected()
        fun onFailed(reason: String)
        fun onLog(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private var job: Job? = null
    private var transport: EarbudsTransport? = null
    private var route: HuaweiDeviceConfig? = null
    private var currentState: EarbudsState? = null
    private var pendingAnc: AncMode? = null

    @Volatile var isConnected: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(device: BluetoothDevice) {
        val identity = runCatching {
            com.melody.melodylink.domain.DeviceIdentity(
                bluetoothName = device.name,
                address = device.address,
                serviceUuids = device.uuids?.map { it.uuid.toString().lowercase() }?.toSet().orEmpty(),
            )
        }.getOrElse { listener.onFailed("Huawei Bluetooth identity is unavailable"); return }
        val selected = HuaweiDeviceCatalog.find(identity)?.route
            ?: run { listener.onFailed("Huawei device is not recognized"); return }
        val request = generation.incrementAndGet()
        job?.cancel()
        job = scope.launch {
            release(notify = false)
            route = selected
            currentState = null
            pendingAnc = null
            listener.onConnecting()
            val active = transportFactory()
            transport = active
            try {
                active.connect(TransportEndpoint.Rfcomm(device, com.melody.melodylink.huawei.config.HuaweiUuids.spp)).getOrThrow()
                if (request != generation.get()) return@launch
                isConnected = true
                HuaweiCommands.batteryQuery(selected)?.let { active.send(it) }
                HuaweiCommands.stateQuery(selected)?.let { active.send(it) }
                active.incomingFrames().collect { chunk ->
                    if (request != generation.get()) return@collect
                    val parsed = HuaweiStatusParser.parse(chunk, selected) ?: return@collect
                    publish(parsed.toState(selected))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) {
                if (request == generation.get()) listener.onFailed("Huawei RFCOMM failed: ${error.javaClass.simpleName}")
            } finally { if (request == generation.get()) release(notify = isConnected) }
        }
    }

    @Synchronized fun disconnect() { generation.incrementAndGet(); job?.cancel(); scope.launch { release(notify = isConnected) } }

    fun setAncMode(mode: AncMode) {
        val selected = route
        val active = transport
        if (!isConnected || selected == null || active == null) { listener.onAncWriteResult(false, null, "Huawei ANC requested while disconnected"); return }
        if (mode !in selected.toProfileCapabilities().ancModes) { listener.onAncWriteResult(false, null, "Huawei ANC mode unsupported by ${selected.name}"); return }
        val request = generation.get(); pendingAnc = mode
        scope.launch {
            val packet = HuaweiCommands.setAnc(selected, mode)
            if (packet == null || active.send(packet).isFailure) {
                if (request == generation.get()) { pendingAnc = null; listener.onAncWriteResult(false, null, "Huawei ANC command failed") }
            } else if (!selected.supportsAncReadback && request == generation.get()) {
                val state = (currentState ?: EarbudsState(selected.toProfileCapabilities())).copy(ancMode = mode)
                pendingAnc = null; publish(state); listener.onAncWriteResult(true, state, "")
            }
        }
    }

    fun setLowLatency(enabled: Boolean) {
        val selected = route
        val active = transport
        if (!isConnected || selected == null || active == null) {
            listener.onLowLatencyWriteResult(false, null, "Huawei low-latency requested while disconnected")
            return
        }
        val packet = HuaweiCommands.setLowLatency(selected, enabled)
        if (packet == null) {
            listener.onLowLatencyWriteResult(false, null, "Huawei low-latency is unsupported by ${selected.name}")
            return
        }
        val request = generation.get()
        scope.launch {
            if (active.send(packet).isSuccess && request == generation.get()) {
                listener.onLowLatencyWriteResult(true, enabled, "")
            } else if (request == generation.get()) {
                listener.onLowLatencyWriteResult(false, null, "Huawei low-latency command failed")
            }
        }
    }

    fun refreshBattery() {
        val selected = route ?: return
        val active = transport ?: return
        if (!isConnected) return
        scope.launch { HuaweiCommands.batteryQuery(selected)?.let { active.send(it) } }
    }

    fun releaseResources() { disconnect() }

    private suspend fun release(notify: Boolean) {
        val active = transport; transport = null; runCatching { active?.close() }
        isConnected = false; route = null; currentState = null; pendingAnc = null
        if (notify) listener.onDisconnected()
    }

    private fun publish(state: EarbudsState) {
        val previous = currentState; currentState = state
        val expected = pendingAnc
        if (state.battery.isNotEmpty()) listener.onBatteryState(state)
        if (previous == null) listener.onConnected(state)
        if (expected != null && state.ancMode == expected) { pendingAnc = null; listener.onAncWriteResult(true, state, "") }
    }

    private fun com.op.bttest.huawei.HuaweiParsedState.toState(route: HuaweiDeviceConfig) = EarbudsState(
        capabilities = route.toProfileCapabilities(),
        ancMode = ancMode ?: currentState?.ancMode,
        battery = if (battery.isEmpty()) currentState?.battery.orEmpty() else battery,
    )

    private fun HuaweiDeviceConfig.toProfileCapabilities() = EarbudsCapabilities(
        ancModes = buildSet { add(AncMode.OFF); if (supportsAnc) add(AncMode.NOISE_CANCELING); if (supportsTransparency) add(AncMode.TRANSPARENCY) },
        batteryParts = batteryParts,
    )
}
