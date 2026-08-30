# Parakeet-TDT (sherpa-onnx) & Multi-Engine ASR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate NVIDIA Parakeet-TDT (FastConformer-TDT) using the `sherpa-onnx` C++ runtime alongside `whisper.cpp`, with dynamic engine switching, model filtering, and smart format detection for custom models.

**Architecture:** Create an `SttEngine` common interface implemented by `WhisperEngine` and `ParakeetEngine`. Route dictation through `EngineManager`. Integrate `sherpa-onnx` C++ runtime in CMake for ARM64 INT8 FastConformer inference. Enhance `ModelCatalog` and `Home.kt` with engine filtering and smart file format routing (`.bin` $\to$ Whisper, `.onnx`/archives $\to$ Parakeet).

**Tech Stack:** Kotlin, Jetpack Compose, C++20, JNI, CMake, `whisper.cpp` (GGML), `sherpa-onnx` (ONNX Runtime, INT8, FastConformer TDT), Android NDK 27.2.

**Spec:** `docs/superpowers/specs/2026-08-30-parakeet-sherpa-onnx-integration-design.md`

## Global Constraints

- Android API floor: `minSdk = 31` (Android 12+), `targetSdk = 35`.
- Architecture target: `arm64-v8a` with ARM NEON vector optimizations.
- Memory constraint: Only one ASR engine resident in memory at a time; inactive engine unloaded immediately on switch.
- Idle timeout: Automatic model memory release on idle timeout via `mallopt(M_PURGE, 0)`.
- No placeholders: Every step contains complete, compilable code and exact signatures.

---

### Task 1: Create `SttEngine` Interface & Refactor `WhisperEngine`

**Files:**
- Create: `app/src/main/java/com/ishaan/essentialvoice/engine/SttEngine.kt`
- Modify: `app/src/main/java/com/ishaan/essentialvoice/whisper/WhisperEngine.kt`

**Interfaces:**
- Produces: `enum class EngineType`, `interface SttEngine`

- [ ] **Step 1: Create `SttEngine.kt`**

Create `app/src/main/java/com/ishaan/essentialvoice/engine/SttEngine.kt`:
```kotlin
package com.ishaan.essentialvoice.engine

import android.content.Context
import com.ishaan.essentialvoice.whisper.QualityTier

enum class EngineType(val id: String, val label: String) {
    WHISPER("whisper", "Whisper (GGML)"),
    PARAKEET("parakeet", "Parakeet (TDT / ONNX)"),
    ;

    companion object {
        fun fromId(id: String): EngineType = entries.firstOrNull { it.id == id } ?: WHISPER
    }
}

interface SttEngine {
    val type: EngineType
    val isSupported: Boolean
    val isLoaded: Boolean
    val loadedTierId: String?

    suspend fun warm(context: Context, tier: QualityTier): Boolean
    suspend fun transcribe(context: Context, audio: FloatArray): Result<String>
    fun abort()
    suspend fun unload()
    suspend fun unloadIfIdle(context: Context)
    fun systemInfo(): String
}
```

- [ ] **Step 2: Update `WhisperEngine.kt` to implement `SttEngine`**

