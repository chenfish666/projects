package io.github.chenfish666.podspanel

import android.app.Application
import com.google.android.material.color.DynamicColors
import io.github.chenfish666.podspanel.service.Channels

class PodsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        Channels.ensure(this)
    }
}
