# Architecture Design Spec: Parakeet-TDT (sherpa-onnx) & Multi-Engine ASR Integration

**Date:** 2026-08-30  
**Status:** Approved  
**Author:** R0STEFAN / Assistant  
**Target Platform:** Android 12+ (ARM64-v8a)  

---

## 1. Executive Summary

This document specifies the architectural design for integrating the **NVIDIA Parakeet-TDT** (Token-and-Duration Transducer / FastConformer) automatic speech recognition (ASR) architecture into Essential Voice alongside the existing **OpenAI Whisper** (whisper.cpp) engine.

The system will feature:
1. A unified **`SttEngine` abstraction layer** allowing clean, modular execution of multiple ASR engines.
2. High-performance, on-device C++ inference for Parakeet models using **`sherpa-onnx`** with INT8 quantization and ARM NEON optimizations.
3. An explicit **Engine Switcher** in the UI (`Whisper` vs `Parakeet`) that filters model cards according to the active engine.
4. **Smart format detection** during model imports, routing `.bin`/`.gguf` files to Whisper and `.onnx`/archive files to Parakeet.
5. Support for `nvidia/parakeet-tdt-0.6b-v3` (multilingual, 25 languages including Ukrainian) as the primary Parakeet model.

---

## 2. Core Architecture & Component Decomposition

### 2.1 Component Interaction Diagram

```
                        ┌────────────────────────┐
                        │      Dictation.kt      │
                        └───────────┬────────────┘
                                    │
                        ┌───────────▼────────────┐
                        │     EngineManager      │
                        │ (active engine router) │
                        └─────┬────────────┬─────┘
                              │            │
           ┌──────────────────▼──┐      ┌──▼──────────────────┐
           │    WhisperEngine    │      │   ParakeetEngine    │
           │  (implements        │      │  (implements        │
           │   SttEngine)        │      │   SttEngine)        │
           └──────────┬──────────┘      └──────────┬──────────┘
                      │                            │
             (JNI / C++ Bridge)           (JNI / C++ Bridge)
                      │                            │
           ┌──────────▼──────────┐      ┌──────────▼──────────┐
           │     whisper.cpp     │      │     sherpa-onnx     │
           │ (Encoder-Decoder    │      │ (FastConformer-TDT  │
           │  Transformer .bin)  │      │  ONNX Runtime INT8) │
           └─────────────────────┘      └─────────────────────┘
```

---

## 3. Kotlin Subsystems & Contracts

### 3.1 `SttEngine` Common Interface
A new interface `com.ishaan.essentialvoice.engine.SttEngine` will define the standard lifecycle and execution contract for all speech-to-text engines:

```kotlin
package com.ishaan.essentialvoice.engine

import android.content.Context
import com.ishaan.essentialvoice.whisper.QualityTier

enum class EngineType(val id: String, val label: String) {
    WHISPER("whisper", "Whisper (GGML)"),
    PARAKEET("parakeet", "Parakeet (TDT / ONNX)"),
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
}
```

### 3.2 `EngineManager`
A singleton coordinator that returns the currently active engine based on user preference:

```kotlin
object EngineManager {
    fun activeEngine(context: Context): SttEngine {
        val settings = Prefs.get(context).now
        return when (settings.activeEngine) {
            EngineType.PARAKEET -> ParakeetEngine
            EngineType.WHISPER -> WhisperEngine
        }
    }
}
```

### 3.3 `ParakeetEngine` Implementation
Owns the Parakeet C++ context and manages memory:
* Loads INT8 FastConformer encoder, decoder, joiner, and vocabulary files.
* Passes 16kHz mono audio float buffer into `ParakeetLib.nativeTranscribe`.
* Supports automatic unload on idle timeout matching Whisper's memory management behavior.

---

## 4. Native C++ & JNI Layer

### 4.1 `sherpa-onnx` Native Integration
* The CMake build configuration (`app/src/main/cpp/CMakeLists.txt`) links `sherpa-onnx` and `onnxruntime` prebuilt static/shared libraries compiled for `arm64-v8a`.
* Compiler optimization flags `-O3 -DNDEBUG` and ARM NEON / fp16 vectorization flags are maintained.

