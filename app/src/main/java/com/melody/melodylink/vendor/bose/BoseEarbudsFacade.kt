package com.melody.melodylink.vendor.bose

import android.bluetooth.BluetoothDevice
import com.melody.melodylink.bose.BoseDeviceConfig
import com.melody.melodylink.bose.BoseTransportAdapter
import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.BatteryValue
import com.melody.melodylink.domain.DeviceCatalog
import com.melody.melodylink.domain.EarbudsState
import com.melody.melodylink.earbuds.EarbudsFacade
import com.melody.melodylink.sony.config.SonyAdvancedSettingId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Exposes the Bose BMAP session through the shared MelodyLink facade surface. */
class BoseEarbudsFacade(
    private val listener: EarbudsFacade.Listener,
) : EarbudsFacade {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val transport = BoseTransportAdapter(object : BoseTransportAdapter.Listener {
        override fun onConnecting() = listener.onConnecting()
        override fun onConnected(state: EarbudsState) = listener.onConnected(state)
        override fun onBatteryState(left: BatteryValue?, right: BatteryValue?, case: BatteryValue?) {
            val values = buildMap {
                left?.let { put(BatteryPart.LEFT, it) }
                right?.let { put(BatteryPart.RIGHT, it) }
                case?.let { put(BatteryPart.CASE, it) }
            }
            listener.onBatteryState(
                EarbudsState(capabilities = BoseDeviceConfig.capabilities, battery = values),
            )
        }
        override fun onSettingsState(settings: ByteArray) {
            // Publish the live ANC bit through the generic setting-state channel.
            listener.onLog(
                "bose settings " + settings.joinToString(" ") { (it.toInt() and 0xff).toString(16) },
            )
        }
        override fun onAncWriteResult(success: Boolean, state: EarbudsState?, reason: String) =
            listener.onAncWriteResult(success, state, reason)
        override fun onDisconnected() = listener.onDisconnected()
        override fun onFailed(reason: String) = listener.onFailed(reason)
        override fun onLog(message: String) = listener.onLog(message)
    })

    override val isConnected: Boolean get() = transport.isConnected

    override fun setCatalog(catalog: DeviceCatalog) = Unit

    override fun isRegisteredDevice(bluetoothName: String?): Boolean =
        BoseDeviceConfig.matches(bluetoothName)

    override fun connect(device: BluetoothDevice) {
        scope.launch {
            if (transport.connect(device)) transport.readState()
        }
    }

    override fun disconnect() {
        scope.launch { transport.disconnect() }
    }

    override fun setAncMode(mode: AncMode) {
        scope.launch { transport.setAncMode(mode) }
    }

    override fun refreshBattery() {
        scope.launch { transport.refreshBattery() }
    }

    override fun readSetting(id: SonyAdvancedSettingId) = Unit

    override fun writeSetting(id: SonyAdvancedSettingId, value: Boolean) {
        // Bose has no Sony-specific advanced settings; ignore politely.
    }
}
