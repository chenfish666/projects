package io.github.chenfish666.podspanel.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.chenfish666.podspanel.Prefs

/**
 * Starts the background service at the moments it may be needed: when Bluetooth headphones
 * connect, and after the phone restarts or the app is updated. The service stops by itself
 * again when it finds nothing to do, so no notification lingers while no headphones are in use.
 */
class BootReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // After a restart nothing is connected yet; only an always-on equalizer needs the service.
            Intent.ACTION_BOOT_COMPLETED -> {
                val prefs = Prefs(context)
                if (prefs.eqEnabled && !prefs.eqOnlyWithHeadphones) PodsService.start(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> PodsService.start(context)

            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED ->
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) {
                    PodsService.onHeadphonesConnecting(context)
                }

            // Fires for every Bluetooth device, watches and fitness bands included; only audio
            // devices are worth starting for.
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val major = try {
                    device?.bluetoothClass?.majorDeviceClass
                } catch (e: SecurityException) {
                    null
                }
                if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) PodsService.onHeadphonesConnecting(context)
            }
        }
    }
}
