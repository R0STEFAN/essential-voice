package com.ishaan.essentialvoice.whisper

import android.content.Context
import android.util.Log
import com.ishaan.essentialvoice.Prefs
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
object WhisperEngine {

    private const val TAG = "EVEngine"

    private val lock = Mutex()

    @Volatile private var ptr: Long = 0L
    @Volatile private var loadedTier: String? = null
    @Volatile private var lastUsedAt: Long = 0L

    val isLoaded: Boolean get() = ptr != 0L
    val loadedTierId: String? get() = loadedTier

    /** False on a CPU too old for the instructions this build was compiled with. */
    val isSupported: Boolean get() = WhisperLib.isSupported

    fun systemInfo(): String =
        if (!WhisperLib.ensureLoaded()) "unsupported CPU"
        else runCatching { WhisperLib.nativeSystemInfo() }.getOrElse { "unavailable" }

    fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /**
     * Load the configured tier if it is not already resident. Safe to call from
     * anywhere; concurrent callers queue on the same load.
     */
    suspend fun warm(context: Context): Boolean = withContext(Dispatchers.Default) {
        val tier = Prefs.get(context).now.tier
        if (!WhisperLib.ensureLoaded()) {
            Log.e(TAG, "native library unavailable on this CPU")
            return@withContext false
        }
        lock.withLock {
            if (ptr != 0L && loadedTier == tier.id) {
                lastUsedAt = System.currentTimeMillis()
                return@withLock true
            }
            unloadLocked()
            if (!tier.isInstalled(context)) {
                Log.w(TAG, "tier ${tier.id} not downloaded")
                return@withLock false
            }
            val t0 = System.currentTimeMillis()
            val p = WhisperLib.nativeInit(tier.file(context).absolutePath, false)
            if (p == 0L) {
                Log.e(TAG, "nativeInit returned null for ${tier.fileName}")
                return@withLock false
            }
            ptr = p
            loadedTier = tier.id
            lastUsedAt = System.currentTimeMillis()
            Log.i(TAG, "loaded ${tier.fileName} in ${System.currentTimeMillis() - t0}ms")
            true
        }
    }

    /** Blocking transcription. [audio] must be 16kHz mono float in -1..1. */
    suspend fun transcribe(context: Context, audio: FloatArray): Result<String> =
        withContext(Dispatchers.Default) {
            val tier = Prefs.get(context).now.tier

            if (!warm(context)) {
                return@withContext Result.failure(
                    IllegalStateException("The ${tier.label} model is not downloaded yet")
                )
            }

            lock.withLock {
                val p = ptr
                if (p == 0L) return@withLock Result.failure(IllegalStateException("Model unloaded"))

                val threads = defaultThreads()

                // Every shipped tier is an .en model, which carries no language
                // tokens at all — asking one for another language produces
                // confident nonsense, so English is not a setting here.
                val text = runCatching {
                    WhisperLib.nativeTranscribe(
                        p, audio, threads, "en", false,
                        tier.beamSize, tier.bestOf, 0.6f, "",
                    )
                }.getOrElse { return@withLock Result.failure(it) }

                lastUsedAt = System.currentTimeMillis()
                Result.success(text.trim())
            }
        }

    fun abort() = runCatching { WhisperLib.nativeAbort(true) }

    /** Drop the model if it has gone unused for longer than the configured window. */
    suspend fun unloadIfIdle(context: Context) {
        val window = Prefs.get(context).now.idleUnloadSeconds
        if (window <= 0) return
        lock.withLock {
            if (ptr == 0L) return@withLock
            if (System.currentTimeMillis() - lastUsedAt < window * 1000L) return@withLock
            Log.i(TAG, "unloading idle model")
            unloadLocked()
        }
    }

    suspend fun unload() = lock.withLock { unloadLocked() }

    private fun unloadLocked() {
        if (ptr != 0L) {
            WhisperLib.nativeFree(ptr)
            ptr = 0L
            loadedTier = null
            // Freeing without this leaves the arenas charged to our RSS.
            runCatching { WhisperLib.nativeTrimHeap() }
        }
    }
}
