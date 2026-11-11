package io.github.chenfish666.podspanel

import io.github.chenfish666.podspanel.core.AapProtocol
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

    val scan = MutableStateFlow(ScanInfo())

    /** Noise control mode, or null while the direct connection is down or the model has none. */
    val listeningMode = MutableStateFlow<AapProtocol.ListeningMode?>(null)

    /**
     * True when AirPods are connected but this phone could not open the direct connection to
     * them, which is what noise control and exact levels depend on.
     */
    val directUnavailable = MutableStateFlow(false)

    /** What the scanner has seen so far; shown in the app so a problem can be told apart. */
    data class ScanInfo(
        val level: String = "",
        val fast: Boolean = false,
        /** Broadcasts delivered by the system, of any kind that passed the chip's filter. */
        val results: Long = 0,
        val appleResults: Long = 0,
        val statusFrames: Long = 0,
        /** Status broadcasts taken as coming from the user's own pair. */
        val accepted: Long = 0,
        val tooWeak: Long = 0,
        /** Status broadcasts dropped because they are of another model than the user's own pair. */
        val otherPairs: Long = 0,
        val strongestRssi: Int? = null,
        val lastFrameHex: String? = null,
        val lastFrameAgeSeconds: Long? = null,
        /** Breakdown of what was received, only for the copied report. */
        val detail: String = "",
        /** State of the direct connection to the AirPods. */
        val direct: String = "",
    ) {
        fun describe(): String = buildString {
            append("掃描方式：").append(level.ifEmpty { "未啟動" }).append(if (fast) "・快速" else "・省電")
            append("\n收到廣播 ").append(results)
            append("・Apple 裝置 ").append(appleResults)
            append("・耳機狀態 ").append(statusFrames)
            append("\n採用 ").append(accepted).append("・訊號太弱略過 ").append(tooWeak).append("・別人的耳機 ").append(otherPairs)
            append("・最強訊號 ").append(strongestRssi?.let { "$it dBm" } ?: "—")
            append("\n最近一筆：")
            append(lastFrameHex?.let { "$it（$lastFrameAgeSeconds 秒前）" } ?: "尚未收到")
            append("\n直接連線：").append(direct.ifEmpty { "未嘗試" })
        }
    }

    data class EqState(
        val active: Boolean = false,
        /** Shown under the switch: which effect is in use, or why none could be created. */
        val detail: String = "",
        val failed: Boolean = false,
    )
}
