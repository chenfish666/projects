package io.github.chenfish666.podspanel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.chenfish666.podspanel.AppState
import io.github.chenfish666.podspanel.Permissions
import io.github.chenfish666.podspanel.Prefs
import io.github.chenfish666.podspanel.R
import io.github.chenfish666.podspanel.core.ConnectionState
import io.github.chenfish666.podspanel.core.EarAutoPause
import io.github.chenfish666.podspanel.core.LidState
import io.github.chenfish666.podspanel.core.PodsStatus
import io.github.chenfish666.podspanel.core.PodsTracker
import io.github.chenfish666.podspanel.ui.BatteryViews
import io.github.chenfish666.podspanel.ui.MainActivity
import io.github.chenfish666.podspanel.ui.PopupController

/**
 * The always-on part of the app. It listens for AirPods broadcasts, keeps the status
 * notification current, shows the popup, and hosts the equalizer and the alarm mirror, all of
 * which have to keep working while the app's screen is closed.
 */
class PodsService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private lateinit var notifications: NotificationManager
    private lateinit var scanner: PodsScanner
    private lateinit var popup: PopupController
    private lateinit var equalizer: EqEngine
    private lateinit var alarm: AlarmMirror

    private val handler = Handler(Looper.getMainLooper())
    private val tracker = PodsTracker()
    private val earAutoPause = EarAutoPause()

    private var headphones: AudioDeviceInfo? = null
    private var connectPopupUntil = 0L
    private var fastScanUntil = 0L
    private var lastPopupAt = -POPUP_GAP_MS
    private var lastNotificationText: String? = null
    private var mediaForeground = false
    private var started = false

    // Scan supervision: which filter level is in use, and whether coarser ones were tried.
    private var activeLevel = PodsScanner.Level.STATUS
    private var levelSinceMs = 0L
    private var probeExhausted = false
    private var compatApplied = false
    private var accepted = 0L
    private var tooWeak = 0L

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_SAVED_ALARM_VOLUME, Prefs.KEY_SAVED_MUSIC_VOLUME -> Unit
            else -> applySettings()
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshHeadphones(false)
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshHeadphones(false)
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) updateScan()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            publish()
            superviseScan()
            updateScan()
            publishScanInfo()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audio = getSystemService(AudioManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        createChannels()

        scanner = PodsScanner(this, ::onStatus) { AppState.scanProblem.value = it }
        popup = PopupController(this)
        equalizer = EqEngine(this)
        alarm = AlarmMirror(
            context = this,
            prefs = prefs,
            headphones = { headphones },
            inEar = { AppState.pods.value?.takeIf { it.live }?.status?.anyInEar },
            onPlayingChanged = { playing ->
                mediaForeground = playing
                enterForeground()
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            begin()
        }
        when (intent?.action) {
            ACTION_TEST_POPUP -> showPopup(AppState.pods.value?.status ?: DEMO_STATUS, force = true)
            ACTION_TEST_ALARM -> alarm.test()
            ACTION_FAST_SCAN -> {
                fastScanUntil = SystemClock.elapsedRealtime() + FAST_SCAN_MS
                probeExhausted = false
                updateScan()
            }
        }
        return START_STICKY
    }

    private fun begin() {
        AppState.serviceRunning.value = true
        refreshHeadphones(initial = true)
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        ContextCompat.registerReceiver(
            this, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        prefs.store.registerOnSharedPreferenceChangeListener(prefsListener)
        equalizer.startListening()
        alarm.start()
        compatApplied = prefs.scanCompat
        setLevel(baseLevel())
        applySettings()
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (started) {
            prefs.store.unregisterOnSharedPreferenceChangeListener(prefsListener)
            audio.unregisterAudioDeviceCallback(deviceCallback)
            unregisterReceiver(bluetoothReceiver)
            alarm.stop()
        }
        scanner.stop()
        equalizer.release()
        popup.dismiss()
        AppState.serviceRunning.value = false
        AppState.pods.value = null
        AppState.headphones.value = null
        super.onDestroy()
    }

    // ---- AirPods status ----

    private fun onStatus(status: PodsStatus, rssi: Int) {
        val now = SystemClock.elapsedRealtime()
        val before = tracker.snapshot(now)
        val minRssi = if (podsConnected()) CONNECTED_MIN_RSSI else NEARBY_MIN_RSSI
        if (!tracker.offer(status, rssi, now, minRssi)) {
            if (rssi < minRssi) tooWeak++
            return
        }
        accepted++
        val after = tracker.snapshot(now) ?: return
        publish(after)
        popup.update(after.status)

        val lidJustOpened = after.status.lid == LidState.OPEN &&
            (before == null || !before.live || before.status.lid == LidState.CLOSED)
        when {
            prefs.popupOnConnect && now < connectPopupUntil -> {
                connectPopupUntil = 0
                showPopup(after.status, subtitle = headphones?.let { "已連線" })
            }
            prefs.popupOnLidOpen && lidJustOpened && rssi >= LID_POPUP_MIN_RSSI -> showPopup(after.status)
        }

        if (prefs.earAutoPause && headphones != null && status.model.dual) {
            val worn = (if (status.leftInEar) 1 else 0) + (if (status.rightInEar) 1 else 0)
            when (earAutoPause.onReading(worn, audio.isMusicActive, now)) {
                EarAutoPause.Action.PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                EarAutoPause.Action.PLAY -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                EarAutoPause.Action.NONE -> Unit
            }
        }

        if (AppState.alarmRinging.value) alarm.evaluate()
    }

    private fun publish(tracked: PodsTracker.Tracked? = tracker.snapshot(SystemClock.elapsedRealtime())) {
        AppState.pods.value = tracked
        updateNotification(tracked)
    }

    private fun showPopup(status: PodsStatus, subtitle: String? = null, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && !popup.showing && now - lastPopupAt < POPUP_GAP_MS) return
        lastPopupAt = now
        if (popup.show(status, subtitle)) return

        // Without permission to draw over other apps, a heads-up notification is the next best thing.
        val notification = NotificationCompat.Builder(this, CHANNEL_POPUP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(status.model.label)
            .setContentText(BatteryViews.summary(status))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setTimeoutAfter(8_000)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        notify(NOTIFICATION_POPUP, notification)
    }

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---- Headphones ----

    private fun refreshHeadphones(initial: Boolean) {
        val found = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in BLUETOOTH_OUTPUTS }
        val wasConnected = headphones != null
        headphones = found
        AppState.headphones.value = found?.productName?.toString()?.ifBlank { "藍牙耳機" }

        if (found != null && !wasConnected && !initial) onHeadphonesConnected()
        if (found == null && wasConnected) earAutoPause.reset()
        if (!initial) {
            applyEqualizer()
            alarm.evaluate()
        }
    }

    private fun onHeadphonesConnected() {
        val now = SystemClock.elapsedRealtime()
        fastScanUntil = now + FAST_SCAN_MS
        probeExhausted = false
        updateScan()
        if (!prefs.popupOnConnect) return
        val live = tracker.snapshot(now)?.takeIf { it.live }
        if (live != null) {
            showPopup(live.status, subtitle = "已連線")
        } else {
            // The levels are not known yet; show the popup with the first broadcast that arrives.
            connectPopupUntil = now + CONNECT_POPUP_WINDOW_MS
        }
    }

    // ---- Settings ----

    private fun applySettings() {
        // The compatibility switch overrides whatever level was found automatically.
        if (prefs.scanCompat != compatApplied) {
            compatApplied = prefs.scanCompat
            probeExhausted = false
            setLevel(baseLevel())
        }
        updateScan()
        applyEqualizer()
        alarm.evaluate()
        if (!prefs.earAutoPause) earAutoPause.reset()
    }

    private fun applyEqualizer() {
        val wanted = prefs.eqEnabled
        val gated = wanted && prefs.eqOnlyWithHeadphones && headphones == null
        equalizer.update(enabled = wanted && !gated, global = prefs.eqGlobal, gains = prefs.eqGains)
        if (gated) AppState.eq.value = AppState.EqState(detail = "藍牙耳機連線後會自動套用")
    }

    private fun updateScan() {
        if (!Permissions.hasBluetooth(this)) {
            scanner.stop()
            AppState.scanProblem.value = "缺少藍牙權限"
            return
        }
        val fast = uiVisible || SystemClock.elapsedRealtime() < fastScanUntil
        scanner.start(fast = fast, level = activeLevel)
    }

    /** The level to scan at when nothing is being probed. */
    private fun baseLevel(): PodsScanner.Level =
        if (prefs.scanCompat) PodsScanner.Level.OPEN
        else PodsScanner.Level.entries[prefs.scanLevel.coerceIn(0, PodsScanner.Level.entries.lastIndex)]

    private fun setLevel(level: PodsScanner.Level) {
        activeLevel = level
        levelSinceMs = SystemClock.elapsedRealtime()
    }

    /** True when the connected headphones are ones that send the status broadcasts. */
    private fun podsConnected(): Boolean {
        val name = AppState.headphones.value ?: return false
        return POD_NAMES.any { name.contains(it, ignoreCase = true) }
    }

    /**
     * Works around phones that drop AirPods broadcasts when the Bluetooth chip filters for them.
     *
     * While AirPods are connected to this phone they broadcast every few seconds, so silence
     * means the broadcasts are being lost on the way. Each period of silence steps down to a
     * coarser filter; the first level that delivers is remembered. Only done with the screen on:
     * with it off, results are legitimately delayed, and the unfiltered level delivers nothing.
     */
    private fun superviseScan() {
        val now = SystemClock.elapsedRealtime()
        val heardAtThisLevel = scanner.lastFrameAtMs > levelSinceMs
        val silentForMs = now - maxOf(scanner.lastFrameAtMs, levelSinceMs)

        if (heardAtThisLevel && silentForMs < PROBE_MS) {
            if (!prefs.scanCompat && activeLevel.ordinal > prefs.scanLevel) prefs.scanLevel = activeLevel.ordinal
            return
        }
        val expecting = podsConnected() || (uiVisible && !heardAtThisLevel)
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive == true
        if (!expecting || !screenOn || probeExhausted || silentForMs < PROBE_MS) return

        val coarser = activeLevel.coarser()
        if (coarser != null && !prefs.scanCompat) {
            Log.i(TAG, "No status broadcasts at $activeLevel for ${silentForMs}ms, trying $coarser")
            setLevel(coarser)
            updateScan()
        } else {
            probeExhausted = true
            setLevel(baseLevel())
            updateScan()
            if (podsConnected()) AppState.scanProblem.value = silenceAdvice()
        }
    }

    private fun silenceAdvice(): String {
        val locationOff = getSystemService(LocationManager::class.java)?.isLocationEnabled != true
        return buildString {
            append("收不到這副耳機的狀態廣播。")
            // Not needed on stock Android, but some manufacturers hold back scan results without it.
            if (scanner.results == 0L && locationOff) append("可以試著開啟手機的定位服務後，重新開啟這個 App。")
            append("仍然不行的話，請把最下方「進階」裡的診斷資訊複製給開發者。")
        }
    }

    private fun publishScanInfo() {
        val now = SystemClock.elapsedRealtime()
        AppState.scan.value = AppState.ScanInfo(
            level = scanner.level.label,
            fast = scanner.fast,
            results = scanner.results,
            appleResults = scanner.appleResults,
            statusFrames = scanner.statusFrames,
            accepted = accepted,
            tooWeak = tooWeak,
            strongestRssi = scanner.strongestRssi,
            lastFrameHex = scanner.lastFrameHex,
            lastFrameAgeSeconds = scanner.lastFrameAtMs.takeIf { it > 0 }?.let { (now - it) / 1000 },
        )
    }

    // ---- Notifications ----

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "耳機狀態", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_POPUP, "電量彈窗（未開啟懸浮視窗時）", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                setSound(null, null)
            }
        )
    }

    private fun statusNotification(tracked: PodsTracker.Tracked?): Notification {
        val title = tracked?.status?.model?.label ?: getString(R.string.app_name)
        val text = when {
            tracked == null -> AppState.scanProblem.value ?: "尚未偵測到 AirPods"
            tracked.live -> BatteryViews.summary(tracked.status)
            else -> BatteryViews.summary(tracked.status) + "（上次讀取）"
        }
        lastNotificationText = "$title|$text"
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
            .build()
    }

    private fun updateNotification(tracked: PodsTracker.Tracked?) {
        val previous = lastNotificationText
        val notification = statusNotification(tracked)
        if (lastNotificationText != previous) notify(NOTIFICATION_STATUS, notification)
    }

    private fun notify(id: Int, notification: Notification) {
        try {
            notifications.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notifications are not allowed: ${e.message}")
        }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Puts the service in the foreground, which the system requires for it to keep running.
     * While the alarm mirror plays, the service additionally declares media playback.
     *
     * @return false when the system refused, e.g. because a permission is missing
     */
    private fun enterForeground(): Boolean {
        val notification = statusNotification(tracker.snapshot(SystemClock.elapsedRealtime()))
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        val withMedia = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        if (mediaForeground) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_STATUS, notification, withMedia)
                return true
            } catch (e: RuntimeException) {
                // Not fatal: the alarm copy still plays under the plain foreground state below.
                Log.w(TAG, "Could not add media playback to the foreground state: ${e.message}")
            }
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_STATUS, notification, base)
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not enter the foreground: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "PodsService"
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_POPUP = "popup"
        private const val NOTIFICATION_STATUS = 1
        private const val NOTIFICATION_POPUP = 2

        private const val ACTION_TEST_POPUP = "io.github.chenfish666.podspanel.TEST_POPUP"
        private const val ACTION_TEST_ALARM = "io.github.chenfish666.podspanel.TEST_ALARM"
        private const val ACTION_FAST_SCAN = "io.github.chenfish666.podspanel.FAST_SCAN"

        private const val TICK_MS = 5_000L
        private const val FAST_SCAN_MS = 30_000L
        private const val CONNECT_POPUP_WINDOW_MS = 20_000L
        private const val POPUP_GAP_MS = 20_000L
        private const val PROBE_MS = 10_000L

        /** Weakest signal that may start following a pair seen in passing. */
        private const val NEARBY_MIN_RSSI = -72

        /** The same while AirPods are connected to this phone, e.g. worn with the phone in a pocket. */
        private const val CONNECTED_MIN_RSSI = -92

        private val POD_NAMES = listOf("AirPods", "Beats")

        /** A case opened next to the phone is this loud or louder; a stranger's rarely is. */
        private const val LID_POPUP_MIN_RSSI = -60

        private val BLUETOOTH_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        /** Shown by "preview popup" when no AirPods have been seen yet. */
        private val DEMO_STATUS = PodsStatus(
            modelCode = 0x3620, left = 90, right = 80, case = 100,
            leftCharging = false, rightCharging = false, caseCharging = true,
            leftInEar = false, rightInEar = false,
            lid = LidState.OPEN, connection = ConnectionState.UNKNOWN, colorCode = 0,
        )

        /** Set by the main screen so that levels refresh quickly while it is open. */
        @Volatile
        var uiVisible = false

        /**
         * Starts the service if it may run. Without the Bluetooth permission the system refuses
         * to put it in the foreground, so in that case this does nothing.
         */
        fun start(context: Context, action: String? = null): Boolean {
            if (!Permissions.hasBluetooth(context)) return false
            val intent = Intent(context, PodsService::class.java).setAction(action)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not start service: ${e.message}")
                false
            }
        }

        fun testPopup(context: Context) = start(context, ACTION_TEST_POPUP)
        fun testAlarm(context: Context) = start(context, ACTION_TEST_ALARM)
        fun fastScan(context: Context) = start(context, ACTION_FAST_SCAN)
    }
}
