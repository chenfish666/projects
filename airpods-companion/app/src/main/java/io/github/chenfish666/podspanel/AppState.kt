package io.github.chenfish666.podspanel

import io.github.chenfish666.podspanel.core.PodsTracker
import kotlinx.coroutines.flow.MutableStateFlow

/** Live state published by the background service and read by the screen. */
object AppState {

    /** The followed AirPods, or null when none were seen recently. */
    val pods = MutableStateFlow<PodsTracker.Tracked?>(null)

    /** Name of the connected Bluetooth headphones, or null when none are connected. */
    val headphones = MutableStateFlow<String?>(null)

    val serviceRunning = MutableStateFlow(false)

    /** Why scanning does not work right now, or null when it does. */
    val scanProblem = MutableStateFlow<String?>(null)

    val eq = MutableStateFlow(EqState())

    val alarmRinging = MutableStateFlow(false)

    data class EqState(
        val active: Boolean = false,
        /** Shown under the switch: which effect is in use, or why none could be created. */
        val detail: String = "",
        val failed: Boolean = false,
    )
}
