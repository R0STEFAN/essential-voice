package com.ishaan.essentialvoice.engine

import android.content.Context
import com.ishaan.essentialvoice.whisper.QualityTier

enum class EngineType(val id: String, val label: String) {
    WHISPER("whisper", "Whisper (GGML)"),
    PARAKEET("parakeet", "Parakeet (TDT)"),
    GEMINI("gemini", "Gemini (Cloud)"),
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
