package io.github.chenfish666.podspanel

import android.content.Context
import android.content.SharedPreferences
import io.github.chenfish666.podspanel.core.AlarmMode
import io.github.chenfish666.podspanel.core.EqMath

/** All user settings. Backed by SharedPreferences so the service and the screen share them. */
class Prefs(context: Context) {

    val store: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var popupOnConnect: Boolean
        get() = store.getBoolean(KEY_POPUP_CONNECT, true)
        set(v) = store.edit().putBoolean(KEY_POPUP_CONNECT, v).apply()

    var popupOnLidOpen: Boolean
        get() = store.getBoolean(KEY_POPUP_LID, true)
        set(v) = store.edit().putBoolean(KEY_POPUP_LID, v).apply()

    var earAutoPause: Boolean
        get() = store.getBoolean(KEY_EAR_PAUSE, false)
        set(v) = store.edit().putBoolean(KEY_EAR_PAUSE, v).apply()

    var eqEnabled: Boolean
        get() = store.getBoolean(KEY_EQ_ENABLED, false)
        set(v) = store.edit().putBoolean(KEY_EQ_ENABLED, v).apply()

    var eqGains: FloatArray
        get() = EqMath.decode(store.getString(KEY_EQ_GAINS, null))
        set(v) = store.edit().putString(KEY_EQ_GAINS, EqMath.encode(v)).apply()

    /** Id of the selected preset, or null once the sliders were moved by hand. */
    var eqPreset: String?
        get() = store.getString(KEY_EQ_PRESET, "flat")?.ifEmpty { null }
        // Stored as an empty string: storing null would remove the entry, and the next read
        // would fall back to the default preset.
        set(v) = store.edit().putString(KEY_EQ_PRESET, v.orEmpty()).apply()

    var eqOnlyWithHeadphones: Boolean
        get() = store.getBoolean(KEY_EQ_ONLY_HEADPHONES, true)
        set(v) = store.edit().putBoolean(KEY_EQ_ONLY_HEADPHONES, v).apply()

    /** True: one effect on the system's output mix. False: one effect per player session. */
    var eqGlobal: Boolean
        get() = store.getBoolean(KEY_EQ_GLOBAL, true)
        set(v) = store.edit().putBoolean(KEY_EQ_GLOBAL, v).apply()

    var alarmMode: AlarmMode
        get() = AlarmMode.fromId(store.getString(KEY_ALARM_MODE, null))
        set(v) = store.edit().putString(KEY_ALARM_MODE, v.id).apply()

    /** Headphone volume for the mirrored alarm, as a percentage of the media volume range. */
    var alarmVolumePercent: Int
        get() = store.getInt(KEY_ALARM_VOLUME, 60)
        set(v) = store.edit().putInt(KEY_ALARM_VOLUME, v.coerceIn(10, 100)).apply()

    var alarmRingtoneUri: String?
        get() = store.getString(KEY_ALARM_RINGTONE, null)
        set(v) = store.edit().putString(KEY_ALARM_RINGTONE, v).apply()

    /**
     * Volumes changed for a ringing alarm, written before the change and cleared after the
     * restore. If the process dies in between, the next start puts them back.
     */
    var savedAlarmVolume: Int
        get() = store.getInt(KEY_SAVED_ALARM_VOLUME, -1)
        set(v) = store.edit().putInt(KEY_SAVED_ALARM_VOLUME, v).commit().let { }

    var savedMusicVolume: Int
        get() = store.getInt(KEY_SAVED_MUSIC_VOLUME, -1)
        set(v) = store.edit().putInt(KEY_SAVED_MUSIC_VOLUME, v).commit().let { }

    /**
     * The coarsest scan filter this phone turned out to need, as an index into the scanner's
     * levels. Found automatically: see the scan supervision in the service.
     */
    var scanLevel: Int
        get() = store.getInt(KEY_SCAN_LEVEL, 0)
        set(v) = store.edit().putInt(KEY_SCAN_LEVEL, v).apply()

    /** Scan without a hardware filter. Slower on battery, but some firmware drops filtered results. */
    var scanCompat: Boolean
        get() = store.getBoolean(KEY_SCAN_COMPAT, false)
        set(v) = store.edit().putBoolean(KEY_SCAN_COMPAT, v).apply()

    companion object {
        const val KEY_POPUP_CONNECT = "popup_connect"
        const val KEY_POPUP_LID = "popup_lid"
        const val KEY_EAR_PAUSE = "ear_pause"
        const val KEY_EQ_ENABLED = "eq_enabled"
        const val KEY_EQ_GAINS = "eq_gains"
        const val KEY_EQ_PRESET = "eq_preset"
        const val KEY_EQ_ONLY_HEADPHONES = "eq_only_headphones"
        const val KEY_EQ_GLOBAL = "eq_global"
        const val KEY_ALARM_MODE = "alarm_mode"
        const val KEY_ALARM_VOLUME = "alarm_volume"
        const val KEY_ALARM_RINGTONE = "alarm_ringtone"
        const val KEY_SAVED_ALARM_VOLUME = "saved_alarm_volume"
        const val KEY_SAVED_MUSIC_VOLUME = "saved_music_volume"
        const val KEY_SCAN_COMPAT = "scan_compat"
        const val KEY_SCAN_LEVEL = "scan_level"
    }
}
