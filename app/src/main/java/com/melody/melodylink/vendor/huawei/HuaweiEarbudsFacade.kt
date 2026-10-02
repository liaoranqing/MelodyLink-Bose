package com.melody.melodylink.vendor.huawei

import android.bluetooth.BluetoothDevice
import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.EarbudsState
import com.melody.melodylink.huawei.HuaweiTransportAdapter

class HuaweiEarbudsFacade(listener: Listener) {
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

    private val transport = HuaweiTransportAdapter(object : HuaweiTransportAdapter.Listener {
        override fun onConnecting() = listener.onConnecting()
        override fun onConnected(state: EarbudsState) = listener.onConnected(state)
        override fun onBatteryState(state: EarbudsState) = listener.onBatteryState(state)
        override fun onAncWriteResult(success: Boolean, state: EarbudsState?, reason: String) = listener.onAncWriteResult(success, state, reason)
        override fun onLowLatencyWriteResult(success: Boolean, enabled: Boolean?, reason: String) =
            listener.onLowLatencyWriteResult(success, enabled, reason)
        override fun onDisconnected() = listener.onDisconnected()
        override fun onFailed(reason: String) = listener.onFailed(reason)
        override fun onLog(message: String) = listener.onLog(message)
    })

    val isConnected: Boolean get() = transport.isConnected
    fun connect(device: BluetoothDevice) = transport.connect(device)
    fun disconnect() = transport.disconnect()
    fun setAncMode(mode: AncMode) = transport.setAncMode(mode)
    fun setLowLatency(enabled: Boolean) = transport.setLowLatency(enabled)
    fun refreshBattery() = transport.refreshBattery()
    fun releaseResources() = transport.releaseResources()
}
