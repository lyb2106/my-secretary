package com.lyb.mysecretary.stt

import android.content.res.AssetManager

/** Receives decoding progress (0–100) from whisper.cpp. */
fun interface ProgressListener {
    fun onProgress(percent: Int)
}

/** Thin JNI surface over whisper.cpp (see app/src/main/cpp/secretary_jni.cpp). */
object WhisperNative {
    init {
        System.loadLibrary("secretary")
    }

    external fun initFromAsset(assetManager: AssetManager, assetPath: String): Long
    external fun free(ctx: Long)
    external fun requestAbort()
    external fun systemInfo(): String

    /** Returns UTF-8 transcript bytes, or null on failure/abort. */
    external fun transcribe(
        ctx: Long,
        samples: FloatArray,
        nThreads: Int,
        language: String,
        initialPrompt: String,
        vadModelPath: String?,
        listener: ProgressListener?,
    ): ByteArray?
}
