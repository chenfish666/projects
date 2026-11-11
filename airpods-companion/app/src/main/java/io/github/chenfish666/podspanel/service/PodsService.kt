package io.github.chenfish666.podspanel.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import io.github.chenfish666.podspanel.core.AapProtocol
import io.github.chenfish666.podspanel.core.AapState
import io.github.chenfish666.podspanel.core.ConnectionState
import io.github.chenfish666.podspanel.core.EarAutoPause
import io.github.chenfish666.podspanel.core.LidState
import io.github.chenfish666.podspanel.core.OwnPairFilter
import io.github.chenfish666.podspanel.core.PodModel
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
    private var mediaForeground = false
    private var started = false

    // Scan supervision: which way of scanning is in use, and whether the others are being tried.
    private var mainRecipe = PodsScanner.Recipe.STATUS
    private var probing = false
    private var probeDone = false
    private var probeBaseline = emptyMap<PodsScanner.Recipe, Long>()
    private var expectSinceMs = 0L
    private var accepted = 0L

    // The direct connection to the AirPods, when the phone manages to open one.
    private var aapLink: AapLink? = null
    private val aapState = AapState()
    private var aapConnected = false
    private var aapDetail = ""
    private val aapLog = ArrayDeque<String>()
    private var isPods = false

    /** Levels from the previous run, shown until fresh ones arrive. */
    private var restored: PodsTracker.Tracked? = null
    private var savedLevels: String? = null

    /** The content of the service's notification as last posted. */
    private var postedContent: StatusContent? = null

    /** What the island shows, or null while it is not up. */
    private var islandContent: String? = null
    private var tooWeak = 0L
    private var otherPairs = 0L
    private lateinit var ownPair: OwnPairFilter

    /** Since when the service has had nothing to do, or 0 while it is needed. */
    private var idleSinceMs = 0L
    private var connectionStart = false
    private var destroyed = false
    private var directEverConnected = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key !in Prefs.INTERNAL_KEYS) applySettings()
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
            if (stopIfIdle()) return
            publish()
            maybeProbe()
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
        Channels.ensure(this)

        scanner = PodsScanner(this, ::onStatus) { AppState.scanProblem.value = it }
        popup = PopupController(
            context = this,
            islandFollows = { islandWanted(current(SystemClock.elapsedRealtime()), ignorePopup = true) },
            // The island is held back while the card is up, so that the one hands over to the other.
            onDismissed = { if (started && !destroyed) publish() },
        )
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
            connectionStart = intent?.action == ACTION_CONNECTING
            begin()
        }
        when (intent?.action) {
            ACTION_TEST_POPUP -> showPopup(AppState.pods.value?.status ?: DEMO_STATUS, force = true)
            ACTION_TEST_ALARM -> alarm.test()
            ACTION_FAST_SCAN -> {
                fastScanUntil = SystemClock.elapsedRealtime() + FAST_SCAN_MS
                expectBroadcasts()
                updateScan()
                // Opening the app is a good moment for another go at the direct connection.
                if (aapLink?.gaveUp == true) closeAap()
                syncAap()
            }
            ACTION_SET_MODE -> AapProtocol.ListeningMode.fromWire(intent.getIntExtra(EXTRA_MODE, -1))?.let {
                aapLink?.send(AapProtocol.setListeningMode(it))
            }
        }
        return START_STICKY
    }

    private fun begin() {
        AppState.serviceRunning.value = true
        savedLevels = prefs.lastStatus
        restored = PodsStatus.decodeLevels(savedLevels)
            // Levels of a pair that is not known to be the user's own are not worth remembering.
            ?.takeIf { it.modelCode == prefs.ownModelCode }
            ?.let { PodsTracker.Tracked(it, rssi = 0, seenAtMs = 0, live = false) }
        ownPair = OwnPairFilter(prefs.ownModelCode)
        // Started because headphones are connecting: treat them as newly connected, popup included.
        refreshHeadphones(initial = !connectionStart)
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        ContextCompat.registerReceiver(
            this, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        prefs.store.registerOnSharedPreferenceChangeListener(prefsListener)
        equalizer.startListening()
        alarm.start()
        mainRecipe = baseRecipe()
        expectBroadcasts()
        applySettings()
        handler.post(tick)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        if (started) {
            prefs.store.unregisterOnSharedPreferenceChangeListener(prefsListener)
            audio.unregisterAudioDeviceCallback(deviceCallback)
            unregisterReceiver(bluetoothReceiver)
            alarm.stop()
        }
        notifications.cancel(NOTIFICATION_ISLAND)
        closeAap()
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
        if (!ownPair.accept(status.modelCode, rssi, podsConnected(), now)) {
            otherPairs++
            return
        }
        if (ownPair.model != prefs.ownModelCode) {
            // A different pair than before: what was followed so far no longer applies.
            prefs.ownModelCode = ownPair.model
            tracker.reset()
        }
        val before = tracker.snapshot(now)
        val minRssi = if (podsConnected()) CONNECTED_MIN_RSSI else NEARBY_MIN_RSSI
        if (!tracker.offer(status, rssi, now, minRssi)) {
            if (rssi < minRssi) tooWeak++
            return
        }
        accepted++
        val after = current(now) ?: return
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

        // The direct connection reports in-ear changes reliably; broadcasts are the fallback.
        if (prefs.earAutoPause && headphones != null && status.model.dual && aapState.wornCount == null) {
            val worn = (if (status.leftInEar) 1 else 0) + (if (status.rightInEar) 1 else 0)
            autoPause(worn, now)
        }

        if (AppState.alarmRinging.value) alarm.evaluate()
    }

    private fun autoPause(worn: Int, now: Long) {
        when (earAutoPause.onReading(worn, audio.isMusicActive, now)) {
            EarAutoPause.Action.PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            EarAutoPause.Action.PLAY -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            EarAutoPause.Action.NONE -> Unit
        }
    }

    /**
     * The best current picture: exact data from the direct connection when it is up, otherwise
     * the status broadcasts, otherwise the levels remembered from the previous run.
     */
    private fun current(now: Long): PodsTracker.Tracked? {
        val broadcast = tracker.snapshot(now)
        if (aapConnected) {
            val direct = aapState.toStatus(
                modelCode = broadcast?.status?.modelCode ?: prefs.ownModelCode,
                lid = broadcast?.takeIf { it.live }?.status?.lid ?: LidState.UNKNOWN,
            )
            if (direct != null) return PodsTracker.Tracked(direct, broadcast?.rssi ?: 0, now, live = true)
        }
        return broadcast ?: restored
    }

    private fun publish(tracked: PodsTracker.Tracked? = current(SystemClock.elapsedRealtime())) {
        AppState.pods.value = tracked
        updateNotification(tracked)
        if (tracked != null && tracked.live) {
            val levels = tracked.status.encodeLevels()
            if (levels != savedLevels) {
                savedLevels = levels
                prefs.lastStatus = levels
                restored = PodsStatus.decodeLevels(levels)?.let { PodsTracker.Tracked(it, rssi = 0, seenAtMs = 0, live = false) }
            }
        }
    }

    /** What to call the pair: its model when known, otherwise the name it is paired under. */
    private fun displayName(status: PodsStatus): String =
        if (status.model == PodModel.UNKNOWN) AppState.headphones.value ?: status.model.label else status.model.label

    // ---- Direct connection ----

    /** Opens the direct connection when AirPods are connected, and closes it when they are not. */
    private fun syncAap() {
        val device = if (isPods) headphones?.let(::bluetoothDeviceOf) else null
        scanner.targetAddress = device?.address
        if (!probing) mainRecipe = baseRecipe()
        if (device == null) {
            closeAap()
            return
        }
        if (aapLink != null || directUnavailable()) return
        aapLink = AapLink(device, ::onAapEvent, ::onAapState).also { it.open() }
    }

    /** The direct connection already failed on this very system build; a system update lifts this. */
    private fun directUnavailable(): Boolean = prefs.directFailedOnBuild == Build.DISPLAY

    private fun closeAap() {
        aapLink?.close()
        aapLink = null
        aapConnected = false
        aapState.reset()
        AppState.listeningMode.value = null
    }

    private fun onAapState(state: AapLink.State, detail: String) {
        aapDetail = detail
        // A short history, so a report taken at any moment shows how earlier attempts ended.
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        aapLog.addLast("$time $detail")
        if (aapLog.size > AAP_LOG_SIZE) aapLog.removeFirst()
        val connected = state == AapLink.State.CONNECTED
        if (connected) {
            directEverConnected = true
            AppState.scanProblem.value = null
        } else if (state == AapLink.State.FAILED && aapLink?.gaveUp == true && !directEverConnected) {
            // Every attempt failed without the channel ever opening: this phone cannot do it.
            prefs.directFailedOnBuild = Build.DISPLAY
        }
        if (aapConnected && !connected) {
            aapState.reset()
            AppState.listeningMode.value = null
        }
        aapConnected = connected
        publish()
        publishScanInfo()
    }

    private fun onAapEvent(event: AapProtocol.Event) {
        val now = SystemClock.elapsedRealtime()
        aapState.apply(event)
        AppState.listeningMode.value = aapState.mode
        val tracked = current(now)
        publish(tracked)
        if (tracked != null) popup.update(tracked.status)

        when (event) {
            is AapProtocol.Event.Battery ->
                if (prefs.popupOnConnect && now < connectPopupUntil && tracked != null && tracked.live) {
                    connectPopupUntil = 0
                    showPopup(tracked.status, subtitle = "已連線")
                }
            is AapProtocol.Event.Ear -> aapState.wornCount?.let { worn ->
                if (prefs.earAutoPause) {
                    // One report is enough here; the confirmation count exists for noisy broadcasts.
                    autoPause(worn, now)
                    autoPause(worn, now)
                }
            }
            else -> Unit
        }
        if (AppState.alarmRinging.value) alarm.evaluate()
    }

    @SuppressLint("MissingPermission")
    private fun bluetoothDeviceOf(info: AudioDeviceInfo): BluetoothDevice? = try {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val address = info.address
        when {
            adapter == null -> null
            BluetoothAdapter.checkBluetoothAddress(address) -> adapter.getRemoteDevice(address)
            // Some systems hide the address from apps; fall back to the paired device of that name.
            else -> adapter.bondedDevices.firstOrNull { it.name == info.productName?.toString() }
        }
    } catch (e: SecurityException) {
        null
    }

    /** Whether the phone knows the headphones as classic-only or also as a low-energy device. */
    @SuppressLint("MissingPermission")
    private fun podsDeviceType(): String = try {
        when (headphones?.let(::bluetoothDeviceOf)?.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "・傳統藍牙裝置"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "・雙模裝置"
            BluetoothDevice.DEVICE_TYPE_LE -> "・低功耗裝置"
            else -> ""
        }
    } catch (e: SecurityException) {
        ""
    }

    @SuppressLint("MissingPermission")
    private fun looksLikePods(info: AudioDeviceInfo): Boolean {
        val name = info.productName?.toString().orEmpty()
        if (POD_NAMES.any { name.contains(it, ignoreCase = true) }) return true
        // Renamed AirPods still announce Apple's own service.
        return try {
            bluetoothDeviceOf(info)?.uuids?.any { it.uuid == APPLE_SERVICE } == true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun showPopup(status: PodsStatus, subtitle: String? = null, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && !popup.showing && now - lastPopupAt < POPUP_GAP_MS) return
        lastPopupAt = now
        if (popup.show(status, displayName(status), subtitle)) {
            // Takes the island down for as long as the card is up.
            updateIsland(current(now))
            return
        }

        // Without permission to draw over other apps, a heads-up notification is the next best thing.
        val notification = NotificationCompat.Builder(this, Channels.POPUP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayName(status))
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
        isPods = found != null && looksLikePods(found)
        syncAap()

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
        expectBroadcasts()
        updateScan()
        if (!prefs.popupOnConnect) return
        val live = current(now)?.takeIf { it.live }
        if (live != null) {
            showPopup(live.status, subtitle = "已連線")
        } else {
            // The levels are not known yet; show the popup with the first broadcast that arrives.
            connectPopupUntil = now + CONNECT_POPUP_WINDOW_MS
        }
    }

    // ---- Lifetime ----

    /** Whether there is anything for the service to do right now. */
    private fun needed(): Boolean =
        headphones != null || uiVisible || AppState.alarmRinging.value ||
            (prefs.eqEnabled && !prefs.eqOnlyWithHeadphones)

    /**
     * Stops the service once it has had nothing to do for a while, which also removes its
     * notification. [BootReceiver] starts it again when headphones connect.
     *
     * @return true when the service is stopping
     */
    private fun stopIfIdle(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (needed()) {
            idleSinceMs = 0
            return false
        }
        if (idleSinceMs == 0L) idleSinceMs = now
        if (now - idleSinceMs < IDLE_STOP_MS) return false
        Log.i(TAG, "Nothing to do, stopping")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        return true
    }

    // ---- Settings ----

    private fun applySettings() {
        publish()
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
        val wanted = LinkedHashMap<PodsScanner.Recipe, Boolean>()
        wanted[mainRecipe] = fast || probing
        if (probing) PodsScanner.Recipe.entries.forEach { wanted.putIfAbsent(it, true) }
        scanner.run(wanted)
    }

    /** The way of scanning to use when nothing is being tried out. */
    private fun baseRecipe(): PodsScanner.Recipe {
        val saved = PodsScanner.Recipe.entries.firstOrNull { it.name == prefs.scanRecipe }
        // The address recipe needs connected headphones; without them, fall back to the default.
        return if (saved == null || saved == PodsScanner.Recipe.ADDRESS && scanner.targetAddress == null) {
            PodsScanner.Recipe.STATUS
        } else {
            saved
        }
    }

    /** True when the connected headphones are ones that send the status broadcasts. */
    private fun podsConnected(): Boolean = isPods

    /** Marks a moment from which broadcasts should be arriving, and allows a new round of probing. */
    private fun expectBroadcasts() {
        expectSinceMs = SystemClock.elapsedRealtime()
        probeDone = false
        handler.removeCallbacks(probeCheck)
        handler.postDelayed(probeCheck, QUIET_MS + 200)
    }

    private val probeCheck = Runnable { maybeProbe() }
    private val probeEnd = Runnable { endProbe(adopt = true) }

    /**
     * Works around phones on which the usual way of scanning delivers no AirPods broadcasts.
     *
     * While AirPods are connected to this phone they broadcast every second or so. If nothing
     * has arrived a few seconds after they should have, every known way of scanning is run side
     * by side for a short while; whichever delivers is used from then on, and its broadcasts
     * count at once, so the wait is seconds rather than one attempt after another. Only done
     * with the screen on, where every way can deliver.
     */
    private fun maybeProbe() {
        if (probing || probeDone || !podsConnected()) return
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return
        val now = SystemClock.elapsedRealtime()
        if (now - maxOf(scanner.lastFrameAtMs, expectSinceMs) < QUIET_MS) return

        Log.i(TAG, "No status broadcasts via $mainRecipe, trying every way of scanning")
        probing = true
        probeBaseline = scanner.tallies.mapValues { it.value.status }
        updateScan()
        handler.postDelayed(probeEnd, PROBE_MS)
    }

    private fun endProbe(adopt: Boolean) {
        handler.removeCallbacks(probeEnd)
        if (!probing) return
        probing = false
        probeDone = true
        if (adopt) {
            // Cheapest first: a way that keeps working with the screen off beats one that does not.
            val winner = PodsScanner.Recipe.entries.firstOrNull {
                scanner.tallies.getValue(it).status > (probeBaseline[it] ?: 0)
            }
            if (winner != null) {
                Log.i(TAG, "Status broadcasts arrive via $winner")
                mainRecipe = winner
                prefs.scanRecipe = winner.name
            } else if (podsConnected() && !aapConnected) {
                updateScan()
                AppState.scanProblem.value = silenceAdvice()
                return
            }
        }
        updateScan()
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
            level = mainRecipe.label + if (probing) "・正在嘗試其他方式" else "",
            fast = scanner.isFast(mainRecipe),
            results = scanner.results,
            appleResults = scanner.appleResults,
            statusFrames = scanner.statusFrames,
            accepted = accepted,
            tooWeak = tooWeak,
            otherPairs = otherPairs,
            strongestRssi = scanner.strongestRssi,
            lastFrameHex = scanner.lastFrameHex,
            lastFrameAgeSeconds = scanner.lastFrameAtMs.takeIf { it > 0 }?.let { (now - it) / 1000 },
            detail = scanner.detail(),
            direct = when {
                directUnavailable() -> "這個系統版本連不上，已停止嘗試（系統更新後會再試）"
                aapLink == null -> if (headphones == null) "耳機未連線" else if (isPods) "找不到耳機的藍牙位址" else "不是 AirPods"
                else -> aapDetail + podsDeviceType() + aapLog.joinToString("") { "\n  $it" }
            },
        )
    }

    // ---- Notifications ----

    /** What the service's own notification says, decided before anything is built. */
    private data class StatusContent(val title: String, val text: String)

    private fun statusContent(tracked: PodsTracker.Tracked?): StatusContent {
        val title = tracked?.status?.let(::displayName) ?: getString(R.string.app_name)
        val text = when {
            tracked == null -> AppState.scanProblem.value ?: "尚未偵測到 AirPods"
            tracked.live -> BatteryViews.summary(tracked.status)
            else -> BatteryViews.summary(tracked.status) + "（上次讀取）"
        }
        return StatusContent(title, text)
    }

    private fun statusNotification(content: StatusContent): Notification {
        postedContent = content
        return NotificationCompat.Builder(this, Channels.SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
            .build()
    }

    /**
     * Whether the levels belong in the island right now: only for headphones in use, and not
     * while the popup card is up, which hands over to the island when it closes.
     */
    private fun islandWanted(tracked: PodsTracker.Tracked?, ignorePopup: Boolean = false): Boolean =
        tracked != null && tracked.live && headphones != null && prefs.islandEnabled &&
            // Also held back while a popup for freshly connected headphones is still to come.
            (ignorePopup || !popup.showing && SystemClock.elapsedRealtime() >= connectPopupUntil) &&
            Island.availability(this) == Island.Availability.AVAILABLE

    private fun updateNotification(tracked: PodsTracker.Tracked?) {
        val content = statusContent(tracked)
        if (content != postedContent) notify(NOTIFICATION_STATUS, statusNotification(content))
        updateIsland(tracked)
    }

    private fun updateIsland(tracked: PodsTracker.Tracked?) {
        if (!islandWanted(tracked) || tracked == null) {
            if (islandContent != null) {
                islandContent = null
                notifications.cancel(NOTIFICATION_ISLAND)
            }
            return
        }
        val content = displayName(tracked.status) + "|" + BatteryViews.summary(tracked.status)
        if (content == islandContent) return
        val appear = islandContent == null
        val notification = Island.build(this, displayName(tracked.status), tracked.status, appear) ?: return
        islandContent = content
        notify(NOTIFICATION_ISLAND, notification)
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
        val notification = statusNotification(statusContent(current(SystemClock.elapsedRealtime())))
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
        private const val NOTIFICATION_STATUS = 1
        private const val NOTIFICATION_POPUP = 2
        private const val NOTIFICATION_ISLAND = 3

        private const val ACTION_TEST_POPUP = "io.github.chenfish666.podspanel.TEST_POPUP"
        private const val ACTION_TEST_ALARM = "io.github.chenfish666.podspanel.TEST_ALARM"
        private const val ACTION_FAST_SCAN = "io.github.chenfish666.podspanel.FAST_SCAN"
        private const val ACTION_SET_MODE = "io.github.chenfish666.podspanel.SET_MODE"
        private const val ACTION_CONNECTING = "io.github.chenfish666.podspanel.CONNECTING"

        /** Long enough for headphones that are still connecting to show up as an audio device. */
        private const val IDLE_STOP_MS = 30_000L
        private const val EXTRA_MODE = "mode"

        /** Service that every Apple headset announces over classic Bluetooth. */
        private val APPLE_SERVICE = java.util.UUID.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

        private const val TICK_MS = 5_000L
        private const val FAST_SCAN_MS = 30_000L
        private const val CONNECT_POPUP_WINDOW_MS = 20_000L
        private const val POPUP_GAP_MS = 20_000L
        /** How long broadcasts may stay away before other ways of scanning are tried. */
        private const val AAP_LOG_SIZE = 10
        private const val QUIET_MS = 3_000L

        /** How long every way of scanning runs side by side. */
        private const val PROBE_MS = 10_000L

        /** Weakest signal that may start following a pair seen in passing. */
        private const val NEARBY_MIN_RSSI = -72

        /** The same while AirPods are connected to this phone, e.g. worn with the phone in a pocket. */
        private const val CONNECTED_MIN_RSSI = -80

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

        /** Called when the system reports Bluetooth headphones connecting. */
        fun onHeadphonesConnecting(context: Context) = start(context, ACTION_CONNECTING)

        fun setListeningMode(context: Context, mode: AapProtocol.ListeningMode) {
            if (!Permissions.hasBluetooth(context)) return
            val intent = Intent(context, PodsService::class.java).setAction(ACTION_SET_MODE).putExtra(EXTRA_MODE, mode.wire)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not reach service: ${e.message}")
            }
        }
    }
}
