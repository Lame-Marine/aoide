package io.github.lamemarine.aoide

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

/** Shows the bundled open-source licence texts (assets/licenses/). */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val asset = intent.getStringExtra("asset") ?: "third_party_licenses.txt"
        val isMain = asset == "third_party_licenses.txt"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = if (isMain) "Open-source licences" else "ONNX Runtime third-party notices"
            textSize = 26f
            setTextColor(attr(android.R.attr.textColorPrimary))
            setPadding(dp(20), dp(24), dp(20), dp(8))
        })
        if (isMain) {
            root.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "ONNX Runtime third-party notices"
                setOnClickListener {
                    startActivity(Intent(this@LicensesActivity, LicensesActivity::class.java)
                        .putExtra("asset", "onnxruntime_third_party_notices.txt"))
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(dp(20), 0, dp(20), dp(8)) }
            })
        }
        val tv = TextView(this).apply {
            text = "Loading..."
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(attr(android.R.attr.textColorSecondary))
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        root.addView(tv)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(attr(android.R.attr.colorBackground))
            addView(root)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(scroll)

        Thread {
            val text = try {
                assets.open("licenses/$asset").bufferedReader().use { it.readText() }
            } catch (e: Exception) { "Could not load $asset: ${e.message}" }
            runOnUiThread { tv.text = text }
        }.start()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun attr(a: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(a))
        val c = ta.getColor(0, 0)
        ta.recycle()
        return c
    }
}
