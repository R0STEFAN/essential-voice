package com.ishaan.essentialvoice.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Microphone capture for one dictation. Records straight into a growing float
 * buffer at 16kHz so no resampling or file round-trip stands between the user
 * releasing the key and whisper starting.
 */
class Recorder(private val onLevel: (Float) -> Unit) {

    private companion object {
        const val TAG = "EVRecorder"
        const val MAX_SECONDS = 90
    }

    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    private val buffer = FloatArray(SAMPLE_RATE * MAX_SECONDS)
    @Volatile private var written = 0

    val seconds: Float get() = written.toFloat() / SAMPLE_RATE

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        written = 0

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize returned $minBuf")
            return false
        }

        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord construction failed", t)
            return false
        }

        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialised (state=${r.state})")
            r.release()
            return false
        }

        record = r
        running = true
        r.startRecording()

        worker = thread(name = "ev-mic", priority = Thread.MAX_PRIORITY) {
            val chunk = ShortArray(minBuf)
            val subFrame = 256 // ~16ms at 16kHz for smooth real-time equalizer frames
            while (running) {
                val n = r.read(chunk, 0, chunk.size)
                if (n <= 0) continue

                var i = 0
                while (i < n && written < buffer.size) {
                    var subPeak = 0f
                    val end = kotlin.math.min(n, i + subFrame)
                    while (i < end && written < buffer.size) {
                        val v = chunk[i] / 32768f
                        buffer[written++] = v
                        val m = abs(v)
                        if (m > subPeak) subPeak = m
                        i++
                    }
                    onLevel(subPeak)
                }
                if (written >= buffer.size) {
                    Log.w(TAG, "hit the ${MAX_SECONDS}s ceiling, stopping")
                    running = false
                }
            }
        }
        return true
    }

    /** Stops capture and hands back exactly the samples recorded. */
    fun stop(): FloatArray {
        if (!running && record == null) return FloatArray(0)
        running = false
        worker?.join(500)
        worker = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        return buffer.copyOf(written)
    }

    fun release() {
        running = false
        worker = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
    }
}
