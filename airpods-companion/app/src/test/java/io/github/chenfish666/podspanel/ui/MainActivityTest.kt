package io.github.chenfish666.podspanel.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import io.github.chenfish666.podspanel.AppState
import io.github.chenfish666.podspanel.Prefs
import io.github.chenfish666.podspanel.R
import io.github.chenfish666.podspanel.core.AlarmMode
import io.github.chenfish666.podspanel.core.ConnectionState
import io.github.chenfish666.podspanel.core.EqMath
import io.github.chenfish666.podspanel.core.LidState
import io.github.chenfish666.podspanel.core.PodsStatus
import io.github.chenfish666.podspanel.core.PodsTracker
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {

    private val status = PodsStatus(
        modelCode = 0x3620, left = 90, right = 20, case = null,
        leftCharging = true, rightCharging = false, caseCharging = false,
        leftInEar = false, rightInEar = true, lid = LidState.NOT_IN_CASE,
        connection = ConnectionState.MUSIC, colorCode = 0,
    )

    @After
    fun tearDown() {
        AppState.pods.value = null
        AppState.headphones.value = null
    }

    @Test
    fun `screen opens and shows the followed AirPods`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    activity.getString(R.string.status_empty_title),
                    activity.findViewById<TextView>(R.id.statusTitle).text.toString(),
                )

                AppState.pods.value = PodsTracker.Tracked(status, rssi = -50, seenAtMs = 0, live = true)
                shadowOf(Looper.getMainLooper()).idle()

                assertEquals("AirPods 5", activity.findViewById<TextView>(R.id.statusTitle).text.toString())
                val cells = activity.findViewById<View>(R.id.statusCells)
                assertEquals(View.VISIBLE, cells.visibility)
                val left = cells.findViewById<View>(R.id.cellLeft)
                val right = cells.findViewById<View>(R.id.cellRight)
                val case = cells.findViewById<View>(R.id.cellCase)
                assertEquals("90% ⚡", left.findViewById<TextView>(R.id.cellPercent).text.toString())
                assertEquals("20%", right.findViewById<TextView>(R.id.cellPercent).text.toString())
                assertEquals("—", case.findViewById<TextView>(R.id.cellPercent).text.toString())
            }
        }
    }

    @Test
    fun `choosing a preset moves the sliders and stores the curve`() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val presets = activity.findViewById<ChipGroup>(R.id.eqPresets)
                val bass = (0 until presets.childCount).map { presets.getChildAt(it) as Chip }.first { it.tag == "bass" }
                bass.performClick()

                val expected = EqMath.presetById("bass")!!.gains
                assertArrayEquals(expected, prefs.eqGains, 0f)
                assertEquals("bass", prefs.eqPreset)

                val bands = activity.findViewById<LinearLayout>(R.id.eqBands)
                assertEquals(EqMath.BAND_COUNT, bands.childCount)
                val first = bands.getChildAt(0).findViewById<Slider>(R.id.bandSlider)
                assertEquals(expected[0], first.value, 0f)

                // Moving a slider by hand leaves the preset.
                first.value = 3f
                assertNull(prefs.eqPreset)
                assertEquals(3f, prefs.eqGains[0], 0f)
            }
        }
    }

    @Test
    fun `alarm mode buttons store the mode`() {
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(AlarmMode.MIRROR, prefs.alarmMode)
                activity.findViewById<View>(R.id.alarmOnly).performClick()
                assertEquals(AlarmMode.HEADPHONES_ONLY, prefs.alarmMode)
                activity.findViewById<View>(R.id.alarmOff).performClick()
                assertEquals(AlarmMode.OFF, prefs.alarmMode)
            }
        }
    }

    @Test
    fun `popup layout inflates and shows the levels`() {
        val context = android.view.ContextThemeWrapper(
            ApplicationProvider.getApplicationContext(), R.style.Theme_PodsPanel,
        )
        val view = LayoutInflater.from(context).inflate(R.layout.popup_pods, null)
        BatteryViews.bind(view.findViewById(R.id.popupCells), status)
        val right = view.findViewById<View>(R.id.cellRight)
        assertEquals("20%", right.findViewById<TextView>(R.id.cellPercent).text.toString())
        assertEquals("右耳 · 配戴中", right.findViewById<TextView>(R.id.cellLabel).text.toString())
        assertEquals("左 90%⚡ · 右 20%", BatteryViews.summary(status))
    }
}