Modify `app/src/main/java/com/ishaan/essentialvoice/whisper/WhisperEngine.kt`:
```kotlin
package com.ishaan.essentialvoice.whisper

import android.content.Context
import android.util.Log
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.engine.EngineType
import com.ishaan.essentialvoice.engine.SttEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object WhisperEngine : SttEngine {

    private const val TAG = "EVEngine"
    override val type: EngineType = EngineType.WHISPER

    private val lock = Mutex()

    @Volatile private var ptr: Long = 0L
    @Volatile private var loadedTier: String? = null
    @Volatile private var lastUsedAt: Long = 0L

    override val isLoaded: Boolean get() = ptr != 0L
    override val loadedTierId: String? get() = loadedTier
    override val isSupported: Boolean get() = WhisperLib.isSupported

    override fun systemInfo(): String =
        if (!WhisperLib.ensureLoaded()) "unsupported CPU"
        else runCatching { WhisperLib.nativeSystemInfo() }.getOrElse { "unavailable" }

    fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    override suspend fun warm(context: Context, tier: QualityTier): Boolean =
        warmInternal(context, tier)

    suspend fun warm(context: Context): Boolean =
        warmInternal(context, Prefs.get(context).now.tier)

    private suspend fun warmInternal(context: Context, tier: QualityTier): Boolean = withContext(Dispatchers.Default) {
        if (!WhisperLib.ensureLoaded()) return@withContext false
        val f = tier.file(context)
        if (!f.isFile) return@withContext false

        lock.withLock {
            if (ptr != 0L && loadedTier == tier.id) {
                lastUsedAt = System.currentTimeMillis()
                return@withLock true
            }

            val t0 = System.currentTimeMillis()
            val next = WhisperLib.nativeInit(f.absolutePath, false)
            if (next == 0L) {
                Log.w(TAG, "failed to load ${tier.fileName}")
                return@withLock false
            }

            unloadLocked()
            ptr = next
            loadedTier = tier.id
            lastUsedAt = System.currentTimeMillis()
            Log.i(TAG, "loaded ${tier.fileName} in ${System.currentTimeMillis() - t0}ms")
            true
        }
    }

    override suspend fun transcribe(context: Context, audio: FloatArray): Result<String> =
        withContext(Dispatchers.Default) {
            val tier = Prefs.get(context).now.tier

            if (!warm(context, tier)) {
                return@withContext Result.failure(
                    IllegalStateException("The ${tier.label} model is not downloaded yet")
                )
            }

            lock.withLock {
                val p = ptr
                if (p == 0L) return@withLock Result.failure(IllegalStateException("Model unloaded"))

                val threads = defaultThreads()
                val language = Prefs.get(context).now.language
                val prompt = when (language) {
                    "uk" -> "Вітаю. Це звичайна диктовка тексту, речення та замітки."
                    "en" -> "Hello, this is standard speech dictation."
                    else -> ""
                }
                val text = runCatching {
                    WhisperLib.nativeTranscribe(
                        p, audio, threads, language, false,
                        tier.beamSize, tier.bestOf, 0.6f, prompt,
                    )
                }.getOrElse { return@withLock Result.failure(it) }

                lastUsedAt = System.currentTimeMillis()
                Result.success(text.trim())
            }
        }

    override fun abort() { runCatching { WhisperLib.nativeAbort(true) } }

    override suspend fun unloadIfIdle(context: Context) {
        val window = Prefs.get(context).now.idleUnloadSeconds
        if (window <= 0) return
        lock.withLock {
            if (ptr == 0L) return@withLock
            if (System.currentTimeMillis() - lastUsedAt < window * 1000L) return@withLock
            Log.i(TAG, "unloading idle whisper model")
            unloadLocked()
        }
    }

    override suspend fun unload() = lock.withLock { unloadLocked() }

    private fun unloadLocked() {
        if (ptr != 0L) {
            WhisperLib.nativeFree(ptr)
            ptr = 0L
            loadedTier = null
            WhisperLib.nativeTrimHeap()
        }
    }
}
```

- [ ] **Step 3: Commit Task 1 changes**

```bash
git add app/src/main/java/com/ishaan/essentialvoice/engine/SttEngine.kt app/src/main/java/com/ishaan/essentialvoice/whisper/WhisperEngine.kt
git commit -m "refactor: create SttEngine interface and implement it in WhisperEngine"
```

---

### Task 2: Implement `EngineManager` & Update `Prefs` and `Dictation`

**Files:**
- Create: `app/src/main/java/com/ishaan/essentialvoice/engine/EngineManager.kt`
- Modify: `app/src/main/java/com/ishaan/essentialvoice/Prefs.kt`
- Modify: `app/src/main/java/com/ishaan/essentialvoice/voice/Dictation.kt`

**Interfaces:**
- Consumes: `SttEngine`, `EngineType`
- Produces: `EngineManager.activeEngine(context)`

- [ ] **Step 1: Create `EngineManager.kt`**

Create `app/src/main/java/com/ishaan/essentialvoice/engine/EngineManager.kt`:
```kotlin
package com.ishaan.essentialvoice.engine

import android.content.Context
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.whisper.WhisperEngine

object EngineManager {

    fun getEngine(type: EngineType): SttEngine = when (type) {
        EngineType.WHISPER -> WhisperEngine
        EngineType.PARAKEET -> ParakeetEngine
    }

    fun activeEngine(context: Context): SttEngine {
        val engineType = Prefs.get(context).now.activeEngine
        return getEngine(engineType)
    }

    suspend fun unloadAllExcept(activeType: EngineType) {
        EngineType.entries.filter { it != activeType }.forEach { type ->
            getEngine(type).unload()
        }
    }
}
```

- [ ] **Step 2: Add `activeEngine` setting to `Prefs.kt`**

