// JNI bridge for whisper.cpp.  One transcribe call returns the whole joined
// transcript so a dictation costs exactly two JNI crossings, not one per segment.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <string>
#include <cstring>
#include <malloc.h>

#include "whisper.h"
#include "ggml.h"

#define TAG "EVWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// Set from Kotlin when the user cancels mid-transcribe; ggml polls it between graphs.
std::atomic<bool> g_abort{false};

bool abort_cb(void * /*user_data*/) {
    return g_abort.load(std::memory_order_relaxed);
}

// Whisper likes to emit "[BLANK_AUDIO]", "(wind blowing)" and friends on silence.
// Those are never something you meant to dictate, so drop any segment that is
// entirely wrapped in brackets, and trim the rest.
bool is_noise_segment(const std::string &s) {
    size_t b = s.find_first_not_of(" \t\n");
    if (b == std::string::npos) return true;
    size_t e = s.find_last_not_of(" \t\n");
    char first = s[b], last = s[e];
    return (first == '[' && last == ']') ||
           (first == '(' && last == ')') ||
           (first == '*' && last == '*');
}

std::string trim(const std::string &s) {
    size_t b = s.find_first_not_of(" \t\n");
    if (b == std::string::npos) return "";
    size_t e = s.find_last_not_of(" \t\n");
    return s.substr(b, e - b + 1);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeInit(
        JNIEnv *env, jclass, jstring model_path, jboolean use_gpu) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = use_gpu;
    cparams.flash_attn = false;

    LOGI("loading model: %s (gpu=%d)", path, (int) use_gpu);
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (!ctx) LOGW("whisper_init failed");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeFree(
        JNIEnv *, jclass, jlong ptr) {
    if (ptr) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

JNIEXPORT void JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeAbort(
        JNIEnv *, jclass, jboolean on) {
    g_abort.store(on, std::memory_order_relaxed);
}

JNIEXPORT jstring JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeTranscribe(
        JNIEnv *env, jclass,
        jlong ptr,
        jfloatArray audio,
        jint n_threads,
        jstring language,
        jboolean translate,
        jint beam_size,
        jint best_of,
        jfloat no_speech_thold,
        jstring initial_prompt) {

    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (!ctx) return env->NewStringUTF("");

    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const jsize n_samples = env->GetArrayLength(audio);

    whisper_full_params p = whisper_full_default_params(
            beam_size > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);

    p.n_threads         = n_threads;
    p.translate         = translate;
    p.no_context        = true;    // each dictation is independent
    p.single_segment    = false;
    p.no_timestamps     = true;
    p.print_realtime    = false;
    p.print_progress    = false;
    p.print_timestamps  = false;
    p.print_special     = false;
    p.suppress_blank    = true;
    p.suppress_nst      = true;    // drop (music), (laughter) style tokens
    p.temperature       = 0.0f;
    p.no_speech_thold   = no_speech_thold;

    if (beam_size > 1) {
        p.beam_search.beam_size = beam_size;
        p.greedy.best_of = best_of;
    } else {
        p.greedy.best_of = best_of;
    }

    const char *lang = nullptr;
    if (language) {
        lang = env->GetStringUTFChars(language, nullptr);
        p.language = lang;
        p.detect_language = (std::strcmp(lang, "auto") == 0);
    }

    const char *prompt = nullptr;
    if (initial_prompt) {
        prompt = env->GetStringUTFChars(initial_prompt, nullptr);
        if (std::strlen(prompt) > 0) p.initial_prompt = prompt;
    }

    g_abort.store(false, std::memory_order_relaxed);
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = nullptr;

    std::string out;
    if (whisper_full(ctx, p, samples, n_samples) != 0) {
        LOGW("whisper_full failed");
    } else {
        const int n = whisper_full_n_segments(ctx);
        for (int i = 0; i < n; i++) {
            std::string seg = whisper_full_get_segment_text(ctx, i);
            if (is_noise_segment(seg)) continue;
            seg = trim(seg);
            if (seg.empty()) continue;
            if (!out.empty()) out += " ";
            out += seg;
        }
    }

    if (lang)   env->ReleaseStringUTFChars(language, lang);
    if (prompt) env->ReleaseStringUTFChars(initial_prompt, prompt);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    return env->NewStringUTF(out.c_str());
}

// Bionic keeps freed arenas on its own free lists, exactly like glibc does; without
// this an unloaded model stays charged to the process RSS and helps nobody.
JNIEXPORT void JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeTrimHeap(JNIEnv *, jclass) {
#ifdef M_PURGE
    mallopt(M_PURGE, 0);
#endif
}

JNIEXPORT jstring JNICALL
Java_com_ishaan_essentialvoice_whisper_WhisperLib_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
