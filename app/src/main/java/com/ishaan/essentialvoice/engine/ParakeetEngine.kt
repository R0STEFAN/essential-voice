package com.ishaan.essentialvoice.engine

import android.content.Context
import android.util.Log
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.voice.SAMPLE_RATE
import com.ishaan.essentialvoice.whisper.ModelCatalog
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
        if (!ParakeetLib.ensureLoaded()) {
            Log.w(TAG, "ParakeetLib is not loaded")
            return@withContext false
        }

        val baseDir = ModelCatalog.dir(context)
        val tierDir = File(baseDir, tier.id)
        val searchDirs = listOf(tierDir, baseDir)

        val encoder = searchDirs.map { File(it, "encoder.int8.onnx") }.firstOrNull { it.exists() }
            ?: searchDirs.map { File(it, "encoder.onnx") }.firstOrNull { it.exists() }
            ?: searchDirs.map { File(it, tier.fileName) }.firstOrNull { it.exists() && it.name.endsWith(".onnx") }
            ?: searchDirs.map { File(it, "model.int8.onnx") }.firstOrNull { it.exists() }
            ?: searchDirs.map { File(it, "model.onnx") }.firstOrNull { it.exists() }

        val decoder = searchDirs.map { File(it, "decoder.int8.onnx") }.firstOrNull { it.exists() }
            ?: searchDirs.map { File(it, "decoder.onnx") }.firstOrNull { it.exists() }

        val joiner = searchDirs.map { File(it, "joiner.int8.onnx") }.firstOrNull { it.exists() }
            ?: searchDirs.map { File(it, "joiner.onnx") }.firstOrNull { it.exists() }

        val tokens = searchDirs.map { File(it, "tokens.txt") }.firstOrNull { it.exists() }
            ?: searchDirs.map { File(it, "tokens.json") }.firstOrNull { it.exists() }

        if (encoder == null || !encoder.exists()) {
            Log.w(TAG, "Parakeet encoder not found for tier ${tier.id}")
            return@withContext false
        }

        val encoderPath = encoder.absolutePath
        val decoderPath = decoder?.absolutePath ?: ""
        val joinerPath = joiner?.absolutePath ?: ""
        val tokensPath = tokens?.absolutePath ?: ""

        lock.withLock {
            if (ptr != 0L && loadedTier == tier.id) {
                lastUsedAt = System.currentTimeMillis()
                return@withLock true
            }

            val t0 = System.currentTimeMillis()
            val next = ParakeetLib.nativeInit(
                encoderPath,
                decoderPath,
                joinerPath,
                tokensPath,
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
                    IllegalStateException("Parakeet model ${tier.label} is not ready yet")
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