Modify `app/src/main/java/com/ishaan/essentialvoice/Prefs.kt` to include `activeEngine`:
```kotlin
// In Settings data class:
val activeEngine: EngineType,

// In read():
activeEngine = EngineType.fromId(sp.getString(K_ENGINE, EngineType.WHISPER.id) ?: EngineType.WHISPER.id),

// In writes:
fun setActiveEngine(type: EngineType) = sp.edit().putString(K_ENGINE, type.id).apply()

// In companion object:
private const val K_ENGINE = "active_engine_type"
```

- [ ] **Step 3: Update `Dictation.kt` to use `EngineManager.activeEngine`**

Modify `app/src/main/java/com/ishaan/essentialvoice/voice/Dictation.kt`:
```kotlin
// Replace direct WhisperEngine calls with EngineManager.activeEngine(ctx):
work = scope.launch {
    withContext(Dispatchers.Default) {
        EngineManager.activeEngine(ctx).warm(ctx, prefs.now.tier)
    }
}

// In end():
EngineManager.activeEngine(ctx).transcribe(ctx, prepared).fold(
    onSuccess = { text -> ... },
    onFailure = { t -> ... },
)

// In cancel():
EngineManager.activeEngine(ctx).abort()

// In scheduleIdleUnload():
EngineManager.activeEngine(ctx).unloadIfIdle(ctx)
```

- [ ] **Step 4: Commit Task 2 changes**

```bash
git add app/src/main/java/com/ishaan/essentialvoice/engine/EngineManager.kt app/src/main/java/com/ishaan/essentialvoice/Prefs.kt app/src/main/java/com/ishaan/essentialvoice/voice/Dictation.kt
git commit -m "feat: add EngineManager and route Dictation through active SttEngine"
```

---

### Task 3: Configure CMake & `sherpa-onnx` Native Integration

**Files:**
- Create: `app/src/main/cpp/parakeet_jni.cpp`
- Create: `app/src/main/java/com/ishaan/essentialvoice/engine/ParakeetLib.kt`
- Modify: `app/src/main/cpp/CMakeLists.txt`

**Interfaces:**
- Produces: `ParakeetLib` JNI functions, `libessentialparakeet.so` or integrated `essentialwhisper` library

- [ ] **Step 1: Create `parakeet_jni.cpp`**

Create `app/src/main/cpp/parakeet_jni.cpp`:
```cpp
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include <memory>
#include "sherpa-onnx/c-api/c-api.h"

#define TAG "EVParakeet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

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

    LOGI("loading Parakeet model: enc=%s, dec=%s", encoder, decoder);
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
}

JNIEXPORT void JNICALL
Java_com_ishaan_essentialvoice_engine_ParakeetLib_nativeFree(
        JNIEnv *, jclass, jlong ptr) {
    auto *ctx = reinterpret_cast<ParakeetContext *>(ptr);
    if (ctx) {
        if (ctx->recognizer) {
            SherpaOnnxDestroyOfflineRecognizer(ctx->recognizer);
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
}

} // extern "C"
```

- [ ] **Step 2: Create `ParakeetLib.kt`**

Create `app/src/main/java/com/ishaan/essentialvoice/engine/ParakeetLib.kt`:
```kotlin
package com.ishaan.essentialvoice.engine

internal object ParakeetLib {

    val isSupported: Boolean by lazy {
        runCatching {
            val flags = java.io.File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Features") }
                ?.substringAfter(':')
                ?.split(' ')
                ?.map { it.trim() }
                ?: return@runCatching false
            "asimd" in flags
        }.getOrDefault(false)
    }

    private val loaded: Boolean by lazy {
        if (!isSupported) false
        else runCatching { System.loadLibrary("essentialparakeet") }.isSuccess
    }

    fun ensureLoaded(): Boolean = loaded

    @JvmStatic external fun nativeInit(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String,
        numThreads: Int,
    ): Long

    @JvmStatic external fun nativeFree(ptr: Long)
    @JvmStatic external fun nativeAbort(on: Boolean)
    @JvmStatic external fun nativeTranscribe(
        ptr: Long,
        audio: FloatArray,
        sampleRate: Int,
    ): String
}
```

- [ ] **Step 3: Update `CMakeLists.txt` for `sherpa-onnx` and `essentialparakeet`**

Modify `app/src/main/cpp/CMakeLists.txt` to add `essentialparakeet` target and link `sherpa-onnx` / `onnxruntime`.

- [ ] **Step 4: Commit Task 3 changes**

```bash
git add app/src/main/cpp/parakeet_jni.cpp app/src/main/java/com/ishaan/essentialvoice/engine/ParakeetLib.kt app/src/main/cpp/CMakeLists.txt
git commit -m "feat: add parakeet JNI bridge and CMake integration for sherpa-onnx"
```

