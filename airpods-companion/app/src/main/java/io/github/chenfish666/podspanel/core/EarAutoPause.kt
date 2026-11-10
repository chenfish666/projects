package io.github.chenfish666.podspanel.core

/**
 * Pauses playback when an earbud is taken out and resumes it when the earbud goes back in.
 *
 * In-ear readings come from radio broadcasts and occasionally flicker, so a change only counts
 * after [confirmations] identical readings in a row. Playback is resumed only if this class was
 * the one that paused it, and only within [resumeWindowMs]: putting the earbuds back in an hour
 * later should not start music by surprise.
 */
class EarAutoPause(
    private val confirmations: Int = 2,
    private val resumeWindowMs: Long = 120_000,
) {

    enum class Action { NONE, PAUSE, PLAY }

    private var stableCount = -1
    private var candidate = -1
    private var candidateSeen = 0
    private var countBeforePause = -1
    private var pausedAtMs = 0L

    /** @param wornCount number of earbuds currently in an ear, 0 to 2 */
    fun onReading(wornCount: Int, musicActive: Boolean, nowMs: Long): Action {
        if (wornCount == candidate) {
            candidateSeen++
        } else {
            candidate = wornCount
            candidateSeen = 1
        }
        if (candidateSeen < confirmations || candidate == stableCount) return Action.NONE

        val previous = stableCount
        stableCount = candidate
        if (previous == -1) return Action.NONE

        return when {
            stableCount < previous && musicActive -> {
                if (countBeforePause == -1) countBeforePause = previous
                pausedAtMs = nowMs
                Action.PAUSE
            }
            countBeforePause != -1 && stableCount >= countBeforePause -> {
                countBeforePause = -1
                if (musicActive || nowMs - pausedAtMs > resumeWindowMs) Action.NONE else Action.PLAY
            }
            else -> Action.NONE
        }
    }

    fun reset() {
        stableCount = -1
        candidate = -1
        candidateSeen = 0
        countBeforePause = -1
    }
}
