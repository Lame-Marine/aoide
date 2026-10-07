package io.github.lamemarine.aoide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** The History tab: past dictations as cards. Tap = copy, long-press = delete. */
class HistoryView(private val ctx: Context, private val onChanged: () -> Unit = {}) : LinearLayout(ctx) {

    private val store = HistoryStore(ctx)
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val emptyHint = TextView(ctx)

    init {
        orientation = VERTICAL
        val header = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(16), dp(4))
        }
        header.addView(TextView(ctx).apply {
            text = "History"
            textSize = 30f
            setTextColor(attr(android.R.attr.textColorPrimary))
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(ctx).apply {
            text = "Clear all"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(attr(com.google.android.material.R.attr.colorPrimary))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { confirmClear() }
        })
        addView(header)
        addView(TextView(ctx).apply {
            text = "Tap to copy · long-press to delete. Stored only on this phone."
            textSize = 13f
            setTextColor(attr(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(12))
        })
        emptyHint.apply {
            text = "Nothing here yet.\nYour dictations will show up as you use the bubble."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(attr(android.R.attr.textColorSecondary))
            setPadding(dp(24), dp(48), dp(24), dp(48))
        }
        addView(emptyHint)
        addView(list)
    }

    fun render() {
        val max = ctx.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
            .getInt("history_max_items", HistoryStore.DEFAULT_MAX_ITEMS)
        store.prune(max)
        list.removeAllViews()
        val items = store.list()
        emptyHint.visibility = if (items.isEmpty()) VISIBLE else GONE
        for (e in items) list.addView(entryCard(e))
    }

    private fun entryCard(e: HistoryStore.Entry): View {
        val card = MaterialCardView(ctx).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(attr(com.google.android.material.R.attr.colorSurfaceContainer))
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(16), dp(5), dp(16), dp(5))
            }
            setOnClickListener {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("dictation", e.text))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            }
            setOnLongClickListener {
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Delete this entry?")
                    .setMessage(e.text.take(140))
                    .setPositiveButton("Delete") { _, _ -> store.delete(e.id); render(); onChanged() }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
        val col = LinearLayout(ctx).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        col.addView(TextView(ctx).apply {
            text = e.text
            textSize = 16f
            setTextColor(attr(android.R.attr.textColorPrimary))
            maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val modelShort = e.model.removePrefix("sherpa-onnx-").removeSuffix("-int8")
        val dur = if (e.audioMs > 0) " · ${"%.1f".format(e.audioMs / 1000.0)}s" else ""
        col.addView(TextView(ctx).apply {
            text = DateUtils.getRelativeTimeSpanString(e.ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                .toString() + (if (modelShort.isNotBlank()) " · $modelShort" else "") + dur
            textSize = 12f
            setTextColor(attr(android.R.attr.textColorSecondary))
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(col)
        return card
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Clear all history?")
            .setMessage("This deletes every saved dictation. It cannot be undone.")
            .setPositiveButton("Clear") { _, _ -> store.clear(); render(); onChanged() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun attr(a: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(a, tv, true)
        return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId) else tv.data
    }
}
