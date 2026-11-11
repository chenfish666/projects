package io.github.chenfish666.podspanel.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.chenfish666.podspanel.core.AapProtocol
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A direct connection to the AirPods over Apple's accessory protocol.
 *
 * Android offers apps no public way to open this kind of Bluetooth channel to a headset, so the
 * socket is created through a method the platform hides. Whether the connection then succeeds
 * depends on the phone's Bluetooth software: several Android versions fail to negotiate the
 * channel with AirPods. When it fails, the app simply stays on the status broadcasts.
 *
 * All callbacks arrive on the main thread.
 */
@SuppressLint("MissingPermission")
class AapLink(
    private val device: BluetoothDevice,
    private val onEvent: (AapProtocol.Event) -> Unit,
    private val onState: (State, detail: String) -> Unit,
) {

    enum class State { CONNECTING, CONNECTED, FAILED, CLOSED }

    private val main = Handler(Looper.getMainLooper())
    private val writer: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var closed = false
    private var attempt = 0

    /** True once every retry failed; the link then stays idle until it is replaced. */
    @Volatile
    var gaveUp = false
        private set

    fun open() {
        closed = false
        attempt = 0
        connect()
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        socket?.runCatching { close() }
        socket = null
        writer.shutdownNow()
    }

    fun send(message: ByteArray) {
        if (closed) return
        writer.execute {
            try {
                socket?.outputStream?.apply {
                    write(message)
                    flush()
                }
            } catch (e: IOException) {
                Log.w(TAG, "Send failed: ${e.message}")
            }
        }
    }

    private fun connect() {
        if (closed) return
        report(State.CONNECTING, "連線中")
        Thread({ run() }, "AAP-${device.address}").apply { isDaemon = true }.start()
    }

    private fun run() {
        var everReceived = false
        val failure: String = try {
            val created = createSocket()
            socket = created
            created.connect()
            if (closed) return

            val out = created.outputStream
            out.write(AapProtocol.HANDSHAKE)
            out.flush()
            for (packet in AapProtocol.ENABLE_NOTIFICATIONS) {
                out.write(packet)
                out.flush()
            }
            out.write(AapProtocol.ENABLE_FEATURES)
            out.flush()
            report(State.CONNECTED, "已連線")

            // The channel delivers whole messages: every read returns exactly one.
            val buffer = ByteArray(2048)
            val input = created.inputStream
            while (!closed) {
                val length = input.read(buffer)
                if (length < 0) break
                everReceived = true
                val event = AapProtocol.parse(buffer.copyOf(length)) ?: continue
                main.post { if (!closed) onEvent(event) }
            }
            "連線中斷"
        } catch (e: Throwable) {
            // Throwable on purpose: a platform that rejects the hidden-interface exemption fails
            // with an Error, and that must not take the app down.
            // The cause of a reflective call is the informative part.
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
            Log.w(TAG, "AAP connection failed", cause)
            "${cause.javaClass.simpleName}: ${cause.message}"
        } finally {
            socket?.runCatching { close() }
            socket = null
        }
        if (closed) return

        // A session that worked is worth reopening at once; one that never did is retried a
        // few times, further apart, and then left alone until the headphones reconnect.
        if (everReceived) attempt = 0
        val delay = RETRY_DELAYS_MS.getOrNull(attempt++)
        gaveUp = delay == null
        report(State.FAILED, if (delay == null) failure else "$failure（稍後重試）")
        if (delay != null) main.postDelayed({ connect() }, delay)
    }

    // The method is on the platform's restricted list; the exemption below is what makes the call
    // possible, and a failure simply leaves the app on the status broadcasts.
    @SuppressLint("SoonBlockedPrivateApi")
    private fun createSocket(): BluetoothSocket {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/")
        val method = BluetoothDevice::class.java.getDeclaredMethod(
            "createInsecureL2capSocket", Int::class.javaPrimitiveType,
        )
        return method.invoke(device, AapProtocol.PSM) as BluetoothSocket
    }

    private fun report(state: State, detail: String) {
        main.post { if (!closed) onState(state, detail) }
    }

    private companion object {
        const val TAG = "AapLink"
        val RETRY_DELAYS_MS = longArrayOf(3_000, 10_000, 30_000)
    }
}
