package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Utter's signature mint soundwave (the same shape as the app icon).
 * IDLE: static icon wave. RECORDING: live wave driven by mic level. BUSY: slow breathing wave while transcribing.
 */
class WaveformView(context: Context) : View(context) {

    enum class Mode { IDLE, RECORDING, BUSY }

    private var mode = Mode.IDLE
    private var level = 0f        // smoothed mic level 0..1
    private var target = 0f
    private var phase = 0f
    private var lastFrame = 0L
    private val path = Path()
    private val mint = 0xFF6BFFB0.toInt()

    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = mint
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = mint
    }

    private val frame = object : Runnable {
        override fun run() {
            if (mode == Mode.IDLE || !isAttachedToWindow) return
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 0.016f else min(0.05f, (now - lastFrame) / 1e9f)
            lastFrame = now
            level += (target - level) * min(1f, dt * 14f)
            target *= 0.88f
            phase += dt * if (mode == Mode.RECORDING) 9f else 3.2f
            invalidate()
            postOnAnimation(this)
        }
    }

    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        lastFrame = 0L
        if (m == Mode.IDLE) { level = 0f; target = 0f; phase = 0f; invalidate() }
        else { removeCallbacks(frame); postOnAnimation(frame) }
    }

    /** level in 0..1 (already normalised) */
    fun push(l: Float) { target = max(target, l) }

    fun clear() { target = 0f; level = 0f; invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode != Mode.IDLE) postOnAnimation(frame)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val amp = when (mode) {
            Mode.IDLE -> 0.62f
            Mode.RECORDING -> 0.10f + 0.90f * level
            Mode.BUSY -> 0.22f + 0.10f * sin(phase * 0.9f)
        }
        val ph = if (mode == Mode.IDLE) 0.6f else phase
        val sw = w * 0.085f
        core.strokeWidth = sw
        glow.strokeWidth = sw * 2.6f
        glow.alpha = if (mode == Mode.RECORDING) (50 + 80 * level).toInt() else 55

        path.reset()
        val steps = 48
        val half = h * 0.46f
        for (i in 0..steps) {
            val u = i / steps.toFloat()
            val x = sw + u * (w - 2 * sw)
            val d = (u - 0.5f) / 0.24f
            val env = exp(-d * d)                                   // bell envelope like the icon
            val s = sin(u * 2f * PI.toFloat() * 2.3f - ph) +
                0.35f * sin(u * 2f * PI.toFloat() * 4.1f + ph * 0.7f)
            val y = h / 2f - s * env * amp * half * 0.8f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, glow)
        canvas.drawPath(path, core)
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