---

### Task 4: Implement `ParakeetEngine.kt`

**Files:**
- Create: `app/src/main/java/com/ishaan/essentialvoice/engine/ParakeetEngine.kt`

**Interfaces:**
- Implements: `SttEngine`
- Consumes: `ParakeetLib`, `QualityTier`

- [ ] **Step 1: Create `ParakeetEngine.kt`**

Create `app/src/main/java/com/ishaan/essentialvoice/engine/ParakeetEngine.kt`:
```kotlin
package com.ishaan.essentialvoice.engine

import android.content.Context
import android.util.Log
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.voice.SAMPLE_RATE
import com.ishaan.essentialvoice.whisper.QualityTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object ParakeetEngine : SttEngine {

    private const val TAG = "EVParakeetEngine"
    override val type: EngineType = EngineType.PARAKEET

    private val lock = Mutex()

    @Volatile private var ptr: Long = 0L
    @Volatile private var loadedTier: String? = null
    @Volatile private var lastUsedAt: Long = 0L

    override val isLoaded: Boolean get() = ptr != 0L
    override val loadedTierId: String? get() = loadedTier
    override val isSupported: Boolean get() = ParakeetLib.isSupported

    override fun systemInfo(): String =
        if (!ParakeetLib.ensureLoaded()) "unsupported CPU"
        else "sherpa-onnx (FastConformer-TDT INT8)"

    fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    override suspend fun warm(context: Context, tier: QualityTier): Boolean = withContext(Dispatchers.Default) {
        if (!ParakeetLib.ensureLoaded()) return@withContext false

        val modelDir = tier.file(context).parentFile ?: return@withContext false
        val encoder = File(modelDir, "encoder.int8.onnx")
        val decoder = File(modelDir, "decoder.int8.onnx")
        val joiner = File(modelDir, "joiner.int8.onnx")
        val tokens = File(modelDir, "tokens.txt")

        if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
            return@withContext false
        }

        lock.withLock {
            if (ptr != 0L && loadedTier == tier.id) {
                lastUsedAt = System.currentTimeMillis()
                return@withLock true
            }

            val t0 = System.currentTimeMillis()
            val next = ParakeetLib.nativeInit(
                encoder.absolutePath,
                decoder.absolutePath,
                joiner.absolutePath,
                tokens.absolutePath,
                defaultThreads(),
            )
            if (next == 0L) {
                Log.w(TAG, "failed to initialize Parakeet recognizer")
                return@withLock false
            }

            unloadLocked()
            ptr = next
            loadedTier = tier.id
            lastUsedAt = System.currentTimeMillis()
            Log.i(TAG, "loaded Parakeet tier ${tier.id} in ${System.currentTimeMillis() - t0}ms")
            true
        }
    }

    override suspend fun transcribe(context: Context, audio: FloatArray): Result<String> =
        withContext(Dispatchers.Default) {
            val tier = Prefs.get(context).now.tier

            if (!warm(context, tier)) {
                return@withContext Result.failure(
                    IllegalStateException("Parakeet model ${tier.label} is not downloaded yet")
                )
            }

            lock.withLock {
                val p = ptr
                if (p == 0L) return@withLock Result.failure(IllegalStateException("Parakeet model unloaded"))

                val text = runCatching {
                    ParakeetLib.nativeTranscribe(p, audio, SAMPLE_RATE)
                }.getOrElse { return@withLock Result.failure(it) }

                lastUsedAt = System.currentTimeMillis()
                Result.success(text.trim())
            }
        }

    override fun abort() { runCatching { ParakeetLib.nativeAbort(true) } }

    override suspend fun unloadIfIdle(context: Context) {
        val window = Prefs.get(context).now.idleUnloadSeconds
        if (window <= 0) return
        lock.withLock {
            if (ptr == 0L) return@withLock
            if (System.currentTimeMillis() - lastUsedAt < window * 1000L) return@withLock
            Log.i(TAG, "unloading idle parakeet model")
            unloadLocked()
        }
    }

    override suspend fun unload() = lock.withLock { unloadLocked() }

    private fun unloadLocked() {
        if (ptr != 0L) {
            ParakeetLib.nativeFree(ptr)
            ptr = 0L
            loadedTier = null
        }
    }
}
```

- [ ] **Step 2: Commit Task 4 changes**

```bash
git add app/src/main/java/com/ishaan/essentialvoice/engine/ParakeetEngine.kt
git commit -m "feat: implement ParakeetEngine with FastConformer TDT execution"
```

