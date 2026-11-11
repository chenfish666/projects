package io.github.chenfish666.podspanel.service

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.chenfish666.podspanel.R
import io.github.chenfish666.podspanel.core.PodsStatus
import io.github.chenfish666.podspanel.ui.BatteryViews
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo

/**
 * Shows the battery levels in the island of Xiaomi HyperOS 3, the pill around the front camera.
 *
 * HyperOS builds the island from extra data attached to an ordinary notification, so this class
 * only decorates the status notification the service already posts. The system decides whether
 * an app may use the island; where it says no, the notification stays an ordinary one.
 */
object Island {

    enum class Availability(val label: String) {
        AVAILABLE("可用"),
        NOT_XIAOMI("這支手機沒有超級島"),
        NO_ISLAND("這個系統版本沒有超級島"),
        NOT_ALLOWED("系統尚未允許這個 App 使用超級島"),
    }

    /** Asking the system costs a round trip, and the answer only changes when the user flips a setting. */
    private var cached: Availability? = null
    private var cachedAtMs = 0L

    fun availability(context: Context): Availability {
        val now = SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAtMs < CACHE_MS) return it }
        val fresh = when {
            !isXiaomi() -> Availability.NOT_XIAOMI
            !systemHasIsland() -> Availability.NO_ISLAND
            !appAllowed(context) -> Availability.NOT_ALLOWED
            else -> Availability.AVAILABLE
        }
        cached = fresh
        cachedAtMs = now
        return fresh
    }

    /** Forgets the cached answer, for when the user comes back from the system settings. */
    fun refresh() {
        cached = null
    }

    fun isXiaomi(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    /**
     * Turns [builder] into a notification that HyperOS presents in the island.
     *
     * The layout follows the ears: the left earbud's level sits left of the camera, the right
     * one's right of it. When another island takes the space, a ring around the earbud icon
     * shows the lower of the two levels.
     *
     * @param expand let the island open once, used when the headphones have just connected
     * @return null when the extras could not be built, so the caller posts the plain notification
     */
    fun decorate(
        context: Context,
        builder: NotificationCompat.Builder,
        title: String,
        status: PodsStatus,
        expand: Boolean,
    ): Notification? = try {
        fun level(percent: Int?, charging: Boolean) = when {
            percent == null -> "—"
            charging -> "$percent%⚡"
            else -> "$percent%"
        }

        val pod = "miui.focus.pic_$PIC_POD"
        val island = HyperIslandNotification.Builder(context, BUSINESS, title)
            .setSmallWindowTarget("${context.packageName}.ui.MainActivity")
            .addPicture(HyperPicture(PIC_POD, context, R.drawable.ic_notification))
            .setBaseInfo(title = title, content = BatteryViews.summary(status), pictureKey = PIC_POD)
            .setEnableFloat(false)
            .setIslandFirstFloat(expand)
            .setShowNotification(true)

        val lowest = status.lowestPod
        if (lowest != null) {
            island.setSmallIslandCircularProgress(
                pictureKey = PIC_POD,
                progress = lowest,
                color = if (lowest <= LOW_PERCENT) COLOR_LOW else COLOR_OK,
            )
        } else {
            island.setSmallIsland(PIC_POD)
        }

        val left = ImageTextInfoLeft(
            type = 1,
            picInfo = PicInfo(type = 1, pic = pod),
            textInfo = TextInfo(title = level(status.left, status.leftCharging)),
        )
        val right = if (status.model.dual) {
            ImageTextInfoRight(type = 2, textInfo = TextInfo(title = level(status.right, status.rightCharging)))
        } else {
            null
        }
        island.setBigIslandInfo(left = left, right = right)

        builder.addExtras(island.buildResourceBundle())
        builder.build().also { it.extras.putString("miui.focus.param", island.buildJsonParam()) }
    } catch (e: Exception) {
        Log.w(TAG, "Could not build island notification", e)
        null
    }

    @SuppressLint("PrivateApi")
    private fun systemHasIsland(): Boolean = try {
        val getBoolean = Class.forName("android.os.SystemProperties")
            .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        getBoolean.invoke(null, "persist.sys.feature.island", false) as Boolean
    } catch (e: Exception) {
        false
    }

    private fun appAllowed(context: Context): Boolean = try {
        val args = Bundle().apply { putString("package", context.packageName) }
        context.contentResolver
            .call(Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null, args)
            ?.getBoolean("canShowFocus", false) == true
    } catch (e: Exception) {
        false
    }

    private const val TAG = "Island"
    private const val CACHE_MS = 30_000L
    private const val BUSINESS = "headphones_battery"
    private const val PIC_POD = "pod"
    private const val LOW_PERCENT = 20
    private const val COLOR_OK = "#34C759"
    private const val COLOR_LOW = "#FF453A"
}
