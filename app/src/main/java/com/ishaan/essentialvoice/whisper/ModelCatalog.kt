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
    val beamSize: Int,
    /** Candidates the sampler keeps. */
    val bestOf: Int,
    val millisPer10s: Int,
) {
    val sizeMb: Int get() = ((bytes + 500_000) / 1_000_000).toInt()

    /** Human reading of [millisPer10s]: "1.5s", "6s". */
    val waitLabel: String
        get() {
            val s = millisPer10s / 1000f
            return if (s < 3f) "%.1fs".format(s) else "${s.toInt()}s"
        }

    fun file(context: Context): File = File(ModelCatalog.dir(context), fileName)

    fun isInstalled(context: Context): Boolean {
        val f = file(context)
        return f.isFile && f.length() == bytes
    }

    val url: String get() = "${ModelCatalog.BASE_URL}$fileName"
}

object ModelCatalog {

    const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    const val DEFAULT_TIER_ID = "balanced"

    val tiers = listOf(
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
            sub = "Multilingual Small. Recommended for Ukrainian, surzhyk and mixed speech.",
            fileName = "ggml-small.bin",
            bytes = 487_601_967L,
            beamSize = 1,
            bestOf = 2,
            millisPer10s = 6_000,
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

    fun byId(id: String): QualityTier = tiers.firstOrNull { it.id == id } ?: tiers[1]

    fun dir(context: Context): File =
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    /** Deduplicated by file: two tiers can share one model. */
    fun installedBytes(context: Context): Long =
        tiers.filter { it.isInstalled(context) }
            .distinctBy { it.fileName }
            .sumOf { it.bytes }
}
