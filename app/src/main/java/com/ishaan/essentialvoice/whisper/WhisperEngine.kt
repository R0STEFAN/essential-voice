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

/**
 * Owns the one whisper context in the process.
 *
 * Loading a model costs 1–3 seconds and hundreds of MB, so it is loaded on the
 * *hold* rather than on release — the load overlaps the sentence being spoken and
 * is never felt — and dropped again after a spell of not being used.
 */
object WhisperEngine : SttEngine {

    private const val TAG = "EVEngine"
    override val type: EngineType = EngineType.WHISPER

    private val lock = Mutex()

    @Volatile private var ptr: Long = 0L
    @Volatile private var loadedTier: String? = null
    @Volatile private var lastUsedAt: Long = 0L

    override val isLoaded: Boolean get() = ptr != 0L
    override val loadedTierId: String? get() = loadedTier

    /** False on a CPU too old for the instructions this build was compiled with. */
    override val isSupported: Boolean get() = WhisperLib.isSupported

    override fun systemInfo(): String =
        if (!WhisperLib.ensureLoaded()) "unsupported CPU"
        else runCatching { WhisperLib.nativeSystemInfo() }.getOrElse { "unavailable" }

    fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /**
     * Load the configured tier if it is not already resident. Safe to call from
     * anywhere; concurrent callers queue on the same load.
     */
    suspend fun warm(context: Context): Boolean =
        warm(context, Prefs.get(context).now.tier)

    override suspend fun warm(context: Context, tier: QualityTier): Boolean = withContext(Dispatchers.Default) {
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

    /** Blocking transcription. [audio] must be 16kHz mono float in -1..1. */
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

    /** Drop the model if it has gone unused for longer than the configured window. */
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
