package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Removes silence/noise before transcription using the bundled Silero VAD.
 * Keeps a little padding around speech so word starts/ends are not clipped.
 */
class SpeechTrimmer(private val ctx: Context) {

    private var vad: Vad? = null

    @Synchronized
    private fun vad(): Vad {
        vad?.let { return it }
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.45f,
                minSilenceDuration = 0.45f,
                minSpeechDuration = 0.12f,
                windowSize = 512,
                maxSpeechDuration = 30f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        )
        return Vad(assetManager = ctx.assets, config = config).also { vad = it }
    }

    /**
     * @return the audio with silence removed, or an empty array when no speech was found.
     * Falls back to the untouched audio if the VAD cannot run.
     */
    @Synchronized
    fun trim(samples: FloatArray): FloatArray {
        return try {
            val v = vad()
            v.reset()
            // Feed in chunks; the VAD buffers internally
            var i = 0
            while (i < samples.size) {
                val end = minOf(i + CHUNK, samples.size)
                v.acceptWaveform(samples.copyOfRange(i, end))
                i = end
            }
            v.flush()

            val ranges = ArrayList<IntArray>()
            while (!v.empty()) {
                val seg = v.front()
                v.pop()
                ranges.add(intArrayOf(
                    maxOf(0, seg.start - PAD),
                    minOf(samples.size, seg.start + seg.samples.size + PAD),
                ))
            }
            v.reset()
            mergeAndCut(samples, ranges)
        } catch (e: Throwable) {
            Log.w(TAG, "VAD unavailable, using full audio: ${e.message}")
            samples
        }
    }

    @Synchronized
    fun release() {
        vad?.release()
        vad = null
    }

    companion object {
        private const val TAG = "SpeechTrimmer"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 512 * 8
        private const val PAD = SAMPLE_RATE / 5   // 0.2 s either side

        /** Merge overlapping ranges and concatenate the corresponding original audio. */
        internal fun mergeAndCut(samples: FloatArray, ranges: List<IntArray>): FloatArray {
            if (ranges.isEmpty()) return FloatArray(0)
            val sorted = ranges.sortedBy { it[0] }
            val merged = ArrayList<IntArray>()
            for (r in sorted) {
                val last = merged.lastOrNull()
                if (last != null && r[0] <= last[1]) last[1] = maxOf(last[1], r[1])
                else merged.add(intArrayOf(r[0], r[1]))
            }
            val total = merged.sumOf { it[1] - it[0] }
            val out = FloatArray(total)
            var pos = 0
            for (r in merged) {
                System.arraycopy(samples, r[0], out, pos, r[1] - r[0])
                pos += r[1] - r[0]
            }
            return out
        }
    }
}
