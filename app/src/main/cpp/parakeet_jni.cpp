#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <cstring>
#include <memory>

#if __has_include("sherpa-onnx/c-api/c-api.h")
#include "sherpa-onnx/c-api/c-api.h"
#define HAVE_SHERPA_ONNX 1
#elif __has_include("c-api.h")
#include "c-api.h"
#define HAVE_SHERPA_ONNX 1
#else
#define HAVE_SHERPA_ONNX 0
#endif

#define TAG "EVParakeet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

#if HAVE_SHERPA_ONNX
struct ParakeetContext {
    const SherpaOnnxOfflineRecognizer *recognizer = nullptr;
};
#endif

std::atomic<bool> g_parakeet_abort{false};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ishaan_essentialvoice_engine_ParakeetLib_nativeInit(
        JNIEnv *env, jclass,
        jstring encoder_path,
        jstring decoder_path,
        jstring joiner_path,
        jstring tokens_path,
        jint num_threads) {

#if HAVE_SHERPA_ONNX
    const char *encoder = env->GetStringUTFChars(encoder_path, nullptr);
    const char *decoder = env->GetStringUTFChars(decoder_path, nullptr);
    const char *joiner  = env->GetStringUTFChars(joiner_path, nullptr);
    const char *tokens  = env->GetStringUTFChars(tokens_path, nullptr);

    SherpaOnnxOfflineRecognizerConfig config;
    std::memset(&config, 0, sizeof(config));

    config.model_config.transducer.encoder = encoder;
    config.model_config.transducer.decoder = decoder;
    config.model_config.transducer.joiner  = joiner;
    config.model_config.tokens = tokens;
    config.model_config.num_threads = num_threads;
    config.model_config.debug = 0;
    config.model_config.provider = "cpu";
    config.decoding_method = "greedy_search";

    LOGI("loading Parakeet model: enc=%s", encoder);
    const SherpaOnnxOfflineRecognizer *rec = SherpaOnnxCreateOfflineRecognizer(&config);

    env->ReleaseStringUTFChars(encoder_path, encoder);
    env->ReleaseStringUTFChars(decoder_path, decoder);
    env->ReleaseStringUTFChars(joiner_path, joiner);
    env->ReleaseStringUTFChars(tokens_path, tokens);

    if (!rec) {
        LOGW("SherpaOnnxCreateOfflineRecognizer failed");
        return 0;
    }

    auto *ctx = new ParakeetContext();
    ctx->recognizer = rec;
    return reinterpret_cast<jlong>(ctx);
#else
    LOGW("sherpa-onnx not available in this build");
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_ishaan_essentialvoice_engine_ParakeetLib_nativeFree(
        JNIEnv *, jclass, jlong ptr) {
#if HAVE_SHERPA_ONNX
    auto *ctx = reinterpret_cast<ParakeetContext *>(ptr);
    if (ctx) {
        if (ctx->recognizer) {
            SherpaOnnxDestroyOfflineRecognizer(ctx->recognizer);
        }
        delete ctx;
    }
#endif
}

JNIEXPORT void JNICALL
Java_com_ishaan_essentialvoice_engine_ParakeetLib_nativeAbort(
        JNIEnv *, jclass, jboolean on) {
    g_parakeet_abort.store(on, std::memory_order_relaxed);
}

JNIEXPORT jstring JNICALL
Java_com_ishaan_essentialvoice_engine_ParakeetLib_nativeTranscribe(
        JNIEnv *env, jclass,
        jlong ptr,
        jfloatArray audio,
        jint sample_rate) {

#if HAVE_SHERPA_ONNX
    auto *ctx = reinterpret_cast<ParakeetContext *>(ptr);
    if (!ctx || !ctx->recognizer) return env->NewStringUTF("");

    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const jsize n_samples = env->GetArrayLength(audio);

    const SherpaOnnxOfflineStream *stream = SherpaOnnxCreateOfflineStream(ctx->recognizer);
    SherpaOnnxAcceptWaveformOffline(stream, sample_rate, samples, n_samples);
    SherpaOnnxDecodeOfflineStream(ctx->recognizer, stream);

    const SherpaOnnxOfflineRecognizerResult *result = SherpaOnnxGetOfflineStreamResult(stream);
    std::string text = (result && result->text) ? result->text : "";

    SherpaOnnxDestroyOfflineRecognizerResult(result);
    SherpaOnnxDestroyOfflineStream(stream);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    return env->NewStringUTF(text.c_str());
#else
    return env->NewStringUTF("");
#endif
}

} // extern "C"
