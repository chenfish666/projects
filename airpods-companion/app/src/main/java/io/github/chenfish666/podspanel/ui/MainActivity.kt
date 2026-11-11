package io.github.chenfish666.podspanel.ui

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import io.github.chenfish666.podspanel.AppState
import io.github.chenfish666.podspanel.Permissions
import io.github.chenfish666.podspanel.Prefs
import io.github.chenfish666.podspanel.R
import io.github.chenfish666.podspanel.core.AapProtocol
import io.github.chenfish666.podspanel.core.AlarmMode
import io.github.chenfish666.podspanel.core.ConnectionState
import io.github.chenfish666.podspanel.core.EqMath
import io.github.chenfish666.podspanel.core.PodModel
import io.github.chenfish666.podspanel.core.PodsTracker
import io.github.chenfish666.podspanel.databinding.ActivityMainBinding
import io.github.chenfish666.podspanel.service.Island
import io.github.chenfish666.podspanel.service.PodsService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val bandSliders = ArrayList<Slider>()
    private val bandValues = ArrayList<TextView>()

    /** True while code, not the user, is moving sliders or chips. */
    private var syncing = false

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshSetup()
            PodsService.start(this)
        }

    private val pickTone =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            prefs.alarmRingtoneUri = uri?.toString()
            showTone()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val basePadding = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left, top = bars.top, right = bars.right, bottom = basePadding + bars.bottom,
            )
            insets
        }

        setUpPopupCard()
        setUpListeningMode()
        setUpEqualizerCard()
        setUpAlarmCard()
        binding.diagnosticsCopy.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("PodsPanel", diagnosticsReport()))
            toast("已複製")
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(AppState.pods, AppState.headphones, AppState.scanProblem, ::Triple)
                        .collect { (pods, headphones, problem) -> showStatus(pods, headphones, problem) }
                }
                launch { AppState.eq.collect(::showEqState) }
                launch { AppState.listeningMode.collect(::showListeningMode) }
                launch { AppState.scan.collect { binding.diagnostics.text = it.describe() } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PodsService.uiVisible = true
        PodsService.fastScan(this)
    }

    override fun onResume() {
        super.onResume()
        // Permissions are granted on other screens, so re-check every time this one comes back.
        refreshSetup()
        refreshIsland()
        showNextAlarm()
    }

    override fun onStop() {
        PodsService.uiVisible = false
        super.onStop()
    }

    /** Everything needed to tell from afar why the headphones are not being detected. */
    private fun diagnosticsReport(): String {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val location = getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        return buildString {
            appendLine("PodsPanel $version")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}・Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("系統版本：${Build.DISPLAY}")
            appendLine("藍牙耳機：${AppState.headphones.value ?: "未連線"}")
            appendLine(
                "權限：藍牙 ${yesNo(Permissions.hasBluetooth(this@MainActivity))}" +
                    "・通知 ${yesNo(Permissions.hasNotifications(this@MainActivity))}" +
                    "・懸浮視窗 ${yesNo(Permissions.hasOverlay(this@MainActivity))}" +
                    "・不限制背景 ${yesNo(Permissions.ignoresBatteryOptimizations(this@MainActivity))}"
            )
            appendLine("定位服務：${if (location) "開" else "關"}")
            appendLine("超級島：${Island.availability(this@MainActivity).label}")
            appendLine("服務：${if (AppState.serviceRunning.value) "執行中" else "未執行"}・${AppState.scanProblem.value ?: "掃描無錯誤"}")
            appendLine(AppState.scan.value.describe())
            append(AppState.scan.value.detail)
        }
    }

    private fun yesNo(value: Boolean) = if (value) "✓" else "✗"

    // ---- Status ----

    private fun showStatus(tracked: PodsTracker.Tracked?, headphones: String?, problem: String?) {
        binding.statusProblem.apply {
            text = problem
            visibility = if (problem == null) View.GONE else View.VISIBLE
        }
        if (tracked == null) {
            binding.statusTitle.text = headphones ?: getString(R.string.status_empty_title)
            binding.statusSubtitle.text = if (headphones != null) {
                "已連線，等待電量資訊…"
            } else {
                getString(R.string.status_empty_hint)
            }
            binding.statusCells.visibility = View.GONE
            return
        }
        val status = tracked.status
        // The direct connection does not say which model it is; the paired name does well enough.
        binding.statusTitle.text =
            if (status.model == PodModel.UNKNOWN) headphones ?: status.model.label else status.model.label
        binding.statusSubtitle.text = listOfNotNull(
            headphones?.let { "已連線到這支手機" },
            status.connection.label.takeIf {
                status.connection !in setOf(ConnectionState.UNKNOWN, ConnectionState.IDLE, ConnectionState.DISCONNECTED)
            },
            "上次讀取的電量".takeIf { !tracked.live },
        ).joinToString(" · ").ifEmpty { "在附近" }
        binding.statusCells.visibility = View.VISIBLE
        binding.statusCells.alpha = if (tracked.live) 1f else 0.6f
        BatteryViews.bind(binding.statusCells, status)
    }

    // ---- Noise control ----

    private val modeButtons = mapOf(
        R.id.modeOff to AapProtocol.ListeningMode.OFF,
        R.id.modeNoise to AapProtocol.ListeningMode.NOISE_CANCELLATION,
        R.id.modeTransparency to AapProtocol.ListeningMode.TRANSPARENCY,
        R.id.modeAdaptive to AapProtocol.ListeningMode.ADAPTIVE,
    )

    private fun setUpListeningMode() {
        binding.listeningMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || syncing) return@addOnButtonCheckedListener
            modeButtons[checkedId]?.let { PodsService.setListeningMode(this, it) }
        }
    }

    /** The row only exists while the AirPods report a mode, i.e. for models with noise control. */
    private fun showListeningMode(mode: AapProtocol.ListeningMode?) {
        binding.listeningMode.visibility = if (mode == null) View.GONE else View.VISIBLE
        val id = modeButtons.entries.firstOrNull { it.value == mode }?.key ?: return
        syncing = true
        binding.listeningMode.check(id)
        syncing = false
    }

    // ---- Island ----

    private fun refreshIsland() {
        Island.refresh()
        val availability = Island.availability(this)
        val relevant = availability != Island.Availability.NOT_XIAOMI && availability != Island.Availability.NO_ISLAND
        binding.island.visibility = if (relevant) View.VISIBLE else View.GONE
        binding.islandHint.visibility = if (relevant) View.VISIBLE else View.GONE
        binding.islandSettings.visibility =
            if (availability == Island.Availability.NOT_ALLOWED) View.VISIBLE else View.GONE
        binding.islandHint.text = when (availability) {
            Island.Availability.AVAILABLE -> "耳機連線時，電量會顯示在螢幕上方的超級島；左右兩側分別是左耳和右耳。"
            Island.Availability.NOT_ALLOWED ->
                "系統尚未允許這個 App 使用超級島。請到這個 App 的通知設定裡開啟「焦點通知」（或「在狀態列顯示」）。" +
                    "如果設定裡沒有這個選項，代表這個系統版本只開放給小米核准的 App，電量會維持顯示在通知列。"
            else -> ""
        }
    }

    // ---- Setup ----

    private data class SetupStep(val name: String, val why: String, val button: Int, val action: () -> Unit)

    private fun refreshSetup() {
        val steps = buildList {
            if (!Permissions.hasBluetooth(this@MainActivity)) {
                add(SetupStep("藍牙權限", "用來讀取 AirPods 的電量與狀態，必要。", R.string.setup_grant) {
                    requestPermissions.launch(Permissions.bluetooth())
                })
            }
            if (!Permissions.hasNotifications(this@MainActivity)) {
                add(SetupStep("通知", "在通知列顯示電量。", R.string.setup_grant) {
                    requestPermissions.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS))
                })
            }
            if (!Permissions.hasOverlay(this@MainActivity)) {
                add(SetupStep("顯示在其他應用程式上層", "電量彈窗需要；未開啟時改用通知。", R.string.setup_open) {
                    open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
                })
            }
            if (!Permissions.ignoresBatteryOptimizations(this@MainActivity)) {
                add(SetupStep("不限制背景用電", "避免系統在背景把服務關掉。", R.string.setup_grant) {
                    @SuppressLint("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())
                    open(intent, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                })
            }
            if (isXiaomi() && !prefs.store.getBoolean(KEY_AUTOSTART_SEEN, false)) {
                add(SetupStep("自動啟動", "HyperOS 需要另外允許，重開機後才會自己啟動。", R.string.setup_open) {
                    prefs.store.edit().putBoolean(KEY_AUTOSTART_SEEN, true).apply()
                    val autostart = Intent().setComponent(
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity",
                        )
                    )
                    open(autostart, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
                })
            }
        }

        val list = binding.setupList
        while (list.childCount > 1) list.removeViewAt(1)
        for (step in steps) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_setup, list, false)
            row.findViewById<TextView>(R.id.setupName).text = step.name
            row.findViewById<TextView>(R.id.setupWhy).text = step.why
            row.findViewById<Button>(R.id.setupButton).apply {
                setText(step.button)
                setOnClickListener { step.action() }
            }
            list.addView(row)
        }
        binding.setupCard.visibility = if (steps.isEmpty()) View.GONE else View.VISIBLE

        PodsService.start(this)
    }

    private fun isXiaomi(): Boolean =
        Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")

    private fun packageUri(): Uri = Uri.fromParts("package", packageName, null)

    /** Opens the first of the given screens that exists on this phone. */
    private fun open(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: RuntimeException) {
                continue
            }
        }
        toast("找不到對應的設定頁面，請到系統設定中手動開啟")
    }

    // ---- Popup ----

    private fun setUpPopupCard() {
        binding.island.isChecked = prefs.islandEnabled
        binding.island.setOnCheckedChangeListener { _, checked -> prefs.islandEnabled = checked }
        binding.islandSettings.setOnClickListener {
            open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()),
            )
        }
        binding.popupConnect.isChecked = prefs.popupOnConnect
        binding.popupLid.isChecked = prefs.popupOnLidOpen
        binding.earPause.isChecked = prefs.earAutoPause
        binding.popupConnect.setOnCheckedChangeListener { _, checked -> prefs.popupOnConnect = checked }
        binding.popupLid.setOnCheckedChangeListener { _, checked -> prefs.popupOnLidOpen = checked }
        binding.earPause.setOnCheckedChangeListener { _, checked -> prefs.earAutoPause = checked }
        binding.popupTest.setOnClickListener {
            if (!PodsService.testPopup(this)) toast("請先允許藍牙權限")
        }
    }

    // ---- Equalizer ----

    private fun setUpEqualizerCard() {
        val gains = prefs.eqGains
        for (i in 0 until EqMath.BAND_COUNT) {
            val row = LayoutInflater.from(this).inflate(R.layout.item_eq_band, binding.eqBands, false)
            row.findViewById<TextView>(R.id.bandLabel).text = EqMath.formatFrequency(EqMath.CENTERS[i])
            val value = row.findViewById<TextView>(R.id.bandValue)
            val slider = row.findViewById<Slider>(R.id.bandSlider)
            slider.value = snap(gains[i])
            slider.contentDescription = "${EqMath.CENTERS[i]} 赫茲"
            value.text = formatDb(gains[i])
            slider.addOnChangeListener { _, db, _ ->
                value.text = formatDb(db)
                if (syncing) return@addOnChangeListener
                // A hand-moved slider means the curve no longer is any preset.
                prefs.eqPreset = null
                syncing = true
                binding.eqPresets.clearCheck()
                syncing = false
                prefs.eqGains = currentGains()
            }
            bandSliders += slider
            bandValues += value
            binding.eqBands.addView(row)
        }

        for (preset in EqMath.PRESETS) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = preset.label
                isCheckable = true
                tag = preset.id
                isChecked = preset.id == prefs.eqPreset
            }
            binding.eqPresets.addView(chip)
        }
        binding.eqPresets.setOnCheckedStateChangeListener { group, checkedIds ->
            if (syncing) return@setOnCheckedStateChangeListener
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val preset = EqMath.presetById(group.findViewById<Chip>(id).tag as String) ?: return@setOnCheckedStateChangeListener
            applyGains(preset.gains, preset.id)
        }
        binding.eqReset.setOnClickListener {
            syncing = true
            binding.eqPresets.clearCheck()
            (0 until binding.eqPresets.childCount)
                .map { binding.eqPresets.getChildAt(it) as Chip }
                .firstOrNull { it.tag == "flat" }?.isChecked = true
            syncing = false
            applyGains(EqMath.PRESETS.first().gains, "flat")
        }

        binding.eqEnabled.isChecked = prefs.eqEnabled
        binding.eqOnlyHeadphones.isChecked = prefs.eqOnlyWithHeadphones
        binding.eqPerSession.isChecked = !prefs.eqGlobal
        binding.eqEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.eqEnabled = checked
            if (checked && !PodsService.start(this)) toast("請先允許藍牙權限")
        }
        binding.eqOnlyHeadphones.setOnCheckedChangeListener { _, checked -> prefs.eqOnlyWithHeadphones = checked }
        binding.eqPerSession.setOnCheckedChangeListener { _, checked -> prefs.eqGlobal = !checked }
    }

    private fun applyGains(gains: FloatArray, presetId: String?) {
        syncing = true
        gains.forEachIndexed { i, db -> bandSliders[i].value = snap(db) }
        syncing = false
        prefs.eqPreset = presetId
        prefs.eqGains = currentGains()
    }

    private fun currentGains() = FloatArray(EqMath.BAND_COUNT) { bandSliders[it].value }

    /** Sliders move in half-decibel steps and reject values in between. */
    private fun snap(db: Float): Float = Math.round(EqMath.clampGain(db) * 2) / 2f

    private fun formatDb(db: Float): String = when {
        db > 0 -> "+%.1f dB".format(db)
        db < 0 -> "−%.1f dB".format(-db)
        else -> "0 dB"
    }

    private fun showEqState(state: AppState.EqState) {
        binding.eqDetail.apply {
            text = state.detail
            visibility = if (state.detail.isEmpty()) View.GONE else View.VISIBLE
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this,
                    if (state.failed) com.google.android.material.R.attr.colorError
                    else com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            )
        }
    }

    // ---- Alarm ----

    private fun setUpAlarmCard() {
        binding.alarmMode.check(
            when (prefs.alarmMode) {
                AlarmMode.OFF -> R.id.alarmOff
                AlarmMode.MIRROR -> R.id.alarmMirror
                AlarmMode.HEADPHONES_ONLY -> R.id.alarmOnly
            }
        )
        showAlarmExplanation()
        binding.alarmMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            prefs.alarmMode = when (checkedId) {
                R.id.alarmOff -> AlarmMode.OFF
                R.id.alarmOnly -> AlarmMode.HEADPHONES_ONLY
                else -> AlarmMode.MIRROR
            }
            showAlarmExplanation()
        }

        binding.alarmVolume.value = (prefs.alarmVolumePercent / 5 * 5).toFloat().coerceIn(10f, 100f)
        binding.alarmVolume.setLabelFormatter { "${it.toInt()}%" }
        binding.alarmVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) prefs.alarmVolumePercent = value.toInt()
        }

        binding.alarmPickTone.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(
                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    prefs.alarmRingtoneUri?.let(Uri::parse)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                )
            try {
                pickTone.launch(intent)
            } catch (e: RuntimeException) {
                toast("這支手機沒有可用的鈴聲選擇器")
            }
        }
        binding.alarmTest.setOnClickListener {
            when {
                AppState.headphones.value == null -> toast("請先連上藍牙耳機")
                !PodsService.testAlarm(this) -> toast("請先允許藍牙權限")
                else -> toast("耳機會響 5 秒")
            }
        }
        showTone()
    }

    private fun showAlarmExplanation() {
        binding.alarmExplain.text = when (prefs.alarmMode) {
            AlarmMode.OFF -> "鬧鐘照系統原本的方式響。"
            AlarmMode.MIRROR -> "鬧鐘響的時候，耳機同時播放鈴聲；手機喇叭照常響。在時鐘 App 關掉或延後鬧鐘，耳機也會跟著停。"
            AlarmMode.HEADPHONES_ONLY ->
                "耳機播放鈴聲，同時把手機喇叭的鬧鐘音量降到系統允許的最低（多數手機無法完全靜音），鬧鐘結束後恢復。" +
                    "只有偵測到耳機戴在耳朵上才會降低喇叭音量，避免耳機在盒子裡時聽不到鬧鐘。"
        }
    }

    private fun showTone() {
        val uri = prefs.alarmRingtoneUri?.let(Uri::parse)
        val name = uri?.let {
            try {
                RingtoneManager.getRingtone(this, it)?.getTitle(this)
            } catch (e: RuntimeException) {
                null
            }
        }
        binding.alarmTone.text = "耳機鈴聲：" + (name ?: "系統預設鬧鐘鈴聲") +
            "\n（無法讀取時鐘 App 為各個鬧鐘設定的鈴聲，耳機一律播放這裡選的。）"
    }

    private fun showNextAlarm() {
        val next = getSystemService(AlarmManager::class.java)?.nextAlarmClock
        binding.alarmNext.text = if (next == null) {
            "目前沒有設定鬧鐘。"
        } else {
            val pattern = DateFormat.getBestDateTimePattern(resources.configuration.locales[0], "MdEHm")
            "下一個鬧鐘：" + DateFormat.format(pattern, Date(next.triggerTime))
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val KEY_AUTOSTART_SEEN = "autostart_seen"
    }
}
