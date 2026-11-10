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

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private var running = false
    private var fast = false
    private var filtered = true
    private val recentStarts = ArrayDeque<Long>()

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
        val data = result.scanRecord?.getManufacturerSpecificData(ProximityParser.APPLE_COMPANY_ID)
        val status = ProximityParser.parse(data) ?: return
        onProblem(null)
        onStatus(status, result.rssi)
    }

    /**
     * Starts scanning, or switches an ongoing scan to the given settings.
     *
     * @param fast scan continuously; otherwise in short low-power windows
     * @param filtered let the Bluetooth chip pre-filter for AirPods. Required for results while
     *   the screen is off, but some firmware delivers nothing with it.
     */
    fun start(fast: Boolean, filtered: Boolean) {
        if (running && this.fast == fast && this.filtered == filtered) return

        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            stop()
            onProblem("藍牙已關閉")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onProblem("此裝置不支援藍牙低功耗掃描")
            return
        }

        // The system silently ignores an app that starts more than five scans in 30 seconds.
        // When that budget is used up, keep the current scan rather than lose it.
        val now = SystemClock.elapsedRealtime()
        while (recentStarts.isNotEmpty() && now - recentStarts.first() > 30_000) recentStarts.removeFirst()
        if (running && recentStarts.size >= 4) return

        try {
            if (running) scanner.stopScan(callback)
            val settings = ScanSettings.Builder()
                .setScanMode(if (fast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0)
                .build()
            val filters = if (filtered) listOf(statusFilter()) else emptyList()
            scanner.startScan(filters, settings, callback)
            recentStarts.addLast(now)
            running = true
            this.fast = fast
            this.filtered = filtered
            onProblem(null)
        } catch (e: SecurityException) {
            running = false
            onProblem("缺少藍牙權限")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start scan", e)
            running = false
            onProblem("無法啟動藍牙掃描")
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

    private companion object {
        const val TAG = "PodsScanner"
    }
}
