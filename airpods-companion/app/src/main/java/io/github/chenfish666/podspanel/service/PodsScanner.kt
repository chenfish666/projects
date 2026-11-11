package io.github.chenfish666.podspanel.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.chenfish666.podspanel.core.PodsStatus
import io.github.chenfish666.podspanel.core.ProximityParser
import java.util.TreeMap

/**
 * Listens for AirPods status broadcasts over Bluetooth LE.
 *
 * Phones differ in which way of scanning actually delivers these broadcasts, so the scanner
 * knows several ([Recipe]) and can run them side by side. The service uses that to find, within
 * seconds, the one that works on the phone at hand.
 *
 * Callers must hold the Bluetooth scan permission.
 */
@SuppressLint("MissingPermission")
class PodsScanner(
    private val context: Context,
    private val onStatus: (status: PodsStatus, rssi: Int) -> Unit,
    private val onProblem: (String?) -> Unit,
) {

    /** One way of asking the system for the broadcasts. Listed from cheapest to most expensive. */
    enum class Recipe(val label: String) {
        /** The Bluetooth chip filters for AirPods status and reports each broadcast at once. */
        STATUS("硬體過濾"),

        /** As above, but the chip collects broadcasts and hands them over in batches. */
        BATCH("硬體過濾・批次回報"),

        /** As [STATUS], but results are delivered through the system instead of a live callback. */
        INTENT("硬體過濾・系統轉送"),

        /**
         * No filter at all, including the newer "extended" kind of broadcast. Everything in
         * range arrives, and the system only delivers while the screen is on.
         */
        OPEN("不過濾（僅螢幕亮著時有資料）"),
    }

    /** What one recipe has delivered since the service started. */
    class Tally {
        var results = 0L
        var pairing = 0L
        var status = 0L
        var lastStatusAtMs = 0L
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<Recipe, ScanCallback>()
    private val fastRunning = HashMap<Recipe, Boolean>()
    private val recentStarts = ArrayDeque<Long>()
    private val recentlyForwarded = ArrayDeque<String>()

    val tallies: Map<Recipe, Tally> = Recipe.entries.associateWith { Tally() }

    var appleResults = 0L
        private set
    var strongestRssi: Int? = null
        private set
    var lastFrameHex: String? = null
        private set
    var lastFrameAtMs = 0L
        private set

    val results: Long get() = tallies.values.sumOf { it.results }
    val statusFrames: Long get() = tallies.values.sumOf { it.status }
    val running: Set<Recipe> get() = fastRunning.keys.toSet()
    fun isFast(recipe: Recipe): Boolean = fastRunning[recipe] == true

    private val recordTypes = TreeMap<Int, Long>()
    private val pairingKinds = LinkedHashMap<String, PairingSample>()
    private var startFailure: String? = null

    private class PairingSample(var count: Long, var bestRssi: Int, var hex: String)

    private val flushBatch = object : Runnable {
        override fun run() {
            val callback = callbacks[Recipe.BATCH] ?: return
            try {
                // Some chips hold batches far longer than asked for unless told to hand them over.
                leScanner()?.flushPendingScanResults(callback)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Flush failed: ${e.message}")
            }
            handler.postDelayed(this, BATCH_FLUSH_MS)
        }
    }

    /**
     * Makes exactly the given recipes run, each either fast (continuous) or in low-power windows.
     * Recipes already running as requested are left untouched.
     */
    fun run(wanted: Map<Recipe, Boolean>) {
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            stop()
            onProblem("藍牙已關閉")
            return
        }
        for (recipe in fastRunning.keys.toList()) {
            if (recipe !in wanted) stopRecipe(recipe)
        }
        for ((recipe, fast) in wanted) {
            if (fastRunning[recipe] == fast) continue
            // The system ignores an app that starts more than five scans in 30 seconds. When
            // that budget is spent, keep what runs and let the caller try again later.
            val now = SystemClock.elapsedRealtime()
            while (recentStarts.isNotEmpty() && now - recentStarts.first() > 30_000) recentStarts.removeFirst()
            if (recentStarts.size >= MAX_STARTS_PER_WINDOW) continue

            if (recipe in fastRunning) stopRecipe(recipe)
            if (startRecipe(adapter, recipe, fast)) {
                recentStarts.addLast(now)
                fastRunning[recipe] = fast
            }
        }
        if (fastRunning.isNotEmpty()) onProblem(null) else startFailure?.let(onProblem)
    }

    fun stop() {
        for (recipe in fastRunning.keys.toList()) stopRecipe(recipe)
    }

    private fun leScanner(): BluetoothLeScanner? = bluetoothManager?.adapter?.bluetoothLeScanner

    private fun startRecipe(adapter: BluetoothAdapter, recipe: Recipe, fast: Boolean): Boolean {
        val scanner = adapter.bluetoothLeScanner ?: run {
            startFailure = "此裝置不支援藍牙低功耗掃描"
            return false
        }
        if (recipe == Recipe.BATCH && !adapter.isOffloadedScanBatchingSupported) return false

        val settings = ScanSettings.Builder()
            .setScanMode(if (fast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(if (recipe == Recipe.BATCH) BATCH_DELAY_MS else 0)
            .apply {
                if (recipe == Recipe.OPEN) {
                    setLegacy(false)
                    setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                }
            }
            .build()
        val filters = if (recipe == Recipe.OPEN) emptyList() else listOf(statusFilter())

        return try {
            if (recipe == Recipe.INTENT) {
                intentSink = { results -> results.forEach { handle(Recipe.INTENT, it) } }
                scanner.startScan(filters, settings, deliveryIntent()) == 0
            } else {
                val callback = callbackFor(recipe)
                callbacks[recipe] = callback
                scanner.startScan(filters, settings, callback)
                if (recipe == Recipe.BATCH) handler.postDelayed(flushBatch, BATCH_FLUSH_MS)
                true
            }
        } catch (e: SecurityException) {
            startFailure = "缺少藍牙權限"
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start $recipe scan", e)
            startFailure = "無法啟動藍牙掃描"
            false
        }
    }

    private fun stopRecipe(recipe: Recipe) {
        fastRunning.remove(recipe)
        try {
            if (recipe == Recipe.INTENT) {
                leScanner()?.stopScan(deliveryIntent())
                intentSink = null
            } else {
                callbacks.remove(recipe)?.let { leScanner()?.stopScan(it) }
                if (recipe == Recipe.BATCH) handler.removeCallbacks(flushBatch)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not stop $recipe scan", e)
        }
    }

    private fun callbackFor(recipe: Recipe) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(recipe, result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(recipe, it) }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "$recipe scan failed: $errorCode")
            fastRunning.remove(recipe)
            callbacks.remove(recipe)
            startFailure = "藍牙掃描失敗（錯誤碼 $errorCode）"
            if (fastRunning.isEmpty()) onProblem(startFailure)
        }
    }

    private fun handle(recipe: Recipe, result: ScanResult) {
        val tally = tallies.getValue(recipe)
        tally.results++
        val data = result.scanRecord?.getManufacturerSpecificData(ProximityParser.APPLE_COMPANY_ID) ?: return

        // Recipes run side by side deliver the same broadcast more than once; count each for the
        // recipe that delivered it, but pass it on only the first time.
        val identity = result.device?.address + "@" + result.timestampNanos
        val first = identity !in recentlyForwarded
        if (first) {
            recentlyForwarded.addLast(identity)
            if (recentlyForwarded.size > DEDUPE_WINDOW) recentlyForwarded.removeFirst()
            appleResults++
            record(data, result.rssi)
        }
        if (containsPairingRecord(data)) tally.pairing++

        val status = ProximityParser.parse(data) ?: return
        val now = SystemClock.elapsedRealtime()
        tally.status++
        tally.lastStatusAtMs = now
        if (!first) return

        strongestRssi = maxOf(strongestRssi ?: result.rssi, result.rssi)
        // Only the readable part; the rest of the broadcast is encrypted.
        lastFrameHex = data.take(11).joinToString(" ") { "%02X".format(it) }
        lastFrameAtMs = now
        onProblem(null)
        onStatus(status, result.rssi)
    }

    private fun containsPairingRecord(data: ByteArray): Boolean {
        var pos = 0
        while (pos + 2 <= data.size) {
            if (data[pos].toInt() and 0xFF == ProximityParser.TYPE_PROXIMITY_PAIRING) return true
            pos += 2 + (data[pos + 1].toInt() and 0xFF)
        }
        return false
    }

    /** Tallies the records inside one Apple broadcast, keeping a sample of each pairing-type shape. */
    private fun record(data: ByteArray, rssi: Int) {
        var pos = 0
        while (pos + 2 <= data.size) {
            val type = data[pos].toInt() and 0xFF
            val length = data[pos + 1].toInt() and 0xFF
            recordTypes.merge(type, 1L, Long::plus)
            if (type == ProximityParser.TYPE_PROXIMITY_PAIRING) {
                fun byteAt(i: Int) = data.getOrNull(pos + 2 + i)?.let { "%02X".format(it) } ?: "--"
                val kind = "長度 $length・開頭 ${byteAt(0)}・型號 ${byteAt(1)}${byteAt(2)}"
                val sample = pairingKinds[kind]
                if (sample == null) {
                    if (pairingKinds.size < MAX_PAIRING_KINDS) pairingKinds[kind] = PairingSample(1, rssi, hex(data))
                } else {
                    sample.count++
                    if (rssi > sample.bestRssi) {
                        sample.bestRssi = rssi
                        sample.hex = hex(data)
                    }
                }
            }
            pos += 2 + length
        }
    }

    private fun hex(data: ByteArray) = data.joinToString(" ") { "%02X".format(it) }

    /** Multi-line detail for the diagnostics report. */
    fun detail(): String = buildString {
        append("各掃描方式（收到／配對類／可解讀）：")
        for (recipe in Recipe.entries) {
            val tally = tallies.getValue(recipe)
            append("\n  ").append(recipe.name).append(' ')
                .append(tally.results).append('／').append(tally.pairing).append('／').append(tally.status)
            if (recipe in fastRunning) append("（執行中）")
        }
        val adapter = bluetoothManager?.adapter
        if (adapter != null) {
            append("\n晶片能力：過濾 ").append(yesNo(adapter.isOffloadedFilteringSupported))
                .append("・批次 ").append(yesNo(adapter.isOffloadedScanBatchingSupported))
                .append("・延伸廣播 ").append(yesNo(adapter.isLeExtendedAdvertisingSupported))
        }
        append("\nApple 廣播類型：")
        append(
            if (recordTypes.isEmpty()) "無"
            else recordTypes.entries.joinToString("・") { "%02X×%d".format(it.key, it.value) }
        )
        append("\n配對類（07）廣播：")
        if (pairingKinds.isEmpty()) append("無")
        for ((kind, sample) in pairingKinds) {
            append("\n  ").append(kind).append("・").append(sample.count).append(" 筆・最強 ")
                .append(sample.bestRssi).append(" dBm\n  ").append(sample.hex)
        }
    }

    private fun yesNo(value: Boolean) = if (value) "✓" else "✗"

    private fun statusFilter(): ScanFilter {
        val prefix = byteArrayOf(
            ProximityParser.TYPE_PROXIMITY_PAIRING.toByte(),
            ProximityParser.STATUS_LENGTH.toByte(),
        )
        val mask = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        return ScanFilter.Builder()
            .setManufacturerData(ProximityParser.APPLE_COMPANY_ID, prefix, mask)
            .build()
    }

    private fun deliveryIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            // The system fills in the results, so the intent has to stay mutable.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(context, ResultReceiver::class.java).setAction(ACTION_DELIVER)
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /** Receives the results of the [Recipe.INTENT] scan from the system. */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_DELIVER) return
            if (intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0) != 0) return
            val results = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
            }
            if (results != null) intentSink?.invoke(results)
        }
    }

    companion object {
        private const val TAG = "PodsScanner"
        private const val ACTION_DELIVER = "io.github.chenfish666.podspanel.SCAN_RESULTS"
        private const val MAX_STARTS_PER_WINDOW = 5
        private const val MAX_PAIRING_KINDS = 12
        private const val DEDUPE_WINDOW = 64
        private const val BATCH_DELAY_MS = 1_000L
        private const val BATCH_FLUSH_MS = 2_000L

        /** Where [ResultReceiver] hands its results; set while the intent-based scan runs. */
        @Volatile
        private var intentSink: ((List<ScanResult>) -> Unit)? = null
    }
}
