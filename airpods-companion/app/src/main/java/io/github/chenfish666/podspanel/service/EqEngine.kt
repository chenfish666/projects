package io.github.chenfish666.podspanel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.chenfish666.podspanel.AppState
import io.github.chenfish666.podspanel.core.EqMath
import io.github.chenfish666.podspanel.core.gainAt

/**
 * Applies the equalizer curve to audio played on this phone.
 *
 * AirPods have no equalizer of their own that a phone could set (on an iPhone, too, the EQ runs
 * on the phone), so the curve is applied to the audio before it is sent to the headphones.
 *
 * Two ways of attaching, because no single one works on every phone:
 *  - global: one effect on the system output mix (audio session 0). Covers every app, but some
 *    manufacturers block it or route around it.
 *  - per session: one effect for each player that announces its audio session, which most
 *    music apps do. Players that were already running have to be restarted to be picked up.
 */
class EqEngine(private val context: Context) {

    private interface Band {
        fun setGains(gains: FloatArray)
        fun release()
    }

    private val effects = HashMap<Int, Band>()
    private val announcedSessions = LinkedHashSet<Int>()
    private var enabled = false
    private var global = true
    private var gains = FloatArray(EqMath.BAND_COUNT)
    private var receiverRegistered = false

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
            if (session <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    announcedSessions += session
                    if (enabled && !global) attach(session)
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    announcedSessions -= session
                    effects.remove(session)?.release()
                    publish()
                }
            }
        }
    }

    /** Players announce sessions whether or not the EQ is on, so listen from the start. */
    fun startListening() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(context, sessionReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    fun update(enabled: Boolean, global: Boolean, gains: FloatArray) {
        val modeChanged = this.enabled != enabled || this.global != global
        this.enabled = enabled
        this.global = global
        this.gains = gains.copyOf()

        if (modeChanged) {
            releaseEffects()
            if (enabled) {
                if (global) attach(GLOBAL_SESSION) else announcedSessions.toList().forEach(::attach)
            }
        } else if (enabled) {
            effects.values.forEach { it.setGains(this.gains) }
        }
        publish()
    }

    fun release() {
        releaseEffects()
        if (receiverRegistered) {
            context.unregisterReceiver(sessionReceiver)
            receiverRegistered = false
        }
        AppState.eq.value = AppState.EqState()
    }

    private fun releaseEffects() {
        effects.values.forEach { it.release() }
        effects.clear()
        lastFailure = null
    }

    private var lastFailure: String? = null

    private fun attach(session: Int) {
        if (effects.containsKey(session)) return
        val band = createDynamics(session) ?: createLegacy(session)
        if (band == null) {
            lastFailure = if (session == GLOBAL_SESSION) {
                "這支手機不允許全域等化器，請改用「逐一套用」模式"
            } else {
                "無法在播放器上建立等化器"
            }
        } else {
            lastFailure = null
            band.setGains(gains)
            effects[session] = band
        }
        publish()
    }

    private fun createDynamics(session: Int): Band? = try {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, EqMath.BAND_COUNT, // pre-EQ: the ten bands
            false, 0,                // no multiband compressor
            false, 0,                // no post-EQ
            false,                   // no limiter; the preamp keeps peaks below full scale
        ).build()
        val effect = DynamicsProcessing(PRIORITY, session, config)
        object : Band {
            override fun setGains(gains: FloatArray) {
                val eq = DynamicsProcessing.Eq(true, true, EqMath.BAND_COUNT)
                for (i in 0 until EqMath.BAND_COUNT) {
                    eq.setBand(i, DynamicsProcessing.EqBand(true, EqMath.CUTOFFS[i], gains[i]))
                }
                effect.setPreEqAllChannelsTo(eq)
                effect.setInputGainAllChannelsTo(EqMath.preampDb(gains))
                effect.enabled = true
            }

            override fun release() = effect.release()
        }.also { Log.i(TAG, "DynamicsProcessing attached to session $session") }
    } catch (e: RuntimeException) {
        Log.w(TAG, "DynamicsProcessing unavailable for session $session: ${e.message}")
        null
    }

    /** The older fixed-band equalizer, for phones that refuse DynamicsProcessing. */
    private fun createLegacy(session: Int): Band? = try {
        val effect = Equalizer(PRIORITY, session)
        val range = effect.bandLevelRange
        object : Band {
            override fun setGains(gains: FloatArray) {
                // No preamp here, so shift the whole curve down instead to keep headroom.
                val offset = EqMath.preampDb(gains)
                for (band in 0 until effect.numberOfBands) {
                    val hz = effect.getCenterFreq(band.toShort()) / 1000f
                    val millibel = ((EqMath.gainAt(hz, gains) + offset) * 100).toInt()
                    effect.setBandLevel(
                        band.toShort(),
                        millibel.coerceIn(range[0].toInt(), range[1].toInt()).toShort(),
                    )
                }
                effect.enabled = true
            }

            override fun release() = effect.release()
        }.also { Log.i(TAG, "Legacy equalizer attached to session $session") }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Legacy equalizer unavailable for session $session: ${e.message}")
        null
    }

    private fun publish() {
        val failure = lastFailure
        AppState.eq.value = when {
            !enabled -> AppState.EqState()
            failure != null && effects.isEmpty() -> AppState.EqState(detail = failure, failed = true)
            global -> AppState.EqState(active = effects.isNotEmpty(), detail = "已套用到所有聲音")
            effects.isEmpty() -> AppState.EqState(detail = "等待播放器…請重新開啟音樂 App 後播放")
            else -> AppState.EqState(active = true, detail = "已套用到 ${effects.size} 個播放器")
        }
    }

    private companion object {
        const val TAG = "EqEngine"
        const val GLOBAL_SESSION = 0
        const val CHANNELS = 2
        const val PRIORITY = 100
    }
}
