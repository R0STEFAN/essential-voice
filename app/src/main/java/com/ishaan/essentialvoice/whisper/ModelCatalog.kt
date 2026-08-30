package com.ishaan.essentialvoice.whisper

import android.content.Context
import com.ishaan.essentialvoice.engine.EngineType
import java.io.File

/**
 * The quality toggle, expressed as settings that were measured on this
 * phone rather than guessed.
 */
data class QualityTier(
    val id: String,
    val label: String,
    val sub: String,
    val fileName: String,
    val bytes: Long,
    val engine: EngineType = EngineType.WHISPER,
    /** >1 selects beam search; 1 means greedy sampling. */
    val beamSize: Int = 1,
    /** Candidates the sampler keeps. */
    val bestOf: Int = 2,
    val millisPer10s: Int = 3000,
    val downloadUrl: String? = null,
    val isCustom: Boolean = false,
    val subFiles: List<Pair<String, Long>> = emptyList(),
) {
    val sizeMb: Int get() = if (bytes > 0) ((bytes + 500_000) / 1_000_000).toInt() else 0

    /** Human reading of [millisPer10s]: "1.5s", "6s". */
    val waitLabel: String
        get() {
            val s = millisPer10s / 1000f
            return if (s < 3f) "%.1fs".format(s) else "${s.toInt()}s"
        }

    fun file(context: Context): File = File(ModelCatalog.dir(context), fileName)

    fun isInstalled(context: Context): Boolean {
        if (subFiles.isNotEmpty()) {
            val tierDir = File(ModelCatalog.dir(context), id)
            val filesOk = subFiles.all { (name, expectedBytes) ->
                val f = File(tierDir, name)
                f.isFile && (expectedBytes <= 0 || f.length() == expectedBytes)
            }
            if (filesOk) return true
        }
        val f = file(context)
        if (engine == EngineType.PARAKEET) {
            val tierDir = File(ModelCatalog.dir(context), id)
            val baseDir = ModelCatalog.dir(context)
            val hasOnnx = listOf(
                File(tierDir, "encoder.int8.onnx"),
                File(tierDir, "encoder.onnx"),
                File(baseDir, "encoder.int8.onnx"),
                File(baseDir, fileName),
            ).any { it.exists() && it.length() > 0 }
            if (hasOnnx) return true
        }
        return if (bytes > 0) (f.isFile && f.length() == bytes) else (f.isFile && f.length() > 0)
    }

    val url: String get() = downloadUrl ?: "${ModelCatalog.BASE_URL}$fileName"
}

object ModelCatalog {

    const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    const val DEFAULT_TIER_ID = "futo_244"

