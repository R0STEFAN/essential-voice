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
            // For single-file testing, check if the single file exists
            if (!tier.file(context).exists()) return@withContext false
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
