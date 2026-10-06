package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var modeRowSub: TextView
    private lateinit var opacitySub: TextView
    private lateinit var fillerSub: TextView
    private lateinit var languageSub: TextView
    private lateinit var retentionSub: TextView
    private lateinit var modelContainer: LinearLayout

    private val modelRows = mutableMapOf<String, ModelRowViews>()

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
        val delBtn: MaterialButton
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        // Top large header (like "Connected devices")
        val header = TextView(this).apply {
            text = "Phone Whisper"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        // Status row
        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        // --- Setup Section ---
        root.addView(sectionHeader("Setup"))
        
        val audioRow = settingsRow("Audio permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val accRow = settingsRow("Accessibility service", "Checking...") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        // Local Models section
        modelContainer = vertical(0)
        modelContainer.addView(sectionHeader("Local models"))
        var lastFamily = ""
        for (m in MODEL_CATALOG) {
            if (m.family != lastFamily) {
                lastFamily = m.family
                modelContainer.addView(TextView(this).apply {
                    text = m.family
                    textSize = 12f
                    setTypeface(typeface, Typeface.BOLD)
                    alpha = 0.7f
                    setPadding(dp(24), dp(14), dp(24), dp(2))
                })
            }
            modelContainer.addView(buildModelRow(m))
        }
        root.addView(modelContainer)

        // --- Button Section ---
        root.addView(sectionHeader("Button"))

        val modeRow = settingsRow("Trigger", modeLabel()) { chooseMode() }
        modeRowSub = modeRow.findViewWithTag("subtitle")
        root.addView(modeRow)

        val opacityRow = settingsRow("Idle opacity", "${opacityPct()}%")
        opacitySub = opacityRow.findViewWithTag("subtitle")
        root.addView(opacityRow)
        root.addView(SeekBar(this).apply {
            max = 80
            progress = opacityPct() - 20
            setPadding(dp(24), 0, dp(24), dp(8))
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
        })

        // --- Audio & text Section ---
        root.addView(sectionHeader("Audio & text"))
        val langRow = settingsRow("Language", languageText()) { chooseLanguage() }
        languageSub = langRow.findViewWithTag("subtitle")
        root.addView(langRow)
        root.addView(toggleRow("Trim silence", "Skip silent parts; ignore clips with no speech", "trim_silence", true))
        root.addView(toggleRow("Remove filler words", "Drops um, uh, er and similar", "clean_fillers", true))
        val fillerRow = settingsRow("Filler word list", fillerList()) { editFillers() }
        fillerSub = fillerRow.findViewWithTag("subtitle")
        root.addView(fillerRow)
        root.addView(toggleRow("Fix capitalisation", "Capital letters at sentence starts, and \"I\"", "clean_caps", true))
        root.addView(toggleRow("Spoken punctuation", "Say \"comma\", \"period\", \"new line\" to insert them", "spoken_punct", false))

        // --- History Section ---
        root.addView(sectionHeader("History"))
        root.addView(toggleRow("Save history", "Keep a private list of past dictations on this phone", "history_enabled", true))
        val retRow = settingsRow("Keep for", retentionText()) { chooseRetention() }
        retentionSub = retRow.findViewWithTag("subtitle")
        root.addView(retRow)
        root.addView(settingsRow("View history", "Tap an entry to copy it") {
            startActivity(Intent(this, HistoryActivity::class.java))
        })
        root.addView(settingsRow("Clear history", "Delete every saved dictation") { confirmClearHistory() })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r); refresh()
    }

    // --- Model Rows ---

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
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply {
                topMargin = dp(8)
            }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(delBtn)
            addView(radio)
        }

        val row = settingsRow(
            if (model.recommended) model.name else model.name,
            "${model.langs} · ${model.note} · ${model.sizeMb} MB download",
            rightContainer
        ) {
            onModelAction(model)
        }
        
        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)
        
        modelRows[model.archive] = ModelRowViews(
            radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn, delBtn
        )
        refreshCard(model)
        
        return row
    }

    private fun onModelAction(model: Model) {
        val views = modelRows[model.archive] ?: return

        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }

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
                        toast("${model.name} ready!")
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
        android.app.AlertDialog.Builder(this)
            .setTitle("Uninstall ${model.name}?")
            .setMessage("This removes the downloaded files from your phone. You can download it again later.")
            .setPositiveButton("Uninstall") { _, _ ->
                val wasActive = prefs().getString("model_name", "") == model.archive
                if (wasActive) WhisperAccessibilityService.instance?.unloadModel()
                ModelDownloader.delete(this, model)
                if (wasActive) {
                    prefs().edit().putString("model_name", "").apply()
                    val next = MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }
                    if (next != null) selectModel(next.archive)
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
        
        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = "${model.langs} · ${model.note} · ${model.sizeMb} MB download"
        }
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    // --- State Updates ---

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"

        val cur = prefs().getString("model_name", "") ?: ""
        if (cur.isBlank() || !File(filesDir, "models/$cur").exists()) {
            MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }
                ?.let { selectModel(it.archive) }
        }

        val ready = audio && acc && hasModel
        statusSubtitle.text = if (ready) "Ready. Open a keyboard and use the bubble"
                              else if (!hasModel) "Download a speech model below"
                              else "Setup required"
        statusSubtitle.setTextColor(if (ready) attrColor(com.google.android.material.R.attr.colorPrimary) else attrColor(android.R.attr.textColorSecondary))

        refreshAllCards()
    }

    private fun languageText() = languageName(prefs().getString("language", "auto") ?: "auto") +
        " · Whisper multilingual models only"

    private fun chooseLanguage() {
        val current = LANGUAGES.indexOfFirst { it.first == (prefs().getString("language", "auto") ?: "auto") }.coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Language")
            .setSingleChoiceItems(LANGUAGES.map { it.second }.toTypedArray(), current) { dialog, which ->
                prefs().edit().putString("language", LANGUAGES[which].first).apply()
                languageSub.text = languageText()
                WhisperAccessibilityService.instance?.reloadModel()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val retentionOptions = listOf(1 to "1 day", 7 to "7 days", 30 to "30 days", 0 to "Until I clear it")

    private fun retentionText() = retentionOptions
        .firstOrNull { it.first == prefs().getInt("history_retention_days", HistoryStore.DEFAULT_RETENTION_DAYS) }?.second ?: "30 days"

    private fun chooseRetention() {
        val cur = prefs().getInt("history_retention_days", HistoryStore.DEFAULT_RETENTION_DAYS)
        android.app.AlertDialog.Builder(this)
            .setTitle("Keep history for")
            .setSingleChoiceItems(retentionOptions.map { it.second }.toTypedArray(),
                retentionOptions.indexOfFirst { it.first == cur }.coerceAtLeast(0)) { dialog, which ->
                prefs().edit().putInt("history_retention_days", retentionOptions[which].first).apply()
                retentionSub.text = retentionText()
                Thread { HistoryStore(this).prune(retentionOptions[which].first) }.start()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearHistory() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Clear all history?")
            .setMessage("This deletes every saved dictation. It cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                Thread { HistoryStore(this).clear() }.start()
                toast("History cleared")
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

    private fun fillerList() = prefs().getString("filler_words", TextCleaner.DEFAULT_FILLERS) ?: TextCleaner.DEFAULT_FILLERS

    private fun editFillers() {
        val input = EditText(this).apply {
            hint = "um, uh, er"
            setText(fillerList())
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Filler words (comma separated)")
            .setView(input)
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

    // --- Button settings ---

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
        android.app.AlertDialog.Builder(this)
            .setTitle("Button trigger")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                prefs().edit().putString("trigger_mode", keys[which]).apply()
                modeRowSub.text = modeLabel()
                WhisperAccessibilityService.instance?.applySettings()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- UI Helpers ---

    private fun settingsRow(title: String, subtitle: String, widget: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }

        val textContainer = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }
        
        textContainer.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
        })
        
        textContainer.addView(TextView(this).apply {
            tag = "subtitle"
            text = subtitle
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(2), 0, 0)
        })

        row.addView(textContainer)
        if (widget != null) row.addView(widget)

        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary)) // Neutral Android-like blue
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

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