    val tiers = listOf(
        // ---- Whisper Engine Models ----
        QualityTier(
            id = "futo_244",
            label = "FUTO 244",
            sub = "FUTO Multilingual-244 (ACFT fine-tuned). Best for Ukrainian & voice dictation.",
            fileName = "voice-input-multilingual-244.bin",
            bytes = 264_464_624L,
            engine = EngineType.WHISPER,
            beamSize = 1,
            bestOf = 2,
            millisPer10s = 3_500,
            downloadUrl = "https://keyboard.futo.org/voice-input-multilingual-244.bin",
        ),
        QualityTier(
            id = "balanced",
            label = "Balanced",
            sub = "Multilingual Base. Everyday balance of speed and accuracy.",
            fileName = "ggml-base.bin",
            bytes = 147_951_465L,
            engine = EngineType.WHISPER,
            beamSize = 1,
            bestOf = 2,
            millisPer10s = 2_400,
        ),
        QualityTier(
            id = "accurate",
            label = "Accurate",
            sub = "Multilingual Small. High precision for Ukrainian and surzhyk.",
            fileName = "ggml-small.bin",
            bytes = 487_601_967L,
            engine = EngineType.WHISPER,
            beamSize = 1,
            bestOf = 2,
            millisPer10s = 6_000,
        ),
        QualityTier(
            id = "fast",
            label = "Fast",
            sub = "Multilingual Tiny. Fast short commands; may miss context.",
            fileName = "ggml-tiny.bin",
            bytes = 77_691_713L,
            engine = EngineType.WHISPER,
            beamSize = 1,
            bestOf = 2,
            millisPer10s = 1_500,
        ),
        QualityTier(
            id = "maximum",
            label = "Maximum",
            sub = "The Accurate Small model, searched harder with beam search.",
            fileName = "ggml-small.bin",
            bytes = 487_601_967L,
            engine = EngineType.WHISPER,
            beamSize = 5,
            bestOf = 5,
            millisPer10s = 8_200,
        ),

        // ---- Parakeet Engine Models ----
        QualityTier(
            id = "parakeet_tdt_v3",
            label = "Parakeet TDT 0.6B v3",
            sub = "NVIDIA FastConformer-TDT (INT8). Super fast on mobile, 25 European languages.",
            fileName = "encoder.int8.onnx",
            bytes = 670_809_594L,
            engine = EngineType.PARAKEET,
            beamSize = 1,
            bestOf = 1,
            millisPer10s = 1_000,
            downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/",
            subFiles = listOf(
                "encoder.int8.onnx" to 652_184_281L,
                "decoder.int8.onnx" to 11_845_275L,
                "joiner.int8.onnx" to 6_355_277L,
                "tokens.txt" to 424_761L,
            ),
        ),
    )

    fun detectEngine(fileName: String): EngineType {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".onnx") || lower.endsWith(".tar.bz2") || lower.endsWith(".zip") -> EngineType.PARAKEET
            else -> EngineType.WHISPER
        }
    }

    fun customTiers(context: Context): List<QualityTier> {
        val dir = dir(context)
        val standardFiles = tiers.map { it.fileName }.toSet()
        val files = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".bin") || f.name.endsWith(".gguf") || f.name.endsWith(".onnx"))
                && !f.name.endsWith(".part") && f.name !in standardFiles
        } ?: return emptyList()

        return files.map { file ->
            val detected = detectEngine(file.name)
            val raw = file.nameWithoutExtension
                .removePrefix("ggml-")
                .removePrefix("voice-input-")
                .removePrefix("sherpa-onnx-")
                .replace("-", " ")
                .replace("_", " ")
            val label = raw.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            QualityTier(
                id = "custom_${file.name}",
                label = label.ifBlank { file.name },
                sub = "Custom ${detected.label} model (${file.length() / 1_000_000} MB).",
                fileName = file.name,
                bytes = file.length(),
                engine = detected,
                beamSize = 1,
                bestOf = 2,
                millisPer10s = if (detected == EngineType.PARAKEET) 1500 else 4000,
                isCustom = true,
            )
        }
    }

    fun allTiers(context: Context): List<QualityTier> = tiers + customTiers(context)

    fun tiersForEngine(context: Context, engine: EngineType): List<QualityTier> =
        allTiers(context).filter { it.engine == engine }

    fun byId(context: Context, id: String): QualityTier =
        allTiers(context).firstOrNull { it.id == id } ?: byId(id)

    fun byId(id: String): QualityTier =
        tiers.firstOrNull { it.id == id } ?: QualityTier(
            id = id,
            label = id.removePrefix("custom_"),
            sub = "Custom model",
            fileName = if (id.startsWith("custom_")) id.removePrefix("custom_") else id,
            bytes = 0L,
            engine = detectEngine(id),
            beamSize = 1,
            bestOf = 2,
            millisPer10s = 3000,
            isCustom = true,
        )

    fun dir(context: Context): File =
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    /** Deduplicated by file: two tiers can share one model. */
    fun installedBytes(context: Context): Long =
        allTiers(context).filter { it.isInstalled(context) }
            .distinctBy { it.fileName }
            .sumOf { it.file(context).length() }
}
