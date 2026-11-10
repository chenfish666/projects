package io.github.chenfish666.podspanel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the background service back after a restart of the phone or an update of the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PodsService.start(context)
        }
    }
}
