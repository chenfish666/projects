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

    /** Show the levels in the HyperOS island where the system offers one. */
    var islandEnabled: Boolean
        get() = store.getBoolean(KEY_ISLAND, true)
        set(v) = store.edit().putBoolean(KEY_ISLAND, v).apply()

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
     * Name of the way of scanning that delivers status broadcasts on this phone, or null while
     * the default has not been found wanting. Found automatically by the service.
     */
    var scanRecipe: String?
        get() = store.getString(KEY_SCAN_RECIPE, null)
        set(v) = store.edit().putString(KEY_SCAN_RECIPE, v).apply()

    /** Model code from the last decoded status broadcast, or -1. Names the pair when only the direct connection has data. */
    var lastModelCode: Int
        get() = store.getInt(KEY_LAST_MODEL, -1)
        set(v) = store.edit().putInt(KEY_LAST_MODEL, v).apply()

    /** The last known levels, so the app has something to show before fresh data arrives. */
    var lastStatus: String?
        get() = store.getString(KEY_LAST_STATUS, null)
        set(v) = store.edit().putString(KEY_LAST_STATUS, v).apply()

    companion object {
        const val KEY_POPUP_CONNECT = "popup_connect"
        const val KEY_POPUP_LID = "popup_lid"
        const val KEY_EAR_PAUSE = "ear_pause"
        const val KEY_ISLAND = "island"
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
        const val KEY_SCAN_RECIPE = "scan_recipe"
        const val KEY_LAST_MODEL = "last_model"
        const val KEY_LAST_STATUS = "last_status"

        /** Keys the service writes itself; a change to them is not a change of settings. */
        val INTERNAL_KEYS = setOf(
            KEY_SAVED_ALARM_VOLUME, KEY_SAVED_MUSIC_VOLUME, KEY_SCAN_RECIPE, KEY_LAST_MODEL, KEY_LAST_STATUS,
        )
    }
}
