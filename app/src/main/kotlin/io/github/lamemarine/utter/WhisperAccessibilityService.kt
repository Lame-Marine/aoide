package io.github.lamemarine.utter

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val FEEDBACK_OFFSET_DP = 64
        private const val HOLD_MS = 300L
        private const val POLL_MS = 1000L

        private const val COLOR_IDLE = 0xEE02042C.toInt()
        private const val COLOR_RECORDING = 0xEE03163A.toInt()
        private const val COLOR_BUSY = 0xEE0A1236.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE02042C.toInt()
        private const val COLOR_RING = 0xFF6BFFB0.toInt()
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var waveform: WaveformView? = null
    private var overlayVisible = true
    @Volatile private var touching = false
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    private val trimmer by lazy { SpeechTrimmer(this) }

    // Local transcription engine (loaded lazily)
    private var localTranscriber: LocalTranscriber? = null
    private val modelLoading = java.util.concurrent.atomic.AtomicBoolean(false)

    private val dp get() = resources.displayMetrics.density
    /** Full display size in the current rotation. */
    private val fullH: Int get() = try {
        (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds.height()
    } catch (_: Exception) { resources.displayMetrics.heightPixels }

    /** Usable size for the bubble: window coordinates exclude the camera cutout, so subtract it. */
    private fun usable(): IntArray = try {
        val m = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics
        val c = m.windowInsets.getInsets(android.view.WindowInsets.Type.displayCutout())
        intArrayOf(m.bounds.width() - c.left - c.right, m.bounds.height() - c.top - c.bottom)
    } catch (_: Exception) { intArrayOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) }

    private val screenW: Int get() = usable()[0]
    private val screenH: Int get() = usable()[1]

    // --- bubble position: remembered side + height, re-applied after rotation / restart ---
    private fun bubbleX(ring: Int, margin: Int): Int =
        if (prefs().getBoolean("bubble_right", true)) screenW - ring - margin else margin

    private fun bubbleY(ring: Int, margin: Int): Int {
        val frac = prefs().getFloat("bubble_y_frac", 0.5f)
        return (frac * screenH - ring / 2f).toInt().coerceIn(margin, maxOf(margin, screenH - ring - margin))
    }

    private fun saveBubblePosition(right: Boolean, yFrac: Float) {
        prefs().edit().putBoolean("bubble_right", right).putFloat("bubble_y_frac", yFrac.coerceIn(0.05f, 0.95f)).apply()
    }

    /** Put the bubble back on screen. [force] re-applies the saved position (after rotation). */
    private fun clampBubble(force: Boolean) {
        val v = overlayView ?: return
        val p = layoutParams ?: return
        if (touching) return
        val ring = (RING_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()
        val off = p.x < 0 || p.y < 0 || p.x > screenW - ring || p.y > screenH - ring
        if (!force && !off) return
        p.x = bubbleX(ring, margin)
        p.y = bubbleY(ring, margin)
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(v, p) } catch (_: Exception) {}
        feedbackLayoutParams?.let {
            positionFeedback(it, p)
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(feedbackView, it) } catch (_: Exception) {}
        }
        Diag.add(this, "bubble repositioned (${if (force) "screen changed" else "was off-screen"}, ${screenW}x${screenH})")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ clampBubble(force = true) }, 150)
        handler.postDelayed(evalVisibility, 400)
    }

    override fun onServiceConnected() {
        instance = this
        if (!Diag.isEnabled(this)) Diag.clear(this)   // wipe any stale log while debug mode is off
        Diag.add(this, "service connected (pid ${android.os.Process.myPid()}, uptime ${android.os.SystemClock.elapsedRealtime() / 1000}s)")
        showOverlay()
        setOverlayVisible(false)
        handler.postDelayed(evalVisibility, 300)
        handler.postDelayed(pollVisibility, POLL_MS)
        // Try to load local model in background
        thread { initLocalModel() }
    }

    private val evalVisibility = Runnable { updateOverlayVisibility() }

    // Safety net: events can be dropped or arrive before the keyboard settles, so re-check on a timer too.
    private val pollVisibility = object : Runnable {
        override fun run() {
            if (instance == null) return
            if (overlayView?.isAttachedToWindow != true) rebuildOverlay()
            clampBubble(force = false)
            updateOverlayVisibility()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Debounce bursts, then check again once the keyboard animation has settled
        handler.removeCallbacks(evalVisibility)
        handler.postDelayed(evalVisibility, 120)
        handler.postDelayed(evalVisibility, 600)
    }

    private fun rebuildOverlay() {
        Diag.add(this, "bubble window was missing; re-adding")
        try { removeOverlay() } catch (_: Exception) {}
        try {
            showOverlay()
            overlayVisible = true
            setOverlayVisible(false)
        } catch (e: Exception) {
            Diag.add(this, "bubble re-add failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Diag.add(this, "service unbound")
        return super.onUnbind(intent)
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        Diag.add(this, "app swiped away from recents (service should keep running)")
        super.onTaskRemoved(rootIntent)
    }

    /** True when a keyboard is showing or an editable field has input focus. */
    private fun textFieldActive(): Boolean {
        // Only trust a keyboard window that actually occupies screen space. A focused field alone is not
        // enough: it stays focused after the keyboard is collapsed.
        val minH = (120 * dp).toInt()
        return try {
            val r = android.graphics.Rect()
            windows.any {
                if (it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) false
                else { it.getBoundsInScreen(r); r.height() >= minH && r.top < fullH - minH }
            }.also { Log.d(TAG, "textFieldActive=$it") }
        } catch (_: Exception) { false }
    }

    private fun imeSummary(): String = try {
        val r = android.graphics.Rect()
        val ime = windows.filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (ime.isEmpty()) "no keyboard window"
        else ime.joinToString { it.getBoundsInScreen(r); "keyboard ${r.width()}x${r.height()}@y${r.top}" }
    } catch (_: Exception) { "?" }

    private fun updateOverlayVisibility() {
        val shouldShow = consented() && (state != State.IDLE || textFieldActive())
        if (shouldShow == overlayVisible) return
        Diag.add(this, "bubble ${if (shouldShow) "shown" else "hidden"} (${imeSummary()})")
        setOverlayVisible(shouldShow)
    }

    private fun setOverlayVisible(visible: Boolean) {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayVisible = visible
        params.flags = if (visible) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
        view.animate().cancel()
        if (visible) {
            view.visibility = View.VISIBLE
            view.animate().alpha(targetAlpha()).setDuration(150).start()
        } else {
            view.animate().alpha(0f).setDuration(150).withEndAction {
                if (!overlayVisible) view.visibility = View.GONE
            }.start()
        }
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        Diag.add(this, "service destroyed")
        handler.removeCallbacks(pollVisibility)
        instance = null
        removeOverlay()
        super.onDestroy()
    }

    private fun initLocalModel() {
        modelLoading.set(true)
        try { initLocalModelInner() } finally { modelLoading.set(false) }
    }

    private fun initLocalModelInner() {
        val modelName = prefs().getString("model_name", "") ?: ""
        val previous = localTranscriber
        val lang = prefs().getString("language", "auto").let { if (it == null || it == "auto") "" else it }
        val loaded = if (modelName.isBlank()) {
            // Auto-detect first available model
            val models = LocalTranscriber.availableModels(this)
            if (models.isNotEmpty()) {
                Log.i(TAG, "Auto-detected model: ${models.first()}")
                LocalTranscriber.create(this, models.first(), lang)
            } else null
        } else {
            LocalTranscriber.create(this, modelName, lang)
        }
        // Never replace a working engine with a failed load
        if (loaded != null) localTranscriber = loaded
        else if (previous != null) Log.w(TAG, "Model load failed; keeping previous engine")
        if (localTranscriber != null) {
            Log.i(TAG, "Local transcription ready")
        } else {
            Log.i(TAG, "No local model found, will use API")
        }
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() { thread { initLocalModel() } }

    /** Drop the loaded engine (used when its model is uninstalled). */
    fun unloadModel() { localTranscriber = null }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val wave = WaveformView(this)
        val wavePad = (7 * dp).toInt()

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            addView(wave, FrameLayout.LayoutParams(buttonSize - wavePad * 2, buttonSize - wavePad * 2, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX(ringSize, margin)
            y = bubbleY(ringSize, margin)
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var downTime = 0L
        var downState = State.IDLE
        var startedOnDown = false
        var dragging = false

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    downTime = System.currentTimeMillis()
                    downState = state
                    dragging = false
                    startedOnDown = false
                    // Hold / Both: start capturing immediately so no speech is lost
                    if (state == State.IDLE && triggerMode() != "tap") {
                        startRecording()
                        startedOnDown = state == State.RECORDING
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (!dragging && moved >= TAP_THRESHOLD_DP * dp) {
                        dragging = true
                        // Dragging the bubble is not dictation
                        if (startedOnDown) { cancelRecording(); startedOnDown = false }
                    }
                    if (dragging) {
                        params.x = startX + (ev.rawX - touchX).toInt()
                        params.y = startY + (ev.rawY - touchY).toInt()
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    touching = false
                    if (dragging) {
                        val toRight = params.x + ringSize / 2 > screenW / 2
                        params.x = if (toRight) screenW - ringSize - margin else margin
                        saveBubblePosition(toRight, (params.y + ringSize / 2f) / screenH)
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                    } else {
                        val held = System.currentTimeMillis() - downTime
                        val mode = triggerMode()
                        when {
                            mode == "tap" -> onTap()
                            startedOnDown && held >= HOLD_MS -> stopAndTranscribe()   // hold released
                            startedOnDown && mode == "hold" -> {                        // too short for hold-only
                                cancelRecording()
                                showFeedback("Hold to talk")
                            }
                            startedOnDown -> { /* Both: quick tap -> keep recording until next tap */ }
                            downState == State.RECORDING -> stopAndTranscribe()         // Both: tap to stop
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    if (startedOnDown) { cancelRecording(); startedOnDown = false }
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        waveform = wave
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
        feedbackView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            feedbackView = null
        }
        button = null
        spinner = null
        waveform = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
        setStroke((1.5f * dp).toInt(), 0x886BFFB0.toInt())
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post {
            button?.background = circle(color)
            val recording = color == COLOR_RECORDING
            val idle = color == COLOR_IDLE
            // mic icon only when idle; waveform while recording; spinner (setBusy) while processing
            waveform?.setMode(when {
                recording -> WaveformView.Mode.RECORDING
                idle -> WaveformView.Mode.IDLE
                else -> WaveformView.Mode.BUSY
            })
            if (idle) waveform?.clear()
            if (overlayVisible) overlayView?.animate()?.alpha(targetAlpha())?.setDuration(150)?.start()
            if (idle) updateOverlayVisibility() else Unit
        }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            // transcribing is shown by the breathing wave (see setAppearance), no ring
        }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> {}
        }
    }

    private fun startRecording() {
        if (!consented()) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Phone Whisper app"); return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { toast("Audio permission denied"); return }

        val ar = audioRecord!!
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Diag.add(this, "mic FAILED: AudioRecord not initialised")
            ar.release(); audioRecord = null
            toast("Microphone unavailable"); return
        }
        pcmStream = ByteArrayOutputStream()
        try {
            ar.startRecording()
        } catch (e: Exception) {
            Diag.add(this, "mic FAILED: startRecording threw ${e.javaClass.simpleName}: ${e.message}")
            ar.release(); audioRecord = null; pcmStream = null
            toast("Microphone unavailable"); return
        }
        if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            Diag.add(this, "mic FAILED: recording state ${ar.recordingState} after start")
            ar.release(); audioRecord = null; pcmStream = null
            toast("Microphone unavailable"); return
        }
        state = State.RECORDING
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        val t0 = System.currentTimeMillis()
        Diag.add(this, "recording started (${triggerMode()} mode)")

        thread {
            val buf = ByteArray(bufSize)
            var peak = 0f
            var logged = false
            while (state == State.RECORDING) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) {
                    pcmStream?.write(buf, 0, n)
                    val lvl = WaveformView.levelOf(buf, n)
                    if (lvl > peak) peak = lvl
                    handler.post { waveform?.push(lvl) }
                    if (!logged && System.currentTimeMillis() - t0 > 1200) {
                        logged = true
                        val silenced = try { audioRecord?.activeRecordingConfiguration?.isClientSilenced } catch (_: Exception) { null }
                        Diag.add(this, "mic check after 1s: peak level %.3f, silenced by Android: %s".format(peak, silenced))
                    }
                }
            }
        }
    }

    /** The user must have agreed to the in-app accessibility disclosure before the bubble does anything. */
    private fun consented() = prefs().getBoolean("a11y_consent_v1", false)

    private fun triggerMode() = prefs().getString("trigger_mode", "both") ?: "both"
    private fun idleAlpha() = prefs().getInt("button_opacity", 85).coerceIn(20, 100) / 100f
    private fun targetAlpha() = if (state == State.IDLE) idleAlpha() else 1f

    /** Called from the settings screen when opacity/mode change. */
    fun applySettings() {
        handler.post {
            updateOverlayVisibility()
            overlayView?.let {
                if (overlayVisible) { it.animate().cancel(); it.alpha = targetAlpha() }
            }
        }
    }

    /** Abort a recording without transcribing (e.g. the user started dragging the bubble). */
    private fun cancelRecording() {
        if (state != State.RECORDING) return
        state = State.IDLE
        audioRecord?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        audioRecord = null
        pcmStream = null
        setBusy(false)
        setAppearance(COLOR_IDLE)
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        stopPulse()
        setAppearance(COLOR_BUSY)
        setBusy(true)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) { reset("No audio captured"); return }

        val local = localTranscriber
        if (local != null) {
            transcribeLocal(pcm, local)
        } else if (modelLoading.get() || LocalTranscriber.availableModels(this).isNotEmpty()) {
            // Service just restarted and the model is still loading: wait for it rather than failing.
            Diag.add(this, "model not ready yet; waiting for it to load")
            if (!modelLoading.get()) thread { initLocalModel() }
            thread {
                var waited = 0
                while (localTranscriber == null && waited < 25_000) { Thread.sleep(200); waited += 200 }
                val l = localTranscriber
                if (l != null) {
                    Diag.add(this, "model ready after ${waited}ms")
                    transcribeLocal(pcm, l)
                } else {
                    Diag.add(this, "model failed to load")
                    handler.post { reset("Speech model failed to load. Check the Models tab") }
                }
            }
        } else {
            reset("No speech model installed. Open Utter and download one")
        }
    }

    private fun transcribeLocal(pcm: ByteArray, transcriber: LocalTranscriber) {
        thread {
            try {
                // Convert 16-bit PCM bytes to float samples
                val samples = FloatArray(pcm.size / 2)
                for (i in samples.indices) {
                    val lo = pcm[i * 2].toInt() and 0xFF
                    val hi = pcm[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }

                // Drop silence/noise first; no speech at all -> skip the model entirely
                var speech = samples
                if (prefs().getBoolean("trim_silence", true)) {
                    val tv = System.currentTimeMillis()
                    speech = trimmer.trim(samples)
                    Log.i(TAG, "VAD: kept ${speech.size / 16}ms of ${samples.size / 16}ms in ${System.currentTimeMillis() - tv}ms")
                    if (speech.isEmpty()) {
                        Diag.add(this, "no speech found in ${samples.size / 16}ms of audio")
                        handleTranscriptionResult(null); return@thread
                    }
                }

                val t0 = System.currentTimeMillis()
                val text = transcriber.transcribe(speech, SAMPLE_RATE)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Local transcription: ${ms}ms, ${speech.size / SAMPLE_RATE}s audio")

                Diag.add(this, "transcribed: ${text.length} chars in ${ms}ms (${speech.size / 16}ms of speech)")
                handleTranscriptionResult(text, speech.size / 16, ms.toInt())
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                handler.post {
                    toast("Local error: ${e.message}")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    private fun cleanerOptions(): TextCleaner.Options {
        val p = prefs()
        val fillers = if (p.getBoolean("clean_fillers", true))
            TextCleaner.parseFillers(p.getString("filler_words", TextCleaner.DEFAULT_FILLERS) ?: TextCleaner.DEFAULT_FILLERS)
        else emptyList()
        return TextCleaner.Options(
            fillers = fillers,
            fixCaps = p.getBoolean("clean_caps", true),
            spokenPunct = p.getBoolean("spoken_punct", false),
        )
    }

    private val history by lazy { HistoryStore(this) }

    private fun saveToHistory(text: String, audioMs: Int, procMs: Int) {
        val p = prefs()
        if (!p.getBoolean("history_enabled", true)) return
        val model = p.getString("model_name", "") ?: ""
        val maxItems = p.getInt("history_max_items", HistoryStore.DEFAULT_MAX_ITEMS)
        thread {
            try {
                history.add(text, model, audioMs, procMs)
                history.prune(maxItems)
            } catch (e: Exception) {
                Log.w(TAG, "History write failed: ${e.message}")
            }
        }
    }

    private fun handleTranscriptionResult(raw: String?, audioMs: Int = 0, procMs: Int = 0) {
        val text = raw?.let { TextCleaner.apply(it, cleanerOptions()) }
        if (!text.isNullOrBlank()) saveToHistory(text, audioMs, procMs)
        handler.post {
            if (text.isNullOrBlank()) {
                toast("No speech detected")
            } else {
                injectText(text)
            }
            state = State.IDLE
            setBusy(false)
            setAppearance(COLOR_IDLE)
        }
    }

    private fun reset(msg: String) {
        toast(msg)
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        feedback?.let { showFeedback(it, feedbackDurationMs) }

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active window root found")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(TAG, "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused}")
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        logNode("Trying node", node)
        if (node.isPassword) {
            Log.i(TAG, "Skipping a password field")
            return false
        }

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val replacementStart = minOf(start, end)
            val replacementEnd = maxOf(start, end)
            val updated = current.replaceRange(replacementStart, replacementEnd, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        // Deliberately no text, description or package name: never log what is on someone's screen.
        Log.i(TAG, "$prefix class=${node.className} focused=${node.isFocused} editable=${node.isEditable} actions=[$actions]")
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
