package io.github.lamemarine.aoide

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional, opt-in troubleshooting log. Off by default; when off, nothing is recorded.
 * While on it stores service/microphone events (never audio, never dictated text) in files/diag.log,
 * and switches itself off after [AUTO_OFF_MS].
 */
object Diag {
    private const val TAG = "UtterDiag"
    private const val MAX_BYTES = 60_000
    private const val KEEP_LINES = 250
    const val AUTO_OFF_MS = 24L * 3600L * 1000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean {
        val until = prefs(ctx).getLong("debug_until", 0L)
        if (until == 0L) return false
        if (System.currentTimeMillis() > until) {
            // expired: switch off and wipe
            prefs(ctx).edit().putLong("debug_until", 0L).apply()
            clear(ctx)
            return false
        }
        return true
    }

    fun enable(ctx: Context) {
        prefs(ctx).edit().putLong("debug_until", System.currentTimeMillis() + AUTO_OFF_MS).apply()
        add(ctx, "debug mode ON (auto-off in 24h)")
        for (line in deviceReport(ctx).lines()) add(ctx, "  $line")
    }

    fun disable(ctx: Context) {
        prefs(ctx).edit().putLong("debug_until", 0L).apply()
        clear(ctx)
    }

    fun expiresAt(ctx: Context): Long = prefs(ctx).getLong("debug_until", 0L)

    @Synchronized
    fun add(ctx: Context, msg: String) {
        if (!isEnabled(ctx)) return
        Log.i(TAG, msg)
        try {
            val f = File(ctx.filesDir, "diag.log")
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$stamp  $msg\n")
            if (f.length() > MAX_BYTES) {
                val keep = f.readLines().takeLast(KEEP_LINES)
                f.writeText(keep.joinToString("\n") + "\n")
            }
        } catch (_: Exception) { }
    }

    @Synchronized
    fun readLog(ctx: Context, lines: Int = 120): String = try {
        val f = File(ctx.filesDir, "diag.log")
        if (!f.exists()) "(no events recorded)" else f.readLines().takeLast(lines).joinToString("\n")
    } catch (_: Exception) { "(could not read log)" }

    @Synchronized
    fun clear(ctx: Context) { try { File(ctx.filesDir, "diag.log").delete() } catch (_: Exception) { } }

    /** Device and settings snapshot. Contains no personal content. */
    fun deviceReport(ctx: Context): String {
        val p = prefs(ctx)
        val dm = ctx.resources.displayMetrics
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val a11yList = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
        val micGranted = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val storageFreeMb = try { ctx.filesDir.usableSpace / (1024 * 1024) } catch (_: Exception) { -1 }
        val model = p.getString("model_name", "") ?: ""
        return listOf(
            "App: Aoide $version",
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}",
            "Screen: ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi",
            "Microphone permission: $micGranted",
            "Accessibility service enabled: ${a11yList.contains(ctx.packageName)}  connected: ${WhisperAccessibilityService.instance != null}",
            "Battery optimisation exempt: ${pm.isIgnoringBatteryOptimizations(ctx.packageName)}",
            "Free storage for app: ${storageFreeMb} MB",
            "Model: ${model.ifBlank { "(none)" }}  language: ${p.getString("language", "auto")}",
            "Trigger: ${p.getString("trigger_mode", "both")}  opacity: ${p.getInt("button_opacity", 85)}%  trim silence: ${p.getBoolean("trim_silence", true)}",
            "Cleanup: fillers=${p.getBoolean("clean_fillers", true)} caps=${p.getBoolean("clean_caps", true)} spoken=${p.getBoolean("spoken_punct", false)}  history=${p.getBoolean("history_enabled", true)}",
        ).joinToString("\n")
    }

    /** Full text for sharing: device snapshot plus recorded events. */
    fun fullReport(ctx: Context): String =
        "=== Aoide debug report ===\n" + deviceReport(ctx) + "\n\n=== Events ===\n" + readLog(ctx, 250)
}
