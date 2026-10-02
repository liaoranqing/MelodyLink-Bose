package com.melody.melodylink.vendor.samsung

import android.bluetooth.BluetoothDevice
import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.EarbudsState
import com.melody.melodylink.samsung.SamsungTransportAdapter

/** Samsung-specific facade kept separate until host hooks are generalized beyond Sony settings. */
class SamsungEarbudsFacade(listener: Listener) {
    interface Listener {
        fun onConnecting()
        fun onConnected(state: EarbudsState)
        fun onBatteryState(state: EarbudsState)
        fun onAncWriteResult(success: Boolean, state: EarbudsState?, reason: String)
        fun onDisconnected()
        fun onFailed(reason: String)
        fun onLog(message: String)
    }

    private val transport = SamsungTransportAdapter(object : SamsungTransportAdapter.Listener {
        override fun onConnecting() = listener.onConnecting()
        override fun onConnected(state: EarbudsState) = listener.onConnected(state)
        override fun onBatteryState(state: EarbudsState) = listener.onBatteryState(state)
        override fun onAncWriteResult(success: Boolean, state: EarbudsState?, reason: String) =
            listener.onAncWriteResult(success, state, reason)
        override fun onDisconnected() = listener.onDisconnected()
        override fun onFailed(reason: String) = listener.onFailed(reason)
        override fun onLog(message: String) = listener.onLog(message)
    })

    val isConnected: Boolean
        get() = transport.isConnected

    fun connect(device: BluetoothDevice) = transport.connect(device)
    fun disconnect() = transport.disconnect()
    fun setAncMode(mode: AncMode) = transport.setAncMode(mode)
    fun refreshBattery() = transport.refreshBattery()
    fun releaseResources() = transport.releaseResources()
}
