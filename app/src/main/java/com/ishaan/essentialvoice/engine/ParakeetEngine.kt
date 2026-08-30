package com.ishaan.essentialvoice.engine

import android.content.Context
import android.util.Log
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.voice.SAMPLE_RATE
import com.ishaan.essentialvoice.whisper.ModelCatalog
import com.ishaan.essentialvoice.whisper.QualityTier
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object ParakeetEngine : SttEngine {

    private const val TAG = "EVParakeetEngine"
    override val type: EngineType = EngineType.PARAKEET

    private val lock = Mutex()

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var loadedTier: String? = null
    @Volatile private var lastUsedAt: Long = 0L

    override val isLoaded: Boolean get() = recognizer != null
    override val loadedTierId: String? get() = loadedTier
    override val isSupported: Boolean = true

    override fun systemInfo(): String = "sherpa-onnx (FastConformer-TDT INT8)"

    fun defaultThreads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    override suspend fun warm(context: Context, tier: QualityTier): Boolean = withContext(Dispatchers.Default) {
        val baseDir = ModelCatalog.dir(context)
        val tierDir = File(baseDir, tier.id)

        // Auto-extract archive if present on disk
        val archiveFile = File(baseDir, tier.fileName)
        val hasExtracted = File(tierDir, "encoder.int8.onnx").exists() || File(tierDir, "encoder.onnx").exists()
        if (!hasExtracted && archiveFile.exists()) {
            val name = archiveFile.name.lowercase()
            if (name.endsWith(".tar.bz2") || name.endsWith(".zip") || name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
                com.ishaan.essentialvoice.whisper.ModelDownloader.extractArchive(archiveFile, tierDir)
            }
        }

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

        lock.withLock {
            if (recognizer != null && loadedTier == tier.id) {
                lastUsedAt = System.currentTimeMillis()
                return@withLock true
            }

            val t0 = System.currentTimeMillis()

            val modelConfig = OfflineModelConfig().apply {
                transducer = OfflineTransducerModelConfig().apply {
                    this.encoder = encoder.absolutePath
                    this.decoder = decoder?.absolutePath ?: ""
                    this.joiner = joiner?.absolutePath ?: ""
                }
                this.tokens = tokens?.absolutePath ?: ""
                this.numThreads = defaultThreads()
                this.provider = "cpu"
                this.modelType = "nemo_transducer"
            }

            val config = OfflineRecognizerConfig().apply {
                this.modelConfig = modelConfig
                this.decodingMethod = "greedy_search"
            }

            val next = runCatching {
                OfflineRecognizer(assetManager = null, config = config)
            }.onFailure { Log.w(TAG, "failed to initialize Sherpa-ONNX OfflineRecognizer", it) }.getOrNull()
            if (next == null) {
                return@withLock false
            }

            unloadLocked()
            recognizer = next
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
                val rec = recognizer ?: return@withLock Result.failure(IllegalStateException("Parakeet model unloaded"))

                val text = runCatching {
                    val stream = rec.createStream()
                    stream.acceptWaveform(samples = audio, sampleRate = SAMPLE_RATE)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    val resultText = result.text
                    stream.release()
                    resultText
                }.getOrElse { return@withLock Result.failure(it) }

                lastUsedAt = System.currentTimeMillis()
                Result.success(text.trim())
            }
        }

    override fun abort() { /* Sherpa streams release on demand */ }

    override suspend fun unloadIfIdle(context: Context) {
        val window = Prefs.get(context).now.idleUnloadSeconds
        if (window <= 0) return
        lock.withLock {
            if (recognizer == null) return@withLock
            if (System.currentTimeMillis() - lastUsedAt < window * 1000L) return@withLock
            Log.i(TAG, "unloading idle parakeet model")
            unloadLocked()
        }
    }

    override suspend fun unload() = lock.withLock { unloadLocked() }

    private fun unloadLocked() {
        recognizer?.release()
        recognizer = null
        loadedTier = null
    }
}
