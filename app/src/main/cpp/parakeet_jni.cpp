#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <atomic>
#include <string>
#include <cstring>
#include <memory>

#define TAG "EVParakeet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// Structures matching sherpa-onnx C-API layout
struct SherpaOnnxOfflineTransducerModelConfig {
    const char *encoder;
    const char *decoder;
    const char *joiner;
};

struct SherpaOnnxOfflineModelConfig {
    SherpaOnnxOfflineTransducerModelConfig transducer;
    const char *paraformer;
    const char *nemo_ctc;
    const char *whisper;
    const char *tdnn;
    const char *tokens;
    int32_t num_threads;
    int32_t debug;
    const char *provider;
    const char *model_type;
    const char *modeling_unit;
    const char *bpe_vocab;
    const char *telespeech_ctc;
    const char *sense_voice;
    const char *moonshine;
};

struct SherpaOnnxOfflineRecognizerConfig {
    SherpaOnnxOfflineModelConfig model_config;
    const char *decoding_method;
    int32_t max_active_paths;
    const char *hotwords_file;
    float hotwords_score;
    const char *rule_fsts;
    const char *rule_fars;
    float blank_penalty;
};

struct SherpaOnnxOfflineRecognizerResult {
    const char *text;
    const char *timestamps;
    int32_t count;
    const float *tokens_arr;
    const char *json;
    const char *lang;
    const char *emotion;
    const char *event;
};

typedef void SherpaOnnxOfflineRecognizer;
typedef void SherpaOnnxOfflineStream;

typedef const SherpaOnnxOfflineRecognizer* (*fn_create_rec)(const SherpaOnnxOfflineRecognizerConfig*);
typedef void (*fn_destroy_rec)(const SherpaOnnxOfflineRecognizer*);
typedef const SherpaOnnxOfflineStream* (*fn_create_stream)(const SherpaOnnxOfflineRecognizer*);
typedef void (*fn_destroy_stream)(const SherpaOnnxOfflineStream*);
typedef void (*fn_accept_waveform)(const SherpaOnnxOfflineStream*, int32_t, const float*, int32_t);
typedef void (*fn_decode_stream)(const SherpaOnnxOfflineRecognizer*, const SherpaOnnxOfflineStream*);
typedef const SherpaOnnxOfflineRecognizerResult* (*fn_get_result)(const SherpaOnnxOfflineStream*);
typedef void (*fn_destroy_result)(const SherpaOnnxOfflineRecognizerResult*);

struct SherpaApi {
    void *handle = nullptr;
    fn_create_rec create_rec = nullptr;
    fn_destroy_rec destroy_rec = nullptr;
    fn_create_stream create_stream = nullptr;
    fn_destroy_stream destroy_stream = nullptr;
    fn_accept_waveform accept_waveform = nullptr;
    fn_decode_stream decode_stream = nullptr;
    fn_get_result get_result = nullptr;
    fn_destroy_result destroy_result = nullptr;

    bool init() {
        if (handle) return true;
        handle = dlopen("libsherpa-onnx-c-api.so", RTLD_NOW | RTLD_LOCAL);
        if (!handle) {
            handle = dlopen("libsherpa-onnx-jni.so", RTLD_NOW | RTLD_LOCAL);
        }
        if (!handle) {
            LOGW("could not dlopen libsherpa-onnx-c-api.so: %s", dlerror());
            return false;
        }

        create_rec = (fn_create_rec)dlsym(handle, "SherpaOnnxCreateOfflineRecognizer");
        destroy_rec = (fn_destroy_rec)dlsym(handle, "SherpaOnnxDestroyOfflineRecognizer");
        create_stream = (fn_create_stream)dlsym(handle, "SherpaOnnxCreateOfflineStream");
        destroy_stream = (fn_destroy_stream)dlsym(handle, "SherpaOnnxDestroyOfflineStream");
        accept_waveform = (fn_accept_waveform)dlsym(handle, "SherpaOnnxAcceptWaveformOffline");
        decode_stream = (fn_decode_stream)dlsym(handle, "SherpaOnnxDecodeOfflineStream");
        get_result = (fn_get_result)dlsym(handle, "SherpaOnnxGetOfflineStreamResult");
        destroy_result = (fn_destroy_result)dlsym(handle, "SherpaOnnxDestroyOfflineRecognizerResult");

        return create_rec && destroy_rec && create_stream && accept_waveform && decode_stream && get_result;
    }
};

SherpaApi g_api;

struct ParakeetContext {
    const SherpaOnnxOfflineRecognizer *recognizer = nullptr;
};

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

    if (!g_api.init()) {
        LOGW("sherpa-onnx C API symbols not loaded");
        return 0;
    }

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

    LOGI("loading Parakeet TDT model: enc=%s", encoder);
    const SherpaOnnxOfflineRecognizer *rec = g_api.create_rec(&config);

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
}

JNIEXPORT void JNICALL
Java_com_ishaan_essentialvoice_engine_ParakeetLib_nativeFree(
        JNIEnv *, jclass, jlong ptr) {
    auto *ctx = reinterpret_cast<ParakeetContext *>(ptr);
    if (ctx) {
        if (ctx->recognizer && g_api.destroy_rec) {
            g_api.destroy_rec(ctx->recognizer);
        }
        delete ctx;
    }
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

    auto *ctx = reinterpret_cast<ParakeetContext *>(ptr);
    if (!ctx || !ctx->recognizer || !g_api.create_stream) return env->NewStringUTF("");

    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const jsize n_samples = env->GetArrayLength(audio);

    const SherpaOnnxOfflineStream *stream = g_api.create_stream(ctx->recognizer);
    g_api.accept_waveform(stream, sample_rate, samples, n_samples);
    g_api.decode_stream(ctx->recognizer, stream);

    const SherpaOnnxOfflineRecognizerResult *result = g_api.get_result(stream);
    std::string text = (result && result->text) ? result->text : "";

    if (g_api.destroy_result) g_api.destroy_result(result);
    if (g_api.destroy_stream) g_api.destroy_stream(stream);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    return env->NewStringUTF(text.c_str());
}

} // extern "C"
