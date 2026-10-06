package com.kafkasl.phonewhisper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Simple list of past dictations. Tap = copy, long-press = delete. */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var store: HistoryStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "History"
        store = HistoryStore(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "History"
            textSize = 32f
            setPadding(dp(24), dp(48), dp(24), dp(4))
        })
        root.addView(TextView(this).apply {
            text = "Tap to copy. Long-press to delete."
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(12))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            addView(root)
        })
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        list.removeAllViews()
        val retention = getSharedPreferences("phonewhisper", MODE_PRIVATE)
            .getInt("history_retention_days", HistoryStore.DEFAULT_RETENTION_DAYS)
        store.prune(retention)
        val items = store.list()
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Nothing here yet."
                setTextColor(attrColor(android.R.attr.textColorSecondary))
                setPadding(dp(24), dp(16), dp(24), dp(16))
            })
            return
        }
        for (e in items) list.addView(row(e))
    }

    private fun row(e: HistoryStore.Entry): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(14), dp(24), dp(14))
            isClickable = true
            isFocusable = true
            val out = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
            setBackgroundResource(out.resourceId)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dictation", e.text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
            setOnLongClickListener {
                android.app.AlertDialog.Builder(context)
                    .setTitle("Delete this entry?")
                    .setMessage(e.text.take(120))
                    .setPositiveButton("Delete") { _, _ -> store.delete(e.id); render() }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
        row.addView(TextView(this).apply {
            text = e.text
            textSize = 16f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val modelShort = e.model.removePrefix("sherpa-onnx-").removeSuffix("-int8")
        val dur = if (e.audioMs > 0) " · ${"%.1f".format(e.audioMs / 1000.0)}s audio" else ""
        row.addView(TextView(this).apply {
            text = DateUtils.getRelativeTimeSpanString(e.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                .toString() + " · " + modelShort + dur
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(0, dp(4), 0, 0)
        })
        return row
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0)
        ta.recycle()
        return c
    }
}
