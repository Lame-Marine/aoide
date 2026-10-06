package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny on-device event log (no audio, no dictated text) so service lifecycle and mic problems can be
 * inspected from Settings -> Diagnostics. Entries are plain lines in files/diag.log.
 */
object Diag {
    private const val TAG = "UtterDiag"
    private const val MAX_BYTES = 60_000
    private const val KEEP_LINES = 250

    @Synchronized
    fun add(ctx: Context, msg: String) {
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
    fun read(ctx: Context, lines: Int = 80): String = try {
        val f = File(ctx.filesDir, "diag.log")
        if (!f.exists()) "(nothing logged yet)" else f.readLines().takeLast(lines).joinToString("\n")
    } catch (_: Exception) { "(could not read log)" }

    @Synchronized
    fun clear(ctx: Context) { try { File(ctx.filesDir, "diag.log").delete() } catch (_: Exception) { } }
}