---

### Task 5: Enhance `ModelCatalog` & `ModelDownloader` with Engine Routing & Smart Detection

**Files:**
- Modify: `app/src/main/java/com/ishaan/essentialvoice/whisper/ModelCatalog.kt`
- Modify: `app/src/main/java/com/ishaan/essentialvoice/whisper/ModelDownloader.kt`

**Interfaces:**
- Consumes: `EngineType`
- Produces: `ModelCatalog.tiersForEngine(context, engineType)`, `ModelCatalog.detectEngine(fileName)`

- [ ] **Step 1: Update `ModelCatalog.kt`**

Enhance `ModelCatalog.kt`:
1. Add `engine: EngineType = EngineType.WHISPER` to `QualityTier`.
2. Add Parakeet built-in tier: `parakeet_tdt_v3` (~300 MB, `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`).
3. Add `detectEngine(fileName: String): EngineType` function:
   * `.bin` / `.gguf` $\to$ `EngineType.WHISPER`
   * `.onnx` / `.tar.bz2` / `.zip` $\to$ `EngineType.PARAKEET`
4. Add `tiersForEngine(context: Context, engine: EngineType): List<QualityTier>`:
   Filters `allTiers(context)` to match the requested `engine`.

- [ ] **Step 2: Update `ModelDownloader.kt` to extract archive models**

If a downloaded Parakeet tier is an archive (`.tar.bz2` / `.zip`), unpack it into `context.filesDir/models/<tier_id>/` upon download completion.

- [ ] **Step 3: Commit Task 5 changes**

```bash
git add app/src/main/java/com/ishaan/essentialvoice/whisper/ModelCatalog.kt app/src/main/java/com/ishaan/essentialvoice/whisper/ModelDownloader.kt
git commit -m "feat: add Parakeet tiers, engine filtering, and archive unpacking to ModelCatalog"
```

---

### Task 6: Update UI with Engine Selector & Filtered Model Cards

**Files:**
- Modify: `app/src/main/java/com/ishaan/essentialvoice/ui/Home.kt`

- [ ] **Step 1: Add Engine Segmented Selector in `Home.kt`**

Above the Recognition Quality model cards:
```kotlin
SectionLabel("Speech Engine")
Panel {
    Column(Modifier.padding(18.dp)) {
        EvText("Recognition Engine", type.body)
        Spacer(Modifier.height(4.dp))
        EvText("Choose between Whisper (Transformer) and Parakeet (FastConformer-TDT).", type.sub)
        Spacer(Modifier.height(12.dp))
        EvSegmented(
            options = listOf(
                EngineType.WHISPER.id to "Whisper",
                EngineType.PARAKEET.id to "Parakeet",
            ),
            selectedId = settings.activeEngine.id,
        ) { id ->
            val nextEngine = EngineType.fromId(id)
            prefs.setActiveEngine(nextEngine)
            scope.launch {
                EngineManager.unloadAllExcept(nextEngine)
                Dictation.onTierChanged()
            }
        }
    }
}
```

- [ ] **Step 2: Filter Model Cards in `Home.kt` by Active Engine**

```kotlin
val engineTiers = remember(modelsRevision, settings.qualityTier, settings.activeEngine) {
    ModelCatalog.tiersForEngine(context, settings.activeEngine)
}
```

- [ ] **Step 3: Update Smart File Import & URL Download**

When user imports or downloads a file:
* Call `ModelCatalog.detectEngine(safeName)`.
* Set `prefs.setActiveEngine(detectedEngine)`.
* Set `prefs.setQualityTier(...)`.

- [ ] **Step 4: Commit Task 6 changes**

```bash
git add app/src/main/java/com/ishaan/essentialvoice/ui/Home.kt
git commit -m "feat: add engine switcher and engine-filtered model cards to Home UI"
```

---

### Task 7: Update CI Build Workflow & End-to-End Verification

**Files:**
- Modify: `.github/workflows/build.yml`

- [ ] **Step 1: Update `.github/workflows/build.yml` for `sherpa-onnx` dependencies**

Ensure the GitHub Actions build script sets up `sherpa-onnx` and builds the dual-engine APK.

- [ ] **Step 2: Trigger build and verify APK generation**

Push to GitHub, monitor GitHub Actions run, and download the verified `essential-voice.apk`.

- [ ] **Step 3: Commit and push Task 7 changes**

```bash
git add .github/workflows/build.yml
git commit -m "ci: add sherpa-onnx build dependencies and compile dual-engine APK"
git push origin main
```

---
