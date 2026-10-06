package io.github.lamemarine.utter

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.util.concurrent.TimeUnit

data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,          // download size (compressed archive)
    val family: String,
    val langs: String,
    val note: String,
    val recommended: Boolean = false,
)

private const val EN = "English"
private const val MULTI99 = "99 languages (auto-detect)"
private const val EU25 = "25 European languages (auto-detect)"

val MODEL_CATALOG = listOf(
    // --- NVIDIA Parakeet ---
    Model("Parakeet 110M (CTC)", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        104, "Parakeet", EN, "Very fast, light", recommended = true),
    Model("Parakeet 110M (TDT)", "sherpa-onnx-nemo-parakeet_tdt_transducer_110m-en-36000-int8",
        108, "Parakeet", EN, "Very fast, light"),
    Model("Parakeet 0.6B v2", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        482, "Parakeet", EN, "High accuracy, English"),
    Model("Parakeet 0.6B v3", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        487, "Parakeet", EU25, "High accuracy, multilingual"),
    // --- OpenAI Whisper ---
    Model("Whisper Tiny (English)", "sherpa-onnx-whisper-tiny.en",
        118, "Whisper", EN, "Smallest, lowest accuracy"),
    Model("Whisper Tiny", "sherpa-onnx-whisper-tiny",
        116, "Whisper", MULTI99, "Smallest, lowest accuracy"),
    Model("Whisper Base (English)", "sherpa-onnx-whisper-base.en",
        208, "Whisper", EN, "Good balance"),
    Model("Whisper Base", "sherpa-onnx-whisper-base",
        207, "Whisper", MULTI99, "Good balance"),
    Model("Whisper Small (English)", "sherpa-onnx-whisper-small.en",
        635, "Whisper", EN, "More accurate, slower"),
    Model("Whisper Small", "sherpa-onnx-whisper-small",
        639, "Whisper", MULTI99, "More accurate, slower"),
    Model("Whisper Distil Small (English)", "sherpa-onnx-whisper-distil-small.en",
        453, "Whisper", EN, "Distilled, faster than Small"),
    Model("Whisper Turbo", "sherpa-onnx-whisper-turbo",
        563, "Whisper", MULTI99, "Most accurate here, heaviest"),
    // --- Moonshine ---
    Model("Moonshine Tiny", "sherpa-onnx-moonshine-tiny-en-int8",
        107, "Moonshine", EN, "Fast, short clips"),
    Model("Moonshine Base", "sherpa-onnx-moonshine-base-en-int8",
        250, "Moonshine", EN, "Fast, more accurate"),
    // --- NVIDIA FastConformer ---
    Model("FastConformer CTC", "sherpa-onnx-nemo-fast-conformer-ctc-en-24500-int8",
        104, "FastConformer", EN, "Fast, light"),
    Model("FastConformer CTC (EN/DE/ES/FR)", "sherpa-onnx-nemo-fast-conformer-ctc-en-de-es-fr-14288-int8",
        102, "FastConformer", "English, German, Spanish, French", "Fast, light"),
)

sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

object ModelDownloader {
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS).build()

    fun modelDir(ctx: Context, model: Model) =
        File(ctx.filesDir, "models/${model.archive}")

    /** Installed only if extraction finished: a tokens file must be present. */
    fun isInstalled(ctx: Context, model: Model) =
        modelDir(ctx, model).let { d -> d.isDirectory && hasTokens(d) }

    fun hasTokens(dir: File) =
        dir.listFiles()?.any { it.name == "tokens.txt" || it.name.endsWith("-tokens.txt") } == true

    /** Download and extract model. Callbacks fire on background thread. */
    fun download(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val url = "$BASE_URL/${model.archive}.tar.bz2"
        val tmpFile = File(ctx.cacheDir, "${model.archive}.tar.bz2")
        val outDir = File(ctx.filesDir, "models")

        Thread {
            try {
                downloadFile(url, tmpFile, onState)
                onState(DownloadState.Extracting)
                // Extract to a scratch dir, then move into place, so an interrupted
                // extraction never leaves a half-installed model behind.
                val scratch = File(ctx.cacheDir, "extract-${model.archive}")
                scratch.deleteRecursively()
                extractTarBz2(tmpFile, scratch)
                scratch.listFiles()?.forEach { slimDown(it) }
                outDir.mkdirs()
                scratch.listFiles()?.forEach { child ->
                    val target = File(outDir, child.name)
                    target.deleteRecursively()
                    if (!child.renameTo(target)) {
                        child.copyRecursively(target, overwrite = true)
                        child.deleteRecursively()
                    }
                }
                scratch.deleteRecursively()
                onState(DownloadState.Done)
            } catch (e: Exception) {
                onState(DownloadState.Error(e.message ?: "Unknown error"))
            } finally {
                tmpFile.delete()
            }
        }.start()
    }

    /** Drop sample audio and full-precision weights when an int8 twin exists (saves disk). */
    private fun slimDown(dir: File) {
        if (!dir.isDirectory) return
        File(dir, "test_wavs").deleteRecursively()
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".onnx") && !f.name.contains("int8")) {
                if (File(dir, f.name.removeSuffix(".onnx") + ".int8.onnx").exists()) f.delete()
            }
        }
    }

    fun delete(ctx: Context, model: Model) =
        modelDir(ctx, model).deleteRecursively()

    private fun downloadFile(
        url: String, dest: File, onState: (DownloadState) -> Unit
    ) {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        val body = response.body ?: throw IOException("Empty response")
        val total = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { src ->
            FileOutputStream(dest).use { dst ->
                val buf = ByteArray(16384)
                var n: Int
                while (src.read(buf).also { n = it } != -1) {
                    dst.write(buf, 0, n)
                    downloaded += n
                    if (total > 0)
                        onState(DownloadState.Downloading(downloaded.toFloat() / total))
                }
            }
        }
    }

    /** Extract tar.bz2 to outDir. Validates paths to prevent traversal. */
    fun extractTarBz2(archive: File, outDir: File) {
        outDir.mkdirs()
        val bzIn = BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        TarArchiveInputStream(bzIn).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val dest = File(outDir, entry.name)
                require(dest.canonicalPath.startsWith(outDir.canonicalPath)) {
                    "Path traversal: ${entry.name}"
                }
                if (entry.isDirectory) dest.mkdirs()
                else {
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { tar.copyTo(it) }
                }
            }
        }
    }
}
