package com.lyb.mysecretary.stt

import android.content.Context
import com.lyb.mysecretary.BuildConfig
import java.io.File

/**
 * Loads the bundled Whisper model for a single job and releases it right after, so nothing
 * stays resident in memory between uses.
 */
class SpeechToText(private val context: Context) {

    class SttException(message: String) : Exception(message)

    val modelName: String get() = BuildConfig.WHISPER_MODEL

    fun transcribe(
        samples: FloatArray,
        threads: Int,
        initialPrompt: String,
        onProgress: (Int) -> Unit,
    ): String {
        val ctx = WhisperNative.initFromAsset(context.assets, "models/${BuildConfig.WHISPER_MODEL}")
        if (ctx == 0L) throw SttException("음성 인식 모델을 불러오지 못했습니다 (${BuildConfig.WHISPER_MODEL}).")
        try {
            val bytes = WhisperNative.transcribe(
                ctx, samples, threads, "ko", initialPrompt, vadModelPath(),
            ) { onProgress(it) } ?: throw SttException("음성 인식이 중단되었거나 실패했습니다.")
            return String(bytes, Charsets.UTF_8).trim()
        } finally {
            WhisperNative.free(ctx)
        }
    }

    fun abort() = WhisperNative.requestAbort()

    /** whisper.cpp opens the VAD model by path, so copy the small asset out once. */
    private fun vadModelPath(): String? {
        val name = BuildConfig.VAD_MODEL
        val target = File(context.filesDir, name)
        if (target.exists() && target.length() > 0) return target.absolutePath
        return try {
            context.assets.open("models/$name").use { input ->
                val tmp = File(context.filesDir, "$name.tmp")
                tmp.outputStream().use { input.copyTo(it) }
                tmp.renameTo(target)
            }
            target.absolutePath
        } catch (e: Exception) {
            null  // VAD is an optimisation; transcribe the whole file without it.
        }
    }
}
