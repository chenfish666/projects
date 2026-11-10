package io.github.chenfish666.podspanel.core

import kotlin.math.roundToInt

/**
 * Turns the stream of status broadcasts from every AirPods in range into one steady picture of
 * the user's own pair.
 *
 * Broadcasts carry no stable identity: the two earbuds send from different, rotating addresses.
 * So "own pair" is decided by signal strength, the same way the phone held next to an opening
 * case sees it far louder than a stranger's across the room. Once a pair is followed, a
 * broadcast of another model, or a much weaker one, is ignored until the followed pair has been
 * silent for [staleAfterMs].
 */
class PodsTracker(
    private val minRssi: Int = -72,
    private val staleAfterMs: Long = 12_000,
    private val forgetAfterMs: Long = 90_000,
    private val takeoverMargin: Int = 12,
) {

    data class Tracked(
        val status: PodsStatus,
        val rssi: Int,
        val seenAtMs: Long,
        /** False once no broadcast arrived for a while; levels shown are then the last known. */
        val live: Boolean,
    )

    private var current: PodsStatus? = null
    private var smoothedRssi = 0.0
    private var seenAtMs = 0L
    private var lastCase: Int? = null
    private var lastCaseCharging = false
    private var lastLid = LidState.UNKNOWN

    /**
     * Feeds one broadcast. Returns true when it was accepted as coming from the followed pair.
     */
    fun offer(status: PodsStatus, rssi: Int, nowMs: Long): Boolean {
        val followed = current
        val stale = followed == null || nowMs - seenAtMs > staleAfterMs

        val accept = when {
            stale -> rssi >= minRssi
            status.modelCode != followed!!.modelCode || status.colorCode != followed.colorCode ->
                // Someone else's pair has to be clearly closer to take over.
                rssi >= minRssi && rssi > smoothedRssi + takeoverMargin
            else -> rssi > smoothedRssi - takeoverMargin * 2
        }
        if (!accept) return false

        val sameDevice = followed != null &&
            followed.modelCode == status.modelCode && followed.colorCode == status.colorCode
        if (!sameDevice) {
            lastCase = null
            lastCaseCharging = false
            lastLid = LidState.UNKNOWN
            smoothedRssi = rssi.toDouble()
        } else {
            smoothedRssi = if (stale) rssi.toDouble() else smoothedRssi * 0.7 + rssi * 0.3
        }

        if (status.case != null) {
            lastCase = status.case
            lastCaseCharging = status.caseCharging
        }
        when (status.lid) {
            LidState.OPEN, LidState.CLOSED, LidState.NOT_IN_CASE -> lastLid = status.lid
            LidState.UNKNOWN -> Unit
        }

        current = status
        seenAtMs = nowMs
        return true
    }

    /** The followed pair, with the case level and lid carried over from earlier broadcasts. */
    fun snapshot(nowMs: Long): Tracked? {
        val status = current ?: return null
        val age = nowMs - seenAtMs
        if (age > forgetAfterMs) {
            reset()
            return null
        }
        val filled = status.copy(
            case = status.case ?: lastCase,
            caseCharging = if (status.case != null) status.caseCharging else lastCaseCharging,
            lid = if (status.lid == LidState.UNKNOWN) lastLid else status.lid,
        )
        return Tracked(filled, smoothedRssi.roundToInt(), seenAtMs, live = age <= staleAfterMs)
    }

    fun reset() {
        current = null
        lastCase = null
        lastCaseCharging = false
        lastLid = LidState.UNKNOWN
        seenAtMs = 0
    }
}
