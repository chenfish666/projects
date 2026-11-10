package io.github.chenfish666.podspanel.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.chenfish666.podspanel.core.PodsStatus
import io.github.chenfish666.podspanel.core.ProximityParser

/**
 * Listens for AirPods status broadcasts over Bluetooth LE.
 *
 * Callers must hold the Bluetooth scan permission before calling [start].
 */
@SuppressLint("MissingPermission")
class PodsScanner(
    context: Context,
    private val onStatus: (status: PodsStatus, rssi: Int) -> Unit,
    private val onProblem: (String?) -> Unit,
) {

    /**
     * How much of the filtering is left to the Bluetooth chip. Some phones (several running
     * HyperOS among them) silently drop AirPods broadcasts when the chip filters for them, so
     * the caller can step down to a coarser filter, at the cost of more work for this app.
     */
    enum class Level(val label: String) {
        /** The chip passes only AirPods status broadcasts. */
        STATUS("只收耳機狀態（硬體過濾）"),

        /** The chip passes every broadcast of an Apple device; this app picks out the status ones. */
        APPLE("只收 Apple 裝置（硬體過濾）"),

        /** No chip filter. The system only delivers results while the screen is on. */
        OPEN("不過濾（僅螢幕亮著時有資料）");

        fun coarser(): Level? = entries.getOrNull(ordinal + 1)
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private var running = false
    private val recentStarts = ArrayDeque<Long>()

    var fast = false
        private set
    var level = Level.STATUS
        private set

    /** Counters since the service started, for the diagnostics shown in the app. */
    var results = 0L
        private set
    var appleResults = 0L
        private set
    var statusFrames = 0L
        private set
    var strongestRssi: Int? = null
        private set
    var lastFrameHex: String? = null
        private set
    var lastFrameAtMs = 0L
        private set

    // Finer detail for the copied diagnostics report: what arrives, even when none of it decodes.
    private val resultsPerLevel = LongArray(Level.entries.size)
    private val recordTypes = java.util.TreeMap<Int, Long>()
    private val pairingKinds = LinkedHashMap<String, PairingSample>()

    private class PairingSample(var count: Long, var bestRssi: Int, var hex: String)

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
            running = false
            onProblem("藍牙掃描失敗（錯誤碼 $errorCode）")
        }
    }

    private fun handle(result: ScanResult) {
        results++
        resultsPerLevel[level.ordinal]++
        val data = result.scanRecord?.getManufacturerSpecificData(ProximityParser.APPLE_COMPANY_ID) ?: return
        appleResults++
        record(data, result.rssi)
        val status = ProximityParser.parse(data) ?: return
        statusFrames++
        strongestRssi = maxOf(strongestRssi ?: result.rssi, result.rssi)
        // Only the readable part; the rest of the broadcast is encrypted.
        lastFrameHex = data.take(11).joinToString(" ") { "%02X".format(it) }
        lastFrameAtMs = SystemClock.elapsedRealtime()
        onProblem(null)
        onStatus(status, result.rssi)
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
                    if (pairingKinds.size < MAX_PAIRING_KINDS) {
                        pairingKinds[kind] = PairingSample(1, rssi, hex(data))
                    }
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
        append("各掃描方式收到的廣播：")
        append(Level.entries.joinToString("・") { "${it.name} ${resultsPerLevel[it.ordinal]}" })
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

    /**
     * Starts scanning, or switches an ongoing scan to the given settings.
     *
     * @param fast scan continuously; otherwise in short low-power windows
     * @return true when the scan now runs with the given settings
     */
    fun start(fast: Boolean, level: Level): Boolean {
        if (running && this.fast == fast && this.level == level) return true

        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            stop()
            onProblem("藍牙已關閉")
            return false
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onProblem("此裝置不支援藍牙低功耗掃描")
            return false
        }

        // The system silently ignores an app that starts more than five scans in 30 seconds.
        // When that budget is used up, keep the current scan rather than lose it.
        val now = SystemClock.elapsedRealtime()
        while (recentStarts.isNotEmpty() && now - recentStarts.first() > 30_000) recentStarts.removeFirst()
        if (running && recentStarts.size >= 4) return false

        return try {
            if (running) scanner.stopScan(callback)
            val settings = ScanSettings.Builder()
                .setScanMode(if (fast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0)
                .build()
            val filters = when (level) {
                Level.STATUS -> listOf(statusFilter())
                Level.APPLE -> listOf(appleFilter())
                Level.OPEN -> emptyList()
            }
            scanner.startScan(filters, settings, callback)
            recentStarts.addLast(now)
            running = true
            this.fast = fast
            this.level = level
            onProblem(null)
            true
        } catch (e: SecurityException) {
            running = false
            onProblem("缺少藍牙權限")
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start scan", e)
            running = false
            onProblem("無法啟動藍牙掃描")
            false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not stop scan", e)
        }
    }

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

    private fun appleFilter(): ScanFilter = ScanFilter.Builder()
        .setManufacturerData(ProximityParser.APPLE_COMPANY_ID, ByteArray(0))
        .build()

    private companion object {
        const val TAG = "PodsScanner"
        const val MAX_PAIRING_KINDS = 12
    }
}
