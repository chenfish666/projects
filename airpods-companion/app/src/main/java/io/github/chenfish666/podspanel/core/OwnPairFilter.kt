package io.github.chenfish666.podspanel.core

/**
 * Keeps other people's AirPods out.
 *
 * Status broadcasts do not say whose AirPods they come from, and on a train or in a café
 * several pairs are in range. Two things are known about the user's own pair:
 *
 *  - its model, learnt from a broadcast that arrives strongly while AirPods are connected to
 *    this phone, which in practice only the pair being worn does;
 *  - that while it is not connected, it is only of interest right next to the phone, i.e. when
 *    its case is opened there.
 *
 * Broadcasts of other models are dropped, and so is everything that is not close by while no
 * AirPods are connected. A stranger's pair of the same model can still get through while the
 * user's own is connected; telling those two apart is left to signal strength, in [PodsTracker].
 *
 * @param model model code learnt earlier, or -1 when none has been learnt yet
 */
class OwnPairFilter(
    model: Int = -1,
    private val learnRssi: Int = -60,
    private val nearRssi: Int = -60,
    private val unlearntRssi: Int = -72,
    private val relearnAfterMs: Long = 10_000,
) {

    var model: Int = model
        private set

    private var ownSeenAtMs: Long? = null

    /**
     * @param connected whether AirPods are connected to this phone right now
     * @return true when the broadcast may be the user's own pair
     */
    fun accept(modelCode: Int, rssi: Int, connected: Boolean, nowMs: Long): Boolean {
        if (modelCode == model) {
            if (!connected && rssi < nearRssi) return false
            ownSeenAtMs = nowMs
            return true
        }
        // A different model right next to the phone while AirPods are connected, and the known
        // one has gone quiet: the user has most likely changed AirPods.
        val ownQuiet = ownSeenAtMs?.let { nowMs - it > relearnAfterMs } ?: true
        if (connected && rssi >= learnRssi && ownQuiet) {
            model = modelCode
            ownSeenAtMs = nowMs
            return true
        }
        // Nothing learnt yet: go by distance, and only while AirPods are connected at all.
        return model == -1 && connected && rssi >= unlearntRssi
    }
}
