package io.github.chenfish666.podspanel

import android.app.Application
import com.google.android.material.color.DynamicColors

class PodsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
