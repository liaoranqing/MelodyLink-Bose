package com.melody.melodylink.hook

import com.melody.melodylink.domain.AncMode
import com.melody.melodylink.domain.BatteryPart
import com.melody.melodylink.domain.EarbudsState

/** Host-facing conversion kept independent from vendor protocol models. */
object MelodyStateBridge {
    fun ancModeIndex(state: EarbudsState?): Int = when (state?.ancMode) {
        AncMode.OFF -> 0
        AncMode.NOISE_CANCELING -> 1
        // Enco X3 whitelist exposes exactly three noise modes (index 0/1/2 →
        // tile type 1/5/2). TRANSPARENCY must be index 2; the old value 3 was
        // out of range and clamped back to 0, collapsing 通透 onto 关闭 so the
        // volume-panel tile only ever cycled two states.
        AncMode.AMBIENT_SOUND -> 2
        AncMode.TRANSPARENCY -> 2
        null -> -1
    }

    fun batteryPercent(state: EarbudsState?, part: BatteryPart): Int? =
        state?.battery?.get(part)?.percent
}
