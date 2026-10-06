package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Small scrolling level meter: bars show the most recent mic levels. */
class WaveformView(context: Context) : View(context) {

    private val barCount = 7
    private val levels = FloatArray(barCount)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()

    /** level in 0..1 (already normalised) */
    fun push(level: Float) {
        for (i in 0 until barCount - 1) levels[i] = levels[i + 1]
        // light smoothing against the previous bar so it doesn't look jittery
        levels[barCount - 1] = max(level, levels[barCount - 2] * 0.55f)
        invalidate()
    }

    fun clear() {
        levels.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val slot = w / barCount
        val barW = slot * 0.55f
        val minH = barW            // idle dots
        for (i in 0 until barCount) {
            val bh = min(h, minH + levels[i] * (h - minH))
            val cx = slot * (i + 0.5f)
            rect.set(cx - barW / 2, (h - bh) / 2, cx + barW / 2, (h + bh) / 2)
            canvas.drawRoundRect(rect, barW / 2, barW / 2, paint)
        }
    }

    companion object {
        /** RMS of 16-bit little-endian PCM -> 0..1 with a gentle curve for speech levels. */
        fun levelOf(buf: ByteArray, n: Int): Float {
            val count = n / 2
            if (count == 0) return 0f
            var sum = 0.0
            for (i in 0 until count) {
                val s = ((buf[i * 2 + 1].toInt() shl 8) or (buf[i * 2].toInt() and 0xFF)).toShort().toInt()
                sum += s.toDouble() * s
            }
            val rms = sqrt(sum / count) / 32768.0
            return min(1f, (sqrt(rms) * 2.2).toFloat())
        }
    }
}
