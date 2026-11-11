package io.github.chenfish666.podspanel.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import io.github.chenfish666.podspanel.R
import io.github.chenfish666.podspanel.core.ConnectionState
import io.github.chenfish666.podspanel.core.PodsStatus

/**
 * The card that slides up from the bottom of the screen with the battery levels.
 *
 * It is drawn over whatever app is open, which needs the "display over other apps" permission.
 * The window does not take focus, so the app underneath stays usable while the card is up.
 */
class PopupController(context: Context) {

    private val themed = ContextThemeWrapper(context.applicationContext, R.style.Theme_PodsPanel)
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private val dismissLater = Runnable { dismiss() }

    val showing: Boolean get() = root != null

    fun allowed(): Boolean = Settings.canDrawOverlays(themed)

    /** Shows the card, or refreshes it and restarts its timer when it is already up. */
    fun show(status: PodsStatus, title: String, subtitle: String? = null): Boolean {
        if (!allowed()) return false
        val view = root ?: create() ?: return false
        view.findViewById<TextView>(R.id.popupTitle).text = title
        bind(view, status, subtitle)
        handler.removeCallbacks(dismissLater)
        handler.postDelayed(dismissLater, VISIBLE_MS)
        return true
    }

    /** Refreshes the levels on a card that is already up; does nothing otherwise. */
    fun update(status: PodsStatus) {
        root?.let { bind(it, status, null) }
    }

    fun dismiss() {
        handler.removeCallbacks(dismissLater)
        val view = root ?: return
        root = null
        view.findViewById<View>(R.id.popupCard).animate()
            .translationY(view.height.toFloat())
            .alpha(0f)
            .setDuration(180)
            .withEndAction { remove(view) }
            .start()
    }

    private fun create(): View? {
        val view = LayoutInflater.from(themed).inflate(R.layout.popup_pods, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }
        try {
            windowManager.addView(view, params)
        } catch (e: RuntimeException) {
            // The permission can be withdrawn at any moment, and some systems add a second
            // switch for windows opened from the background.
            Log.w(TAG, "Could not show popup: ${e.message}")
            return null
        }
        root = view

        view.findViewById<View>(R.id.popupClose).setOnClickListener { dismiss() }
        val card = view.findViewById<View>(R.id.popupCard)
        card.setOnClickListener { dismiss() }
        card.alpha = 0f
        card.translationY = 320 * themed.resources.displayMetrics.density
        card.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
        return view
    }

    private fun bind(view: View, status: PodsStatus, subtitle: String?) {
        val line = subtitle ?: status.connection.label.takeIf { status.connection != ConnectionState.UNKNOWN }
        view.findViewById<TextView>(R.id.popupSubtitle).apply {
            if (line.isNullOrEmpty()) {
                // Keep the previous subtitle when a refresh has nothing better to say.
                if (text.isNullOrEmpty()) visibility = View.GONE
            } else {
                text = line
                visibility = View.VISIBLE
            }
        }
        BatteryViews.bind(view.findViewById(R.id.popupCells), status)
    }

    private fun remove(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Popup was already gone: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "PopupController"
        const val VISIBLE_MS = 6_000L
    }
}