### 4.2 JNI Bridge (`parakeet_jni.cpp` & `ParakeetLib.kt`)
* Functions exposed:
  * `nativeInit(encoder: String, decoder: String, joiner: String, tokens: String, threads: Int): Long`
  * `nativeTranscribe(ptr: Long, samples: FloatArray, sampleRate: Int): String`
  * `nativeFree(ptr: Long)`
  * `nativeAbort(on: Boolean)`

---

## 5. Model Catalog & Smart Format Detection

### 5.1 `QualityTier` Updates
The `QualityTier` model is extended with engine classification and model assets mapping:

```kotlin
data class QualityTier(
    val id: String,
    val label: String,
    val sub: String,
    val fileName: String,
    val bytes: Long,
    val engine: EngineType = EngineType.WHISPER,
    val beamSize: Int = 1,
    val bestOf: Int = 2,
    val millisPer10s: Int = 3000,
    val downloadUrl: String? = null,
    val isCustom: Boolean = false,
    val assetFiles: List<String> = emptyList(), // For multi-file models (encoder, decoder, joiner, tokens)
)
```

### 5.2 Built-in Models

#### Whisper Models:
* **FUTO 244** (`voice-input-multilingual-244.bin`, ~264 MB)
* **Balanced** (`ggml-base.bin`, ~148 MB)
* **Accurate** (`ggml-small.bin`, ~488 MB)
* **Fast** (`ggml-tiny.bin`, ~78 MB)
* **Maximum** (`ggml-small.bin`, beam 5, ~488 MB)

#### Parakeet Models:
* **Parakeet TDT 0.6B v3 (INT8)** (~300 MB):
  * Architecture: FastConformer + Transducer (TDT)
  * Files: `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx`, `tokens.txt`
  * Download: Direct repository URL from Hugging Face / sherpa-onnx release assets.
  * Performance: ~0.8s - 1.2s per 10s audio on ARM64.

### 5.3 Smart Import Routing
When importing a custom file or downloading a URL:
1. **File Type Detection:**
   * Extension `.bin` or `.gguf` $\to$ Assigned to `EngineType.WHISPER`.
   * Extension `.onnx` or `.tar.bz2` or archive containing `.onnx` $\to$ Assigned to `EngineType.PARAKEET`.
2. **Catalog Placement:**
   * The custom model is added to the corresponding engine tab and activated immediately.

---

## 6. UI & User Experience

### 6.1 Engine Selector in `Home.kt`
* Situated above the models horizontal card row:
  * `EvSegmented` with options: `[ Whisper (GGML) | Parakeet (TDT / ONNX) ]`.
* Switching the segment filters the card row to only display models applicable to the selected engine.
* Changes `prefs.setActiveEngine(...)` and reloads the active tier.

### 6.2 Model Actions
* Each card maintains uniform actions: Download, Delete, Select, and Cancel.
* Storage line reflects disk usage across all installed models from both engines.

---

## 7. Performance & Resource Constraints

1. **Memory Ceiling:** At most one engine (Whisper or Parakeet) is resident in memory at any given time. Switching engines unloads the inactive context immediately.
2. **Idle Unload:** Both engines respect `idleUnloadSeconds` (default 300s) and call heap trimming (`mallopt(M_PURGE, 0)`).
3. **Threading:** Defaults to available CPU cores clamped to `1..4` to prevent battery drain.

---

## 8. Verification & Test Plan

1. **Unit & Engine Tests:**
   * Verify `SttEngine` transitions between `WHISPER` and `PARAKEET`.
   * Test smart format detection on various filenames (`.bin`, `.onnx`, `.tar.bz2`).
2. **Speech Recognition Accuracy & Speed:**
   * Test Ukrainian, English, and surzhyk speech dictation on `parakeet-tdt-0.6b-v3`.
   * Measure wall time on a standard 10-second clip.
3. **Build Pipeline:**
   * Verify GitHub Actions workflow builds the ARM64 APK with both `whisper.cpp` and `sherpa-onnx` without errors.
