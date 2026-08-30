package com.ishaan.essentialvoice.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ishaan.essentialvoice.Prefs
import com.ishaan.essentialvoice.voice.SAMPLE_RATE
import com.ishaan.essentialvoice.whisper.QualityTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

object GeminiEngine : SttEngine {

    private const val TAG = "EVGeminiEngine"
    override val type: EngineType = EngineType.GEMINI

    override val isLoaded: Boolean = true
    override val loadedTierId: String = "gemini_cloud"
    override val isSupported: Boolean = true

    override fun systemInfo(): String = "Google Gemini Cloud ASR (AI Studio API)"

    override suspend fun warm(context: Context, tier: QualityTier): Boolean {
        val key = Prefs.get(context).now.geminiApiKey.trim()
        if (key.isBlank()) {
            Log.w(TAG, "Google AI Studio API Key is empty")
            return false
        }
        return true
    }

    override suspend fun transcribe(context: Context, audio: FloatArray): Result<String> =
        withContext(Dispatchers.IO) {
            val prefs = Prefs.get(context).now
            val key = prefs.geminiApiKey.trim()
            val model = prefs.geminiModel.ifBlank { Prefs.DEFAULT_GEMINI_MODEL }

            if (key.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Please set your Google AI Studio API key in Gemini settings")
                )
            }

            val wavBytes = encodeWav(audio, SAMPLE_RATE)
            val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

            val prompt = "Transcribe this audio recording verbatim in the spoken language (Ukrainian / Surzhyk / English). " +
                "Output only the exact transcribed speech text with natural punctuation and capitalization. " +
                "Do not summarize, do not translate, and do not add any conversational commentary, explanations, or notes."

            val jsonBody = JSONObject().apply {
                val parts = JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                    put(JSONObject().put("inline_data", JSONObject().apply {
                        put("mime_type", "audio/wav")
                        put("data", base64Audio)
                    }))
                }
                put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                put("generationConfig", JSONObject().put("temperature", 0.0))
            }

            var conn: HttpURLConnection? = null
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

                conn.outputStream.use { os ->
                    os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                val responseStr = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    val errorMsg = parseErrorMessage(err).ifBlank { "HTTP $code from Gemini API" }
                    return@withContext Result.failure(IllegalStateException(errorMsg))
                }

                val jsonResponse = JSONObject(responseStr)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "")
                        return@withContext Result.success(text.trim())
                    }
                }

                Result.success("")
            } catch (t: Throwable) {
                Log.w(TAG, "Gemini transcription failed", t)
                Result.failure(t)
            } finally {
                conn?.disconnect()
            }
        }

    override fun abort() { /* HTTP request completes or times out */ }

    override suspend fun unloadIfIdle(context: Context) {}

    override suspend fun unload() {}

    /** Tests API key validity against Google AI Studio API. */
    suspend fun testApiKey(apiKey: String, model: String = Prefs.DEFAULT_GEMINI_MODEL): Result<String> =
        withContext(Dispatchers.IO) {
            val trimmedKey = apiKey.trim()
            if (trimmedKey.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API Key cannot be empty"))
            }

            var conn: HttpURLConnection? = null
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model?key=$trimmedKey"
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

                val code = conn.responseCode
                if (code in 200..299) {
                    Result.success("Key is valid & active!")
                } else {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    val errorMsg = parseErrorMessage(err).ifBlank { "HTTP $code: Invalid API Key" }
                    Result.failure(IllegalStateException(errorMsg))
                }
            } catch (t: Throwable) {
                Result.failure(t)
            } finally {
                conn?.disconnect()
            }
        }

    private fun parseErrorMessage(jsonStr: String): String {
        return runCatching {
            val json = JSONObject(jsonStr)
            val error = json.optJSONObject("error")
            error?.optString("message") ?: ""
        }.getOrDefault("")
    }

    /** Converts normalized float audio samples into a standard 16-bit mono WAV. */
    private fun encodeWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val pcmData = ByteArray(samples.size * 2)
        var idx = 0
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            val intSample = (clamped * 32767f).roundToInt().toShort()
            pcmData[idx++] = (intSample.toInt() and 0xFF).toByte()
            pcmData[idx++] = ((intSample.toInt() shr 8) and 0xFF).toByte()
        }

        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 1 * 2

        val baos = ByteArrayOutputStream(44 + pcmData.size)
        val dos = DataOutputStream(baos)

        // RIFF header
        dos.writeBytes("RIFF")
        dos.writeInt(Integer.reverseBytes(totalDataLen))
        dos.writeBytes("WAVE")
        dos.writeBytes("fmt ")
        dos.writeInt(Integer.reverseBytes(16)) // Subchunk1Size for PCM
        dos.writeShort(java.lang.Short.reverseBytes(1)) // AudioFormat 1 = PCM
        dos.writeShort(java.lang.Short.reverseBytes(1)) // NumChannels = 1
        dos.writeInt(Integer.reverseBytes(sampleRate))
        dos.writeInt(Integer.reverseBytes(byteRate))
        dos.writeShort(java.lang.Short.reverseBytes(2)) // BlockAlign = 2
        dos.writeShort(java.lang.Short.reverseBytes(16)) // BitsPerSample = 16
        dos.writeBytes("data")
        dos.writeInt(Integer.reverseBytes(pcmData.size))
        dos.write(pcmData)
        dos.flush()

        return baos.toByteArray()
    }
}
