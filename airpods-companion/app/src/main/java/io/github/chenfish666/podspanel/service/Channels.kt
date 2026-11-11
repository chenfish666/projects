package io.github.chenfish666.podspanel.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** The app's notification channels. Created at app start so that their settings can be opened at any time. */
object Channels {

    /**
     * The notification Android requires of a service that runs in the background. It is in a
     * channel of its own so that the user can turn it off without losing anything else.
     */
    const val SERVICE = "status"

    /** Carries the island. Never shown in the notification shade. */
    const val ISLAND = "island"

    /** Battery popup as a heads-up notification, for when drawing over other apps is not allowed. */
    const val POPUP = "popup"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(SERVICE, "背景執行（可以關閉）", NotificationManager.IMPORTANCE_LOW).apply {
                description = "耳機連線期間，系統要求顯示的常駐通知。關閉後不影響超級島與其他功能。"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(ISLAND, "超級島", NotificationManager.IMPORTANCE_LOW).apply {
                description = "在超級島顯示耳機電量。"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(POPUP, "電量彈窗（未開啟懸浮視窗時）", NotificationManager.IMPORTANCE_HIGH).apply {
                setShowBadge(false)
                setSound(null, null)
            }
        )
    }
}
