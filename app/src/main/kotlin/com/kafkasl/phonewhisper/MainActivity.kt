package com.kafkasl.phonewhisper

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import java.io.File

class MainActivity : AppCompatActivity() {

    // ---- pages ----
    private lateinit var pageHome: ScrollView
    private lateinit var pageModels: ScrollView
    private lateinit var pageSettings: ScrollView
    private lateinit var pageHistory: ScrollView
    private lateinit var historyView: HistoryView

    // ---- home ----
    private lateinit var heroTitle: TextView
    private lateinit var heroBody: TextView
    private lateinit var heroChips: TextView
    private lateinit var tipText: TextView
    private lateinit var lastCard: View
    private lateinit var lastText: TextView
    private lateinit var lastMeta: TextView
    private lateinit var checkMic: CheckRow
    private lateinit var checkAcc: CheckRow
    private lateinit var checkModel: CheckRow
    private lateinit var checkBatt: CheckRow
    private lateinit var checklistWrap: View

    // ---- settings ----
    private lateinit var modeSub: TextView
    private lateinit var opacitySub: TextView
    private lateinit var languageSub: TextView
    private lateinit var fillerSub: TextView
    private lateinit var keepSub: TextView

    private val modelRows = mutableMapOf<String, ModelRowViews>()

    private data class ModelRowViews(
        val title: TextView,
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val delBtn: MaterialButton,
    )

