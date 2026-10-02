package com.melody.melodylink.vendor.xiaomi

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.EarbudsState
import com.melody.melodylink.xiaomi.XiaomiTransportAdapter

class XiaomiEarbudsFacade(context: Context, listener: Listener) {
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

    private val transport = XiaomiTransportAdapter(context, object : XiaomiTransportAdapter.Listener {
        override fun onConnecting() = listener.onConnecting()
        override fun onConnected(state: EarbudsState) = listener.onConnected(state)
        override fun onStateChanged(state: EarbudsState) = listener.onStateChanged(state)
        override fun onBatteryState(state: EarbudsState) = listener.onBatteryState(state)
        override fun onAncWriteResult(success: Boolean, state: EarbudsState?, reason: String) = listener.onAncWriteResult(success, state, reason)
        override fun onDisconnected() = listener.onDisconnected()
        override fun onFailed(reason: String) = listener.onFailed(reason)
        override fun onLog(message: String) = listener.onLog(message)
    })

    val isConnected get() = transport.isConnected
    fun connect(device: BluetoothDevice) = transport.connect(device)
    fun disconnect() = transport.disconnect()
    fun setAncMode(mode: AncMode) = transport.setAncMode(mode)
    fun refreshBattery() = transport.refreshBattery()
    fun acceptVendorBatteryEvent(value: String) = transport.acceptVendorBatteryEvent(value)
    fun acceptVendorBatteryEvent(values: Collection<String>) = transport.acceptVendorBatteryEvent(values)
    fun releaseResources() = transport.releaseResources()
}
