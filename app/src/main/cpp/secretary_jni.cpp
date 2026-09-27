// JNI bridge between Kotlin (com.lyb.mysecretary.stt.WhisperNative) and whisper.cpp.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <atomic>
#include <string>

#include "whisper.h"

#define TAG "SecretaryJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_abort{false};

size_t asset_read(void *ctx, void *output, size_t read_size) {
    int n = AAsset_read(static_cast<AAsset *>(ctx), output, read_size);
    return n > 0 ? static_cast<size_t>(n) : 0;
}

bool asset_eof(void *ctx) {
    return AAsset_getRemainingLength64(static_cast<AAsset *>(ctx)) <= 0;
}

void asset_close(void *ctx) {
    AAsset_close(static_cast<AAsset *>(ctx));
}

struct ProgressBridge {
    JNIEnv *env;
    jobject listener;
    jmethodID on_progress;
};

void on_progress(whisper_context *, whisper_state *, int progress, void *user_data) {
    auto *bridge = static_cast<ProgressBridge *>(user_data);
    if (bridge->listener != nullptr && bridge->on_progress != nullptr) {
        bridge->env->CallVoidMethod(bridge->listener, bridge->on_progress, progress);
    }
}

bool should_abort(void *) {
    return g_abort.load();
}

bool encoder_begin(whisper_context *, whisper_state *, void *) {
    return !g_abort.load();
}

std::string to_string(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_lyb_mysecretary_stt_WhisperNative_initFromAsset(
        JNIEnv *env, jobject, jobject asset_manager, jstring asset_path) {
    AAssetManager *mgr = AAssetManager_fromJava(env, asset_manager);
    std::string path = to_string(env, asset_path);
    AAsset *asset = AAssetManager_open(mgr, path.c_str(), AASSET_MODE_STREAMING);
    if (asset == nullptr) {
        LOGW("model asset not found: %s", path.c_str());
        return 0;
    }

    whisper_model_loader loader = {};
    loader.context = asset;
    loader.read = &asset_read;
    loader.eof = &asset_eof;
    loader.close = &asset_close;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context *ctx = whisper_init_with_params(&loader, cparams);
    LOGI("model loaded: %s (%s)", path.c_str(), ctx ? "ok" : "failed");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lyb_mysecretary_stt_WhisperNative_free(JNIEnv *, jobject, jlong ctx_ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx != nullptr) whisper_free(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lyb_mysecretary_stt_WhisperNative_requestAbort(JNIEnv *, jobject) {
    g_abort.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lyb_mysecretary_stt_WhisperNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

// Returns the transcript as UTF-8 bytes (decoded in Kotlin, which tolerates invalid
// sequences), or null when decoding failed or was aborted.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lyb_mysecretary_stt_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ctx_ptr, jfloatArray samples, jint n_threads,
        jstring language, jstring initial_prompt, jstring vad_model_path, jobject listener) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    if (ctx == nullptr) return nullptr;
    g_abort.store(false);

    std::string lang = to_string(env, language);
    std::string prompt = to_string(env, initial_prompt);
    std::string vad_path = to_string(env, vad_model_path);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.beam_search.beam_size = 5;
    params.n_threads = n_threads;
    params.language = lang.c_str();
    params.detect_language = false;
    params.translate = false;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    // Drop non-speech tokens such as "(음악)" or "[웃음]".
    params.suppress_nst = true;

    if (!prompt.empty()) {
        params.initial_prompt = prompt.c_str();
        // Keep the user vocabulary in front of every 30 s window, not only the first.
        params.carry_initial_prompt = true;
    }

    if (!vad_path.empty()) {
        params.vad = true;
        params.vad_model_path = vad_path.c_str();
        params.vad_params = whisper_vad_default_params();
        // Generous padding so word onsets/endings are not clipped.
        params.vad_params.min_silence_duration_ms = 500;
        params.vad_params.speech_pad_ms = 200;
    }

    ProgressBridge bridge = {env, listener, nullptr};
    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        bridge.on_progress = env->GetMethodID(cls, "onProgress", "(I)V");
        env->DeleteLocalRef(cls);
    }
    params.progress_callback = &on_progress;
    params.progress_callback_user_data = &bridge;
    params.abort_callback = &should_abort;
    params.abort_callback_user_data = nullptr;
    params.encoder_begin_callback = &encoder_begin;
    params.encoder_begin_callback_user_data = nullptr;

    jsize n_samples = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    int rc = whisper_full(ctx, params, pcm, n_samples);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);

    if (rc != 0 || g_abort.load()) {
        LOGW("whisper_full failed or aborted (rc=%d)", rc);
        return nullptr;
    }

    std::string text;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char *seg = whisper_full_get_segment_text(ctx, i);
        if (seg == nullptr) continue;
        if (!text.empty()) text += '\n';
        text += seg;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()),
                            reinterpret_cast<const jbyte *>(text.data()));
    return out;
}