    private class CheckRow(val view: View, val icon: TextView, val sub: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pageHome = scroll(buildHome())
        pageModels = scroll(buildModels())
        pageSettings = scroll(buildSettings())
        historyView = HistoryView(this) { refresh() }
        pageHistory = scroll(historyView)

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, 0, 1f)
            addView(pageHome); addView(pageModels); addView(pageSettings); addView(pageHistory)
        }

        val nav = BottomNavigationView(this).apply {
            inflateMenu(R.menu.bottom_nav)
            setOnItemSelectedListener { item ->
                show(item.itemId)
                true
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(frame)
            addView(nav, LinearLayout.LayoutParams(LP_MATCH, LP_WRAP))
        })
        show(R.id.nav_home)

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    private fun show(id: Int) {
        pageHome.visibility = if (id == R.id.nav_home) View.VISIBLE else View.GONE
        pageModels.visibility = if (id == R.id.nav_models) View.VISIBLE else View.GONE
        pageSettings.visibility = if (id == R.id.nav_settings) View.VISIBLE else View.GONE
        pageHistory.visibility = if (id == R.id.nav_history) View.VISIBLE else View.GONE
        if (id == R.id.nav_history) historyView.render()
        if (id == R.id.nav_home) refresh()
    }

    // =====================================================================
    //  HOME
    // =====================================================================

    private fun buildHome(): LinearLayout {
        val root = vertical(0)
        root.addView(pageTitle(getString(R.string.app_name), "Offline voice typing for any app"))

        // Hero status card
        val hero = MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorPrimaryContainer))
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, LP_WRAP).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) }
        }
        val heroCol = vertical(dp(24), dp(22))
        heroTitle = TextView(this).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
        }
        heroBody = TextView(this).apply {
            textSize = 15f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
            setPadding(0, dp(6), 0, 0)
        }
        heroChips = TextView(this).apply {
            textSize = 13f
            alpha = 0.75f
            setTextColor(attrColor(com.google.android.material.R.attr.colorOnPrimaryContainer))
            setPadding(0, dp(14), 0, 0)
        }
        heroCol.addView(heroTitle); heroCol.addView(heroBody); heroCol.addView(heroChips)
        hero.addView(heroCol)
        root.addView(hero)

        // Setup checklist
        checkMic = checkRow("Microphone", "Needed to hear you") {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        checkAcc = checkRow("Accessibility service", "Lets the bubble type into other apps") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        checkModel = checkRow("Speech model", "Download one in the Models tab") { show(R.id.nav_models); }
        checkBatt = checkRow("Keep running in background", "Stops Android putting Utter to sleep") { requestBatteryExemption() }
        checklistWrap = section("Setup", listOf(checkMic.view, checkAcc.view, checkModel.view, checkBatt.view))
        root.addView(checklistWrap)

        // How to use
        tipText = TextView(this).apply {
            textSize = 15f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        root.addView(section("How to dictate", listOf(tipText)))

        val tryInput = EditText(this).apply {
            hint = "Tap here, then use the bubble to dictate"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            textSize = 15f
            background = null
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        root.addView(section("Try it here", listOf(tryInput)))

        // Latest dictation
        lastText = TextView(this).apply {
            textSize = 15f
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        }
        lastMeta = TextView(this).apply {
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(6), 0, 0)
        }
        val lastCol = vertical(dp(20), dp(16)).apply {
            addView(lastText); addView(lastMeta)
            isClickable = true
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dictation", lastText.text))
                toast("Copied")
            }
        }
        lastCard = section("Latest dictation", listOf(lastCol))
        root.addView(lastCard)

        root.addView(spacer(24))
        return root
    }

    private fun checkRow(title: String, sub: String, onClick: () -> Unit): CheckRow {
        val icon = TextView(this).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(14) }
        }
        val row = settingsRow(title, sub, leading = icon, onClick = onClick)
        return CheckRow(row, icon, row.findViewWithTag("subtitle"))
    }

    private fun setCheck(c: CheckRow, ok: Boolean, okText: String, todoText: String) {
        c.icon.text = if (ok) "✓" else "!"
        c.icon.setTextColor(if (ok) attrColor(com.google.android.material.R.attr.colorPrimary) else 0xFFE8710A.toInt())
        c.sub.text = if (ok) okText else todoText
    }

    // =====================================================================
    //  MODELS
    // =====================================================================

    private fun buildModels(): LinearLayout {
        val root = vertical(0)
        root.addView(pageTitle("Speech models", "Everything runs on your phone. Downloads are the only time the app goes online."))
        var family = ""
        var rows = mutableListOf<View>()
        fun flush() {
            if (rows.isNotEmpty()) root.addView(section(family, rows.toList()))
            rows = mutableListOf()
        }
        for (m in MODEL_CATALOG) {
            if (m.family != family) { flush(); family = m.family }
            rows.add(buildModelRow(m))
        }
        flush()
        root.addView(spacer(24))
        return root
    }

    private fun buildModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val delBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "\uD83D\uDDD1"
            textSize = 16f
            setOnClickListener { confirmDelete(model) }
        }
        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply { topMargin = dp(8) }
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn); addView(delBtn); addView(radio)
        }
        val row = settingsRow(model.name, "", right) { onModelAction(model) }
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)
        modelRows[model.archive] = ModelRowViews(
            textContainer.getChildAt(0) as TextView, radio, progress,
            textContainer.findViewWithTag("subtitle"), dlBtn, delBtn
        )
        refreshCard(model)
        return row
    }

    private fun modelSubtitle(model: Model, active: Boolean, installed: Boolean): String {
        val base = "${model.langs} · ${model.note}"
        return when {
            active -> "Active · $base"
            installed -> "Installed · $base"
            else -> "$base · ${model.sizeMb} MB download"
        }
    }

    private fun onModelAction(model: Model) {
        val views = modelRows[model.archive] ?: return
        if (ModelDownloader.isInstalled(this, model)) { selectModel(model.archive); return }

        views.dlBtn.isEnabled = false
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = false
        views.subtitle.text = "Starting download..."

        ModelDownloader.download(this, model) { state ->
            runOnUiThread {
                when (state) {
                    is DownloadState.Downloading -> {
                        views.progress.progress = (state.progress * 100).toInt()
                        views.subtitle.text = "Downloading: ${(state.progress * 100).toInt()}%"
                    }
                    is DownloadState.Extracting -> {
                        views.progress.isIndeterminate = true
                        views.subtitle.text = "Extracting..."
                    }
                    is DownloadState.Done -> {
                        views.progress.visibility = View.GONE
                        selectModel(model.archive)
                        toast("${model.name} ready")
                    }
                    is DownloadState.Error -> {
                        views.progress.visibility = View.GONE
                        views.subtitle.text = "Error: ${state.message}"
                        views.dlBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun confirmDelete(model: Model) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Uninstall ${model.name}?")
            .setMessage("This removes the downloaded files from your phone. You can download it again later.")
            .setPositiveButton("Uninstall") { _, _ ->
                val wasActive = prefs().getString("model_name", "") == model.archive
                if (wasActive) WhisperAccessibilityService.instance?.unloadModel()
                ModelDownloader.delete(this, model)
                if (wasActive) {
                    prefs().edit().putString("model_name", "").apply()
                    MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }?.let { selectModel(it.archive) }
                }
                refreshAllCards(); refresh()
                toast("${model.name} uninstalled")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        refreshAllCards(); refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)
        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE
        views.delBtn.visibility = if (installed) View.VISIBLE else View.GONE
        views.title.setTextColor(if (active) attrColor(com.google.android.material.R.attr.colorPrimary) else attrColor(android.R.attr.textColorPrimary))
        if (views.progress.visibility == View.GONE) views.subtitle.text = modelSubtitle(model, active, installed)
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    // =====================================================================
    //  SETTINGS
    // =====================================================================

    private fun buildSettings(): LinearLayout {
        val root = vertical(0)
        root.addView(pageTitle("Settings", null))

        // Button
        val modeRow = settingsRow("Trigger", modeLabel()) { chooseMode() }
        modeSub = modeRow.findViewWithTag("subtitle")
        val opacityRow = settingsRow("Idle opacity", "${opacityPct()}%")
        opacitySub = opacityRow.findViewWithTag("subtitle")
        val slider = SeekBar(this).apply {
            max = 80
            progress = opacityPct() - 20
            setPadding(dp(20), 0, dp(20), dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    val pct = value + 20
                    prefs().edit().putInt("button_opacity", pct).apply()
                    opacitySub.text = "$pct%"
                    WhisperAccessibilityService.instance?.applySettings()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(section("Bubble", listOf(modeRow, opacityRow, slider)))

        // Speech
        val langRow = settingsRow("Language", languageText()) { chooseLanguage() }
        languageSub = langRow.findViewWithTag("subtitle")
        root.addView(section("Speech", listOf(
            langRow,
            toggleRow("Trim silence", "Skip silent parts; ignore clips with no speech", "trim_silence", true),
        )))

        // Text cleanup
        val fillerRow = settingsRow("Filler word list", fillerList()) { editFillers() }
        fillerSub = fillerRow.findViewWithTag("subtitle")
        root.addView(section("Text cleanup", listOf(
            toggleRow("Remove filler words", "Drops um, uh, er and similar", "clean_fillers", true),
            fillerRow,
            toggleRow("Fix capitalisation", "Capital letters at sentence starts, and \"I\"", "clean_caps", true),
            toggleRow("Spoken punctuation", "Say \"comma\", \"period\", \"new line\" to insert them", "spoken_punct", false),
        )))

        // History
        val keepRow = settingsRow("Keep last", keepText()) { chooseKeep() }
        keepSub = keepRow.findViewWithTag("subtitle")
        root.addView(section("History", listOf(
            toggleRow("Save history", "A private list of past dictations, on this phone only", "history_enabled", true),
            keepRow,
        )))

        // Reliability
        root.addView(section("Reliability", listOf(
            settingsRow("Battery optimisation", "Set to Unrestricted so Android doesn't stop the bubble") {
                requestBatteryExemption()
            },
        )))

        // About
        root.addView(section("About", listOf(
            infoRow("Privacy", "Audio is processed on this phone and never uploaded. The only network use is downloading models when you ask."),
            infoRow("Version", packageManager.getPackageInfo(packageName, 0).versionName ?: ""),
            debugToggleRow(),
            settingsRow("Debug report", "Device info and recorded events, to copy and share") { showDiagnostics() },
        )))
        root.addView(spacer(24))
        return root
    }

    private lateinit var debugSub: TextView

    private fun debugSubtitle(): String {
        if (!Diag.isEnabled(this)) return "Off. Turn on to record service and microphone events (never audio or your words)"
        val left = ((Diag.expiresAt(this) - System.currentTimeMillis()) / 3_600_000L).coerceAtLeast(0)
        return "On. Records events only, switches itself off in about ${left}h"
    }

    private fun debugToggleRow(): LinearLayout {
        val sw = MaterialSwitch(this).apply {
            isChecked = Diag.isEnabled(this@MainActivity)
            isClickable = false
        }
        val row = settingsRow("Debug mode", debugSubtitle(), sw) {
            val on = !sw.isChecked
            if (on) Diag.enable(this) else Diag.disable(this)
            sw.isChecked = on
            debugSub.text = debugSubtitle()
        }
        debugSub = row.findViewWithTag("subtitle")
        return row
    }

    private fun showDiagnostics() {
        val on = Diag.isEnabled(this)
        val tv = TextView(this).apply {
            text = (if (on) "" else "Debug mode is off, so no events are being recorded. Turn it on, reproduce the problem, then come back here.\n\n") +
                Diag.deviceReport(this@MainActivity) + "\n\n--- events ---\n" + Diag.readLog(this@MainActivity)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val sc = ScrollView(this).apply { addView(tv) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Debug report")
            .setView(sc)
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("utter debug report", Diag.fullReport(this)))
                toast("Copied")
            }
            .setNeutralButton("Clear events") { _, _ -> Diag.clear(this) }
            .setNegativeButton("Close", null)
            .show()
        sc.post { sc.fullScroll(View.FOCUS_DOWN) }
    }

    private fun opacityPct() = prefs().getInt("button_opacity", 85).coerceIn(20, 100)

    private fun modeLabel() = when (prefs().getString("trigger_mode", "both")) {
        "tap" -> "Tap to start, tap to stop"
        "hold" -> "Hold to talk, release to finish"
        else -> "Tap to toggle, or hold to talk"
    }

    private fun chooseMode() {
        val keys = arrayOf("tap", "hold", "both")
        val labels = arrayOf("Tap (tap to start, tap to stop)", "Hold (hold to talk, release to finish)", "Both (tap or hold)")
        val current = keys.indexOf(prefs().getString("trigger_mode", "both")).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Bubble trigger")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs().edit().putString("trigger_mode", keys[which]).apply()
                modeSub.text = modeLabel()
                WhisperAccessibilityService.instance?.applySettings()
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun languageText() = languageName(prefs().getString("language", "auto") ?: "auto") +
        " · Whisper multilingual models only"

    private fun chooseLanguage() {
        val current = LANGUAGES.indexOfFirst { it.first == (prefs().getString("language", "auto") ?: "auto") }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Language")
            .setSingleChoiceItems(LANGUAGES.map { it.second }.toTypedArray(), current) { dialog, which ->
                prefs().edit().putString("language", LANGUAGES[which].first).apply()
                languageSub.text = languageText()
                WhisperAccessibilityService.instance?.reloadModel()
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val keepOptions = listOf(25, 50, 100, 250, 500)
    private fun keepCount() = prefs().getInt("history_max_items", HistoryStore.DEFAULT_MAX_ITEMS)
    private fun keepText() = "${keepCount()} dictations"

    private fun chooseKeep() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Keep the last…")
            .setSingleChoiceItems(keepOptions.map { "$it dictations" }.toTypedArray(),
                keepOptions.indexOf(keepCount()).coerceAtLeast(0)) { dialog, which ->
                prefs().edit().putInt("history_max_items", keepOptions[which]).apply()
                keepSub.text = keepText()
                Thread { HistoryStore(this).prune(keepOptions[which]) }.start()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fillerList() = prefs().getString("filler_words", TextCleaner.DEFAULT_FILLERS) ?: TextCleaner.DEFAULT_FILLERS

    private fun editFillers() {
        val input = EditText(this).apply {
            hint = "um, uh, er"
            setText(fillerList())
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Filler words (comma separated)")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val cleaned = TextCleaner.parseFillers(input.text.toString()).joinToString(", ")
                prefs().edit().putString("filler_words", cleaned).apply()
                fillerSub.text = cleaned.ifBlank { "(none)" }
            }
            .setNeutralButton("Reset") { _, _ ->
                prefs().edit().putString("filler_words", TextCleaner.DEFAULT_FILLERS).apply()
                fillerSub.text = TextCleaner.DEFAULT_FILLERS
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleRow(title: String, subtitle: String, key: String, default: Boolean): LinearLayout {
        val sw = MaterialSwitch(this).apply {
            isChecked = prefs().getBoolean(key, default)
            isClickable = false
        }
        return settingsRow(title, subtitle, sw) {
            val v = !sw.isChecked
            prefs().edit().putBoolean(key, v).apply()
            sw.isChecked = v
        }
    }

    // =====================================================================
    //  STATE
    // =====================================================================

    private fun triggerTip() = when (prefs().getString("trigger_mode", "both")) {
        "tap" -> "1. Tap a text box in any app.\n2. Tap the bubble, speak, then tap it again.\nYour words appear where the cursor is."
        "hold" -> "1. Tap a text box in any app.\n2. Hold the bubble while you speak, then let go.\nYour words appear where the cursor is."
        else -> "1. Tap a text box in any app.\n2. Hold the bubble while you speak and let go, or tap once to start and tap again to finish.\nYour words appear where the cursor is."
    }

    private fun isBatteryExempt() =
        (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()

        val cur = prefs().getString("model_name", "") ?: ""
        if (cur.isBlank() || !File(filesDir, "models/$cur").exists()) {
            MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }?.let { selectModel(it.archive); return }
        }

        setCheck(checkMic, audio, "Granted", "Tap to grant")
        setCheck(checkAcc, acc, "Enabled", "Tap to enable in Accessibility settings")
        val active = MODEL_CATALOG.firstOrNull { it.archive == cur }
        setCheck(checkModel, hasModel, active?.name ?: "Installed", "Tap to choose a model")

        val batt = isBatteryExempt()
        setCheck(checkBatt, batt, "Unrestricted", "Tap to allow")
        val ready = audio && acc && hasModel && batt
        heroTitle.text = if (ready) "Ready to dictate" else "Almost there"
        heroBody.text = if (ready) "Open any app, tap a text box, and use the bubble."
        else "Finish the steps below and you're set."
        heroChips.text = listOfNotNull(
            active?.name,
            languageName(prefs().getString("language", "auto") ?: "auto").takeIf { active?.family == "Whisper" },
        ).joinToString("  ·  ")
        heroChips.visibility = if (heroChips.text.isNullOrBlank()) View.GONE else View.VISIBLE
        checklistWrap.visibility = if (ready) View.GONE else View.VISIBLE

        tipText.text = triggerTip()

        val last = try { HistoryStore(this).list(1).firstOrNull() } catch (_: Exception) { null }
        if (last != null && prefs().getBoolean("history_enabled", true)) {
            lastCard.visibility = View.VISIBLE
            lastText.text = last.text
            lastMeta.text = "Tap to copy · " + android.text.format.DateUtils.getRelativeTimeSpanString(
                last.ts, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
        } else lastCard.visibility = View.GONE

        refreshAllCards()
    }

    // =====================================================================
    //  UI HELPERS
    // =====================================================================

    private fun scroll(content: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(content)
    }

    private fun pageTitle(title: String, subtitle: String?): LinearLayout = vertical(dp(24), 0).apply {
        setPadding(dp(24), dp(48), dp(24), dp(8))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 32f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        if (subtitle != null) addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(4), 0, 0)
        })
    }

    /** A titled group of rows inside one rounded card. */
    private fun section(title: String?, rows: List<View>): LinearLayout {
        val wrap = vertical(0).apply { setPadding(dp(16), dp(10), dp(16), dp(4)) }
        if (title != null) wrap.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(8), 0, dp(8), dp(8))
        })
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainer))
        }
        val inner = vertical(0)
        rows.forEachIndexed { i, r ->
            if (i > 0 && r !is SeekBar) inner.addView(View(this).apply {
                setBackgroundColor(attrColor(com.google.android.material.R.attr.colorOutlineVariant))
                alpha = 0.6f
                layoutParams = LinearLayout.LayoutParams(LP_MATCH, 1).apply { leftMargin = dp(20); rightMargin = dp(20) }
            })
            inner.addView(r)
        }
        card.addView(inner)
        wrap.addView(card)
        return wrap
    }

    private fun settingsRow(
        title: String, subtitle: String, widget: View? = null, leading: View? = null, onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }
        if (leading != null) row.addView(leading)
        val textContainer = vertical(0).apply { layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f) }
        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 13f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textContainer)
        if (widget != null) row.addView(widget)
        return row
    }

    private fun infoRow(title: String, body: String) = settingsRow(title, body)

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(h)) }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
