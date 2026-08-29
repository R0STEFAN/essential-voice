package com.ishaan.essentialvoice.whisper

import android.content.Context
import java.io.File

/**
 * The quality toggle, expressed as four settings that were measured on this
 * phone rather than guessed.
 *
 * [millisPer10s] is the wall time whisper.cpp took on a CMF Phone 2 Pro for an
 * eleven-second clip of clear speech at four threads — the number the tier card
 * shows, so a tier's cost in waiting is as visible as its cost in bytes.
 *
 * Anything larger than `small` was tried and rejected on this hardware:
 * `medium.en-q5_0` spends 19.6s in the encoder alone and `large-v3-turbo-q5_0`
 * spends 32.6s, both fixed costs that no thread count or beam setting moves.
 * Neither is a dictation tool. Quantised `small.en-q5_1` was also slower than
 * fp16 here (7.1s against 5.8s): the Dimensity does fp16 natively, so
 * dequantising costs time and buys nothing but disk.
 */
data class QualityTier(
    val id: String,
    val label: String,
    val sub: String,
    val fileName: String,
    val bytes: Long,
    /** >1 selects beam search; 1 means greedy sampling. */
    val beamSize: Int = 1,
    /** Candidates the sampler keeps. */
    val bestOf: Int = 2,
    val millisPer10s: Int = 3000,
    val downloadUrl: String? = null,
    val isCustom: Boolean = false,
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
        val f = file(context)
        return if (bytes > 0) (f.isFile && f.length() == bytes) else (f.isFile && f.length() > 0)
    }

    val url: String get() = downloadUrl ?: "${ModelCatalog.BASE_URL}$fileName"
}

object ModelCatalog {

    const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    const val DEFAULT_TIER_ID = "balanced"

    val tiers = listOf(
        QualityTier(
            id = "futo_244",
            label = "FUTO 244",
            sub = "FUTO Multilingual-244 (ACFT fine-tuned). Best for Ukrainian & voice dictation.",
            fileName = "voice-input-multilingual-244.bin",
            bytes = 264_464_624L,
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
            beamSize = 5,
            bestOf = 5,
            millisPer10s = 8_200,
        ),
    )

    fun customTiers(context: Context): List<QualityTier> {
        val dir = dir(context)
        val standardFiles = tiers.map { it.fileName }.toSet()
        val files = dir.listFiles { f ->
            f.isFile && f.name.endsWith(".bin") && !f.name.endsWith(".part") && f.name !in standardFiles
        } ?: return emptyList()
        return files.map { file ->
            val raw = file.nameWithoutExtension
                .removePrefix("ggml-")
                .removePrefix("voice-input-")
                .replace("-", " ")
                .replace("_", " ")
            val label = raw.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            QualityTier(
                id = "custom_${file.name}",
                label = label.ifBlank { file.name },
                sub = "Custom model (${file.length() / 1_000_000} MB).",
                fileName = file.name,
                bytes = file.length(),
                beamSize = 1,
                bestOf = 2,
                millisPer10s = 4_000,
                isCustom = true,
            )
        }
    }

    fun allTiers(context: Context): List<QualityTier> = tiers + customTiers(context)

    fun byId(context: Context, id: String): QualityTier =
        allTiers(context).firstOrNull { it.id == id } ?: byId(id)

    fun byId(id: String): QualityTier =
        tiers.firstOrNull { it.id == id } ?: QualityTier(
            id = id,
            label = id.removePrefix("custom_"),
            sub = "Custom model",
            fileName = if (id.startsWith("custom_")) id.removePrefix("custom_") else id,
            bytes = 0L,
            beamSize = 1,
            bestOf = 2,
            millisPer10s = 4_000,
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
