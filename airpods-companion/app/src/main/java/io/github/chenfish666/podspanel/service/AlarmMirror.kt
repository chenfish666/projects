package io.github.chenfish666.podspanel.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.chenfish666.podspanel.AppState
import io.github.chenfish666.podspanel.Prefs
import io.github.chenfish666.podspanel.core.AlarmPolicy
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Makes the clock app's alarm audible in Bluetooth headphones.
 *
 * The system tells every app which kinds of sound are currently playing. When a sound of the
 * "alarm" kind starts, this class plays the alarm tone a second time as ordinary media, which
 * the system always sends to the headphones, and stops as soon as the real alarm stops. That
 * keeps it in step with whatever clock app is in use: dismissing or snoozing there silences the
 * headphones too.
 *
 * It cannot read which tone a particular alarm uses, so it plays the tone chosen in this app's
 * settings (by default the phone's default alarm tone).
 */
class AlarmMirror(
    private val context: Context,
    private val prefs: Prefs,
    private val headphones: () -> AudioDeviceInfo?,
    private val inEar: () -> Boolean?,
    private val onPlayingChanged: (Boolean) -> Unit,
) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var alarmPlaying = false
    private var alarmOnHeadphones = false
    private var mirroring = false
    private var player: MediaPlayer? = null
    private var tone: AudioTrack? = null
    private var testing = false

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            onConfigs(configs)
        }
    }

    // Clock apps restart their player between loops of the tone; without a short grace period
    // the mirror would stutter along with it.
    private val stopLater = Runnable {
        alarmPlaying = false
        AppState.alarmRinging.value = false
        evaluate()
    }

    fun start() {
        restoreVolumes()
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
        onConfigs(audio.activePlaybackConfigurations)
    }

    fun stop() {
        audio.unregisterAudioPlaybackCallback(playbackCallback)
        handler.removeCallbacksAndMessages(null)
        alarmPlaying = false
        testing = false
        AppState.alarmRinging.value = false
        stopMirror()
    }

    /** Call when headphones connect or disconnect, or a setting changes. */
    fun evaluate() {
        if (testing) return
        val decision = AlarmPolicy.decide(
            AlarmPolicy.Input(
                mode = prefs.alarmMode,
                alarmPlaying = alarmPlaying,
                headphonesConnected = headphones() != null,
                alarmAlreadyOnHeadphones = alarmOnHeadphones,
                inEar = inEar(),
            )
        )
        if (decision.mirror) startMirror(decision.quietSpeaker) else stopMirror()
    }

    /** Plays the alarm tone in the headphones for a few seconds so the user can check it. */
    fun test(): Boolean {
        if (headphones() == null) return false
        handler.removeCallbacks(endTest)
        testing = true
        startMirror(quietSpeaker = false)
        handler.postDelayed(endTest, TEST_DURATION_MS)
        return true
    }

    private val endTest = Runnable {
        testing = false
        stopMirror()
        evaluate()
    }

    private fun onConfigs(configs: List<AudioPlaybackConfiguration>) {
        val alarms = configs.filter { it.audioAttributes.usage == AudioAttributes.USAGE_ALARM }
        if (alarms.isEmpty()) {
            if (alarmPlaying) {
                handler.removeCallbacks(stopLater)
                handler.postDelayed(stopLater, STOP_GRACE_MS)
            }
            return
        }
        handler.removeCallbacks(stopLater)
        // Before Android 12 an app cannot see where another app's sound is routed.
        alarmOnHeadphones = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            alarms.any { it.audioDeviceInfo?.type in BLUETOOTH_OUTPUTS }
        if (!alarmPlaying) {
            alarmPlaying = true
            AppState.alarmRinging.value = true
            Log.i(TAG, "Alarm started, already on headphones: $alarmOnHeadphones")
        }
        evaluate()
    }

    private fun startMirror(quietSpeaker: Boolean) {
        if (quietSpeaker) quietenSpeaker()
        if (mirroring) return
        val device = headphones() ?: return
        mirroring = true
        onPlayingChanged(true)
        raiseMediaVolume()

        val uri = toneUri()
        if (uri == null || !startPlayer(uri, device)) startTone(device)
    }

    private fun stopMirror() {
        if (mirroring) {
            mirroring = false
            player?.runCatching { stop() }
            player?.release()
            player = null
            tone?.runCatching { stop() }
            tone?.release()
            tone = null
            onPlayingChanged(false)
        }
        restoreVolumes()
    }

    private fun toneUri(): Uri? {
        prefs.alarmRingtoneUri?.let { return Uri.parse(it) }
        return RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
    }

    private fun mediaAttributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun startPlayer(uri: Uri, device: AudioDeviceInfo): Boolean = try {
        val created = MediaPlayer()
        player = created
        created.setAudioAttributes(mediaAttributes())
        created.setDataSource(context, uri)
        created.setPreferredDevice(device)
        created.isLooping = true
        created.setOnPreparedListener { if (mirroring && player === it) it.start() }
        created.setOnErrorListener { failed, what, extra ->
            Log.w(TAG, "Alarm tone failed to play: $what/$extra")
            if (player === failed) {
                failed.release()
                player = null
                if (mirroring) startTone(device)
            }
            true
        }
        created.prepareAsync()
        true
    } catch (e: Exception) {
        Log.w(TAG, "Alarm tone could not be opened: ${e.message}")
        player?.release()
        player = null
        false
    }

    /** A plain beeping tone, for when the chosen alarm tone cannot be read. */
    private fun startTone(device: AudioDeviceInfo) {
        try {
            val samples = beepPattern()
            val track = AudioTrack.Builder()
                .setAudioAttributes(mediaAttributes())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            track.write(samples, 0, samples.size)
            track.setLoopPoints(0, samples.size, -1)
            track.preferredDevice = device
            track.play()
            tone = track
        } catch (e: Exception) {
            Log.e(TAG, "Fallback tone failed", e)
        }
    }

    private fun raiseMediaVolume() {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * prefs.alarmVolumePercent / 100f).roundToInt().coerceIn(1, max)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (current == target) return
            if (prefs.savedMusicVolume < 0) prefs.savedMusicVolume = current
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to change media volume: ${e.message}")
        }
    }

    private fun quietenSpeaker() {
        try {
            val min = audio.getStreamMinVolume(AudioManager.STREAM_ALARM)
            val current = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            if (current <= min) return
            if (prefs.savedAlarmVolume < 0) prefs.savedAlarmVolume = current
            audio.setStreamVolume(AudioManager.STREAM_ALARM, min, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to change alarm volume: ${e.message}")
        }
    }

    /** Puts back every volume this class changed. Safe to call when nothing was changed. */
    private fun restoreVolumes() {
        restore(AudioManager.STREAM_ALARM, prefs.savedAlarmVolume) { prefs.savedAlarmVolume = -1 }
        restore(AudioManager.STREAM_MUSIC, prefs.savedMusicVolume) { prefs.savedMusicVolume = -1 }
    }

    private fun restore(stream: Int, saved: Int, clear: () -> Unit) {
        if (saved < 0) return
        try {
            audio.setStreamVolume(stream, saved.coerceAtMost(audio.getStreamMaxVolume(stream)), 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to restore volume of stream $stream: ${e.message}")
        }
        clear()
    }

    private fun beepPattern(): ShortArray {
        // Two short beeps, then a pause; one second in total.
        val samples = ShortArray(SAMPLE_RATE)
        val beep = SAMPLE_RATE * 15 / 100
        val fade = SAMPLE_RATE / 200
        for (start in intArrayOf(0, SAMPLE_RATE * 25 / 100)) {
            for (i in 0 until beep) {
                val envelope = minOf(1f, i / fade.toFloat(), (beep - i) / fade.toFloat())
                samples[start + i] =
                    (sin(2 * PI * BEEP_HZ * i / SAMPLE_RATE) * envelope * Short.MAX_VALUE * 0.6).toInt().toShort()
            }
        }
        return samples
    }

    private companion object {
        const val TAG = "AlarmMirror"
        const val STOP_GRACE_MS = 1_500L
        const val TEST_DURATION_MS = 5_000L
        const val SAMPLE_RATE = 44_100
        const val BEEP_HZ = 1_760.0

        val BLUETOOTH_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
