package com.ishaan.essentialvoice.engine

import android.content.Context
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.whisper.WhisperEngine

object EngineManager {

    fun getEngine(type: EngineType): SttEngine = when (type) {
        EngineType.WHISPER -> WhisperEngine
        EngineType.PARAKEET -> ParakeetEngine
        EngineType.GEMINI -> GeminiEngine
    }

    fun activeEngine(context: Context): SttEngine {
        val engineType = Prefs.get(context).now.activeEngine
        return getEngine(engineType)
    }

    suspend fun unloadAllExcept(activeType: EngineType) {
        EngineType.entries.filter { it != activeType }.forEach { type ->
            getEngine(type).unload()
        }
    }
}
